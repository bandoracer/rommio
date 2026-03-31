package io.github.bandoracer.rommio.domain.input

import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformControlProfileResolverTest {
    private val resolver = PlatformControlProfileResolver()

    @Test
    fun `expanded controller-first families resolve to controller tier`() {
        assertControllerProfile("dreamcast", "dreamcast")
        assertControllerProfile("naomi", "dreamcast")
        assertControllerProfile("gamecube", "dolphin")
        assertControllerProfile("ngc", "dolphin")
        assertControllerProfile("wii", "dolphin")
        assertControllerProfile("3ds", "3ds")
        assertControllerProfile("3do", "3do")
        assertControllerProfile("virtualboy", "virtualboy")
        assertControllerProfile("32x", "sega32x")
    }

    @Test
    fun `dsi stays inside the ds family`() {
        val profile = resolver.resolve("nintendo-dsi")

        assertEquals("nds", profile.familyId)
        assertEquals(EmbeddedSupportTier.TOUCH_SUPPORTED, profile.supportTier)
        assertEquals(TouchSupportMode.FULL, profile.touchSupportMode)
        assertEquals(PlayerOrientationPolicy.PORTRAIT_ONLY, profile.playerOrientationPolicy)
    }

    @Test
    fun `new nintendo 3ds stays inside the 3ds family`() {
        val profile = resolver.resolve("new-nintendo-3ds")

        assertEquals("3ds", profile.familyId)
        assertEquals(EmbeddedSupportTier.CONTROLLER_SUPPORTED, profile.supportTier)
        assertEquals(TouchSupportMode.CONTROLLER_FIRST, profile.touchSupportMode)
    }

    @Test
    fun `unknown platforms resolve to unsupported profiles`() {
        val profile = resolver.resolve("wiiu")

        assertEquals(EmbeddedSupportTier.UNSUPPORTED, profile.supportTier)
        assertEquals(TouchSupportMode.CONTROLLER_FIRST, profile.touchSupportMode)
        assertTrue(profile.controllerFallbackMessage?.contains("not enabled") == true)
    }

    private fun assertControllerProfile(platformSlug: String, expectedFamilyId: String) {
        val profile = resolver.resolve(platformSlug)

        assertEquals(expectedFamilyId, profile.familyId)
        assertEquals(EmbeddedSupportTier.CONTROLLER_SUPPORTED, profile.supportTier)
        assertEquals(TouchSupportMode.CONTROLLER_FIRST, profile.touchSupportMode)
        assertEquals(PlayerOrientationPolicy.AUTO, profile.playerOrientationPolicy)
    }
}
