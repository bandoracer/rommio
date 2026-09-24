package io.github.bandoracer.rommio.domain.progress

import io.github.bandoracer.rommio.domain.player.PlayerEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GameSaveRollbackRequired : IllegalStateException("This snapshot also replaces the current in-game save with its paired game-save data.")

enum class ProgressSessionPhase { PREPARED, RUNNING, PAUSED, CHECKPOINTING, RESTORING, SAVE_FAILED, STOPPED, CLOSED }
data class ProgressSessionStatus(
    val phase: ProgressSessionPhase = ProgressSessionPhase.PREPARED,
    val lastCommitted: CheckpointWriteResult? = null,
    val error: String? = null,
)

/** Owns accepted operations independently of their UI waiters. Production activation is gated
 * on qualified persistence, launch/migration wiring, and replacing all legacy lifecycle writers.
 */
class ProgressSessionCoordinator(
    private val engine: PlayerEngine,
    private val writer: SessionCheckpointWriter,
    private val loader: MemoryCheckpointLoader,
    private val commitFault: (ProgressCommitBoundary) -> Unit = {},
    private val saveIntervalMs: Long = 30_000,
    private val stateIntervalMs: Long = 120_000,
    private val onNativeClosed: suspend () -> Unit = {},
    /** Observes each commit in order on this lane (local play data only); must not throw or block. */
    private val onCommitted: (CheckpointWriteResult) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutableStatus = MutableStateFlow(ProgressSessionStatus())
    val status: StateFlow<ProgressSessionStatus> = mutableStatus.asStateFlow()
    private data class CaptureRequest(val reason: String, val includeState: Boolean, val slotId: String?)
    private var failedRequest = CaptureRequest("pause", true, null)
    private var exitRequested = false
    private val periodicQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastStateCaptureAt = System.nanoTime()


    init {
        require(saveIntervalMs > 0 && stateIntervalMs >= saveIntervalMs)
        scope.launch {
            try { for (command in queue) command() }
            finally { scope.cancel() }
        }
        scope.launch {
            while (isActive) {
                delay(saveIntervalMs)
                if (status.value.phase == ProgressSessionPhase.RUNNING && periodicQueued.compareAndSet(false, true)) {
                    submit {
                        try {
                            if (status.value.phase == ProgressSessionPhase.RUNNING && !exitRequested) {
                                val includeState = (System.nanoTime() - lastStateCaptureAt) / 1_000_000 >= stateIntervalMs
                                doCheckpoint("periodic", includeState, null)
                            }
                        } finally { periodicQueued.set(false) }
                    }
                }
            }
        }
        scope.launch {
            engine.failureSignals().collect { message ->
                submit { if (status.value.phase !in setOf(ProgressSessionPhase.CLOSED, ProgressSessionPhase.STOPPED)) stop(IllegalStateException(message)) }
            }
        }
    }

    private fun <T> submit(block: suspend () -> T): Deferred<T> {
        // No caller Job is inherited. Canceling a screen's await never cancels an accepted save.
        val result = CompletableDeferred<T>()
        if (queue.trySend {
                try { result.complete(block()) }
                catch (error: Throwable) { result.completeExceptionally(error) }
            }.isFailure) result.completeExceptionally(IllegalStateException("The session has closed."))
        return result
    }

    private fun phase(value: ProgressSessionPhase, error: String? = null) {
        mutableStatus.value = mutableStatus.value.copy(phase = value, error = error)
    }
    private fun requireLive() {
        check(status.value.phase !in setOf(ProgressSessionPhase.PREPARED, ProgressSessionPhase.CLOSED, ProgressSessionPhase.STOPPED)) {
            "The session is not ready."
        }
        check(!exitRequested) { "Finish or abandon the pending exit before continuing." }
    }
    private fun committed(result: CheckpointWriteResult) {
        mutableStatus.value = mutableStatus.value.copy(lastCommitted = result)
        try { onCommitted(result) } catch (_: Exception) {} // Observers never affect a durable commit.
    }
    private suspend fun capture(reason: String, includeState: Boolean = true, slotId: String? = null): CheckpointWriteResult {
        failedRequest = CaptureRequest(reason, includeState, slotId)
        return writer.captureAndCommit(reason, includeState, slotId, commitFault).also {
            committed(it)
            if (includeState && !it.stateRejected) lastStateCaptureAt = System.nanoTime()
        }
    }

    fun start(checkpointId: String? = null, useState: Boolean = true): Deferred<Unit> = submit {
        check(status.value.phase == ProgressSessionPhase.PREPARED)
        try {
            engine.awaitReady()
            engine.setPaused(true)
            if (checkpointId != null) {
                phase(ProgressSessionPhase.RESTORING)
                val actual = engine.captureMemorySnapshot(false)
                val target = loader.load(checkpointId, actual, useState)
                check(engine.restoreMemorySnapshot(target)) { "Initial restore failed. Start a new cold-boot session with compatible save data." }
            }
            phase(ProgressSessionPhase.PAUSED)
        } catch (error: Exception) {
            stop(error)
            throw error
        }
    }

    fun resume(): Deferred<Unit> = submit {
        requireLive()
        check(status.value.phase == ProgressSessionPhase.PAUSED && !writer.hasPendingCommit()) { "Resolve the save failure before resuming." }
        engine.setPaused(false)
        phase(ProgressSessionPhase.RUNNING)
    }

    fun checkpoint(reason: String, includeState: Boolean = true, slotId: String? = null): Deferred<CheckpointWriteResult> = submit {
        doCheckpoint(reason, includeState, slotId)
    }

    private suspend fun doCheckpoint(reason: String, includeState: Boolean, slotId: String?): CheckpointWriteResult {
        requireLive()
        check(status.value.phase in setOf(ProgressSessionPhase.RUNNING, ProgressSessionPhase.PAUSED))
        require(reason in setOf("periodic", "manual", "pause", "background"))
        val continueRunning = status.value.phase == ProgressSessionPhase.RUNNING && reason in setOf("periodic", "manual")
        engine.setPaused(true)
        phase(ProgressSessionPhase.CHECKPOINTING)
        try {
            val result = capture(reason, includeState, slotId)
            if (continueRunning) engine.setPaused(false)
            phase(if (continueRunning) ProgressSessionPhase.RUNNING else ProgressSessionPhase.PAUSED)
            return result
        } catch (error: Exception) {
            if (status.value.phase != ProgressSessionPhase.STOPPED) phase(ProgressSessionPhase.SAVE_FAILED, error.message)
            throw error
        }
    }

    fun restore(checkpointId: String, confirmGameRollback: Boolean = false): Deferred<CheckpointWriteResult> = submit {
        destructive { actual ->
            // Compatibility and hash checks occur before a safety save or native mutation.
            val target = loader.load(checkpointId, actual, includeState = true)
            if (!confirmGameRollback && actual.memory.any { (region, bytes) -> !bytes.contentEquals(target.memory[region]) }) {
                throw GameSaveRollbackRequired()
            }
            suspend { engine.restoreMemorySnapshot(target) }
        }
    }

    fun reset(): Deferred<CheckpointWriteResult> = submit {
        destructive { suspend { engine.reset(); true } }
    }

    private suspend fun destructive(prepare: suspend (io.github.bandoracer.rommio.domain.player.CoreMemorySnapshot) -> suspend () -> Boolean): CheckpointWriteResult {
        requireLive()
        check(status.value.phase in setOf(ProgressSessionPhase.RUNNING, ProgressSessionPhase.PAUSED))
        engine.setPaused(true)
        phase(ProgressSessionPhase.RESTORING)
        val action: suspend () -> Boolean
        val safety: io.github.bandoracer.rommio.domain.player.CoreMemorySnapshot
        try {
            val actual = engine.captureMemorySnapshot(false)
            action = prepare(actual)
            val checkpoint = capture("safety")
            check(!checkpoint.stateRejected && checkpoint.resumeReference != null) { "A restorable safety checkpoint is required before this action." }
            safety = loader.load(checkpoint.checkpoint.checkpointId, actual, includeState = true)
            writer.pin(checkpoint.checkpoint.checkpointId)
        } catch (error: Exception) {
            phase(if (writer.hasPendingCommit()) ProgressSessionPhase.SAVE_FAILED else ProgressSessionPhase.PAUSED, error.message)
            throw error
        }
        val restored = try { action() } catch (_: Exception) { false }
        if (!restored) {
            val rolledBack = try { engine.restoreMemorySnapshot(safety) } catch (_: Exception) { false }
            val error = IllegalStateException(if (rolledBack) "Restore was rejected. The safety checkpoint has been restored."
                else "Restore and recovery failed. The session has stopped; the safety checkpoint is retained.")
            if (rolledBack) phase(ProgressSessionPhase.PAUSED, error.message) else stop(error)
            throw error
        }
        try {
            return capture("restore").also { phase(ProgressSessionPhase.PAUSED) }
        } catch (error: Exception) {
            // The chosen state is now live but remains paused until its new revision commits.
            if (status.value.phase != ProgressSessionPhase.STOPPED) phase(ProgressSessionPhase.SAVE_FAILED, error.message)
            throw error
        }
    }

    fun finishExit(): Deferred<CheckpointWriteResult> = submit {
        check(status.value.phase in setOf(ProgressSessionPhase.RUNNING, ProgressSessionPhase.PAUSED))
        exitRequested = true
        engine.setPaused(true)
        phase(ProgressSessionPhase.CHECKPOINTING)
        try {
            val result = capture("exit")
            close()
            result
        } catch (error: Exception) {
            if (status.value.phase != ProgressSessionPhase.STOPPED) phase(ProgressSessionPhase.SAVE_FAILED, error.message)
            throw error
        }
    }

    fun retrySave(): Deferred<CheckpointWriteResult> = submit {
        check(status.value.phase == ProgressSessionPhase.SAVE_FAILED)
        try {
            val result = if (writer.hasPendingCommit()) writer.retryCommit(commitFault).also(::committed) else capture(failedRequest.reason, failedRequest.includeState, failedRequest.slotId)
            if (exitRequested) close() else phase(ProgressSessionPhase.PAUSED)
            result
        } catch (error: Exception) {
            if (status.value.phase != ProgressSessionPhase.STOPPED) phase(ProgressSessionPhase.SAVE_FAILED, error.message)
            throw error
        }
    }

    /** Explicit user action; never used as an automatic response to capture failure. */
    fun exitWithoutLatestProgress(): Deferred<Unit> = submit {
        check(status.value.phase !in setOf(ProgressSessionPhase.CLOSED, ProgressSessionPhase.STOPPED))
        exitRequested = true
        close()
    }

    private suspend fun close() {
        try { engine.close(); onNativeClosed(); phase(ProgressSessionPhase.CLOSED) }
        catch (error: Exception) { phase(ProgressSessionPhase.STOPPED, error.message); throw error }
        finally { queue.close() }
    }
    private suspend fun stop(error: Exception) {
        try { engine.close(); onNativeClosed() } finally { phase(ProgressSessionPhase.STOPPED, error.message); queue.close() }
    }
}
