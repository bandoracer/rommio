package io.github.bandoracer.rommio.domain.progress

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.bandoracer.rommio.domain.player.LibretroPlayerEngine
import io.github.bandoracer.rommio.domain.player.PlayerEngine
import io.github.bandoracer.rommio.domain.player.PlayerSession
import io.github.bandoracer.rommio.domain.storage.StorageAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ApplicationProgressPhase { WAITING_FOR_VIEW, SELECTING, AWAITING_CHOICE, ACTIVE, FAILED, CLOSED }
data class ApplicationProgressState(
    val phase: ApplicationProgressPhase = ApplicationProgressPhase.WAITING_FOR_VIEW,
    val decision: ProgressLaunchDecision? = null,
    val progress: ProgressSessionStatus? = null,
    val error: String? = null,
)

/** Application lifetime owner. Merely constructing it neither opens the v2 store nor enables writes. */
class ApplicationProgressSessions(
    private val storeProvider: suspend () -> ProgressStore,
    private val registry: FceummPersistenceRegistry,
    private val deviceId: suspend () -> String,
    private val engineFactory: () -> PlayerEngine = { LibretroPlayerEngine() },
    private val modern: Boolean = false,
    private val beforePrepare: suspend (PlayerSession) -> Boolean = { true },
    private val afterCommit: (CheckpointWriteResult?) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val mutableActive = MutableStateFlow<ApplicationProgressSession?>(null)
    val active: StateFlow<ApplicationProgressSession?> = mutableActive.asStateFlow()

    /** An accepted preparation belongs to the application even if its screen stops awaiting it. */
    fun prepare(source: PlayerSession, legacyProgressPresent: Boolean, publish: Boolean): Deferred<ApplicationProgressSession> {
        val result = CompletableDeferred<ApplicationProgressSession>()
        scope.launch {
            try {
                result.complete(StorageAccess.normal { mutex.withLock {
                    check(mutableActive.value == null) { "Close the current player before opening another game." }
                    val store = storeProvider()
                    val id = deviceId()
                    require(java.util.UUID.fromString(id).toString() == id)
                    val identity = store.resolveIdentity(requireNotNull(source.progressBinding))
                    if (modern) ProgressUpgrade(store).upgrade(identity)
                    val cloudChecked = beforePrepare(source)
                    val workspace = store.prepareWorkspace(identity, source)
                    var engine: PlayerEngine? = null
                    try {
                        engine = engineFactory()
                        ApplicationProgressSession(store, workspace, engine, registry, id, legacyProgressPresent, publish, source, modern, cloudChecked, afterCommit) { owner ->
                            mutex.withLock { if (mutableActive.value === owner) mutableActive.value = null }
                        }.also { mutableActive.value = it }
                    } catch (error: Throwable) {
                        withContext(NonCancellable) { engine?.close(); store.releaseSession(workspace.lease) }
                        throw error
                    }
                } })
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        return result
    }
}

/** Owns the native lifecycle; an Activity's destruction must not directly dispose accepted work. */
class ApplicationProgressSession internal constructor(
    private val store: ProgressStore,
    val workspace: ProgressWorkspace,
    val engine: PlayerEngine,
    private val registry: FceummPersistenceRegistry,
    private val deviceId: String,
    private var legacyProgressPresent: Boolean,
    private val publish: Boolean,
    private val source: PlayerSession,
    private val modern: Boolean,
    private val cloudChecked: Boolean,
    private val afterCommit: (CheckpointWriteResult?) -> Unit,
    private val released: suspend (ApplicationProgressSession) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(ApplicationProgressState())
    val state: StateFlow<ApplicationProgressState> = mutableState.asStateFlow()
    private val planner = ProgressLaunchPlanner(store)
    private val nativeLifecycle = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private var coordinator: ProgressSessionCoordinator? = null
    private var view: View? = null
    private var screenLifecycle: Lifecycle? = null
    private var screenObserver: LifecycleEventObserver? = null
    private var foreground = false
    private var resumeOnForeground = false
    private var closed = false
    private var attachment = 0L
    private var legacyImportAttempted = false
    private var allowUncheckedCloud = false

    init {
        nativeLifecycle.registry.currentState = Lifecycle.State.CREATED
        scope.launch { try { for (command in queue) command() } finally { scope.cancel() } }
    }

    private fun <T> submit(block: suspend () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        if (queue.trySend {
                try {
                    check(!closed) { "The player session has closed." }
                    val value = block()
                    mutableState.value = mutableState.value.copy(progress = coordinator?.status?.value ?: mutableState.value.progress, error = null)
                    result.complete(value)
                } catch (error: Throwable) {
                    if (!closed) mutableState.value = mutableState.value.copy(error = error.message)
                    result.completeExceptionally(error)
                }
            }.isFailure) result.completeExceptionally(IllegalStateException("The player session has closed."))
        return result
    }

    /** Call on Main. Native views use application context and the retained lifecycle, not an Activity. */
    fun attach(context: Context, owner: LifecycleOwner): View {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed && owner.lifecycle.currentState != Lifecycle.State.DESTROYED)
        if (screenLifecycle === owner.lifecycle) return requireNotNull(view)
        check(screenLifecycle == null || screenLifecycle?.currentState == Lifecycle.State.DESTROYED || view?.isAttachedToWindow != true) {
            "The player is already displayed by another screen."
        }
        screenObserver?.let { screenLifecycle?.removeObserver(it) }
        val generation = ++attachment
        val host = engine.createOrAttachView(context.applicationContext, nativeLifecycle, workspace.session)
        (host.parent as? ViewGroup)?.removeView(host)
        view = host
        screenLifecycle = owner.lifecycle
        screenObserver = LifecycleEventObserver { _, event ->
            if (generation != attachment || closed) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    foreground = true
                    submit { if (generation == attachment && foreground) foreground() }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    foreground = false
                    // Only ON_PAUSE captures. STOP/DESTROY must not enqueue duplicate final saves.
                    if (event == Lifecycle.Event.ON_PAUSE) submit { if (generation == attachment) background() }
                }
                else -> Unit
            }
        }.also { owner.lifecycle.addObserver(it) }
        // A paused readiness frame is allowed while loading; it never executes gameplay.
        nativeLifecycle.registry.currentState = Lifecycle.State.RESUMED
        foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        return host
    }

    fun select(request: ProgressLaunchRequest = ProgressLaunchRequest.Automatic): Deferred<ProgressLaunchDecision> = submit { selectNow(request) }

    private suspend fun selectNow(request: ProgressLaunchRequest): ProgressLaunchDecision {
        check(view != null && coordinator == null)
        if (!cloudChecked && !allowUncheckedCloud) {
            val decision = ProgressLaunchDecision.ContinueLocal("Cloud progress not checked. You can continue with local progress, or go back and retry sync. Progress from another device will be preserved when it arrives.")
            mutableState.value = mutableState.value.copy(phase = ApplicationProgressPhase.AWAITING_CHOICE, decision = decision)
            return decision
        }
        mutableState.value = mutableState.value.copy(phase = ApplicationProgressPhase.SELECTING, error = null)
        try {
            val decision = planner.prepare(workspace, engine, registry, request, legacyProgressPresent)
            mutableState.value = mutableState.value.copy(decision = decision, phase = ApplicationProgressPhase.AWAITING_CHOICE)
            if (decision is ProgressLaunchDecision.Ready) {
                val commands = planner.start(decision, engine, deviceId, workspace.stagingDirectory, publish, workspace, if (modern) 3 else 2)
                coordinator = commands
                mutableState.value = mutableState.value.copy(phase = ApplicationProgressPhase.ACTIVE, progress = commands.status.value)
                scope.launch {
                    var publishedCommit: String? = null
                    commands.status.collect { status ->
                        mutableState.value = mutableState.value.copy(progress = status)
                        if (status.lastCommitted != null && publishedCommit != status.lastCommitted.operationId) {
                            publishedCommit = status.lastCommitted.operationId
                            afterCommit(status.lastCommitted)
                        }
                        if (status.phase in setOf(ProgressSessionPhase.CLOSED, ProgressSessionPhase.STOPPED)) {
                            submit { releaseNative() }
                        }
                    }
                }
            }
            return decision
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(phase = ApplicationProgressPhase.FAILED, error = error.message)
            throw error
        }
    }

    fun continueLocal(): Deferred<ProgressLaunchDecision> = submit {
        check(coordinator == null)
        allowUncheckedCloud = true
        selectNow(ProgressLaunchRequest.Automatic)
    }

    /** Read-only original files; importing never edits or removes them. */
    suspend fun legacyChoices(): List<java.io.File> = withContext(Dispatchers.IO) {
        listOf(source.saveRamFile).filter { it.isFile } +
            source.saveStatesDirectory.listFiles().orEmpty().filter { it.isFile && it.extension == "state" }.sortedBy { it.name }
    }

    fun importLegacy(file: java.io.File): Deferred<ProgressLaunchDecision> = submit {
        check(!legacyImportAttempted) { "Close and reopen the game before choosing another import. Original files are preserved." }
        check(coordinator == null && store.referenceSnapshot(workspace.identity).isEmpty()) { "Saved progress already exists." }
        check(store.recoverWorkingSessions(workspace.identity).isEmpty()) { "Resolve the interrupted session first." }
        require(legacyChoices().any { it.canonicalFile == file.canonicalFile }) { "Unknown legacy save." }
        val actual = engine.prepareForProgressSelection()
        val support = registry.qualify(workspace.session, actual)
        val qualification = (support as? CorePersistenceSupport.Qualified)?.contract
            ?: error((support as CorePersistenceSupport.Unsupported).reason)
        if (file.canonicalFile == source.saveRamFile.canonicalFile) {
            check(qualification.regions == setOf(io.github.bandoracer.rommio.domain.player.PersistentMemory.SRAM)) {
                "This cartridge requires additional saved memory. Import a compatible state instead; the original save remains preserved."
            }
            val bytes = withContext(Dispatchers.IO) {
                require(file.length() == actual.memory.getValue(io.github.bandoracer.rommio.domain.player.PersistentMemory.SRAM).size.toLong()) { "The old save has an incompatible size." }
                file.readBytes()
            }
            legacyImportAttempted = true
            check(engine.restoreMemorySnapshot(actual.copy(memory = actual.memory +
                (io.github.bandoracer.rommio.domain.player.PersistentMemory.SRAM to bytes)))) { "Unable to import the in-game save." }
        } else {
            require(file.length() in 1..(256L * 1024 * 1024)) { "Invalid old state size." }
            legacyImportAttempted = true
            check(engine.loadState(file)) { "The old state is incompatible. Its original file is preserved." }
        }
        val writer = SessionCheckpointWriter(store, workspace.identity, qualification, deviceId, workspace.sessionId,
            emptyMap(), workspace.stagingDirectory, engine::captureMemorySnapshot, publish, if (modern) 3 else 2)
        val imported = writer.captureAndCommit("import")
        afterCommit(imported)
        check(file.canonicalFile == source.saveRamFile.canonicalFile || !imported.stateRejected) {
            "The in-game save was imported, but a resume state could not be created. Close and reopen the game to start from the imported in-game save. The original state is preserved."
        }
        legacyProgressPresent = false
        selectNow(ProgressLaunchRequest.Automatic)
    }

    fun resolveBranch(operationId: String, snapshot: Boolean): Deferred<ProgressLaunchDecision> = submit {
        check(coordinator == null)
        val choices = mutableState.value.decision as? ProgressLaunchDecision.ChooseBranch ?: error("Refresh the progress choices.")
        val heads = if (snapshot) choices.resume else choices.current
        val selected = heads.single { it.operationId == operationId }
        val reference = if (snapshot) selected.changedReferences.single { it.startsWith("state/resume/") } else "game/current"
        store.resolveReference(workspace.identity, reference, operationId, heads.map { it.operationId }, deviceId, workspace.sessionId, publish)
        afterCommit(null)
        selectNow(ProgressLaunchRequest.Automatic)
    }

    fun startFreshPreservingLegacy(): Deferred<ProgressLaunchDecision> = submit {
        check(!legacyImportAttempted) { "Close and reopen the game before choosing another import. Original files are preserved." }
        check(coordinator == null && store.referenceSnapshot(workspace.identity).isEmpty())
        legacyProgressPresent = false
        selectNow(ProgressLaunchRequest.Automatic)
    }

    fun interruptedSessions(): Deferred<List<Pair<AbandonedProgressWorkspace, List<PendingWorkspaceCheckpoint>>>> = submit {
        store.recoverWorkingSessions(workspace.identity).map { abandoned ->
            abandoned to if (abandoned.problem == null) store.pendingWorkspaceCheckpoints(workspace.identity, abandoned.sessionId) else emptyList()
        }
    }

    fun recoverCapture(sessionId: String, captureId: String): Deferred<Unit> = submit {
        check(coordinator == null)
        store.recoverWorkspaceCheckpoint(workspace.identity, sessionId, captureId)
        Unit
    }

    fun discardInterruptedSession(sessionId: String): Deferred<ProgressLaunchDecision> = submit {
        check(coordinator == null)
        store.discardAbandonedWorkspace(workspace.identity, sessionId)
        selectNow(ProgressLaunchRequest.Automatic)
    }

    fun history(beforeSequence: Long = Long.MAX_VALUE): Deferred<ProgressHistoryPage> = submit {
        store.history(workspace.identity, beforeSequence)
    }

    fun resume(): Deferred<Unit> = submit {
        check(foreground) { "Bring the player to the foreground before resuming." }
        if (coordinator?.status?.value?.phase != ProgressSessionPhase.RUNNING) requireNotNull(coordinator).resume().await()
    }
    fun checkpoint(reason: String, includeState: Boolean = true, slotId: String? = null): Deferred<CheckpointWriteResult> = submit {
        requireNotNull(coordinator).checkpoint(reason, includeState, slotId).await()
    }
    fun restore(checkpointId: String, confirmGameRollback: Boolean = false): Deferred<CheckpointWriteResult> = submit { requireNotNull(coordinator).restore(checkpointId, confirmGameRollback).await() }
    fun reset(): Deferred<CheckpointWriteResult> = submit { requireNotNull(coordinator).reset().await() }
    fun retrySave(): Deferred<CheckpointWriteResult> = submit { requireNotNull(coordinator).retrySave().await() }
    fun finishExit(): Deferred<CheckpointWriteResult> = submit {
        requireNotNull(coordinator).finishExit().await().also { releaseNative() }
    }
    fun exitWithoutLatestProgress(): Deferred<Unit> = submit {
        coordinator?.takeIf { it.status.value.phase !in setOf(ProgressSessionPhase.CLOSED, ProgressSessionPhase.STOPPED) }
            ?.exitWithoutLatestProgress()?.await()
        releaseNative()
    }

    /** Controlled navigation must await this before removing the view; it retains the paused session. */
    fun prepareViewRemoval(): Deferred<Unit> = submit {
        val wasForeground = foreground
        foreground = false
        try {
            background()
            check(coordinator?.status?.value?.phase != ProgressSessionPhase.SAVE_FAILED) { "Retry the save or explicitly abandon the latest progress before leaving." }
            screenObserver?.let { screenLifecycle?.removeObserver(it) }
            screenLifecycle = null; screenObserver = null; attachment++
            (view?.parent as? ViewGroup)?.removeView(view)
        } catch (error: Exception) {
            // Failed navigation leaves the current visible screen usable for retry/resume.
            foreground = wasForeground
            if (foreground) nativeLifecycle.registry.currentState = Lifecycle.State.RESUMED
            throw error
        }
    }

    private suspend fun background() {
        if (nativeLifecycle.registry.currentState != Lifecycle.State.RESUMED) return
        val commands = coordinator
        if (commands == null) engine.awaitReady()
        else if (commands.status.value.phase in setOf(ProgressSessionPhase.RUNNING, ProgressSessionPhase.PAUSED)) {
            resumeOnForeground = commands.status.value.phase == ProgressSessionPhase.RUNNING
            commands.checkpoint("background").await()
        }
        if (!foreground) nativeLifecycle.registry.currentState = Lifecycle.State.STARTED
    }

    private suspend fun foreground() {
        nativeLifecycle.registry.currentState = Lifecycle.State.RESUMED
        if (resumeOnForeground && coordinator?.status?.value?.phase == ProgressSessionPhase.PAUSED) {
            requireNotNull(coordinator).resume().await()
        }
        resumeOnForeground = false
    }

    private suspend fun releaseNative() {
        engine.close() // If teardown failed, ownership remains held and replacement stays blocked.
        store.releaseSession(workspace.lease)
        screenObserver?.let { screenLifecycle?.removeObserver(it) }
        screenObserver = null; screenLifecycle = null; attachment++
        nativeLifecycle.registry.currentState = Lifecycle.State.DESTROYED
        (view?.parent as? ViewGroup)?.removeView(view)
        view = null; closed = true
        mutableState.value = mutableState.value.copy(phase = ApplicationProgressPhase.CLOSED)
        released(this)
        queue.close()
    }
}
