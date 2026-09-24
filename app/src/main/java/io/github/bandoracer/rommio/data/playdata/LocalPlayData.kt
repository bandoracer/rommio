package io.github.bandoracer.rommio.data.playdata

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import io.github.bandoracer.rommio.data.local.PlayDataDao
import io.github.bandoracer.rommio.data.local.PlaySessionEntity
import io.github.bandoracer.rommio.domain.playdata.ActivePlayClock
import io.github.bandoracer.rommio.domain.playdata.PlayDataHooks
import io.github.bandoracer.rommio.domain.playdata.PlayDataSession
import io.github.bandoracer.rommio.domain.playdata.PlaySessionRules
import io.github.bandoracer.rommio.domain.playdata.PlaySessionStart
import io.github.bandoracer.rommio.domain.playdata.SaveKey
import io.github.bandoracer.rommio.domain.progress.CheckpointWriteResult
import io.github.bandoracer.rommio.domain.progress.ProgressSessionPhase
import io.github.bandoracer.rommio.domain.progress.ProgressSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Records play sessions in the local table and save-state thumbnails in [thumbnails]
 * (docs/play-data-local.md). Writes run on one serial lane off the player's threads; a failure is
 * logged and dropped, never surfaced as a save failure.
 */
class LocalPlayData(
    private val dao: PlayDataDao,
    private val thumbnails: SaveThumbnailStore,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = SystemClock::elapsedRealtime,
    private val grabFrame: (View?) -> Deferred<Bitmap?> = SurfaceFrameGrabber::request,
) : PlayDataHooks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lane = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val live = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val revision = MutableStateFlow(0L)

    /** Bumps whenever a session row or thumbnail changes, so readers can refresh. */
    val changes: StateFlow<Long> = revision.asStateFlow()

    init {
        scope.launch { for (write in lane) try { write() } catch (error: CancellationException) { throw error } catch (error: Exception) { Log.w(TAG, "Play data write failed", error) } }
        // Sessions left open by process death end at their last heartbeat, reason "background".
        enqueue { closeAbandoned() }
    }

    private fun enqueue(block: suspend () -> Unit) { lane.trySend(block) }
    private fun changed() = revision.update { it + 1 }

    internal suspend fun closeAbandoned() {
        dao.openSessions().filter { it.sessionId !in live }.forEach { row ->
            val close = PlaySessionRules.closeAbandoned(row.startedAtEpochMs, row.lastHeartbeatEpochMs, row.activeDurationMs)
            dao.finish(row.sessionId, close.endedAtEpochMs, close.activeDurationMs, close.reason.key)
        }
        changed()
    }

    override fun begin(start: PlaySessionStart, view: () -> View?): PlayDataSession {
        live += start.sessionId
        val now = wallClock()
        val identity = start.identity
        enqueue {
            dao.upsertSession(PlaySessionEntity(
                sessionId = start.sessionId,
                namespaceId = identity.namespaceId,
                contentId = identity.contentId,
                serverKey = identity.binding.serverKey,
                accountId = identity.binding.accountId,
                romId = identity.binding.romId,
                fileId = identity.binding.files.minByOrNull { it.discIndex }?.fileId,
                platformSlug = start.platformSlug,
                deviceId = start.deviceId,
                coreId = start.coreId,
                startedAtEpochMs = now,
                endedAtEpochMs = null,
                lastHeartbeatEpochMs = now,
                activeDurationMs = 0L,
                endReason = null,
            ))
            changed()
        }
        return Session(start, view)
    }

    private inner class Session(private val start: PlaySessionStart, private val view: () -> View?) : PlayDataSession {
        private val clock = ActivePlayClock(monotonic)
        private val lock = Any()
        private var finished = false
        private var ticker: Job? = null
        private var pendingFrame: Deferred<Bitmap?>? = null

        private fun key(checkpointId: String) = SaveKey(start.identity.namespaceId, start.identity.contentId, checkpointId)

        private fun heartbeat() {
            val beat = wallClock()
            val active = synchronized(lock) { clock.activeMs() }
            enqueue { dao.heartbeat(start.sessionId, beat, active) }
        }

        override fun onStatus(status: ProgressSessionStatus) {
            val terminal = status.phase == ProgressSessionPhase.CLOSED || status.phase == ProgressSessionPhase.STOPPED
            val (wasRunning, isRunning) = synchronized(lock) {
                if (finished) return
                val before = clock.isRunning
                clock.setRunning(status.phase == ProgressSessionPhase.RUNNING)
                before to clock.isRunning
            }
            if (terminal) { finish(status); return }
            if (wasRunning != isRunning) {
                heartbeat()
                if (isRunning) startTicker() else { ticker?.cancel(); ticker = null }
            }
        }

        private fun startTicker() {
            ticker?.cancel()
            ticker = scope.launch {
                while (isActive) {
                    delay(PlaySessionRules.HEARTBEAT_INTERVAL_MS)
                    heartbeat()
                }
            }
        }

        private fun finish(status: ProgressSessionStatus) {
            val (active, sinceState) = synchronized(lock) {
                if (finished) return
                finished = true
                clock.setRunning(false)
                clock.activeMs() to clock.activeSinceManualState()
            }
            ticker?.cancel()
            val last = status.lastCommitted
            val exitResume = status.phase == ProgressSessionPhase.CLOSED && last != null && last.checkpoint.captureReason == "exit" &&
                last.checkpoint.stateCompatibility != null && !last.stateRejected
            val reason = PlaySessionRules.reasonForExit(exitResume, sinceState)
            val end = wallClock()
            enqueue {
                dao.finish(start.sessionId, end, active, reason.key)
                live -= start.sessionId
                changed()
            }
        }

        override fun onCaptureStarting(includeState: Boolean) {
            if (!includeState) return
            // Engine is paused here: the surface holds exactly the frame being serialised.
            pendingFrame = try { grabFrame(view()) } catch (_: Throwable) { null }
        }

        override fun onCommitted(result: CheckpointWriteResult) {
            val frame = pendingFrame
            pendingFrame = null
            val checkpoint = result.checkpoint
            val hasState = checkpoint.stateCompatibility != null && !result.stateRejected
            if (hasState && checkpoint.captureReason == "manual") synchronized(lock) { clock.markManualState() }
            if (hasState) heartbeat()
            // A restore/reset capture happens before the restored frame is drawn; its preview is
            // copied from the source in onRestored instead (or left blank after a reset).
            if (!hasState || frame == null || checkpoint.captureReason == "restore") return
            val target = key(checkpoint.checkpointId)
            scope.launch {
                val bitmap = withTimeoutOrNull(FRAME_TIMEOUT_MS) { frame.await() } ?: return@launch
                enqueue {
                    try { thumbnails.write(target, bitmap) } finally { bitmap.recycle() }
                    changed()
                }
            }
        }

        override fun onRestored(sourceCheckpointId: String, result: CheckpointWriteResult) {
            if (result.checkpoint.stateCompatibility == null) return
            val from = key(sourceCheckpointId)
            val to = key(result.checkpoint.checkpointId)
            enqueue { thumbnails.copy(from, to); changed() }
        }
    }

    private companion object {
        const val TAG = "RommioPlayData"
        const val FRAME_TIMEOUT_MS = 3_000L
    }
}
