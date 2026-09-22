package io.github.bandoracer.rommio.domain.storage

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilesTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun failedWriteLeavesPreviousBytesAndCleansStaging() {
        val target = directory.newFile("save.srm").apply { writeText("last good progress") }
        assertThrows(IOException::class.java) {
            AtomicFiles.write(target) {
                it.write("partial new progress".toByteArray())
                throw IOException("disk full")
            }
        }
        assertEquals("last good progress", target.readText())
        assertEquals(listOf("save.srm"), directory.root.list()!!.toList())
    }

    @Test fun successiveResumeWritesAdvanceWithoutChangingManualSlotZero() {
        val slot = directory.newFile("42_slot0.state").apply { writeText("manual") }
        val resume = directory.newFile("__rommio_resume_42_17.state").apply { writeText("legacy") }
        AtomicFiles.writeResume(resume, "first".toByteArray())
        AtomicFiles.writeResume(resume, "second".toByteArray())
        assertEquals("second", resume.readText())
        assertEquals("manual", slot.readText())
        assertEquals("legacy", File(resume.parentFile, resume.name + ".before-resume-fix").readText())
    }
}
