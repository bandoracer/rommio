package io.github.bandoracer.rommio.domain.playdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySessionRulesTest {
    @Test
    fun exitWithAResumePointEndsOnResume() {
        assertEquals(PlaySessionEndReason.RESUME, PlaySessionRules.reasonForExit(exitUpdatedResume = true, activeMsSinceManualState = null))
        assertEquals(PlaySessionEndReason.RESUME, PlaySessionRules.reasonForExit(true, activeMsSinceManualState = 60_000))
    }

    @Test
    fun leavingRightAfterASaveStateEndsOnTheState() {
        assertEquals(PlaySessionEndReason.STATE, PlaySessionRules.reasonForExit(true, activeMsSinceManualState = 0))
        assertEquals(PlaySessionEndReason.STATE, PlaySessionRules.reasonForExit(true, activeMsSinceManualState = PlaySessionRules.STATE_GRACE_MS - 1))
        assertEquals(PlaySessionEndReason.RESUME, PlaySessionRules.reasonForExit(true, activeMsSinceManualState = PlaySessionRules.STATE_GRACE_MS))
        // Even when the exit's state capture failed, the player left on the state they made.
        assertEquals(PlaySessionEndReason.STATE, PlaySessionRules.reasonForExit(false, activeMsSinceManualState = 1_000))
    }

    @Test
    fun exitWithoutANewResumePointIsQuit() {
        assertEquals(PlaySessionEndReason.QUIT, PlaySessionRules.reasonForExit(exitUpdatedResume = false, activeMsSinceManualState = null))
        assertEquals(PlaySessionEndReason.QUIT, PlaySessionRules.reasonForExit(false, activeMsSinceManualState = 10 * 60_000))
    }

    @Test
    fun abandonedSessionsEndAtTheirLastHeartbeatInTheBackground() {
        val close = PlaySessionRules.closeAbandoned(startedAtEpochMs = 1_000, lastHeartbeatEpochMs = 61_000, activeDurationMs = 45_000)
        assertEquals(61_000L, close.endedAtEpochMs)
        assertEquals(45_000L, close.activeDurationMs)
        assertEquals(PlaySessionEndReason.BACKGROUND, close.reason)
    }

    @Test
    fun abandonedSessionsNeverEndBeforeTheyStartOrClaimMoreThanTheirSpan() {
        val backwards = PlaySessionRules.closeAbandoned(startedAtEpochMs = 5_000, lastHeartbeatEpochMs = 4_000, activeDurationMs = 3_000)
        assertEquals(5_000L, backwards.endedAtEpochMs)
        assertEquals(0L, backwards.activeDurationMs)
        val tooLong = PlaySessionRules.closeAbandoned(startedAtEpochMs = 0, lastHeartbeatEpochMs = 10_000, activeDurationMs = 25_000)
        assertEquals(10_000L, tooLong.activeDurationMs)
    }

    @Test
    fun endReasonKeysRoundTrip() {
        PlaySessionEndReason.entries.forEach { assertEquals(it, PlaySessionEndReason.fromKey(it.key)) }
        assertEquals(listOf("resume", "state", "quit", "background"), PlaySessionEndReason.entries.map { it.key })
        assertNull(PlaySessionEndReason.fromKey(null))
        assertNull(PlaySessionEndReason.fromKey("crashed"))
    }

    @Test
    fun activeClockCountsOnlyRunningTime() {
        var now = 0L
        val clock = ActivePlayClock { now }
        assertEquals(0L, clock.activeMs())
        clock.setRunning(true); now = 10_000
        assertEquals(10_000L, clock.activeMs())
        clock.setRunning(false); now = 70_000 // paused for a minute
        assertEquals(10_000L, clock.activeMs())
        clock.setRunning(true); now = 75_000
        clock.setRunning(true) // repeated states do not restart the span
        now = 80_000
        assertEquals(20_000L, clock.activeMs())
        assertTrue(clock.isRunning)
        clock.setRunning(false)
        assertFalse(clock.isRunning)
    }

    @Test
    fun activeClockMeasuresPlayAfterTheLastManualState() {
        var now = 0L
        val clock = ActivePlayClock { now }
        assertNull(clock.activeSinceManualState())
        clock.setRunning(true); now = 30_000
        clock.markManualState()
        assertEquals(0L, clock.activeSinceManualState())
        now = 32_000
        assertEquals(2_000L, clock.activeSinceManualState())
        clock.setRunning(false); now = 600_000 // paused time is not play
        assertEquals(2_000L, clock.activeSinceManualState())
    }
}
