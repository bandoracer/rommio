package io.github.bandoracer.rommio.domain.progress

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class PSPMemoryStickBundleTest {
    private fun matches(root: File, bytes: ByteArray) = PSPMemoryStickBundle.matches(root, bytes) {
        Files.getLastModifiedTime(it).to(java.util.concurrent.TimeUnit.NANOSECONDS)
    }

    private fun bundle(path: String = "SAVE.BIN", data: ByteArray = byteArrayOf(1, 2, 3)): ByteArray {
        val name = path.toByteArray()
        return ByteBuffer.allocate(12 + 24 + name.size + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RMMF0001".toByteArray()).putInt(1).putShort(name.size.toShort()).put(2).put(0)
            .putLong(data.size.toLong()).putLong(1000).putInt(0).put(name).put(data).array()
    }
    @Test fun cleanupRequiresExactFilesAndMetadata() {
        val root = Files.createTempDirectory("psp-bundle-test").toFile()
        try {
            val file = File(root, "SAVE.BIN"); file.writeBytes(byteArrayOf(1, 2, 3))
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(1_000_000))
            assertTrue(matches(root, bundle()))
            File(root, "new-empty-folder").mkdir()
            assertFalse(matches(root, bundle()))
            File(root, "new-empty-folder").delete()
            file.writeBytes(byteArrayOf(4, 5, 6))
            assertFalse(matches(root, bundle()))
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsEveryTruncationAndTrailingBytes() {
        val valid = bundle()
        for (length in 0 until valid.size) assertTrue(runCatching { PSPMemoryStickBundle.files(valid.copyOf(length)) }.isFailure)
        assertTrue(runCatching { PSPMemoryStickBundle.files(valid + 0) }.isFailure)
        assertEquals(1, PSPMemoryStickBundle.files(valid).size)
    }
    @Test fun rejectsTraversalAndMissingParent() {
        for (name in listOf("../SAVE", "/SAVE", "PSP/SAVE", "PSP\\SAVE"))
            assertTrue(name, runCatching { PSPMemoryStickBundle.files(bundle(name)) }.isFailure)
    }
    @Test fun emptyBundleStillDetectsLaterDirectories() {
        val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put("RMMF0001".toByteArray()).putInt(0).array()
        val root = Files.createTempDirectory("psp-empty-test").toFile()
        try {
            assertTrue(matches(root, bytes))
            File(root, "PSP").mkdir()
            assertFalse(matches(root, bytes))
        } finally { root.deleteRecursively() }
    }
}
