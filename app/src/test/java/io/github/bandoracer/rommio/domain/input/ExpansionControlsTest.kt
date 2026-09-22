package io.github.bandoracer.rommio.domain.input

import android.view.KeyEvent
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import org.junit.Assert.*
import org.junit.Test

class ExpansionControlsTest {
    @Test fun allExpansionPlatformsHaveTouchControlsAndDistinctProfiles() {
        val resolver = PlatformControlProfileResolver()
        assertEquals(resolver.supportedProfiles().size, resolver.supportedProfiles().map { it.familyId }.toSet().size)
        for (slug in listOf("genesis","segacd","sms","gamegear","psx","n64","psp","nes","snes","gb","gba","atari2600","tg16","arcade","nds")) {
            val profile = resolver.resolve(slug)
            assertEquals(EmbeddedSupportTier.TOUCH_SUPPORTED,profile.supportTier)
            val controls = requireNotNull(ExpansionControls.controls(profile.familyId))
            assertEquals(controls.size,controls.map { it.id }.toSet().size)
            for (a in controls) for (b in controls) if (!a.center && !b.center && a.id != b.id && a.left == b.left) {
                val radius = (a.size + b.size)/2
                assertTrue("Overlapping $slug ${a.label}/${b.label}", kotlin.math.hypot(a.x-b.x,a.y-b.y) >= radius)
            }
        }
        val sega = ExpansionControls.controls("sega16")!!.associate { it.label to it.key }
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y,sega["A"]); assertEquals(KeyEvent.KEYCODE_BUTTON_B,sega["B"])
        assertEquals(KeyEvent.KEYCODE_BUTTON_L1,sega["X"]); assertEquals(KeyEvent.KEYCODE_BUTTON_X,sega["Y"])
        assertEquals(KeyEvent.KEYCODE_BUTTON_R1,sega["Z"])
        for (family in listOf("psx","psp","n64")) assertTrue(ExpansionControls.controls(family)!!.any { it.stick == 0 })
        assertTrue(ExpansionControls.controls("psx")!!.any { it.stick == 1 })
    }
}
