package io.github.bandoracer.rommio.domain.player

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bandoracer.rommio.domain.storage.AppManagedLibraryStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PackagedCoreArtifactsTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val profile = buildPlatformRuntimeFamilies().first { it.familyId == "nes" }.runtimeOptions.first()
    private val abi = requireNotNull(currentCoreAbi())
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var library: AppManagedLibraryStore
    private lateinit var store: CoreArtifactStore
    private lateinit var packaged: PackagedCoreArtifacts
    @Before fun setup() {
        root = File(base.filesDir, "packaged-core-${UUID.randomUUID()}").canonicalFile.apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        library = AppManagedLibraryStore(context).apply { ensureRootLayout() }
        store = CoreArtifactStore(library.coresDirectory())
        packaged = PackagedCoreArtifacts(context, library, store)
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun everyBundledAbiHasVerifiedBytesAndReopensWithExactProvenance() {
        AndroidCoreAbi.entries.forEach { target ->
            val manifest = requireNotNull(packaged.find(profile, target))
            val installed = packaged.install(profile, target, manifest.identity)
            assertEquals(manifest.identity, installed.identity)
            assertTrue(installed.identity.approvedPin)
            assertEquals("packaged-source", installed.identity.source)
            assertEquals(manifest.file.sha256, CoreBinary.validate(installed.file, target))
            assertEquals(installed, CoreArtifactStore(library.coresDirectory()).selected(profile, target, fresh = true))
        }
    }

    @Test fun freshReadinessAndLaunchInstallWithoutNetwork() = runBlocking {
        val resolver = CoreCatalogResolver(context, library, FirmwareStore(library), store, packaged) { abi }
        val ready = resolver.resolve("nes", "nes")
        assertEquals(PlayerCapability.READY, ready.capability)
        assertNull(ready.coreLibrary)
        assertNull(store.selected(profile, abi))
        val installed = resolver.prepareCore(profile, requireNotNull(ready.coreIdentity))
        assertEquals(installed.identity, ready.coreIdentity)
        assertEquals(installed.file, resolver.resolve("nes", "nes").coreLibrary)
        val networkForbidden = OkHttpClient.Builder().addInterceptor { error("Bundled installation attempted HTTP") }.build()
        assertEquals(installed.file, LibretroCoreInstaller(library, store, networkForbidden, packaged) { abi }.installCore(profile))
        // Exercise the install action itself from an empty selection, separately from launch.
        File(library.coresDirectory(), "selected").deleteRecursively()
        val fromAction = LibretroCoreInstaller(library, store, networkForbidden, packaged) { abi }.installCore(profile)
        assertEquals(installed.identity.sha256, CoreBinary.validate(fromAction, abi))
        assertTrue(installed.file.exists())
    }

    @Test fun existingSelectionAndLegacyBytesAreNotAutomaticallyUpgraded() = runBlocking {
        val legacy = File(library.coresDirectory(), profile.libraryFileName).apply { writeBytes(syntheticCore(abi)) }
        val resolver = CoreCatalogResolver(context, library, FirmwareStore(library), store, packaged) { abi }
        val ready = resolver.resolve("nes", "nes")
        assertEquals(legacy, ready.coreLibrary)
        val frozen = resolver.prepareCore(profile, requireNotNull(ready.coreIdentity))
        assertFalse(frozen.identity.approvedPin)
        val forbidden = OkHttpClient.Builder().addInterceptor { error("Existing selection attempted HTTP") }.build()
        assertEquals(frozen.file, LibretroCoreInstaller(library, store, forbidden, packaged) { abi }.installCore(profile))
        assertArrayEquals(legacy.readBytes(), frozen.file.readBytes())
    }

    @Test fun corruptAssetsAndChangedSelectionPreservePreviouslyInstalledCore() {
        val previous = packaged.install(profile, abi)
        val expected = requireNotNull(packaged.find(profile, abi))
        val bytes = base.assets.open(expected.file.assetPath).use { it.readBytes() }
        val manifest = base.assets.open("cores/fceumm/manifest.json").use { it.readBytes() }
        fun rejected(asset: ByteArray, metadata: ByteArray = manifest) {
            val invalid = PackagedCoreArtifacts(library.coresDirectory(), store) { path ->
                if (path.endsWith("manifest.json")) metadata.inputStream() else asset.inputStream()
            }
            assertThrows(IllegalStateException::class.java) { invalid.install(profile, abi) }
            assertEquals(previous, store.selected(profile, abi, fresh = true))
        }
        rejected(bytes.copyOf(bytes.size - 1))
        rejected(bytes + byteArrayOf(0))
        rejected(bytes.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        // Even an internally consistent manifest cannot authorize a binary for the wrong ABI.
        val other = AndroidCoreAbi.entries.first { it != abi }
        val wrong = requireNotNull(packaged.find(profile, other))
        val wrongBytes = base.assets.open(wrong.file.assetPath).use { it.readBytes() }
        rejected(wrongBytes, manifest.toString(Charsets.UTF_8)
            .replace(expected.file.sha256, wrong.file.sha256)
            .replace("\"sizeBytes\": ${expected.file.sizeBytes}", "\"sizeBytes\": ${wrong.file.sizeBytes}").toByteArray())
        assertThrows(IllegalStateException::class.java) {
            packaged.install(profile, abi, expected.identity.copy(sha256 = "0".repeat(64)))
        }
        assertEquals(previous, store.selected(profile, abi, fresh = true))
        assertTrue(File(library.coresDirectory(), "staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun privateMemoryIdsRequireExactBundledBytesRegardlessOfImportedApprovalFlags() {
        val installed = packaged.install(profile, abi)
        assertEquals(PersistentMemory.FCEUMM_SEGMENTS_V2, packaged.verifiedMemoryInventory(profile, installed))
        val unknown = File(root, "unknown.so").apply { writeBytes(syntheticCore(abi)) }
        val identity = installed.identity.copy(sha256 = CoreBinary.sha256(unknown), sizeBytes = unknown.length(), approvedPin = true)
        assertEquals(PersistentMemory.STANDARD, packaged.verifiedMemoryInventory(profile, InstalledCore(unknown, identity)))
        assertEquals(PersistentMemory.STANDARD, packaged.verifiedMemoryInventory(profile, installed.copy(identity = installed.identity.copy(runtimeId = "unknown"))))
        // Copying trusted metadata onto different bytes must fail before any private-ID query.
        assertThrows(IllegalStateException::class.java) {
            packaged.verifiedMemoryInventory(profile, InstalledCore(unknown, installed.identity))
        }
    }

    @Test fun invalidManifestCannotBecomeReadyOrFallBackToNetwork() = runBlocking {
        val bad = PackagedCoreArtifacts(library.coresDirectory(), store) { "{}".byteInputStream() }
        val resolver = CoreCatalogResolver(context, library, FirmwareStore(library), store, bad) { abi }
        assertEquals(PlayerCapability.MISSING_CORE, resolver.resolve("nes", "nes").capability)
        var networkRequests = 0
        val client = OkHttpClient.Builder().addInterceptor { networkRequests++; error("Unexpected network") }.build()
        val error = runCatching { LibretroCoreInstaller(library, store, client, bad) { abi }.installCore(profile) }.exceptionOrNull()
        assertNotNull(error)
        assertEquals(0, networkRequests)
        assertNull(store.selected(profile, abi))
    }
}
