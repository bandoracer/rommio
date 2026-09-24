package io.github.bandoracer.rommio.domain.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreInventoryTest {
    private val nes = RuntimeProfile("fceumm", "FCEUmm", setOf("nes"), "libfceumm_libretro_android.so",
        download = CoreDownloadDescriptor(CoreDistributionProvider.LIBRETRO_BUILDBOT, "fceumm"))
    private val local = RuntimeProfile("local", "Local core", setOf("x"), "liblocal.so")
    private fun family(profile: RuntimeProfile, tier: EmbeddedSupportTier = EmbeddedSupportTier.TOUCH_SUPPORTED) =
        PlatformRuntimeFamily("fam-${profile.runtimeId}", "Family ${profile.runtimeId}", profile.platformSlugs, tier, profile.runtimeId, listOf(profile))

    @Test
    fun capabilitiesMapToTheIosStatusLabels() {
        val f = family(nes)
        assertEquals("Ready", CoreInventory.entry(f, CoreResolution(PlayerCapability.READY, f, nes)).status.label)
        val bios = CoreInventory.entry(f, CoreResolution(PlayerCapability.MISSING_BIOS, f, nes, missingBios = listOf("scph5501.bin")))
        assertEquals(CoreInventoryStatus.NEEDS_BIOS, bios.status)
        assertEquals("Needs BIOS", bios.status.label)
        assertEquals(listOf("scph5501.bin"), bios.missingBios)
        assertEquals(CoreInventoryStatus.SETS_UP_ON_FIRST_PLAY, CoreInventory.entry(f, CoreResolution(PlayerCapability.MISSING_CORE, f, nes)).status)
        val g = family(local)
        assertEquals(CoreInventoryStatus.NOT_INCLUDED, CoreInventory.entry(g, CoreResolution(PlayerCapability.MISSING_CORE, g, local)).status)
        assertEquals(CoreInventoryStatus.UNSUPPORTED, CoreInventory.entry(f, CoreResolution(PlayerCapability.UNSUPPORTED, f)).status)
        assertEquals("FCEUmm", CoreInventory.entry(f, CoreResolution(PlayerCapability.UNSUPPORTED, f)).runtimeName)
    }

    @Test
    fun onlyShippedOrInstallableCoresAreInTheBuild() {
        assertTrue(CoreInventoryStatus.READY.inBuild)
        assertTrue(CoreInventoryStatus.SETS_UP_ON_FIRST_PLAY.inBuild)
        assertTrue(CoreInventoryStatus.NEEDS_BIOS.inBuild)
        assertFalse(CoreInventoryStatus.NOT_INCLUDED.inBuild)
        assertFalse(CoreInventoryStatus.UNSUPPORTED.inBuild)
    }

    @Test
    fun theSettingsSubtitleCountsReadyAndBios() {
        fun entry(status: CoreInventoryStatus) = CoreInventoryEntry("f", "F", "c", EmbeddedSupportTier.TOUCH_SUPPORTED, status)
        assertEquals("2 ready · 1 need BIOS", CoreInventory.summary(listOf(entry(CoreInventoryStatus.READY), entry(CoreInventoryStatus.READY), entry(CoreInventoryStatus.NEEDS_BIOS))))
        assertEquals("1 ready", CoreInventory.summary(listOf(entry(CoreInventoryStatus.READY), entry(CoreInventoryStatus.SETS_UP_ON_FIRST_PLAY))))
        assertEquals("0 ready", CoreInventory.summary(emptyList()))
    }

    @Test
    fun theWholeCatalogueResolves() {
        val resolver = object : CoreResolver {
            val families = buildPlatformRuntimeFamilies()
            override fun resolve(platformSlug: String, fileExtension: String?): CoreResolution {
                val family = families.first { platformSlug in it.platformSlugs }
                return CoreResolution(PlayerCapability.MISSING_CORE, family, family.runtimeOptions.first { it.runtimeId == family.defaultRuntimeId })
            }
            override fun prepareCore(profile: RuntimeProfile, expected: CoreArtifactIdentity): InstalledCore = error("unused")
            override fun platformSupport(platformSlug: String): PlatformRuntimeFamily? = families.firstOrNull { platformSlug in it.platformSlugs }
            override fun supportedPlatforms(): List<PlatformRuntimeFamily> = families
        }
        val entries = CoreInventory.resolve(resolver)
        assertEquals(resolver.families.size, entries.size)
        assertTrue(entries.all { it.status == CoreInventoryStatus.SETS_UP_ON_FIRST_PLAY })
    }
}
