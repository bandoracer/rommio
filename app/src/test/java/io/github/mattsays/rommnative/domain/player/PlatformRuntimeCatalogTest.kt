package io.github.mattsays.rommnative.domain.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformRuntimeCatalogTest {
    private val families = buildPlatformRuntimeFamilies().associateBy { it.familyId }

    @Test
    fun `mainline expansion families are present with controller support tiers`() {
        assertFamily(
            familyId = "dreamcast",
            expectedDefaultRuntime = "flycast",
            expectedSupportTier = EmbeddedSupportTier.CONTROLLER_SUPPORTED,
            expectedSlugs = setOf("dreamcast", "naomi"),
        )
        assertFamily(
            familyId = "3do",
            expectedDefaultRuntime = "opera",
            expectedSupportTier = EmbeddedSupportTier.CONTROLLER_SUPPORTED,
            expectedSlugs = setOf("3do"),
        )
        assertFamily(
            familyId = "virtualboy",
            expectedDefaultRuntime = "beetle_vb",
            expectedSupportTier = EmbeddedSupportTier.CONTROLLER_SUPPORTED,
            expectedSlugs = setOf("virtualboy", "virtual-boy"),
        )
        assertFamily(
            familyId = "dolphin",
            expectedDefaultRuntime = "dolphin",
            expectedSupportTier = EmbeddedSupportTier.CONTROLLER_SUPPORTED,
            expectedSlugs = setOf("gamecube", "ngc", "wii"),
        )
        assertFamily(
            familyId = "3ds",
            expectedDefaultRuntime = "citra",
            expectedSupportTier = EmbeddedSupportTier.CONTROLLER_SUPPORTED,
            expectedSlugs = setOf("3ds", "new-nintendo-3ds"),
        )
        assertFamily(
            familyId = "sega32x",
            expectedDefaultRuntime = "picodrive",
            expectedSupportTier = EmbeddedSupportTier.CONTROLLER_SUPPORTED,
            expectedSlugs = setOf("32x", "sega32x", "sega-32x"),
        )
    }

    @Test
    fun `ds family is promoted to melonds ds and includes dsi`() {
        val family = families.getValue("nds")

        assertEquals("melonds_ds", family.defaultRuntimeId)
        assertEquals(EmbeddedSupportTier.TOUCH_SUPPORTED, family.supportTier)
        assertTrue(family.platformSlugs.contains("nintendo-dsi"))
        assertTrue(family.runtimeOptions.any { it.runtimeId == "melonds_ds" && it.download?.artifactBaseName == "melondsds" })
    }

    @Test
    fun `3ds family includes new nintendo 3ds`() {
        val family = families.getValue("3ds")

        assertTrue(family.platformSlugs.contains("new-nintendo-3ds"))
    }

    @Test
    fun `research tier families stay out of the runtime catalog`() {
        assertFalse(families.containsKey("ps2"))
        assertFalse(families.containsKey("saturn"))
    }

    private fun assertFamily(
        familyId: String,
        expectedDefaultRuntime: String,
        expectedSupportTier: EmbeddedSupportTier,
        expectedSlugs: Set<String>,
    ) {
        val family = families.getValue(familyId)

        assertEquals(expectedDefaultRuntime, family.defaultRuntimeId)
        assertEquals(expectedSupportTier, family.supportTier)
        assertEquals(expectedSlugs, family.platformSlugs)
    }
}
