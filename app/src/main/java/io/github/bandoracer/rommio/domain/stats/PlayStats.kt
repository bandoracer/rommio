package io.github.bandoracer.rommio.domain.stats

import io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One committed progress checkpoint, reduced to what Stats v1 needs. Read from the local
 * progress store; nothing here is written back or synced.
 */
data class PlayCheckpoint(
    val checkpointId: String,
    val romId: Long,
    val sessionId: String,
    val deviceId: String,
    val createdAtEpochMs: Long,
    val captureReason: String,
)

/**
 * A launch of one game: recorded in the local play-sessions table, or (for play before the table
 * existed) derived from the checkpoints that share its session id.
 */
data class PlaySession(
    val sessionId: String,
    val romId: Long,
    val deviceId: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val playedMs: Long,
    val checkpointCount: Int,
    /** Null for derived sessions and for a session still open. */
    val endReason: PlaySessionEndReason? = null,
    val platformSlug: String? = null,
    val source: PlaySessionSource = PlaySessionSource.DERIVED,
)

enum class PlaySessionSource { RECORDED, DERIVED }

/** A row of the local play-sessions table, reduced to what Stats needs. */
data class RecordedPlaySession(
    val sessionId: String,
    val romId: Long,
    val deviceId: String,
    val platformSlug: String?,
    val startedAtEpochMs: Long,
    /** Null while open; then the last heartbeat stands in for the end. */
    val endedAtEpochMs: Long?,
    val lastHeartbeatEpochMs: Long,
    val activeDurationMs: Long,
    val endReason: PlaySessionEndReason?,
)

data class GamePlayTotal(
    val romId: Long,
    val playedMs: Long,
    val sessionCount: Int,
    val lastPlayedAtEpochMs: Long,
)

data class PlayStats(
    val totalPlayedMs: Long = 0,
    val sessionCount: Int = 0,
    val lastPlayedAtEpochMs: Long? = null,
    /** Consecutive days with play, ending today (or yesterday if nothing yet today). */
    val currentStreakDays: Int = 0,
    /** Most played first. */
    val games: List<GamePlayTotal> = emptyList(),
    /** Most recent first. */
    val sessions: List<PlaySession> = emptyList(),
) {
    val isEmpty: Boolean get() = sessions.isEmpty()

    /** Some of the play shown was estimated from automatic saves (before sessions were recorded). */
    val includesEstimates: Boolean get() = sessions.any { it.source == PlaySessionSource.DERIVED }

    /** Last time each game was played, for "recently played first" ordering. */
    val lastPlayedByRom: Map<Long, Long> get() = games.associate { it.romId to it.lastPlayedAtEpochMs }
}

/**
 * Stats v1: play sessions derived from existing local checkpoints (plan, Phase 2).
 *
 * - Checkpoints are grouped by session id; each group is one session of one game.
 * - Android captures game-save data every 30 s while a game runs, so consecutive checkpoints in
 *   a session that are at most [CONTINUOUS_GAP_MS] apart count as continuous play. A longer gap
 *   (paused, backgrounded, or a capture that was skipped) counts as at most one capture interval,
 *   so time spent paused is not counted as play.
 * - Import checkpoints are not play and are ignored.
 * - With [onlyDeviceId], checkpoints captured on other devices (synced down) are left out, so
 *   Stats describe play on this device only.
 *
 * Phase 4: recorded sessions ([RecordedPlaySession]) replace this derivation; it remains only
 * for play before the first recorded session (see [merge]).
 */
object PlayStatsCalculator {
    const val CONTINUOUS_GAP_MS: Long = 30_000L
    private val ignoredReasons = setOf("import")

    fun sessions(
        checkpoints: List<PlayCheckpoint>,
        continuousGapMs: Long = CONTINUOUS_GAP_MS,
        onlyDeviceId: String? = null,
    ): List<PlaySession> {
        require(continuousGapMs > 0)
        return checkpoints
            .asSequence()
            .filter { it.captureReason !in ignoredReasons && it.sessionId.isNotBlank() }
            .filter { onlyDeviceId == null || it.deviceId == onlyDeviceId }
            .distinctBy { it.checkpointId }
            .groupBy { it.sessionId to it.romId }
            .map { (key, group) ->
                val ordered = group.sortedBy { it.createdAtEpochMs }
                val played = ordered.zipWithNext { a, b ->
                    (b.createdAtEpochMs - a.createdAtEpochMs).coerceAtLeast(0L).coerceAtMost(continuousGapMs)
                }.sum()
                PlaySession(
                    sessionId = key.first,
                    romId = key.second,
                    deviceId = ordered.first().deviceId,
                    startedAtEpochMs = ordered.first().createdAtEpochMs,
                    endedAtEpochMs = ordered.last().createdAtEpochMs,
                    playedMs = played,
                    checkpointCount = ordered.size,
                )
            }
            .sortedWith(compareByDescending<PlaySession> { it.endedAtEpochMs }.thenBy { it.sessionId })
    }

    fun derive(
        checkpoints: List<PlayCheckpoint>,
        nowEpochMs: Long,
        zone: ZoneId,
        continuousGapMs: Long = CONTINUOUS_GAP_MS,
        onlyDeviceId: String? = null,
    ): PlayStats = summarize(sessions(checkpoints, continuousGapMs, onlyDeviceId), nowEpochMs, zone)

    /** A recorded row as a Stats session; sessions with no active play are not play. */
    fun fromRecorded(row: RecordedPlaySession): PlaySession? {
        if (row.activeDurationMs <= 0L) return null
        val end = row.endedAtEpochMs ?: maxOf(row.lastHeartbeatEpochMs, row.startedAtEpochMs)
        return PlaySession(
            sessionId = row.sessionId,
            romId = row.romId,
            deviceId = row.deviceId,
            startedAtEpochMs = row.startedAtEpochMs,
            endedAtEpochMs = end,
            playedMs = row.activeDurationMs,
            checkpointCount = 0,
            endReason = row.endReason,
            platformSlug = row.platformSlug,
            source = PlaySessionSource.RECORDED,
        )
    }

    /**
     * Merge rule (docs/play-data-local.md): recorded sessions are authoritative. Checkpoint-derived
     * sessions fill in only play from before the first recorded session on this device — a derived
     * session counts when its session id has no recorded row and it started before the earliest
     * recorded start. With no recorded rows, everything is derived (Stats v1 behaviour).
     */
    fun merge(
        recorded: List<RecordedPlaySession>,
        checkpoints: List<PlayCheckpoint>,
        nowEpochMs: Long,
        zone: ZoneId,
        onlyDeviceId: String? = null,
        continuousGapMs: Long = CONTINUOUS_GAP_MS,
    ): PlayStats {
        val rows = recorded.filter { onlyDeviceId == null || it.deviceId == onlyDeviceId }
        val recordedIds = rows.map { it.sessionId }.toSet()
        val epoch = rows.minOfOrNull { it.startedAtEpochMs } ?: Long.MAX_VALUE
        val derived = sessions(checkpoints, continuousGapMs, onlyDeviceId)
            .filter { it.sessionId !in recordedIds && it.startedAtEpochMs < epoch }
        val sessions = (rows.mapNotNull(::fromRecorded) + derived)
            .sortedWith(compareByDescending<PlaySession> { it.endedAtEpochMs }.thenBy { it.sessionId })
        return summarize(sessions, nowEpochMs, zone)
    }

    fun summarize(sessions: List<PlaySession>, nowEpochMs: Long, zone: ZoneId): PlayStats {
        if (sessions.isEmpty()) return PlayStats()
        val games = sessions.groupBy { it.romId }.map { (romId, list) ->
            GamePlayTotal(
                romId = romId,
                playedMs = list.sumOf { it.playedMs },
                sessionCount = list.size,
                lastPlayedAtEpochMs = list.maxOf { it.endedAtEpochMs },
            )
        }.sortedWith(
            compareByDescending<GamePlayTotal> { it.playedMs }
                .thenByDescending { it.lastPlayedAtEpochMs }
                .thenBy { it.romId },
        )
        val playedDays = sessions.flatMap { session ->
            listOf(session.startedAtEpochMs, session.endedAtEpochMs).map { day(it, zone) }
        }.toSet()
        return PlayStats(
            totalPlayedMs = sessions.sumOf { it.playedMs },
            sessionCount = sessions.size,
            lastPlayedAtEpochMs = sessions.maxOf { it.endedAtEpochMs },
            currentStreakDays = currentStreak(playedDays, day(nowEpochMs, zone)),
            games = games,
            sessions = sessions,
        )
    }

    /**
     * Consecutive days with play ending today. A streak that ended yesterday is still current
     * (today is not over); a day with no play before that ends it.
     */
    fun currentStreak(playedDays: Set<LocalDate>, today: LocalDate): Int {
        var cursor = when {
            today in playedDays -> today
            today.minusDays(1) in playedDays -> today.minusDays(1)
            else -> return 0
        }
        var streak = 0
        while (cursor in playedDays) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun day(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}
