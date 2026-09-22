package io.github.bandoracer.rommio.domain.player

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bandoracer.rommio.domain.storage.AppManagedLibraryStore
import io.github.bandoracer.rommio.domain.storage.StorageAccess
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.InstallIdentity
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic firmware bytes, with injected identification hashes; no copyrighted BIOS payloads. */
@RunWith(AndroidJUnit4::class)
class FirmwareStoreTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var library: AppManagedLibraryStore
    private lateinit var store: FirmwareStore
    private val bytes = "synthetic firmware".toByteArray()
    private val wrong = "wrong bytes".toByteArray()
    private val paths = listOf("scph5500.bin", "scph5501.bin", "scph5502.bin", "dc/dc_boot.bin", "panafz10.bin", "panafz1.bin", "lynxboot.img", "bios_CD_E.bin", "bios_CD_U.bin", "bios_CD_J.bin")
    private fun hash(data: ByteArray, kind: String = "MD5") = MessageDigest.getInstance(kind).digest(data).joinToString("") { "%02x".format(it) }
    private fun profile(slug: String) = buildPlatformRuntimeFamilies().first { slug in it.platformSlugs }.let { f -> f.runtimeOptions.first { it.runtimeId == f.defaultRuntimeId } }
    private fun install(slug: String = "dreamcast", account: Int = 1, digest: String = "a".repeat(64)) =
        DownloadedRomEntity(10, 20, slug, "Game", "game.chd", "/unused", 100, 1,
            "profile-$account", "https://romm.example", account, digest)
    private fun write(file: File, data: ByteArray = bytes) = file.apply { parentFile!!.mkdirs(); writeBytes(data) }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { } catch (_: IOException) { }
    }
    @Before fun setup() {
        root = File(base.cacheDir, "firmware-${UUID.randomUUID()}").canonicalFile.apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir() = File(root, "files") }
        library = AppManagedLibraryStore(context); library.ensureRootLayout()
        store = FirmwareStore(library, paths.map { FirmwareDefinition(it, it, hash(bytes)) })
    }
    @After fun cleanup() { StorageAccess.requireRecovery(false); root.deleteRecursively() }

    @Test fun readinessReadsTheProvisionedSystemPathAndRejectsWrongBytes() = runBlocking {
        val p = profile("psx")
        write(File(library.biosDirectory(), "scph5501.bin"))
        assertEquals(listOf("scph5501.bin"), store.missing(p, "psx"))
        assertEquals(1, store.status().first { it.definition.path == "scph5501.bin" }.legacyCopies.size)
        write(File(library.systemDirectory(), "scph5501.bin"), wrong)
        assertEquals(listOf("scph5501.bin"), store.missing(p, "psx"))
        store.import("scph5501.bin") { ByteArrayInputStream(bytes) }
        assertTrue(store.missing(p, "psx").isEmpty())
        val backup = File(library.rootDirectory(), "firmware-backups/${hash(wrong, "SHA-256")}/scph5501.bin")
        assertArrayEquals(wrong, backup.readBytes())
        assertArrayEquals(bytes, File(library.biosDirectory(), "scph5501.bin").readBytes())
    }

    @Test fun wrongOrInterruptedImportLeavesThePreviousFileIntact() = runBlocking {
        store.import("scph5501.bin") { ByteArrayInputStream(bytes) }
        rejected { store.import("scph5501.bin") { ByteArrayInputStream(wrong) } }
        rejected { store.import("scph5501.bin") { object : InputStream() {
            var n = 0
            override fun read(): Int = if (++n < 3) 1 else throw IOException("Interrupted document")
        } } }
        assertArrayEquals(bytes, File(library.systemDirectory(), "scph5501.bin").readBytes())
        assertTrue(File(library.systemDirectory(), "scph5501.bin").parentFile!!.listFiles()!!.none { it.name.endsWith(".partial") })
    }

    @Test fun legacyFlatDreamcastBiosCopiesToNestedPathWithoutMovingOriginal() = runBlocking {
        val original = write(File(library.biosDirectory(), "dc_boot.bin"))
        val entry = store.status().first { it.definition.path == "dc/dc_boot.bin" }
        assertEquals(listOf(original.path), entry.legacyCopies)
        store.useLegacyCopy(entry.definition.path, original.path)
        assertArrayEquals(bytes, File(library.systemDirectory(), "dc/dc_boot.bin").readBytes())
        assertArrayEquals(bytes, original.readBytes())
        assertTrue(store.missing(profile("dreamcast"), "dreamcast").isEmpty())
    }

    @Test fun runtimeCopiesUseNestedLayoutAndDoNotBorrowMutableGlobalSaves() = runBlocking {
        store.import("dc/dc_boot.bin") { ByteArrayInputStream(bytes) }
        val oldVmu = write(File(library.systemDirectory(), "dc/vmu_save_A1.bin"), wrong)
        val first = store.prepare(install(), profile("dreamcast"))
        assertArrayEquals(bytes, File(first.systemDirectory, "dc/dc_boot.bin").readBytes())
        assertFalse(File(first.systemDirectory, "dc/vmu_save_A1.bin").exists())
        assertEquals(mapOf("dc/dc_boot.bin" to hash(bytes, "SHA-256")), first.sha256)
        val vmu = write(File(first.systemDirectory, "dc/vmu_save_A1.bin"), "new progress".toByteArray())
        assertEquals(first, store.prepare(install(), profile("dreamcast")))
        assertEquals("new progress", vmu.readText()); assertArrayEquals(wrong, oldVmu.readBytes())
        val other = store.prepare(install(account = 2), profile("dreamcast"))
        assertNotEquals(first.systemDirectory, other.systemDirectory)
        assertFalse(File(other.systemDirectory, "dc/vmu_save_A1.bin").exists())
        val changedContent = store.prepare(install(digest = "b".repeat(64)), profile("dreamcast"))
        assertNotEquals(first.systemDirectory, changedContent.systemDirectory)
    }

    @Test fun removingRequiredFirmwareInvalidatesReadinessDespiteOldRuntimeCopy() = runBlocking {
        store.import("dc/dc_boot.bin") { ByteArrayInputStream(bytes) }
        val prepared = store.prepare(install(), profile("dreamcast"))
        File(library.systemDirectory(), "dc/dc_boot.bin").delete()
        assertEquals(listOf("dc/dc_boot.bin"), store.missing(profile("dreamcast"), "dreamcast"))
        rejected { store.prepare(install(), profile("dreamcast")) }
        assertArrayEquals(bytes, File(prepared.systemDirectory, "dc/dc_boot.bin").readBytes())
    }

    @Test fun existingRuntimeFirmwareCannotBeSilentlyOverwritten() = runBlocking {
        store.import("dc/dc_boot.bin") { ByteArrayInputStream(bytes) }
        val prepared = store.prepare(install(), profile("dreamcast"))
        val runtime = write(File(prepared.systemDirectory, "dc/dc_boot.bin"), wrong)
        rejected { store.prepare(install(), profile("dreamcast")) }
        assertArrayEquals(wrong, runtime.readBytes())
    }

    @Test fun alternativeOperaBootBiosSatisfiesReadinessAndIsProvisioned() = runBlocking {
        val p = profile("3do")
        assertEquals(listOf("panafz10.bin"), store.missing(p, "3do"))
        store.import("panafz1.bin") { ByteArrayInputStream(bytes) }
        assertTrue(store.missing(p, "3do").isEmpty())
        val runtime = store.prepare(install("3do"), p)
        assertEquals(setOf("panafz1.bin"), runtime.sha256.keys)
        assertArrayEquals(bytes, File(runtime.systemDirectory, "panafz1.bin").readBytes())
    }

    @Test fun segaCdRequirementsDoNotBlockCartridgeGames() = runBlocking {
        val p = profile("genesis")
        assertTrue(store.missing(p, "genesis").isEmpty())
        assertEquals(3, store.missing(p, "segacd").size)
        for (path in listOf("bios_CD_E.bin", "bios_CD_U.bin", "bios_CD_J.bin")) store.import(path) { ByteArrayInputStream(bytes) }
        assertTrue(store.missing(p, "segacd").isEmpty())
    }

    @Test fun activeSessionAndPendingRecoveryBlockFirmwareReplacement() = runBlocking {
        val lease = StorageAccess.session()
        try { rejected { store.import("scph5501.bin") { ByteArrayInputStream(bytes) } } } finally { lease.close() }
        StorageAccess.requireRecovery(true)
        rejected { store.import("scph5501.bin") { ByteArrayInputStream(bytes) } }
        StorageAccess.requireRecovery(false)
        assertFalse(File(library.systemDirectory(), "scph5501.bin").exists())
    }

    @Test fun linkedFirmwareAndTraversalDefinitionsCannotEscapeTheLibrary() = runBlocking {
        val external = write(File(root, "outside.bin"))
        Files.createSymbolicLink(File(library.systemDirectory(), "scph5501.bin").toPath(), external.toPath())
        assertEquals(listOf("scph5501.bin"), store.missing(profile("psx"), "psx"))
        rejected { store.import("scph5501.bin") { ByteArrayInputStream(bytes) } }
        rejected { FirmwareStore(library, listOf(FirmwareDefinition("../escape", "bad", hash(bytes)))) }
        assertArrayEquals(bytes, external.readBytes())
    }

    @Test fun resolverUsesTheSameFirmwareValidationAsRuntimePreparation() = runBlocking {
        val resolver = CoreCatalogResolver(context, library, store)
        assertEquals(PlayerCapability.MISSING_BIOS, resolver.resolve("psx", "chd").capability)
        store.import("scph5501.bin") { ByteArrayInputStream(bytes) }
        assertEquals(PlayerCapability.MISSING_CORE, resolver.resolve("psx", "chd").capability)
        val runtime = store.prepare(install("psx"), profile("psx"))
        assertEquals(hash(bytes, "SHA-256"), runtime.sha256["scph5501.bin"])
    }

    @Test fun cachedOptionalFirmwareRemainsPartOfTheActualRuntimeIdentity() = runBlocking {
        store.import("scph5501.bin") { ByteArrayInputStream(bytes) }
        store.import("scph5500.bin") { ByteArrayInputStream(bytes) }
        val prepared = store.prepare(install("psx"), profile("psx"))
        File(library.systemDirectory(), "scph5500.bin").delete()
        val again = store.prepare(install("psx"), profile("psx"))
        assertEquals(prepared.sha256, again.sha256)
        assertEquals(2, again.sha256.size)
    }

    @Test fun addingAnotherBiosDoesNotChangeAnExistingGamesFirmwareSelection() = runBlocking {
        store.import("scph5501.bin") { ByteArrayInputStream(bytes) }
        val existing = store.prepare(install("psx"), profile("psx"))
        store.import("scph5500.bin") { ByteArrayInputStream(bytes) }
        val again = store.prepare(install("psx"), profile("psx"))
        assertEquals(existing, again)
        assertFalse(File(again.systemDirectory, "scph5500.bin").exists())
        val otherGame = store.prepare(install("psx", digest = "b".repeat(64)), profile("psx"))
        assertEquals(2, otherGame.sha256.size)
    }
}
