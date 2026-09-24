package io.github.bandoracer.rommio.domain.stats

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayStatsCalculatorTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private var counter = 0

    private fun at(day: Int, hour: Int, minute: Int = 0, second: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, minute, second).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun checkpoint(romId: Long, session: String, time: Long, reason: String = "periodic") =
        PlayCheckpoint("cp-${counter++}", romId, session, "device-a", time, reason)

    /** A session with a checkpoint every [stepSeconds] from [start] for [count] captures. */
    private fun run(romId: Long, session: String, start: Long, count: Int, stepSeconds: Long = 30) =
        (0 until count).map { checkpoint(romId, session, start + it * stepSeconds * 1000) }

    @Test
    fun emptyInputHasNoStats() {
        val stats = PlayStatsCalculator.derive(emptyList(), at(20, 12), zone)
        assertTrue(stats.isEmpty)
        assertEquals(0L, stats.totalPlayedMs)
        assertEquals(0, stats.sessionCount)
        assertNull(stats.lastPlayedAtEpochMs)
        assertEquals(0, stats.currentStreakDays)
    }

    @Test
    fun continuousCheckpointsAddUpToThePlayedTime() {
        // 21 captures 30 s apart = 10 minutes of play.
        val sessions = PlayStatsCalculator.sessions(run(7, "s1", at(20, 10), 21))
        assertEquals(1, sessions.size)
        assertEquals(10 * 60_000L, sessions.single().playedMs)
        assertEquals(21, sessions.single().checkpointCount)
        assertEquals(at(20, 10), sessions.single().startedAtEpochMs)
        assertEquals(at(20, 10, 10), sessions.single().endedAtEpochMs)
    }

    @Test
    fun schedulingJitterStillCountsAsContinuous() {
        val start = at(20, 10)
        val checkpoints = listOf(
            checkpoint(7, "s1", start),
            checkpoint(7, "s1", start + 30_040),
            checkpoint(7, "s1", start + 60_090),
        )
        assertEquals(60_000L, PlayStatsCalculator.sessions(checkpoints).single().playedMs)
    }

    @Test
    fun aLongPauseCountsAsAtMostOneCaptureInterval() {
        val start = at(20, 10)
        val checkpoints = run(7, "s1", start, 3) + // 1 min
            checkpoint(7, "s1", start + 60_000 + 15 * 60_000) // paused for 15 min, then one capture
        assertEquals(60_000L + 30_000L, PlayStatsCalculator.sessions(checkpoints).single().playedMs)
    }

    @Test
    fun checkpointsAreGroupedBySessionAndDuplicatesAndImportsIgnored() {
        val a = run(1, "s1", at(20, 9), 5) // 2 min
        val b = run(2, "s2", at(20, 11), 3) // 1 min
        val duplicate = a.first().copy()
        val imported = checkpoint(3, "import-session", at(20, 12), reason = "import")
        val sessions = PlayStatsCalculator.sessions(a + b + duplicate + imported)
        assertEquals(listOf("s2", "s1"), sessions.map { it.sessionId })
        assertEquals(listOf(60_000L, 120_000L), sessions.map { it.playedMs })
    }

    @Test
    fun gameStateAndSaveCheckpointsAtTheSameMomentDoNotAddTime() {
        val start = at(20, 10)
        // Protocol v3 writes a game-save checkpoint and a state checkpoint per capture.
        val checkpoints = (0 until 3).flatMap { i ->
            listOf(checkpoint(7, "s1", start + i * 30_000L), checkpoint(7, "s1", start + i * 30_000L))
        }
        assertEquals(60_000L, PlayStatsCalculator.sessions(checkpoints).single().playedMs)
    }

    @Test
    fun deriveTotalsPerGameAndOverall() {
        val checkpoints = run(1, "s1", at(18, 20), 41) + // 20 min, day 18
            run(1, "s2", at(19, 20), 21) + // 10 min, day 19
            run(2, "s3", at(20, 8), 11) // 5 min, day 20
        val stats = PlayStatsCalculator.derive(checkpoints, at(20, 12), zone)
        assertEquals(35 * 60_000L, stats.totalPlayedMs)
        assertEquals(3, stats.sessionCount)
        assertEquals(at(20, 8, 5), stats.lastPlayedAtEpochMs)
        assertEquals(listOf(1L, 2L), stats.games.map { it.romId })
        assertEquals(30 * 60_000L, stats.games[0].playedMs)
        assertEquals(2, stats.games[0].sessionCount)
        assertEquals(at(19, 20, 10), stats.games[0].lastPlayedAtEpochMs)
        assertEquals(listOf("s3", "s2", "s1"), stats.sessions.map { it.sessionId })
        assertEquals(3, stats.currentStreakDays)
        assertEquals(at(20, 8, 5), stats.lastPlayedByRom[2L])
    }

    @Test
    fun checkpointsFromOtherDevicesCanBeLeftOut() {
        val here = run(1, "s1", at(20, 9), 5) // 2 min on device-a
        val elsewhere = run(2, "s2", at(20, 10), 11).map { it.copy(deviceId = "device-b") } // 5 min
        val all = PlayStatsCalculator.derive(here + elsewhere, at(20, 12), zone)
        assertEquals(7 * 60_000L, all.totalPlayedMs)
        val local = PlayStatsCalculator.derive(here + elsewhere, at(20, 12), zone, onlyDeviceId = "device-a")
        assertEquals(2 * 60_000L, local.totalPlayedMs)
        assertEquals(listOf(1L), local.games.map { it.romId })
        assertTrue(PlayStatsCalculator.derive(elsewhere, at(20, 12), zone, onlyDeviceId = "device-a").isEmpty)
    }

    @Test
    fun streakCountsBackFromTodayOrYesterday() {
        val today = LocalDate.of(2026, 9, 20)
        val days = setOf(today.minusDays(1), today.minusDays(2), today.minusDays(4))
        assertEquals(2, PlayStatsCalculator.currentStreak(days, today))
        assertEquals(3, PlayStatsCalculator.currentStreak(days + today, today))
        assertEquals(0, PlayStatsCalculator.currentStreak(setOf(today.minusDays(2)), today))
        assertEquals(0, PlayStatsCalculator.currentStreak(emptySet(), today))
    }

    @Test
    fun aSessionAcrossMidnightCountsBothDays() {
        val checkpoints = run(1, "late", at(19, 23, 59), 5) // 23:59 → 00:01
        val stats = PlayStatsCalculator.derive(checkpoints, at(20, 9), zone)
        assertEquals(2, stats.currentStreakDays)
    }

    @Test
    fun streakUsesTheLocalZone() {
        // 23:30 UTC on the 19th is the 20th in UTC+2.
        val checkpoints = run(1, "s1", at(19, 23, 30), 2)
        val plusTwo = ZoneOffset.ofHours(2)
        assertEquals(1, PlayStatsCalculator.derive(checkpoints, at(20, 10), plusTwo).currentStreakDays)
        assertEquals(1, PlayStatsCalculator.derive(checkpoints, at(20, 10), zone).currentStreakDays)
        assertEquals(0, PlayStatsCalculator.derive(checkpoints, at(22, 10), plusTwo).currentStreakDays)
    }

    // ---- Phase 4: recorded sessions, with the checkpoint derivation only before them ----

    private fun recorded(
        session: String,
        romId: Long,
        start: Long,
        activeMs: Long,
        end: Long? = start + activeMs,
        reason: io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason? = io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason.RESUME,
        device: String = "device-a",
        heartbeat: Long = end ?: start,
    ) = RecordedPlaySession(session, romId, device, "snes", start, end, heartbeat, activeMs, reason)

    @Test
    fun recordedSessionsUseActiveTimeNotTheWallClockSpan() {
        // An hour on the clock, 20 minutes of active play (the rest paused or in the background).
        val stats = PlayStatsCalculator.merge(listOf(recorded("r1", 1, at(20, 9), 20 * 60_000L, end = at(20, 10))), emptyList(), at(20, 12), zone)
        assertEquals(20 * 60_000L, stats.totalPlayedMs)
        val session = stats.sessions.single()
        assertEquals(PlaySessionSource.RECORDED, session.source)
        assertEquals(io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason.RESUME, session.endReason)
        assertEquals(at(20, 10), session.endedAtEpochMs)
        assertEquals("snes", session.platformSlug)
        assertTrue(!stats.includesEstimates)
    }

    @Test
    fun derivedSessionsFillInOnlyBeforeTheFirstRecordedSession() {
        val before = run(1, "old", at(18, 20), 21) // 10 min derived, before recording began
        val alsoRecorded = run(2, "r1", at(20, 9), 11) // the same launch the table recorded
        val afterUnrecorded = run(3, "stray", at(20, 11), 11) // after the table epoch: ignored
        val rows = listOf(recorded("r1", 2, at(20, 9), 4 * 60_000L), recorded("r2", 1, at(20, 10), 6 * 60_000L))
        val stats = PlayStatsCalculator.merge(rows, before + alsoRecorded + afterUnrecorded, at(20, 12), zone)
        assertEquals(listOf("r2", "r1", "old"), stats.sessions.map { it.sessionId })
        assertEquals((4 + 6 + 10) * 60_000L, stats.totalPlayedMs)
        assertEquals(PlaySessionSource.DERIVED, stats.sessions.last().source)
        assertTrue(stats.includesEstimates)
        assertEquals(listOf(1L, 2L), stats.games.map { it.romId })
        assertEquals(16 * 60_000L, stats.games.first().playedMs)
        assertEquals("Played on the 18th and 20th only", 1, stats.currentStreakDays)
    }

    @Test
    fun withoutRecordedSessionsStatsStayDerived() {
        val checkpoints = run(1, "s1", at(20, 9), 21)
        assertEquals(PlayStatsCalculator.derive(checkpoints, at(20, 12), zone), PlayStatsCalculator.merge(emptyList(), checkpoints, at(20, 12), zone))
    }

    @Test
    fun openAndEmptyRecordedSessions() {
        // Still open (e.g. read while a game runs): ends at the last heartbeat.
        val open = recorded("open", 1, at(20, 9), 5 * 60_000L, end = null, reason = null, heartbeat = at(20, 9, 6))
        // Started but never ran (failed start): not play.
        val empty = recorded("empty", 2, at(20, 10), 0L, reason = io.github.bandoracer.rommio.domain.playdata.PlaySessionEndReason.QUIT)
        val stats = PlayStatsCalculator.merge(listOf(open, empty), emptyList(), at(20, 12), zone)
        assertEquals(listOf("open"), stats.sessions.map { it.sessionId })
        assertEquals(at(20, 9, 6), stats.sessions.single().endedAtEpochMs)
        assertEquals(null, stats.sessions.single().endReason)
    }

    @Test
    fun recordedSessionsFromOtherDevicesAreLeftOut() {
        val rows = listOf(recorded("here", 1, at(20, 9), 60_000L), recorded("there", 2, at(20, 8), 120_000L, device = "device-b"))
        val stats = PlayStatsCalculator.merge(rows, emptyList(), at(20, 12), zone, onlyDeviceId = "device-a")
        assertEquals(listOf("here"), stats.sessions.map { it.sessionId })
        // The other device's row does not move the table epoch either.
        val derived = run(3, "old", at(20, 8, 30), 3)
        assertEquals(listOf("here", "old"), PlayStatsCalculator.merge(rows, derived, at(20, 12), zone, onlyDeviceId = "device-a").sessions.map { it.sessionId })
    }
}
