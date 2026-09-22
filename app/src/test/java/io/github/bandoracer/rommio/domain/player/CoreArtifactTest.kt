package io.github.bandoracer.rommio.domain.player

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test


class CoreArtifactTest {
    private val root = Files.createTempDirectory("core-artifacts").toFile()
    private val profile = buildPlatformRuntimeFamilies().first { it.familyId == "nes" }.runtimeOptions.first()
    private fun source(bytes: ByteArray = syntheticCore()) = File(root, "source.so").apply { writeBytes(bytes) }
    private fun reject(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { } }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun validatesSupportedArchitecturesAndRejectsWrongAbi() {
        AndroidCoreAbi.entries.forEach { abi ->
            val file = source(syntheticCore(abi)); assertEquals(CoreBinary.sha256(file), CoreBinary.validate(file, abi))
            reject { CoreBinary.validate(file, if (abi == AndroidCoreAbi.ARM64_V8A) AndroidCoreAbi.X86_64 else AndroidCoreAbi.ARM64_V8A) }
        }
    }
    @Test fun rejectsHtmlTruncationMalformedOffsetsAndMissingEntryPoints() {
        reject { CoreBinary.validate(source(ByteArray(100) { 32 }), AndroidCoreAbi.ARM64_V8A) }
        reject { CoreBinary.validate(source(syntheticCore().copyOf(600)), AndroidCoreAbi.ARM64_V8A) }
        val malformed = syntheticCore(); ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putLong(40, Long.MAX_VALUE)
        reject { CoreBinary.validate(source(malformed), AndroidCoreAbi.ARM64_V8A) }
        reject { CoreBinary.validate(source(syntheticCore(exports = CoreBinary.exports - "retro_run")), AndroidCoreAbi.ARM64_V8A) }
    }
    @Test fun artifactSelectionSurvivesReopenAndRecordsObservedVersusApprovedIdentity() {
        val store = CoreArtifactStore(File(root, "cores")); val file = source()
        val installed = store.install(profile, AndroidCoreAbi.ARM64_V8A, file, "download", "https://example/core.zip")
        assertFalse(installed.identity.approvedPin); assertNotEquals(file.path, installed.file.path)
        assertEquals(installed, CoreArtifactStore(File(root, "cores")).selected(profile, AndroidCoreAbi.ARM64_V8A))
        val pinned = store.install(profile, AndroidCoreAbi.ARM64_V8A, file, "download", expectedSha256 = CoreBinary.sha256(file))
        assertTrue(pinned.identity.approvedPin); assertTrue(installed.file.exists())
    }
    @Test fun failedReplacementPreservesSelectionAndPreviousBytes() {
        val store = CoreArtifactStore(File(root, "cores")); val previous = store.install(profile, AndroidCoreAbi.ARM64_V8A, source(), "legacy")
        val before = previous.file.readBytes()
        reject { store.install(profile, AndroidCoreAbi.ARM64_V8A, source(syntheticCore(AndroidCoreAbi.X86_64)), "download") }
        reject { store.install(profile, AndroidCoreAbi.ARM64_V8A, source(), "download", expectedSha256 = "0".repeat(64)) }
        assertEquals(previous, store.selected(profile, AndroidCoreAbi.ARM64_V8A, fresh = true))
        assertArrayEquals(before, previous.file.readBytes())
    }
    @Test fun freshLaunchDetectsEvenSameSizeSameTimestampTampering() {
        val store = CoreArtifactStore(File(root, "cores")); val original = source()
        val previous = store.install(profile, AndroidCoreAbi.ARM64_V8A, original, "legacy")
        store.selected(profile, AndroidCoreAbi.ARM64_V8A)
        val timestamp = previous.file.lastModified(); val modified = previous.file.readBytes(); modified[8000] = 1
        previous.file.writeBytes(modified); previous.file.setLastModified(timestamp)
        reject { store.selected(profile, AndroidCoreAbi.ARM64_V8A, fresh = true) }
        val repaired = store.install(profile, AndroidCoreAbi.ARM64_V8A, original, "download")
        assertNotEquals(previous.file, repaired.file); assertArrayEquals(modified, previous.file.readBytes())
    }
    @Test fun invalidSelectionNeverFallsBackToAnUnrelatedFile() {
        val store = CoreArtifactStore(File(root, "cores")); store.install(profile, AndroidCoreAbi.ARM64_V8A, source(), "legacy")
        File(root, "cores/selected/${profile.runtimeId}-arm64-v8a.json").writeText("{}")
        org.junit.Assert.assertThrows(com.squareup.moshi.JsonDataException::class.java) { store.selected(profile, AndroidCoreAbi.ARM64_V8A) }
    }
    @Test fun linkedArtifactStorageCannotWriteOutsideItsRoot() {
        val outside = File(root, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "cores").toPath(), outside.toPath())
        reject { CoreArtifactStore(File(root, "cores")).install(profile, AndroidCoreAbi.ARM64_V8A, source(), "legacy") }
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test fun interruptionBetweenArtifactAndSelectionPreservesThePreviousCore() {
        var interrupt = false
        val store = CoreArtifactStore(File(root, "cores")) { if (interrupt && it == "ARTIFACT_INSTALLED") error("Interrupted publication") }
        val previous = store.install(profile, AndroidCoreAbi.ARM64_V8A, source(), "download")
        interrupt = true
        reject { store.install(profile, AndroidCoreAbi.ARM64_V8A, source(syntheticCore().also { it[8000] = 9 }), "download") }
        assertEquals(previous, CoreArtifactStore(File(root, "cores")).selected(profile, AndroidCoreAbi.ARM64_V8A, fresh = true))
        assertArrayEquals(syntheticCore(), previous.file.readBytes())
    }
}
