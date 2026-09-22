package io.github.bandoracer.rommio.domain.player

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bandoracer.rommio.domain.storage.AppManagedLibraryStore
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreLifecycleTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File
    private lateinit var library: AppManagedLibraryStore
    private lateinit var store: CoreArtifactStore
    private lateinit var resolver: CoreCatalogResolver
    private val profile = buildPlatformRuntimeFamilies().first { it.familyId == "nes" }.runtimeOptions.first()
    private val abi = AndroidCoreAbi.ARM64_V8A
    @Before fun setup() {
        root = File(base.cacheDir, "core-lifecycle-${UUID.randomUUID()}").canonicalFile.apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = File(root, "files") }
        library = AppManagedLibraryStore(context); library.ensureRootLayout()
        store = CoreArtifactStore(library.coresDirectory())
        resolver = CoreCatalogResolver(context, library, FirmwareStore(library), store) { abi }
    }
    @After fun cleanup() { root.deleteRecursively() }
    private fun legacy(bytes: ByteArray = syntheticCore()) = File(library.coresDirectory(), profile.libraryFileName).apply { writeBytes(bytes) }
    @Test fun oldManagedLibraryIsFrozenBeforeNativeHandoffAndOriginalIsRetained() {
        val old = legacy()
        val resolution = resolver.resolve("nes", "nes")
        assertEquals(PlayerCapability.READY, resolution.capability)
        val frozen = resolver.prepareCore(profile, resolution.coreIdentity!!)
        assertNotEquals(old, frozen.file); assertArrayEquals(old.readBytes(), frozen.file.readBytes())
        assertFalse(frozen.identity.approvedPin)
        old.writeText("helper replaced old flat path")
        val next = resolver.resolve("nes", "nes")
        assertEquals(PlayerCapability.READY, next.capability); assertEquals(frozen.file, next.coreLibrary)
        assertEquals(frozen, resolver.prepareCore(profile, next.coreIdentity!!))
    }
    @Test fun wrongAbiAndNonCoreFilesNeverSatisfyReadiness() {
        legacy(syntheticCore(AndroidCoreAbi.X86_64))
        assertEquals(PlayerCapability.MISSING_CORE, resolver.resolve("nes", "nes").capability)
        legacy("not a core".toByteArray())
        assertEquals(PlayerCapability.MISSING_CORE, resolver.resolve("nes", "nes").capability)
    }
    @Test fun launchRejectsChangedCoreAfterEarlierReadinessCheck() {
        val old = legacy()
        val ready = resolver.resolve("nes", "nes")
        old.writeBytes(syntheticCore().also { it[8000] = 9 })
        assertThrows(IllegalStateException::class.java) { resolver.prepareCore(profile, ready.coreIdentity!!) }
        assertNull(store.selected(profile, abi))
        val current = resolver.resolve("nes", "nes")
        val frozen = resolver.prepareCore(profile, current.coreIdentity!!)
        val stamp = frozen.file.lastModified()
        frozen.file.writeBytes(syntheticCore().also { it[8000] = 1 }); frozen.file.setLastModified(stamp)
        assertThrows(IllegalStateException::class.java) { resolver.prepareCore(profile, frozen.identity) }
    }
}
