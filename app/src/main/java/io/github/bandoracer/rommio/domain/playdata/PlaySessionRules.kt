package io.github.bandoracer.rommio.domain.playdata

/**
 * How a play session ended (docs/play-data-local.md, "End reasons"). The key is what the local
 * table stores; both platforms use the same keys.
 */
enum class PlaySessionEndReason(val key: String) {
    /** The player left the game and the exit capture updated the automatic resume point. */
    RESUME("resume"),

    /** The player made a save state and left without playing on (under [PlaySessionRules.STATE_GRACE_MS]). */
    STATE("state"),

    /** The player left without a new resume point (state capture failed or unsupported, left
     * without the latest progress, or the core stopped). */
    QUIT("quit"),

    /** The session was never closed by the player: the process ended (usually in the background)
     * and the session was closed on the next launch at its last heartbeat. */
    BACKGROUND("background");

    companion object {
        fun fromKey(key: String?): PlaySessionEndReason? = entries.firstOrNull { it.key == key }
    }
}

object PlaySessionRules {
    /** Leaving within this much active play after a manual save state counts as ending on it. */
    const val STATE_GRACE_MS: Long = 5_000L

    /** How often an open session's heartbeat and active time are written while it runs. */
    const val HEARTBEAT_INTERVAL_MS: Long = 30_000L

    /**
     * End reason for a session the player closed.
     *
     * @param exitUpdatedResume the final capture committed a resume point.
     * @param activeMsSinceManualState active play since the last manual save state of this
     *   session, or null when the session made none.
     */
    fun reasonForExit(exitUpdatedResume: Boolean, activeMsSinceManualState: Long?): PlaySessionEndReason = when {
        activeMsSinceManualState != null && activeMsSinceManualState < STATE_GRACE_MS -> PlaySessionEndReason.STATE
        exitUpdatedResume -> PlaySessionEndReason.RESUME
        else -> PlaySessionEndReason.QUIT
    }

    /**
     * Closes a session left open by process death: it ends at its last heartbeat (never later,
     * and never before it started), keeping the active time recorded at that heartbeat.
     */
    fun closeAbandoned(startedAtEpochMs: Long, lastHeartbeatEpochMs: Long, activeDurationMs: Long): AbandonedClose {
        val end = maxOf(startedAtEpochMs, lastHeartbeatEpochMs)
        val active = activeDurationMs.coerceIn(0L, end - startedAtEpochMs)
        return AbandonedClose(end, active, PlaySessionEndReason.BACKGROUND)
    }

    data class AbandonedClose(val endedAtEpochMs: Long, val activeDurationMs: Long, val reason: PlaySessionEndReason)
}

/**
 * Active play time for one session: only time spent running (unpaused, in the foreground)
 * counts. Driven by phase changes; [now] is a monotonic clock in milliseconds.
 */
class ActivePlayClock(private val now: () -> Long) {
    private var runningSince: Long? = null
    private var accumulated: Long = 0L
    private var manualStateMark: Long? = null

    val isRunning: Boolean get() = runningSince != null

    fun setRunning(running: Boolean) {
        val t = now()
        val since = runningSince
        if (running && since == null) runningSince = t
        if (!running && since != null) {
            accumulated += (t - since).coerceAtLeast(0L)
            runningSince = null
        }
    }

    fun activeMs(): Long = accumulated + (runningSince?.let { (now() - it).coerceAtLeast(0L) } ?: 0L)

    /** A manual save state committed now. */
    fun markManualState() { manualStateMark = activeMs() }

    /** Active play since the last manual save state, or null when there was none. */
    fun activeSinceManualState(): Long? = manualStateMark?.let { activeMs() - it }
}
