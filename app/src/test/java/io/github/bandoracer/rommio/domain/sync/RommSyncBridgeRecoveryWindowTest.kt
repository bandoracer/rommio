package io.github.bandoracer.rommio.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RommSyncBridgeRecoveryWindowTest {
    @Test
    fun shouldCaptureAutoRecoveryReturnsFalseWithinSameThirtyMinuteBucket() {
        val interval = 30L * 60L * 1000L
        val bucketStart = 10L * interval

        assertFalse(
            shouldCaptureAutoRecovery(
                existingAutoCapturedAt = listOf(bucketStart + 5_000L),
                now = bucketStart + 20_000L,
                intervalMs = interval,
            ),
        )
    }

    @Test
    fun shouldCaptureAutoRecoveryReturnsTrueWhenBucketAdvances() {
        val interval = 30L * 60L * 1000L
        val previousBucket = 10L * interval
        val nextBucket = 11L * interval

        assertTrue(
            shouldCaptureAutoRecovery(
                existingAutoCapturedAt = listOf(previousBucket + 5_000L),
                now = nextBucket + 1_000L,
                intervalMs = interval,
            ),
        )
    }

    @Test
    fun recoveryRingIndexWrapsAcrossTenEntryWindow() {
        val interval = 30L * 60L * 1000L

        assertEquals(0, recoveryRingIndex(now = 10L * interval, intervalMs = interval, ringSize = 10))
        assertEquals(1, recoveryRingIndex(now = 11L * interval, intervalMs = interval, ringSize = 10))
        assertEquals(0, recoveryRingIndex(now = 20L * interval, intervalMs = interval, ringSize = 10))
    }
}
