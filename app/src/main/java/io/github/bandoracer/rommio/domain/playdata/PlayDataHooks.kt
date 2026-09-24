package io.github.bandoracer.rommio.domain.playdata

import android.view.View
import io.github.bandoracer.rommio.domain.progress.CheckpointWriteResult
import io.github.bandoracer.rommio.domain.progress.ProgressIdentity
import io.github.bandoracer.rommio.domain.progress.ProgressSessionStatus

/** What the local play data needs to know when a player session starts. */
data class PlaySessionStart(
    val sessionId: String,
    val identity: ProgressIdentity,
    val deviceId: String,
    val platformSlug: String?,
    val coreId: String?,
)

/**
 * Local play data (sessions and save-state thumbnails) observing one player session. Every call
 * is fire-and-forget: implementations must return quickly, never throw and never block or fail a
 * save. Nothing observed here is written into a checkpoint.
 */
interface PlayDataSession {
    /** Every coordinator status the session sees, including the terminal CLOSED/STOPPED. */
    fun onStatus(status: ProgressSessionStatus)

    /** The core is about to be captured (engine paused). With a state, grab the current frame. */
    fun onCaptureStarting(includeState: Boolean)

    /** A capture committed (on the coordinator's lane, in order). */
    fun onCommitted(result: CheckpointWriteResult)

    /** A restore committed [result] from [sourceCheckpointId]; the loaded moment is the source's. */
    fun onRestored(sourceCheckpointId: String, result: CheckpointWriteResult)
}

fun interface PlayDataHooks {
    /** Starts observing a session. [view] returns the player's host view while it is attached. */
    fun begin(start: PlaySessionStart, view: () -> View?): PlayDataSession
}
