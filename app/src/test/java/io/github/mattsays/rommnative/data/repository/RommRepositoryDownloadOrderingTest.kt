package io.github.mattsays.rommnative.data.repository

import io.github.mattsays.rommnative.model.DownloadRecordEntity
import io.github.mattsays.rommnative.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RommRepositoryDownloadOrderingTest {
    @Test
    fun nextQueuedDownloadReturnsOldestQueuedRecord() {
        val records = listOf(
            record(romId = 1, fileId = 10, status = DownloadStatus.FAILED, enqueuedAt = 50),
            record(romId = 2, fileId = 20, status = DownloadStatus.QUEUED, enqueuedAt = 30),
            record(romId = 3, fileId = 30, status = DownloadStatus.RUNNING, enqueuedAt = 10),
            record(romId = 4, fileId = 40, status = DownloadStatus.QUEUED, enqueuedAt = 15),
        )

        val next = nextQueuedDownload(records)

        assertEquals(4, next?.romId)
        assertEquals(40, next?.fileId)
    }

    @Test
    fun nextQueuedDownloadReturnsNullWhenQueueIsEmpty() {
        val records = listOf(
            record(romId = 1, fileId = 10, status = DownloadStatus.RUNNING, enqueuedAt = 10),
            record(romId = 2, fileId = 20, status = DownloadStatus.COMPLETED, enqueuedAt = 20),
        )

        assertNull(nextQueuedDownload(records))
    }

    @Test
    fun nextPriorityEnqueuedAtMovesRecordAheadOfCurrentQueue() {
        val records = listOf(
            record(romId = 2, fileId = 20, status = DownloadStatus.QUEUED, enqueuedAt = 30),
            record(romId = 4, fileId = 40, status = DownloadStatus.QUEUED, enqueuedAt = 15),
            record(romId = 1, fileId = 10, status = DownloadStatus.RUNNING, enqueuedAt = 10),
        )

        val prioritizedAt = nextPriorityEnqueuedAt(records, now = 100)

        assertEquals(14L, prioritizedAt)
    }

    private fun record(
        romId: Int,
        fileId: Int,
        status: DownloadStatus,
        enqueuedAt: Long,
    ) = DownloadRecordEntity(
        romId = romId,
        fileId = fileId,
        romName = "ROM $romId",
        platformSlug = "nes",
        fileName = "file-$fileId.zip",
        fileSizeBytes = 1024,
        workId = null,
        status = status.name,
        progressPercent = 0,
        bytesDownloaded = 0,
        totalBytes = 1024,
        localPath = null,
        lastError = null,
        enqueuedAtEpochMs = enqueuedAt,
        startedAtEpochMs = null,
        completedAtEpochMs = null,
        updatedAtEpochMs = enqueuedAt,
    )
}
