package io.github.bandoracer.rommio.domain.player

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bandoracer.rommio.domain.storage.AppManagedLibraryStore
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lists read a game's tier per game without resolving its core ([CoreResolver.supportTier]). It
 * must be exactly the tier `RommRepository.embeddedSupportTier(rom)` derived from the full
 * resolution before (at HEAD): Browse only when the resolution is UNSUPPORTED (no family, no
 * core configured, a file type the core does not open), otherwise the family's tier — including
 * when the core is missing or its setup failed (MISSING_CORE), which the Game page reports through
 * the resolution itself, as it did before.
 */
@RunWith(AndroidJUnit4::class)
class CoreSupportTierTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(base.cacheDir, "support-tier-${UUID.randomUUID()}").canonicalFile.apply { mkdirs() }
    private val context = object : ContextWrapper(base) { override fun getFilesDir() = File(root, "files") }
    private val library = AppManagedLibraryStore(context).apply { ensureRootLayout() }

    @After fun cleanup() { root.deleteRecursively() }

    /** The tier the repository derived from a resolution before this change. */
    private fun tierBefore(resolution: CoreResolution): EmbeddedSupportTier =
        if (resolution.capability == PlayerCapability.UNSUPPORTED) EmbeddedSupportTier.UNSUPPORTED
        else resolution.platformFamily?.supportTier ?: EmbeddedSupportTier.UNSUPPORTED

    @Test fun aGamesTierIsTheTierItsResolutionImplies() {
        val resolver = CoreCatalogResolver(context, library)
        var checked = 0
        for (family in resolver.supportedPlatforms()) for (slug in family.platformSlugs) {
            val extensions = listOf(null, "not-a-rom-type") + requireNotNull(family.defaultProfile).supportedExtensions
            for (extension in extensions) {
                assertEquals("$slug .$extension", tierBefore(resolver.resolve(slug, extension)), resolver.supportTier(slug, extension))
                checked++
            }
        }
        assertEquals(EmbeddedSupportTier.UNSUPPORTED, resolver.supportTier("not-a-platform", null))
        assertTrue(checked > 0)
    }

    @Test fun aMissingCoreKeepsTheTierItHadBefore() {
        // No bundled core and nothing installed: the core is missing on this device.
        val resolver = CoreCatalogResolver(context, library, packaged = null)
        val resolution = resolver.resolve("snes", "sfc")
        assertEquals(PlayerCapability.MISSING_CORE, resolution.capability)
        assertEquals(tierBefore(resolution), resolver.supportTier("snes", "sfc"))
        assertEquals(EmbeddedSupportTier.TOUCH_SUPPORTED, resolver.supportTier("snes", "sfc"))
        // A platform with no core in the catalogue is Browse only, before and now.
        assertEquals(PlayerCapability.UNSUPPORTED, resolver.resolve("not-a-platform", null).capability)
        assertEquals(EmbeddedSupportTier.UNSUPPORTED, resolver.supportTier("not-a-platform", null))
    }

    @Test fun aFailedCoreSetupKeepsTheTierItHadBefore() {
        val abi = requireNotNull(currentCoreAbi())
        val profile = requireNotNull(buildPlatformRuntimeFamilies().first { "snes" in it.platformSlugs }.defaultProfile)
        // A broken installation: the selection names no valid core, so inspecting it fails.
        File(library.coresDirectory(), "selected/${profile.runtimeId}-${abi.folderName}.json").apply {
            parentFile!!.mkdirs()
            writeText("{}")
        }
        val resolver = CoreCatalogResolver(context, library, packaged = null) { abi }
        val resolution = resolver.resolve("snes", "sfc")
        assertEquals(PlayerCapability.MISSING_CORE, resolution.capability)
        assertNotNull(resolution.message)
        assertEquals(tierBefore(resolution), resolver.supportTier("snes", "sfc"))
        assertEquals(EmbeddedSupportTier.TOUCH_SUPPORTED, resolver.supportTier("snes", "sfc"))
    }
}
