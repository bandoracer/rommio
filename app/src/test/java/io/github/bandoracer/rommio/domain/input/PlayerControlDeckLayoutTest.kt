package io.github.bandoracer.rommio.domain.input

import io.github.bandoracer.rommio.ui.screen.player.PlayerControlDeckLayout
import org.junit.Assert.*
import org.junit.Test

class PlayerControlDeckLayoutTest {
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun overlaps(other: Rect) = x + w > other.x + .1f && other.x + other.w > x + .1f && y + h > other.y + .1f && other.y + other.h > y + .1f
    }
    @Test fun preservesNaturalVideoWhenSideControlsFit() {
        for ((width, inset, aspect) in listOf(Triple(1000f, 0f, 4f/3), Triple(912f, 62f, 10f/9))) {
            val deck = PlayerControlDeckLayout(width, 420f, bottomInset = 21f, sideInset = inset, aspectRatio = aspect)
            val videoWidth = minOf(deck.gameRight - deck.gameLeft, (deck.gameBottom - deck.gameTop) * aspect)
            assertEquals(399f, videoWidth / aspect, .01f)
        }
    }
    @Test fun onlyReducesVideoToRequiredControlWidth() {
        val deck = PlayerControlDeckLayout(912f, 420f, bottomInset = 21f, sideInset = 62f, deckHeight = 300f, aspectRatio = 16f/9)
        assertEquals(912 - 124 - 2 * (148 * .92f + 4), deck.gameRight - deck.gameLeft, .01f)
        assertEquals(399f, deck.gameBottom - deck.gameTop, .01f)
        val hidden = deck.copy(visible = false, utilityCount = 1)
        assertTrue(hidden.gameRight - hidden.gameLeft >= 399f * 4/3)
    }
    @Test fun allFamiliesKeepHitTargetsOutsideVideoAndEachOther() {
        for (profile in PlatformControlProfileResolver().supportedProfiles()) {
            val controls = ExpansionControls.controls(profile.familyId) ?: continue
            for ((width,height) in listOf(420f to 912f,912f to 420f,375f to 667f,667f to 375f)) for (scale in listOf(.92f,1f,1.3f)) for (mirror in listOf(false,true)) {
                val deck = PlayerControlDeckLayout(width,height,if (height > width) 62f else 0f,21f,if (height > width) 0f else 62f,ExpansionControls.deckHeight(controls),scale, aspectRatio = profile.preferredViewportAspectRatio, utilityCount = controls.count { it.center } + 1)
                val game = Rect(deck.gameLeft,deck.gameTop,deck.gameRight-deck.gameLeft,deck.gameBottom-deck.gameTop)
                val utility = controls.filter { it.center }; val menuIndex = utility.size / 2
                val targets = controls.map { c ->
                    val i = utility.indexOf(c)
                    val x = if (c.center) deck.utilityX(if (i < menuIndex) i else i+1,utility.size+1) else (if (c.left != mirror) deck.leftOrigin else deck.rightOrigin) + c.x * deck.scale
                    val y = if (c.center) deck.utilityY else deck.railTop + c.y * deck.scale
                    val size = if (c.center) 48f else c.size * deck.scale
                    Rect(x-size/2,y-size/2,size,size)
                } + Rect(deck.utilityX(menuIndex,utility.size+1)-24,deck.utilityY-22,48f,44f)
                targets.forEachIndexed { i,target ->
                    val message = "${profile.familyId} ${width}x$height scale=$scale mirror=$mirror $target"
                    assertTrue("Small $message",target.w >= 44)
                    assertTrue("Clipped $message",target.x >= 0 && target.y >= 0 && target.x+target.w <= width && target.y+target.h <= height)
                    assertFalse("Obscures game $message",target.overlaps(game))
                    targets.drop(i+1).forEach { assertFalse("Overlapping $message $it",target.overlaps(it)) }
                }
            }
        }
    }
}
