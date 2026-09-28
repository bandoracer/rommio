package io.github.bandoracer.rommio.ui.screen.player

import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.github.bandoracer.rommio.domain.input.PlatformControlProfileResolver
import io.github.bandoracer.rommio.domain.input.PlayerStickTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Touch controls v2 (docs/player-controls.md): the family plans, the phone geometry, the touch
 * model (hit regions, chord, rolling, hysteresis, dead zone, diagonals, mirrored layouts, ordered
 * release), the analog stick model and the per-console layouts. Mirrors iOS
 * `PlayerTouchModelTests` (same phone sizes, same expectations), plus Android phone, tablet and
 * foldable sizes.
 */
class PlayerTouchModelTest {
    private data class Phone(val name: String, val size: Size, val insets: PlayerStageInsets)

    private val proPortrait = Phone("17 Pro portrait", Size(402f, 874f), PlayerStageInsets(top = 62f, bottom = 34f, cameraTop = 51f))
    private val proLandscape = Phone("17 Pro landscape", Size(874f, 402f), PlayerStageInsets(bottom = 21f, left = 62f, right = 62f, cameraSide = 51f))
    private val phones = listOf(
        proPortrait, proLandscape,
        Phone("SE portrait", Size(375f, 667f), PlayerStageInsets(top = 20f)),
        Phone("SE landscape", Size(667f, 375f), PlayerStageInsets()),
        Phone("Pro Max portrait", Size(440f, 956f), PlayerStageInsets(top = 62f, bottom = 34f, cameraTop = 51f)),
        Phone("Pro Max landscape", Size(956f, 440f), PlayerStageInsets(bottom = 21f, left = 62f, right = 62f, cameraSide = 51f)),
        Phone("Pixel portrait", Size(412f, 915f), PlayerStageInsets(top = 32f, bottom = 24f, cameraTop = 26f)),
        Phone("Pixel landscape", Size(915f, 412f), PlayerStageInsets(bottom = 24f, left = 32f, cameraSide = 26f)),
        Phone("Small Android portrait", Size(360f, 640f), PlayerStageInsets(top = 24f, bottom = 16f)),
        Phone("Small Android landscape", Size(640f, 360f), PlayerStageInsets(bottom = 16f, left = 24f)),
        Phone("Foldable inner", Size(673f, 841f), PlayerStageInsets(top = 24f, bottom = 24f)),
        Phone("Tablet portrait", Size(800f, 1280f), PlayerStageInsets(top = 24f, bottom = 24f)),
        Phone("Tablet landscape", Size(1280f, 800f), PlayerStageInsets(top = 24f, bottom = 24f)),
    )

    private fun plan(family: String, analog: Boolean = false) = requireNotNull(PlayerControlPlan.of(family, analog)) { family }

    private fun stage(family: String, phone: Phone, scale: Float = 1f, mirrored: Boolean = false, analog: Boolean = false): PlayerStage {
        val aspect = PlatformControlProfileResolver().supportedProfiles().firstOrNull { it.familyId == family }?.preferredViewportAspectRatio ?: (4f / 3f)
        return resolvePlayerStage(phone.size.width, phone.size.height, phone.insets, aspect, plan(family, analog), scale, mirrored)
    }

    private val Rect.midY get() = (top + bottom) / 2
    private val Rect.midX get() = (left + right) / 2
    private fun union(rects: List<Rect>) = rects.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
    private fun faceBox(layout: PlayerTouchLayout) = union(layout.controls.filter { it.kind == PlayerTouchKind.FACE }.map { it.frame })
    private fun Rect.intersectsStrictly(other: Rect, inset: Float = 0.5f) =
        left + inset < other.right && other.left < right - inset && top + inset < other.bottom && other.top < bottom - inset

    private val a = KeyEvent.KEYCODE_BUTTON_A
    private val b = KeyEvent.KEYCODE_BUTTON_B
    private val up = KeyEvent.KEYCODE_DPAD_UP
    private val down = KeyEvent.KEYCODE_DPAD_DOWN
    private val right = KeyEvent.KEYCODE_DPAD_RIGHT

    // ---- Plans ----

    @Test fun familiesClassifyIntoTheSpecArrangements() {
        assertEquals(PlayerFaceArrangement.TWO_BUTTON, plan("nes").arrangement)
        assertEquals(PlayerFaceArrangement.TWO_BUTTON, plan("gb").arrangement)
        assertEquals(PlayerFaceArrangement.TWO_BUTTON, plan("gba").arrangement)
        assertEquals(PlayerFaceArrangement.DIAMOND, plan("snes").arrangement)
        assertEquals(PlayerFaceArrangement.DIAMOND, plan("psx").arrangement)
        assertEquals(PlayerFaceArrangement.SIX_BUTTON, plan("sega16").arrangement)
        assertEquals(PlayerFaceArrangement.N64, plan("n64").arrangement)
        assertEquals(listOf("L"), plan("gba").leftShoulders.map { it.label })
        assertEquals(listOf("R"), plan("gba").rightShoulders.map { it.label })
        assertEquals(listOf("L1", "L2"), plan("psx").leftShoulders.map { it.label })
        assertNotNull(plan("psx", analog = true).leftStick)
        assertNotNull(plan("psx", analog = true).rightStick)
        assertEquals(listOf("Select", "Start"), plan("nes").utilities.map { it.label })
        assertEquals(listOf(up, down, KeyEvent.KEYCODE_DPAD_LEFT, right), plan("nes").dpad?.keys)
        assertNull("Controller-first families without touch controls have no plan", PlayerControlPlan.of("dreamcast"))
    }

    @Test fun specSizesAtFullSizeAndTheFloorsAtTheSmallest() {
        val full = PlayerControlMetrics(1f)
        assertEquals(150f, full.dpad)
        assertEquals(64f, full.twoButtonDiameter)
        assertEquals(76f, full.twoButtonSpacing)
        assertEquals(56f, full.diamondDiameter)
        assertEquals(62f, full.diamondRadius)
        assertEquals(50f, full.sixDiameter)
        assertEquals(Size(88f, 40f), full.shoulder)
        assertEquals(Size(64f, 34f), full.pill)
        assertEquals(44f, full.menu)
        // Two-button: B lower-left, A upper-right, centres 76 apart on a 25° rise.
        val nes = plan("nes").faceGeometry(full)
        val bb = nes.first { it.spec.label == "B" }
        val aa = nes.first { it.spec.label == "A" }
        assertTrue(bb.offset.x < aa.offset.x)
        assertTrue(bb.offset.y > aa.offset.y)
        assertEquals(76f, hypot(aa.offset.x - bb.offset.x, aa.offset.y - bb.offset.y), 0.01f)
        assertEquals(25.0, Math.toDegrees(atan2((bb.offset.y - aa.offset.y).toDouble(), (aa.offset.x - bb.offset.x).toDouble())), 0.01)
        // Diamond centres 62 dp from the cluster centre.
        plan("snes").faceGeometry(full).forEach { assertEquals(62f, hypot(it.offset.x, it.offset.y), 0.01f) }
        // The smallest setting keeps every face at 50 dp or more (Nintendo 64's C buttons, 44 dp by
        // the per-console layout, at 44); the scale range is 0.8…1.3.
        assertEquals(0.8f, PlayerControlMetrics(0.5f).scale)
        assertEquals(1.3f, PlayerControlMetrics(2f).scale)
        val small = PlayerControlMetrics(0.8f)
        for (family in listOf("nes", "snes", "sega16", "n64", "psx")) {
            plan(family).faceGeometry(small).forEach { assertTrue(family, it.diameter >= if (it.spec.isAxisButton) 44f else 50f) }
        }
    }

    // ---- Geometry ----

    @Test fun portraitPutsTheGameFullWidthUnderTheCameraAndClustersAt55PercentOfTheBand() {
        val layout = stage("nes", proPortrait)
        val controls = layout.controls
        val game = layout.viewport
        assertTrue(layout.isPortrait)
        assertEquals("Game 8 dp under the camera", 51f + 8f, game.top, 0.5f)
        assertEquals("Edge to edge", 0f, game.left, 0.5f)
        assertEquals("Game spans the display", 402f, game.width, 0.5f)
        val dpad = requireNotNull(controls.dpad)
        val faceBox = faceBox(controls)
        assertEquals("D-pad 20 dp from the left edge", 20f, dpad.frame.left, 0.5f)
        assertEquals("Face 20 dp from the right edge", 402f - 20f, faceBox.right, 0.5f)
        val bandTop = game.bottom + 12f
        val bandBottom = 874f - 34f
        assertEquals("Clusters at 55% of the band", bandTop + 0.55f * (bandBottom - bandTop), dpad.center.y, 0.5f)
        assertEquals(dpad.center.y, faceBox.midY, 0.5f)
        // Select · Menu · Start centred at the bottom, 12 dp above the gesture area.
        assertEquals(201f, controls.menuCenter.x, 0.5f)
        assertEquals(bandBottom - 12f, controls.menuFrame.bottom, 0.5f)
        val select = requireNotNull(controls.control("Select"))
        val start = requireNotNull(controls.control("Start"))
        assertEquals(Size(64f, 34f), select.frame.size)
        assertTrue(select.frame.right < controls.menuFrame.left)
        assertTrue(start.frame.left > controls.menuFrame.right)
        assertEquals(controls.menuCenter.y, select.center.y, 0.5f)
    }

    /**
     * Portrait shoulders (revised 2026-09-26): one level row 28 dp under the game; each side's main
     * shoulder (L, R, L1, R1) a bumper flush against its screen edge, any other inward beside it; the
     * clusters below the row. Mirrors iOS.
     */
    @Test fun portraitShouldersAreALevelRowOfEdgeBumpersUnderTheGame() {
        for (family in listOf("gba", "snes", "psx", "3ds")) {
            for (mirrored in listOf(false, true)) {
                val label = "$family mirrored=$mirrored"
                // (PlayStation with its sticks: no room to stack, so one row.)
                val layout = stage(family, proPortrait, mirrored = mirrored, analog = family == "psx")
                val controls = layout.controls
                val shoulders = controls.controls.filter { it.kind == PlayerTouchKind.SHOULDER }
                assertTrue(label, shoulders.isNotEmpty())
                val top = shoulders.minOf { it.frame.top }
                shoulders.forEach {
                    assertEquals("Level: ${it.id} $label", top, it.frame.top, 0.5f)
                    assertTrue("${it.id} $label", it.frame.height >= 40f - 0.01f)
                }
                assertEquals("28 dp under the game: $label", layout.viewport.bottom + 28f, top, 0.5f)
                val mains = shoulders.filter { it.spec.label in setOf("L", "R", "L1", "R1") }
                assertEquals(label, 2, mains.size)
                mains.forEach { main ->
                    if (main.shape == PlayerTouchShape.BUMPER_LEADING) assertEquals("Flush left: ${main.id} $label", 0f, main.frame.left, 0.5f)
                    else {
                        assertEquals(label, PlayerTouchShape.BUMPER_TRAILING, main.shape)
                        assertEquals("Flush right: ${main.id} $label", 402f, main.frame.right, 0.5f)
                    }
                }
                (shoulders - mains.toSet()).forEach { assertEquals(label, PlayerTouchShape.TAB, it.shape) }
                val rowBottom = shoulders.maxOf { it.frame.bottom }
                controls.controls.filter { it.kind != PlayerTouchKind.SHOULDER && it.kind != PlayerTouchKind.SYSTEM }.forEach {
                    assertTrue("Below the shoulders: ${it.id} $label", it.frame.top >= rowBottom + 12f - 0.5f)
                }
            }
        }
        // A squeezed band (a DS on a small phone, the controls at their smallest): the row gives way,
        // 12 dp under the game at the shoulders' natural height.
        val small = phones.first { it.name == "Small Android portrait" }
        val ds = stage("nds", small, scale = 0.8f)
        val dsRow = ds.controls.controls.filter { it.kind == PlayerTouchKind.SHOULDER }
        assertEquals(ds.viewport.bottom + 12f, dsRow.minOf { it.frame.top }, 0.5f)
        assertTrue(dsRow.all { it.frame.height < 44f })
        // GBA: an 88 dp tab widened over the 20 dp margin to the edge, 44 dp tall.
        val gba = stage("gba", proPortrait).controls
        assertEquals(Size(108f, 44f), gba.control("L")!!.frame.size)
        assertEquals(0f, gba.control("L")!!.frame.left, 0.5f)
        assertEquals(402f, gba.control("R")!!.frame.right, 0.5f)
    }

    /** PlayStation's numbered shoulders stack, 1 above 2, when that costs nothing (revised 2026-09-26). Mirrors iOS. */
    @Test fun numberedShouldersStackWhenThereIsRoom() {
        val portrait = stage("psx", proPortrait)
        val controls = portrait.controls
        assertEquals("The game keeps the full width", 402f, portrait.viewport.width, 0.5f)
        assertEquals(1f, controls.metrics.scale, 0.001f)
        for ((top, bottom, leading) in listOf(Triple("L1", "L2", true), Triple("R1", "R2", false))) {
            val one = controls.control(top)!!
            val two = controls.control(bottom)!!
            assertEquals(portrait.viewport.bottom + 28f, one.frame.top, 0.5f)
            assertEquals("$bottom 8 dp under $top", one.frame.bottom + 8f, two.frame.top, 0.5f)
            for (shoulder in listOf(one, two)) {
                assertEquals(if (leading) PlayerTouchShape.BUMPER_LEADING else PlayerTouchShape.BUMPER_TRAILING, shoulder.shape)
                if (leading) assertEquals(0f, shoulder.frame.left, 0.5f) else assertEquals(402f, shoulder.frame.right, 0.5f)
            }
        }
        val columnBottom = controls.controls.filter { it.kind == PlayerTouchKind.SHOULDER }.maxOf { it.frame.bottom }
        controls.controls.filter { it.kind != PlayerTouchKind.SHOULDER && it.kind != PlayerTouchKind.SYSTEM }.forEach {
            assertTrue("Below the shoulders: ${it.id}", it.frame.top >= columnBottom + 12f - 0.5f)
        }
        // With its sticks there is no room: one row.
        val analog = stage("psx", proPortrait, analog = true).controls
        assertEquals(analog.control("L1")!!.frame.top, analog.control("L2")!!.frame.top, 0.5f)
        // Landscape rails, no sticks: a column at the rail's top, centred in it; with sticks, one row.
        val landscape = stage("psx", proLandscape).controls
        val l1 = landscape.control("L1")!!.frame
        val l2 = landscape.control("L2")!!.frame
        assertEquals(l1.bottom + 8f, l2.top, 0.5f)
        assertEquals(l1.center.x, l2.center.x, 0.5f)
        val rail = stage("psx", proLandscape, analog = true).controls
        assertEquals(rail.control("L1")!!.frame.top, rail.control("L2")!!.frame.top, 0.5f)
    }

    @Test fun landscapeCentresTheGameBetweenRailsWithClustersAt60Percent() {
        val layout = stage("snes", proLandscape)
        val controls = layout.controls
        val game = layout.viewport
        assertFalse(layout.isPortrait)
        assertEquals("Game centred", 437f, game.midX, 0.5f)
        val dpad = requireNotNull(controls.dpad)
        val faceBox = faceBox(controls)
        assertEquals("D-pad at 60% of the height", 402f * 0.6f, dpad.center.y, 0.5f)
        assertEquals("Face at 60% of the height", 402f * 0.6f, faceBox.midY, 0.5f)
        assertTrue(dpad.frame.right < game.left)
        assertTrue(faceBox.left > game.right)
        // Centred in their rails, which start 6 dp from the camera.
        val edge = 51f + 6f
        assertEquals((edge + game.left - 10f) / 2, dpad.center.x, 0.5f)
        assertEquals((game.right + 10f + 874f - edge) / 2, faceBox.midX, 0.5f)
        // Shoulders at the top of each rail; Select and Start at the foot.
        assertEquals(8f, requireNotNull(controls.control("L")).frame.top, 0.5f)
        assertEquals(8f, requireNotNull(controls.control("R")).frame.top, 0.5f)
        val select = requireNotNull(controls.control("Select"))
        val start = requireNotNull(controls.control("Start"))
        assertTrue(select.frame.right < game.left)
        assertTrue(start.frame.left > game.right)
        assertTrue(select.frame.top > dpad.frame.bottom)
        // Menu bottom centre, under the game.
        assertEquals(game.midX, controls.menuCenter.x, 0.5f)
        assertTrue(controls.menuFrame.top > game.bottom)
    }

    /**
     * Portrait: the game spans the display when it fits above the controls; otherwise it keeps 12 dp
     * from both sides. Either way it starts 8 dp under the camera cutout (the top inset without one).
     */
    @Test fun theGameIsFullWidthOrInsideTheBorder() {
        var bordered = 0
        for (phone in phones.filter { it.size.height > it.size.width }) {
            for ((family, analog) in listOf("nes" to false, "gba" to false, "snes" to false, "n64" to false, "psx" to true, "psp" to false, "nds" to false)) {
                val game = stage(family, phone, analog = analog).viewport
                val label = "$family ${phone.name}"
                assertEquals(label, (phone.insets.cameraTop ?: phone.insets.top) + 8f, game.top, 0.5f)
                if (game.width >= phone.size.width - 0.5f) {
                    assertEquals(label, 0f, game.left, 0.5f)
                } else {
                    bordered++
                    assertTrue(label, game.left >= 12f - 0.5f && game.right <= phone.size.width - 12f + 0.5f)
                    assertEquals(label, phone.size.width / 2, game.center.x, 0.5f)
                }
            }
        }
        // A DS, two screens tall, never fits the width above its controls.
        assertTrue(bordered > 0)
        assertTrue(stage("nds", proPortrait).viewport.width < 402f - 24f + 0.5f)
    }

    /** Landscape: a game the rails leave room for at full height (a vertical arcade game) fills it. */
    @Test fun aLandscapeGameFillsTheHeightWhenTheRailsLeaveRoom() {
        val tall = resolvePlayerStage(874f, 402f, proLandscape.insets, 3f / 4f, plan("arcade"))
        val game = tall.viewport
        assertEquals(0f, game.top, 0.5f)
        assertEquals(402f, game.height, 0.5f)
        assertEquals(437f, game.center.x, 0.5f)
        (tall.controls.controls.map { it.frame } + tall.controls.menuFrame).forEach {
            assertFalse("$it over the game", game.intersectsStrictly(it))
        }
        val wide = stage("snes", proLandscape).viewport
        assertTrue(wide.top >= 8f - 0.5f)
        assertTrue(wide.height < 402f - 16f)
    }

    @Test fun mirroringSwapsTheRailsButNotTheLabelsOrTheGame() {
        for (phone in listOf(proPortrait, proLandscape)) {
            val normal = stage("nes", phone)
            val mirrored = stage("nes", phone, mirrored = true)
            assertEquals(phone.name, normal.viewport, mirrored.viewport)
            val pad = requireNotNull(mirrored.controls.dpad)
            val aa = requireNotNull(mirrored.controls.control("A"))
            val bb = requireNotNull(mirrored.controls.control("B"))
            assertTrue("D-pad moves right: ${phone.name}", pad.center.x > phone.size.width / 2)
            assertTrue("Face moves left: ${phone.name}", aa.center.x < phone.size.width / 2)
            // B stays lower-left of A.
            assertTrue(bb.center.x < aa.center.x)
            assertTrue(bb.center.y > aa.center.y)
        }
    }

    /**
     * Every touch family, phone and orientation, size and hand: controls stay on the display, off
     * the game, and off each other; faces are 50 dp or more and every hit region at least 58 dp.
     */
    @Test fun everyFamilyFitsEveryPhoneWithoutOverlap() {
        val families = PlatformControlProfileResolver().supportedProfiles().map { it.familyId }.filter { PlayerControlPlan.of(it) != null }
        assertTrue(families.toString(), families.containsAll(listOf("nes", "snes", "gba", "gb", "psx", "n64", "sega16", "psp", "nds", "3ds")))
        // PlayStation with and without its Analog controls.
        val cases = families.map { it to false } + ("psx" to true)
        for ((family, analog) in cases) for (phone in phones) for (scale in listOf(0.8f, 1f, 1.3f)) for (mirrored in listOf(false, true)) {
            val layout = stage(family, phone, scale, mirrored, analog)
            val label = "$family${if (analog) " analog" else ""} ${phone.name} scale=$scale mirrored=$mirrored"
            val controls = layout.controls
            val display = Rect(-0.5f, -0.5f, phone.size.width + 0.5f, phone.size.height + 0.5f)
            val game = layout.viewport
            // (Atari 2600 on a small phone in landscape, six switches at the feet, is the tightest; a
            // DS game, taller than wide, is judged by its height.)
            val tall = game.height > game.width
            assertTrue("Game too small: $label $game", if (tall) game.height > phone.size.height * 0.45f else game.width > phone.size.width * 0.28f)
            val frames = controls.controls.map { it.id to it.frame }.toMutableList()
            frames += "menu" to controls.menuFrame
            for (control in controls.controls) {
                assertTrue("Small hit region ${control.id}: $label", minOf(control.hitFrame.width, control.hitFrame.height) >= 58f - 0.01f)
                if (control.kind == PlayerTouchKind.FACE) assertTrue("Small face ${control.id}: $label", control.frame.width >= (if (control.spec.isAxisButton) 44f else 50f) - 0.01f)
            }
            frames.forEachIndexed { index, (id, frame) ->
                assertTrue("$id off the display: $label $frame", frame.left >= display.left && frame.top >= display.top && frame.right <= display.right && frame.bottom <= display.bottom)
                assertFalse("$id over the game: $label $frame $game", game.intersectsStrictly(frame))
                frames.drop(index + 1).forEach { (other, otherFrame) ->
                    assertFalse("$id overlaps $other: $label", frame.intersectsStrictly(otherFrame))
                }
            }
        }
    }

    @Test fun withoutTouchSpaceTheGameGrowsAndOnlyMenuRemains() {
        val shown = stage("nes", proPortrait)
        val hidden = resolvePlayerStage(402f, 874f, proPortrait.insets, 4f / 3f, plan("nes"), reserveTouchSpace = false)
        assertTrue(hidden.controls.controls.isEmpty())
        assertTrue(hidden.viewport.width >= shown.viewport.width)
        assertFalse(hidden.viewport.intersectsStrictly(hidden.controls.menuFrame))
    }

    // ---- Hit regions ----

    private fun nes() = stage("nes", proPortrait).controls
    private fun snes() = stage("snes", proPortrait).controls

    @Test fun aButtonsEdgeAndItsGrownRegionHit() {
        val layout = nes()
        val aa = requireNotNull(layout.control("A"))
        // Just inside the grown region, straight up from A (away from B).
        assertEquals(PlayerTouchTarget.Buttons(listOf("A")), layout.target(Offset(aa.center.x, aa.frame.top - 13f)))
        assertNull("Outside the grown region and the cluster zone", layout.target(Offset(aa.center.x, aa.frame.top - 16f)))
    }

    @Test fun noDeadGapsInsideAFaceCluster() {
        val layout = snes()
        val x = requireNotNull(layout.control("X"))
        val y = requireNotNull(layout.control("Y"))
        // The diamond's centre is out of every button's hit region but inside the zone.
        val centre = Offset(x.center.x, y.center.y)
        assertFalse(layout.controls.any { it.kind == PlayerTouchKind.FACE && PlayerTouchRules.hits(centre, it) })
        assertNotNull(layout.target(centre))
        // Between X (top) and Y (left), nearer Y.
        assertEquals(PlayerTouchTarget.Buttons(listOf("Y")), layout.target(Offset(y.center.x + 20f, y.center.y - 26f)))
    }

    @Test fun theChordBandBetweenAAndBPressesBoth() {
        val layout = nes()
        val ac = requireNotNull(layout.control("A")).center
        val bc = requireNotNull(layout.control("B")).center
        val mid = Offset((ac.x + bc.x) / 2, (ac.y + bc.y) / 2)
        val distance = hypot(ac.x - bc.x, ac.y - bc.y)
        fun toward(share: Float) = Offset(mid.x + (ac.x - mid.x) / (distance / 2) * distance * share, mid.y + (ac.y - mid.y) / (distance / 2) * distance * share)
        fun ids(target: PlayerTouchTarget?) = (target as? PlayerTouchTarget.Buttons)?.ids?.toSet()
        assertEquals(setOf("A", "B"), ids(layout.target(mid)))
        assertEquals(setOf("A", "B"), ids(layout.target(toward(0.2f))))
        assertEquals(PlayerTouchTarget.Buttons(listOf("A")), layout.target(toward(0.3f)))
    }

    @Test fun theDpadClaimsItsGrownDisc() {
        val layout = nes()
        val pad = requireNotNull(layout.dpad)
        assertEquals(PlayerTouchTarget.Dpad, layout.target(Offset(pad.frame.left - 12f, pad.center.y)))
        assertTrue(layout.target(Offset(pad.center.x, pad.frame.bottom + 40f)) != PlayerTouchTarget.Dpad)
    }

    // ---- D-pad directions ----

    @Test fun dpadDeadZoneAndEightWaySectors() {
        val r = 75f
        fun direction(degrees: Double, distance: Float = 50f, previous: PlayerDPadDirection = PlayerDPadDirection.NONE): PlayerDPadDirection {
            val radians = Math.toRadians(degrees)
            return PlayerTouchRules.dpadDirection((distance * cos(radians)).toFloat(), (distance * sin(radians)).toFloat(), r, previous)
        }
        assertEquals("Inside the dead zone (18% of 75 = 13.5)", PlayerDPadDirection.NONE, direction(0.0, 13f))
        assertEquals(PlayerDPadDirection.RIGHT, direction(0.0, 14f))
        assertEquals(PlayerDPadDirection.RIGHT, direction(0.0))
        assertEquals(PlayerDPadDirection.RIGHT, direction(29.0))
        assertEquals("Diagonal sectors are 30° wide (30°–60°)", PlayerDPadDirection.DOWN_RIGHT, direction(31.0))
        assertEquals(PlayerDPadDirection.DOWN_RIGHT, direction(45.0))
        assertEquals(PlayerDPadDirection.DOWN, direction(90.0))
        assertEquals(PlayerDPadDirection.DOWN_LEFT, direction(135.0))
        assertEquals(PlayerDPadDirection.LEFT, direction(180.0))
        assertEquals(PlayerDPadDirection.UP_LEFT, direction(225.0))
        assertEquals(PlayerDPadDirection.UP, direction(270.0))
        assertEquals(PlayerDPadDirection.UP_RIGHT, direction(315.0))
        assertEquals(PlayerDPadDirection.DOWN_RIGHT, direction(59.0))
        assertEquals(PlayerDPadDirection.DOWN, direction(61.0))
        // Hysteresis: a held direction keeps a slightly wider sector.
        assertEquals(PlayerDPadDirection.RIGHT, direction(33.0, previous = PlayerDPadDirection.RIGHT))
        assertEquals(PlayerDPadDirection.DOWN_RIGHT, direction(35.0, previous = PlayerDPadDirection.RIGHT))
        assertEquals("Held: the dead zone shrinks to 15%", PlayerDPadDirection.RIGHT, direction(0.0, 12f, previous = PlayerDPadDirection.RIGHT))
        assertEquals(setOf(down, right), PlayerDPadDirection.DOWN_RIGHT.keys)
    }

    /** A small d-pad beside a primary stick (N64, 3DS) is four-way: every angle is one arm. Mirrors iOS. */
    @Test fun aSecondaryDpadIsFourWay() {
        fun direction(degrees: Double, previous: PlayerDPadDirection = PlayerDPadDirection.NONE): PlayerDPadDirection {
            val radians = Math.toRadians(degrees)
            return PlayerTouchRules.dpadDirection((40 * cos(radians)).toFloat(), (40 * sin(radians)).toFloat(), 52f, previous, diagonals = false)
        }
        assertEquals(PlayerDPadDirection.RIGHT, direction(44.0))
        assertEquals(PlayerDPadDirection.DOWN, direction(46.0))
        assertEquals(PlayerDPadDirection.LEFT, direction(135.5))
        assertEquals(PlayerDPadDirection.RIGHT, direction(315.5))
        assertEquals("Held: 4° wider", PlayerDPadDirection.RIGHT, direction(48.0, previous = PlayerDPadDirection.RIGHT))
        for (family in listOf("n64", "3ds")) assertTrue(family, stage(family, proLandscape).controls.dpad!!.fourWay)
        assertFalse(stage("snes", proLandscape).controls.dpad!!.fourWay)
        assertFalse("PSP's d-pad is its primary", stage("psp", proLandscape).controls.dpad!!.fourWay)
        val tracker = PlayerTouchTracker(stage("n64", proLandscape).controls)
        val pad = tracker.layout.dpad!!
        val diagonal = tracker.began(1, Offset(pad.center.x + 30f, pad.center.y + 30f))
        assertEquals("One arm at a time: $diagonal", 1, diagonal.filterIsInstance<PlayerTouchOutput.Digital>().size)
    }

    // ---- Tracker ----

    private fun digital(key: Int, pressed: Boolean) = PlayerTouchOutput.Digital(key, pressed)

    @Test fun aQuickTapPressesThenReleasesInOrder() {
        val tracker = PlayerTouchTracker(nes())
        val ac = requireNotNull(tracker.layout.control("A")).center
        assertEquals(listOf(digital(a, true), PlayerTouchOutput.PressHaptic), tracker.began(1, ac))
        assertEquals(listOf(digital(a, false)), tracker.ended(1))
        assertFalse(tracker.isTracking)
        assertTrue(tracker.pressedControls.isEmpty())
    }

    @Test fun rollingFromBToAReleasesBThenPressesA() {
        val tracker = PlayerTouchTracker(nes())
        val ac = requireNotNull(tracker.layout.control("A")).center
        val bc = requireNotNull(tracker.layout.control("B")).center
        tracker.began(1, bc)
        assertEquals(listOf(digital(b, false), digital(a, true), PlayerTouchOutput.PressHaptic), tracker.moved(1, ac))
        assertEquals(setOf("A"), tracker.pressedControls)
        assertEquals(listOf(digital(a, false)), tracker.ended(1))
    }

    @Test fun rollingThroughTheMidpointChordsThenSettles() {
        val tracker = PlayerTouchTracker(nes())
        val ac = requireNotNull(tracker.layout.control("A")).center
        val bc = requireNotNull(tracker.layout.control("B")).center
        tracker.began(1, bc)
        val mid = Offset((ac.x + bc.x) / 2, (ac.y + bc.y) / 2)
        assertEquals(listOf(digital(a, true), PlayerTouchOutput.PressHaptic), tracker.moved(1, mid))
        assertEquals(setOf("A", "B"), tracker.pressedControls)
        assertEquals(listOf(digital(b, false)), tracker.moved(1, ac))
        assertEquals(setOf("A"), tracker.pressedControls)
    }

    @Test fun rollingHysteresisKeepsTheHeldButtonNearTheBoundary() {
        val layout = snes()
        val tracker = PlayerTouchTracker(layout)
        val y = requireNotNull(layout.control("Y"))
        val x = requireNotNull(layout.control("X"))
        tracker.began(1, y.center)
        // A point barely nearer X than Y (normalised) keeps Y.
        val boundary = Offset((x.center.x + y.center.x) / 2 + 0.75f, (x.center.y + y.center.y) / 2 - 0.75f)
        assertTrue(PlayerTouchRules.normalizedDistance(boundary, x) < PlayerTouchRules.normalizedDistance(boundary, y))
        assertEquals(emptyList<PlayerTouchOutput>(), tracker.moved(1, boundary))
        assertEquals(setOf("Y"), tracker.pressedControls)
        // Clearly on X: switches.
        assertEquals(
            listOf(digital(KeyEvent.KEYCODE_BUTTON_Y, false), digital(KeyEvent.KEYCODE_BUTTON_X, true), PlayerTouchOutput.PressHaptic),
            tracker.moved(1, x.center),
        )
    }

    @Test fun driftingOffKeepsTheButtonUntil40DpBeyondIt() {
        val tracker = PlayerTouchTracker(nes())
        val aa = requireNotNull(tracker.layout.control("A"))
        tracker.began(1, aa.center)
        val reach = aa.frame.width / 2 + PlayerControlMetrics.HIT_GROWTH
        assertEquals("30 dp beyond: still held", emptyList<PlayerTouchOutput>(), tracker.moved(1, Offset(aa.center.x, aa.center.y - reach - 30f)))
        assertEquals(setOf("A"), tracker.pressedControls)
        assertEquals(listOf(digital(a, false)), tracker.moved(1, Offset(aa.center.x, aa.center.y - reach - 45f)))
        // Back onto the button: pressed again.
        assertEquals(listOf(digital(a, true), PlayerTouchOutput.PressHaptic), tracker.moved(1, aa.center))
        assertEquals(listOf(digital(a, false)), tracker.ended(1))
    }

    @Test fun rollingStaysWithinItsKind() {
        val tracker = PlayerTouchTracker(stage("gba", proPortrait).controls)
        val aa = requireNotNull(tracker.layout.control("A"))
        val rr = requireNotNull(tracker.layout.control("R"))
        tracker.began(1, aa.center)
        // Onto the R shoulder: a face touch never presses a shoulder (it drifts, then lets go).
        assertFalse(tracker.moved(1, rr.center).contains(digital(KeyEvent.KEYCODE_BUTTON_R1, true)))
    }

    @Test fun aDpadDragThatLeavesThePadKeepsItsDirection() {
        val tracker = PlayerTouchTracker(nes())
        val pad = requireNotNull(tracker.layout.dpad).center
        assertEquals(listOf(digital(right, true), PlayerTouchOutput.DirectionHaptic), tracker.began(1, Offset(pad.x + 40f, pad.y)))
        assertEquals("Still right, far off the pad", emptyList<PlayerTouchOutput>(), tracker.moved(1, Offset(pad.x + 260f, pad.y + 10f)))
        assertEquals(setOf(right), tracker.dpadKeys)
        assertEquals("Diagonal down-right", listOf(digital(down, true), PlayerTouchOutput.DirectionHaptic), tracker.moved(1, Offset(pad.x + 200f, pad.y + 200f)))
        assertEquals(listOf(digital(down, false), digital(right, false)), tracker.ended(1))
    }

    @Test fun twoTouchesOnOneButtonReleaseOnlyWhenTheLastLifts() {
        val tracker = PlayerTouchTracker(nes())
        val ac = requireNotNull(tracker.layout.control("A")).center
        tracker.began(1, ac)
        assertEquals(listOf<PlayerTouchOutput>(PlayerTouchOutput.PressHaptic), tracker.began(2, Offset(ac.x + 5f, ac.y)))
        assertEquals(emptyList<PlayerTouchOutput>(), tracker.ended(1))
        assertEquals(listOf(digital(a, false)), tracker.ended(2))
    }

    @Test fun releaseAllLetsGoOfEverything() {
        val tracker = PlayerTouchTracker(stage("gba", proPortrait).controls)
        val layout = tracker.layout
        tracker.began(1, Offset(layout.dpad!!.center.x, layout.dpad!!.center.y - 50f))
        tracker.began(2, layout.control("A")!!.center)
        tracker.began(3, layout.control("R")!!.center)
        val released = tracker.releaseAll().filterIsInstance<PlayerTouchOutput.Digital>().filter { !it.pressed }.map { it.key }.toSet()
        assertEquals(setOf(up, a, KeyEvent.KEYCODE_BUTTON_R1), released)
        assertFalse(tracker.isTracking)
        assertTrue(tracker.dpadKeys.isEmpty())
        assertEquals(emptyList<PlayerTouchOutput>(), tracker.releaseAll())
    }

    @Test fun aNewLayoutReleasesWhatTheOldOneHeld() {
        val tracker = PlayerTouchTracker(nes())
        tracker.began(1, tracker.layout.control("A")!!.center)
        assertEquals(listOf(digital(a, false)), tracker.update(stage("nes", proLandscape).controls))
        assertFalse(tracker.isTracking)
    }

    @Test fun axisButtonsPushTheRightStick() {
        val tracker = PlayerTouchTracker(stage("n64", proLandscape).controls)
        val cUp = requireNotNull(tracker.layout.control("C-Up")).center
        assertEquals(listOf(PlayerTouchOutput.Motion(1, 0f, -1f), PlayerTouchOutput.PressHaptic), tracker.began(1, cUp))
        assertEquals(listOf(PlayerTouchOutput.Motion(1, 0f, 0f)), tracker.ended(1))
    }

    // ---- Touch sticks (pinned by scripts/touch_stick_reference.py) ----

    private data class Vector(val profile: String, val dx: Double, val dy: Double, val x: Double, val y: Double, val fullTilt: Boolean)

    /** Every vector `python3 scripts/touch_stick_reference.py` prints, for every profile. */
    private val referenceVectors = listOf(
        Vector("n64", 0.0, 0.0, 0.0000, 0.0000, false),
        Vector("n64", 3.0, 0.0, 0.0000, 0.0000, false),
        Vector("n64", 5.0, 0.0, 0.0006, 0.0000, false),
        Vector("n64", 12.0, 0.0, 0.0943, 0.0000, false),
        Vector("n64", 24.0, 0.0, 0.3724, 0.0000, false),
        Vector("n64", 40.0, 0.0, 0.8700, 0.0000, false),
        Vector("n64", 60.0, 0.0, 1.0000, 0.0000, true),
        Vector("n64", 0.0, -30.0, 0.0000, -0.5449, false),
        Vector("n64", 20.0, 20.0, 0.4246, 0.4246, false),
        Vector("n64", 34.0, 34.0, 0.8600, 0.8600, true),
        Vector("n64", -15.0, 26.0, -0.2877, 0.4986, false),
        Vector("3ds", 0.0, 0.0, 0.0000, 0.0000, false),
        Vector("3ds", 3.0, 0.0, 0.0000, 0.0000, false),
        Vector("3ds", 5.0, 0.0, 0.0072, 0.0000, false),
        Vector("3ds", 12.0, 0.0, 0.1525, 0.0000, false),
        Vector("3ds", 24.0, 0.0, 0.4753, 0.0000, false),
        Vector("3ds", 40.0, 0.0, 0.9727, 0.0000, false),
        Vector("3ds", 60.0, 0.0, 1.0000, 0.0000, true),
        Vector("3ds", 0.0, -30.0, 0.0000, -0.6548, false),
        Vector("3ds", 20.0, 20.0, 0.4260, 0.4260, false),
        Vector("3ds", 34.0, 34.0, 0.7071, 0.7071, true),
        Vector("3ds", -15.0, 26.0, -0.3275, 0.5676, false),
        Vector("psp", 0.0, 0.0, 0.0000, 0.0000, false),
        Vector("psp", 3.0, 0.0, 0.0000, 0.0000, false),
        Vector("psp", 5.0, 0.0, 0.0238, 0.0000, false),
        Vector("psp", 12.0, 0.0, 0.2694, 0.0000, false),
        Vector("psp", 24.0, 0.0, 0.6902, 0.0000, false),
        Vector("psp", 40.0, 0.0, 1.0000, 0.0000, true),
        Vector("psp", 60.0, 0.0, 1.0000, 0.0000, true),
        Vector("psp", 0.0, -30.0, 0.0000, -0.9007, false),
        Vector("psp", 20.0, 20.0, 0.5943, 0.5943, false),
        Vector("psp", 34.0, 34.0, 0.7071, 0.7071, true),
        Vector("psp", -15.0, 26.0, -0.4504, 0.7807, false),
        Vector("psx", 0.0, 0.0, 0.0000, 0.0000, false),
        Vector("psx", 3.0, 0.0, 0.0000, 0.0000, false),
        Vector("psx", 5.0, 0.0, 0.0049, 0.0000, false),
        Vector("psx", 12.0, 0.0, 0.1341, 0.0000, false),
        Vector("psx", 24.0, 0.0, 0.4596, 0.0000, false),
        Vector("psx", 40.0, 0.0, 0.9985, 0.0000, false),
        Vector("psx", 60.0, 0.0, 1.0000, 0.0000, true),
        Vector("psx", 0.0, -30.0, 0.0000, -0.6504, false),
        Vector("psx", 20.0, 20.0, 0.5646, 0.5646, false),
        Vector("psx", 34.0, 34.0, 0.9500, 0.9500, true),
        Vector("psx", -15.0, 26.0, -0.3645, 0.6318, false),
        Vector("psx-right", 0.0, 0.0, 0.0000, 0.0000, false),
        Vector("psx-right", 3.0, 0.0, 0.0000, 0.0000, false),
        Vector("psx-right", 5.0, 0.0, 0.0003, 0.0000, false),
        Vector("psx-right", 12.0, 0.0, 0.0934, 0.0000, false),
        Vector("psx-right", 24.0, 0.0, 0.4488, 0.0000, false),
        Vector("psx-right", 40.0, 0.0, 1.0000, 0.0000, true),
        Vector("psx-right", 60.0, 0.0, 1.0000, 0.0000, true),
        Vector("psx-right", 0.0, -30.0, 0.0000, -0.6934, false),
        Vector("psx-right", 20.0, 20.0, 0.5885, 0.5885, false),
        Vector("psx-right", 34.0, 34.0, 0.9500, 0.9500, true),
        Vector("psx-right", -15.0, 26.0, -0.3887, 0.6737, false)
    )

    private data class Notch(val profile: String, val degrees: Double, val entering: Int?, val holding: Int?)

    /** The script's notch lines: at full tilt (60 dp out) by angle, entering and holding notch 1. */
    private val referenceNotches = listOf(
        Notch("n64", 0.0, 0, 0),
        Notch("n64", 5.0, 0, 0),
        Notch("n64", 10.0, null, null),
        Notch("n64", 40.0, 1, 1),
        Notch("n64", 45.0, 1, 1),
        Notch("n64", 52.0, 1, 1),
        Notch("n64", 55.0, null, 1),
        Notch("n64", 58.0, null, null),
        Notch("n64", 90.0, 2, 2),
        Notch("n64", 135.0, 3, 3),
        Notch("n64", 270.0, 6, 6),
        Notch("3ds", 0.0, null, null),
        Notch("3ds", 5.0, null, null),
        Notch("3ds", 10.0, null, null),
        Notch("3ds", 40.0, null, null),
        Notch("3ds", 45.0, null, null),
        Notch("3ds", 52.0, null, null),
        Notch("3ds", 55.0, null, null),
        Notch("3ds", 58.0, null, null),
        Notch("3ds", 90.0, null, null),
        Notch("3ds", 135.0, null, null),
        Notch("3ds", 270.0, null, null),
        Notch("psp", 0.0, null, null),
        Notch("psp", 5.0, null, null),
        Notch("psp", 10.0, null, null),
        Notch("psp", 40.0, null, null),
        Notch("psp", 45.0, null, null),
        Notch("psp", 52.0, null, null),
        Notch("psp", 55.0, null, null),
        Notch("psp", 58.0, null, null),
        Notch("psp", 90.0, null, null),
        Notch("psp", 135.0, null, null),
        Notch("psp", 270.0, null, null),
        Notch("psx", 0.0, null, null),
        Notch("psx", 5.0, null, null),
        Notch("psx", 10.0, null, null),
        Notch("psx", 40.0, 0, 0),
        Notch("psx", 45.0, 0, 0),
        Notch("psx", 52.0, 0, 0),
        Notch("psx", 55.0, null, null),
        Notch("psx", 58.0, null, null),
        Notch("psx", 90.0, null, null),
        Notch("psx", 135.0, 1, 1),
        Notch("psx", 270.0, null, null),
        Notch("psx-right", 0.0, null, null),
        Notch("psx-right", 5.0, null, null),
        Notch("psx-right", 10.0, null, null),
        Notch("psx-right", 40.0, 0, 0),
        Notch("psx-right", 45.0, 0, 0),
        Notch("psx-right", 52.0, 0, 0),
        Notch("psx-right", 55.0, null, null),
        Notch("psx-right", 58.0, null, null),
        Notch("psx-right", 90.0, null, null),
        Notch("psx-right", 135.0, 1, 1),
        Notch("psx-right", 270.0, null, null),
    )

    @Test fun stickOutputMatchesTheReferenceScriptForEveryProfile() {
        assertEquals(setOf("n64", "3ds", "psp", "psx", "psx-right"), PlayerStickProfile.all.keys)
        for (vector in referenceVectors) {
            val profile = requireNotNull(PlayerStickProfile.all[vector.profile]) { vector.profile }
            val label = "${vector.profile} (${vector.dx}, ${vector.dy})"
            val (x, y) = PlayerStickModel.output(vector.dx, vector.dy, profile)
            // The script prints 4 decimals.
            assertEquals("x $label", vector.x, x, 0.00005)
            assertEquals("y $label", vector.y, y, 0.00005)
            assertEquals("full tilt $label", vector.fullTilt, PlayerStickModel.fullTilt(vector.dx, vector.dy, profile))
        }
        assertEquals(55, referenceVectors.size)
    }

    /** The gate's notches (revised 2026-09-26): caught within 8°, held out to 12°. Mirrors iOS. */
    @Test fun notchesMatchTheReferenceScript() {
        assertEquals(55, referenceNotches.size)
        for (n in referenceNotches) {
            val profile = requireNotNull(PlayerStickProfile.all[n.profile])
            val dx = 60.0 * Math.cos(Math.toRadians(n.degrees))
            val dy = 60.0 * Math.sin(Math.toRadians(n.degrees))
            assertEquals("${n.profile} ${n.degrees}°", n.entering, PlayerStickModel.notch(dx, dy, profile))
            assertEquals("${n.profile} ${n.degrees}° holding 1", n.holding, PlayerStickModel.notch(dx, dy, profile, current = 1))
        }
        assertEquals(listOf(PlayerStickGate.OCTAGON, PlayerStickGate.CIRCLE, PlayerStickGate.CIRCLE, PlayerStickGate.SQUARE, PlayerStickGate.SQUARE),
            listOf("n64", "3ds", "psp", "psx", "psx-right").map { PlayerStickProfile.all.getValue(it).gate })
        assertNull(PlayerStickModel.notch(20.0, 0.0, PlayerStickProfile.N64))
    }

    @Test fun theGateShapeSetsHowFarDiagonalsReach() {
        val quarter = Math.PI / 4
        // A circle reaches 1 everywhere. (The script's circle test is within 1e-6 of √½, so its
        // 0.7071 profiles take the straight-edge formula, within 1e-5 of a circle.)
        assertEquals(1.0, PlayerStickModel.gateScale(quarter, Math.sqrt(0.5)), 0.0)
        assertEquals(1.0, PlayerStickModel.gateScale(0.3, Math.sqrt(0.5)), 0.0)
        assertEquals(1.0, PlayerStickModel.gateScale(quarter, 0.7071), 1e-5)
        for (reach in listOf(0.86, 0.95)) {
            // 1 at the cardinals; at 45° the per-axis value is the diagonal reach.
            for (cardinal in listOf(0.0, Math.PI / 2, Math.PI, -Math.PI / 2)) assertEquals(1.0, PlayerStickModel.gateScale(cardinal, reach), 1e-9)
            for (diagonal in listOf(quarter, 3 * quarter, -quarter, -3 * quarter)) {
                assertEquals(reach, Math.cos(quarter) * PlayerStickModel.gateScale(diagonal, reach), 1e-9)
            }
        }
        // The profiles: N64 an octagon (0.86), DualShock near-square (0.95), 3DS and PSP circles.
        assertEquals(0.86, PlayerStickProfile.N64.diagonalReach, 0.0)
        assertEquals(0.95, PlayerStickProfile.PSX.diagonalReach, 0.0)
        assertEquals(0.95, PlayerStickProfile.PSX_RIGHT.diagonalReach, 0.0)
        assertEquals(0.7071, PlayerStickProfile.THREE_DS.diagonalReach, 0.0)
        assertEquals(0.7071, PlayerStickProfile.PSP.diagonalReach, 0.0)
    }

    /**
     * The drawn ring is the gate the output reaches: its corners sit on [PlayerStickModel.gateScale]'s
     * boundary (the N64's octagon, the DualShock's near-square); a circle draws round.
     */
    @Test fun theGateOutlineIsTheShapeTheOutputReaches() {
        assertNull(PlayerStickModel.gateOutline(0.7071))
        for (reach in listOf(0.86, 0.95)) {
            val outline = PlayerStickModel.gateOutline(reach)!!
            assertEquals(8, outline.size)
            for (point in outline) {
                val angle = atan2(point.y.toDouble(), point.x.toDouble())
                assertEquals(PlayerStickModel.gateScale(angle, reach), hypot(point.x.toDouble(), point.y.toDouble()), 1e-6)
            }
            // Its corners are the octagon's notches: the cardinals and the diagonals.
            assertEquals(PlayerStickGate.OCTAGON.notches,
                outline.map { Math.round((Math.toDegrees(atan2(it.y.toDouble(), it.x.toDouble())) + 360.0) % 360.0).toDouble() })
        }
    }

    @Test fun eachConsoleGetsItsProfileAndTheGamesTuning() {
        assertEquals(PlayerStickProfile.N64, PlayerStickProfile.forStick("n64", 0))
        assertEquals(PlayerStickProfile.THREE_DS, PlayerStickProfile.forStick("3ds", 0))
        assertEquals(PlayerStickProfile.PSP, PlayerStickProfile.forStick("psp", 0))
        assertEquals(PlayerStickProfile.PSX, PlayerStickProfile.forStick("psx", 0))
        assertEquals(PlayerStickProfile.PSX_RIGHT, PlayerStickProfile.forStick("psx", 1))
        // Placed sticks carry their console's profile at the control size.
        val psx = stage("psx", proLandscape, analog = true).controls
        val s = psx.metrics.scale.toDouble()
        assertEquals(44.0 * s, psx.control("stick0")!!.stickProfile!!.travel, 1e-4)
        assertEquals(40.0 * s, psx.control("stick1")!!.stickProfile!!.travel, 1e-4)
        assertEquals(1.6, psx.control("stick1")!!.stickProfile!!.curve, 0.0)
        val n64 = stage("n64", proLandscape).controls
        assertEquals(48.0 * n64.metrics.scale, n64.control("stick0")!!.stickProfile!!.travel, 1e-4)
        assertEquals(36.0 * stage("psp", proLandscape).controls.metrics.scale, stage("psp", proLandscape).controls.control("stick0")!!.stickProfile!!.travel, 1e-4)
        assertEquals(44.0 * stage("3ds", proLandscape).controls.metrics.scale, stage("3ds", proLandscape).controls.control("circlePad")!!.stickProfile!!.travel, 1e-4)
        // Sensitivity: travel × 1.2 / 1 / 0.8; Dead zone: × 0.6 / 1 / 1.6; the gate stays.
        val base = PlayerStickProfile.N64
        val low = base.tuned(PlayerStickTuning(sensitivity = PlayerStickTuning.Sensitivity.LOW))
        val high = base.tuned(PlayerStickTuning(sensitivity = PlayerStickTuning.Sensitivity.HIGH, deadZone = PlayerStickTuning.DeadZone.LARGE))
        val fixed = base.tuned(PlayerStickTuning(deadZone = PlayerStickTuning.DeadZone.SMALL, centre = PlayerStickTuning.Centre.FIXED))
        assertEquals(57.6, low.travel, 1e-9)
        assertEquals(38.4, high.travel, 1e-9)
        assertEquals(0.16, high.deadZone, 1e-9)
        assertEquals(0.06, fixed.deadZone, 1e-9)
        assertEquals(PlayerStickGate.OCTAGON, fixed.gate)
        assertEquals(PlayerStickGate.OCTAGON, low.gate)
        assertEquals(base, base.tuned(PlayerStickTuning()))
        // The tuning reaches the placed stick.
        val tuned = requireNotNull(PlayerControlPlan.of("n64", stickTuning = PlayerStickTuning(sensitivity = PlayerStickTuning.Sensitivity.HIGH, centre = PlayerStickTuning.Centre.FIXED)))
        val tunedLayout = resolvePlayerStage(874f, 402f, proLandscape.insets, 4f / 3f, tuned).controls
        val placed = requireNotNull(tunedLayout.control("stick0"))
        assertTrue(placed.fixedCentre)
        assertEquals(48.0 * 0.8 * tunedLayout.metrics.scale, placed.stickProfile!!.travel, 1e-4)
    }

    @Test fun theStickCentreIsWhereTheThumbLands() {
        val zone = Rect(0f, 0f, 160f, 160f)
        // Anywhere in the zone, edges and corners included: the touch point itself, so a
        // touch-down never reads as tilt.
        assertEquals(Offset(70f, 90f), PlayerStickModel.center(Offset(70f, 90f), zone, 44f))
        assertEquals(Offset(5f, 80f), PlayerStickModel.center(Offset(5f, 80f), zone, 44f))
        assertEquals(Offset(158f, 2f), PlayerStickModel.center(Offset(158f, 2f), zone, 44f))
    }

    /**
     * The centre is where the thumb lands and stays there until it lifts (revised 2026-09-26: no
     * follow); the N64's octagon clicks in its notches at full tilt; full tilt between notches ticks.
     */
    @Test fun aStickCentreStaysWhereTheThumbLandedAndItsOctagonClicks() {
        val tracker = PlayerTouchTracker(stage("n64", proLandscape).controls)
        val stick = requireNotNull(tracker.layout.control("stick0"))
        assertEquals(PlayerTouchKind.STICK, stick.kind)
        val travel = stick.travel
        assertEquals("N64: full at 48 dp (at the control size)", 48f * tracker.layout.metrics.scale, travel, 0.001f)
        val down = Offset(stick.frame.left + 70f, stick.frame.top + 75f)
        fun at(degrees: Double, reach: Float) = Offset(
            down.x + reach * travel * Math.cos(Math.toRadians(degrees)).toFloat(),
            down.y + reach * travel * Math.sin(Math.toRadians(degrees)).toFloat(),
        )
        val click = PlayerTouchOutput.NotchHaptic(PlayerStickGate.OCTAGON)
        // Touch-down there makes it the centre: no deflection, no haptic.
        assertEquals(listOf(PlayerTouchOutput.Motion(0, 0f, 0f)), tracker.began(1, down))
        assertEquals(down, tracker.stickPositions["stick0"]?.center)
        // Full right, the octagon's 0° notch: a click; none while it stays there.
        assertEquals(listOf(PlayerTouchOutput.Motion(0, 1f, 0f), click), tracker.moved(1, at(0.0, 1f)))
        assertEquals(emptyList<PlayerTouchOutput>(), tracker.moved(1, at(0.0, 1.01f)))
        assertEquals(true, tracker.stickPositions["stick0"]?.fullTilt)
        // Far outside the zone: still this stick, the centre where the thumb landed, no haptic.
        assertEquals(emptyList<PlayerTouchOutput>(), tracker.moved(1, at(0.0, 8f)).filter { it !is PlayerTouchOutput.Motion })
        assertEquals(down, tracker.stickPositions["stick0"]?.center)
        assertEquals("The knob at the rim", down.x + travel, tracker.stickPositions["stick0"]!!.knob.x, 0.01f)
        // Round the rim to 22.5°: no click; into the 45° notch: a click; held within 12°: no second.
        assertFalse(tracker.moved(1, at(22.5, 1.5f)).any { it == click || it == PlayerTouchOutput.FullTiltHaptic })
        assertTrue(tracker.moved(1, at(45.0, 1.5f)).contains(click))
        assertFalse(tracker.moved(1, at(55.0, 1.5f)).contains(click))
        // Back inside, then out between notches: the tick instead.
        tracker.moved(1, at(0.0, 0.5f))
        assertEquals(false, tracker.stickPositions["stick0"]?.fullTilt)
        assertEquals(PlayerTouchOutput.FullTiltHaptic, tracker.moved(1, at(22.5, 1.2f)).last())
        // A second touch in the zone doesn't take the held stick.
        assertEquals(emptyList<PlayerTouchOutput>(), tracker.began(2, stick.center))
        // Lift: straight back to zero.
        assertEquals(listOf(PlayerTouchOutput.Motion(0, 0f, 0f)), tracker.ended(1))
        assertNull(tracker.stickPositions["stick0"])
        // A touch at the zone's corner makes that point the centre: no tilt.
        val corner = Offset(stick.frame.left + 1f, stick.frame.top + 1f)
        val cornerOutput = tracker.began(3, corner)
        assertTrue(cornerOutput.filterIsInstance<PlayerTouchOutput.Motion>().all { it.x == 0f && it.y == 0f })
        assertEquals(corner, tracker.stickPositions["stick0"]?.center)
        assertEquals(listOf(PlayerTouchOutput.Motion(0, 0f, 0f)), tracker.releaseAll())
    }

    /** The DualShock's near-square gate bumps at its corners only; a round gate never clicks. Mirrors iOS. */
    @Test fun theSquareGateBumpsAtItsCornersAndARoundGateOnlyTicks() {
        fun run(family: String, analog: Boolean, id: String, degrees: Double): List<PlayerTouchOutput> {
            val tracker = PlayerTouchTracker(stage(family, proLandscape, analog = analog).controls)
            val stick = requireNotNull(tracker.layout.control(id))
            tracker.began(1, stick.center)
            return tracker.moved(1, Offset(stick.center.x + 1.5f * stick.travel * Math.cos(Math.toRadians(degrees)).toFloat(),
                stick.center.y + 1.5f * stick.travel * Math.sin(Math.toRadians(degrees)).toFloat()))
        }
        assertEquals(PlayerTouchOutput.FullTiltHaptic, run("psx", true, "stick0", 0.0).last())
        assertEquals(PlayerTouchOutput.NotchHaptic(PlayerStickGate.SQUARE), run("psx", true, "stick0", 45.0).last())
        assertEquals(PlayerTouchOutput.NotchHaptic(PlayerStickGate.SQUARE), run("psx", true, "stick1", 225.0).last())
        val circle = run("3ds", false, "circlePad", 45.0)
        assertEquals(PlayerTouchOutput.FullTiltHaptic, circle.last())
        assertTrue(circle.none { it is PlayerTouchOutput.NotchHaptic })
    }

    @Test fun aFixedCentreStaysAtTheZonesCentreAndNeverFollows() {
        val plan = requireNotNull(PlayerControlPlan.of("psp", stickTuning = PlayerStickTuning(centre = PlayerStickTuning.Centre.FIXED)))
        val tracker = PlayerTouchTracker(resolvePlayerStage(874f, 402f, proLandscape.insets, 16f / 9f, plan).controls)
        val stick = requireNotNull(tracker.layout.control("stick0"))
        val travel = stick.travel.toDouble()
        val point = Offset(stick.center.x + 20f, stick.center.y)
        val (x, _) = PlayerStickModel.output(20.0, 0.0, stick.stickProfile!!)
        val first = tracker.began(1, point).filterIsInstance<PlayerTouchOutput.Motion>().single()
        assertEquals("Off the fixed centre: deflected at once", x.toFloat(), first.x, 0.0001f)
        assertEquals(0f, first.y)
        assertEquals(stick.center, tracker.stickPositions["stick0"]?.center)
        tracker.moved(1, Offset(stick.center.x + 300f, stick.center.y))
        assertEquals("Never follows", stick.center, tracker.stickPositions["stick0"]?.center)
        assertTrue(travel > 0)
    }

    @Test fun clicksOnStickConsolesArePressesWithTheLightTick() {
        val tracker = PlayerTouchTracker(stage("psx", proLandscape, analog = true).controls)
        val l3 = requireNotNull(tracker.layout.control("L3"))
        assertEquals(listOf(digital(KeyEvent.KEYCODE_BUTTON_THUMBL, true), PlayerTouchOutput.PressHaptic), tracker.began(1, l3.center))
        assertEquals(listOf(digital(KeyEvent.KEYCODE_BUTTON_THUMBL, false)), tracker.ended(1))
    }

    // ---- Per-console layouts ----

    @Test fun nintendo64PutsTheStickInTheThumbSpotWithTheSmallDpadAbove() {
        val layout = stage("n64", proLandscape)
        val controls = layout.controls
        val game = layout.viewport
        // (The control size gives way a little so the game keeps 70 % of its height.)
        val s = controls.metrics.scale
        assertTrue("scale $s", s >= 0.88f)
        val stick = requireNotNull(controls.control("stick0"))
        val pad = requireNotNull(controls.dpad)
        assertEquals("Primary zone", 160f * s, stick.frame.width, 0.01f)
        assertEquals("Primary zone", 160f * s, stick.frame.height, 0.01f)
        assertEquals("Small d-pad", 104f * s, pad.frame.width, 0.01f)
        assertEquals(stick.center.x, pad.center.x, 0.5f)
        assertEquals("D-pad just above the stick", 10f, stick.frame.top - pad.frame.bottom, 0.5f)
        assertTrue("Left rail", stick.frame.right < game.left)
        // Z: a large tab at the top of the left rail, L at its outer corner; R at the top of the right rail.
        val z = requireNotNull(controls.control("Z"))
        val l = requireNotNull(controls.control("L"))
        val r = requireNotNull(controls.control("R"))
        assertEquals(92f * s, z.frame.width, 0.01f)
        assertEquals(max(40f, 48f * s), z.frame.height, 0.01f)
        assertTrue(z.frame.width > l.frame.width)
        assertEquals(8f, z.frame.top, 0.5f)
        assertTrue(l.frame.right < z.frame.left)
        assertTrue(z.frame.right < game.left)
        assertTrue(r.frame.left > game.right)
        assertEquals(8f, r.frame.top, 0.5f)
        // A (64) with B (56) down-left; the C buttons a 44 dp diamond above-right of A.
        val aa = requireNotNull(controls.control("A"))
        val bb = requireNotNull(controls.control("B"))
        assertEquals(64f * s, aa.frame.width, 0.01f)
        assertEquals(56f * s, bb.frame.width, 0.01f)
        assertTrue(bb.center.x < aa.center.x)
        assertTrue(bb.center.y > aa.center.y)
        val cs = listOf("C-Up", "C-Down", "C-Left", "C-Right").map { requireNotNull(controls.control(it)) { it } }
        val (cUp, cDown, cLeft, cRight) = cs
        for (c in cs) {
            assertEquals(c.id, 44f, c.frame.width, 0.01f)
            assertTrue("C buttons push the right stick", c.spec.isAxisButton)
        }
        val cCentre = Offset(cs.map { it.center.x }.average().toFloat(), cs.map { it.center.y }.average().toFloat())
        assertTrue("C diamond right of A", cCentre.x > aa.center.x)
        assertTrue("C diamond above A", cCentre.y < aa.center.y)
        assertEquals(cUp.center.x, cDown.center.x, 0.01f)
        assertEquals(cLeft.center.y, cRight.center.y, 0.01f)
        assertTrue(cUp.center.y < cDown.center.y)
        assertTrue(cLeft.center.x < cRight.center.x)
        // Primary controls level: the stick and the face cluster share a height.
        assertEquals(faceBox(controls).midY, stick.center.y, 0.5f)
        // Start beside Menu, bottom centre under the game; nothing at the rails' feet.
        val start = requireNotNull(controls.control("Start"))
        assertTrue(controls.menuFrame.top > game.bottom)
        assertEquals(controls.menuCenter.y, start.center.y, 0.5f)
        assertTrue(start.frame.left > controls.menuFrame.right)
        assertEquals(game.midX, (controls.menuFrame.left + start.frame.right) / 2, 0.5f)
    }

    @Test fun nintendo3dsAddsTheCirclePadWithTheDpadBelowIt() {
        val plan = plan("3ds")
        assertEquals("Left analog to the core", 0, plan.leftStick?.stick)
        assertTrue(plan.leftStickPrimary)
        assertEquals(PlayerControlPlan.DPadSlot.BELOW, plan.dpadSlot)
        assertEquals(PlayerFaceArrangement.DIAMOND, plan.arrangement)
        assertEquals(listOf("ZL", "L"), plan.leftShoulders.map { it.id })
        assertEquals(listOf("R", "ZR"), plan.rightShoulders.map { it.id })
        for (phone in listOf(proPortrait, proLandscape)) {
            val layout = stage("3ds", phone)
            val controls = layout.controls
            val pad = requireNotNull(controls.control("circlePad")) { phone.name }
            val dpad = requireNotNull(controls.dpad) { phone.name }
            assertEquals(PlayerTouchKind.STICK, pad.kind)
            assertEquals(phone.name, 160f * controls.metrics.scale, pad.frame.width, 0.01f)
            assertEquals(phone.name, 104f * controls.metrics.scale, dpad.frame.width, 0.01f)
            assertEquals("D-pad below the circle pad: ${phone.name}", 10f, dpad.frame.top - pad.frame.bottom, 0.5f)
            assertEquals(pad.center.x, dpad.center.x, 0.5f)
            // The face diamond level with the circle pad.
            assertEquals(phone.name, pad.center.y, faceBox(controls).midY, 0.5f)
            // Nothing over the game (both screens).
            for (control in controls.controls) {
                assertFalse("${control.id} over the game: ${phone.name}", layout.viewport.intersectsStrictly(control.frame))
            }
        }
        // Beside the stick, toward the centre, when a band is too short for the stack.
        val compact = requireNotNull(plan.compact())
        assertEquals(PlayerControlPlan.DPadSlot.BESIDE, compact.dpadSlot)
        val beside = compact.place(
            PlayerControlAnchors(0f..300f, 500f..800f, alignOuter = true, leftClusterY = 200f, rightClusterY = 200f,
                shoulders = PlayerControlAnchors.Shoulders.Top(8f), utility = PlayerControlAnchors.Utility.Row(400f, 380f, 600f)),
            PlayerControlMetrics(1f), mirrored = false,
        )
        val stick = requireNotNull(beside.control("circlePad"))
        val small = requireNotNull(beside.dpad)
        assertEquals(0f, stick.frame.left, 0.01f)
        assertEquals(10f, small.frame.left - stick.frame.right, 0.01f)
        assertEquals(stick.center.y, small.center.y, 0.01f)
        assertNull("Only stacked primaries compact", plan("psp").compact())
    }

    @Test fun pspPutsTheNubBelowTheDpad() {
        val controls = stage("psp", proLandscape).controls
        val nub = requireNotNull(controls.control("stick0"))
        val dpad = requireNotNull(controls.dpad)
        assertEquals("Secondary zone", 120f * controls.metrics.scale, nub.frame.width, 0.01f)
        assertEquals(36f * controls.metrics.scale, nub.travel, 0.01f)
        assertEquals("The d-pad keeps the thumb spot", 150f * controls.metrics.scale, dpad.frame.width, 0.01f)
        assertEquals(10f, nub.frame.top - dpad.frame.bottom, 0.5f)
        assertEquals(dpad.center.x, nub.center.x, 0.5f)
        assertFalse(plan("psp").leftStickPrimary)
    }

    @Test fun playStationShowsSticksAndClicksOnlyWithAnalogControls() {
        val off = plan("psx")
        assertNull(off.leftStick)
        assertNull(off.rightStick)
        assertEquals("The Analog mode button stays", listOf("Select", "Start", "Analog"), off.utilities.map { it.id })
        val on = plan("psx", analog = true)
        assertEquals(listOf("Select", "Start", "L3", "R3", "Analog"), on.utilities.map { it.id })
        assertEquals(0, on.leftStick?.stick)
        assertEquals(1, on.rightStick?.stick)
        for (phone in listOf(proPortrait, proLandscape)) {
            val controls = stage("psx", phone, analog = true).controls
            val left = requireNotNull(controls.control("stick0"))
            val right = requireNotNull(controls.control("stick1"))
            val dpad = requireNotNull(controls.dpad)
            val face = faceBox(controls)
            // Under the clusters, lined up under the taller one.
            assertTrue(phone.name, left.frame.top > dpad.frame.bottom)
            assertEquals(phone.name, 10f, left.frame.top - face.bottom, 0.5f)
            assertEquals(phone.name, left.frame.top, right.frame.top, 0.5f)
            assertEquals(dpad.center.x, left.center.x, 0.5f)
            assertEquals(face.midX, right.center.x, 0.5f)
            assertTrue(left.frame.width >= controls.metrics.minimumSecondaryZone - 0.01f)
            val hidden = stage("psx", phone).controls
            assertNull(hidden.control("stick0"))
            assertNull(hidden.control("L3"))
            assertNotNull(hidden.control("Analog"))
        }
    }

    @Test fun consolesWithoutSticksGetNone() {
        for (family in listOf("nes", "snes", "gba", "gb", "sega16", "nds", "atari2600")) {
            assertFalse(family, PlayerControlPlan.of(family)?.hasSticks ?: true)
        }
    }

    @Test fun touchesOnTheGameGoToTheGame() {
        // DS and 3DS touch screens, and any game a grown hit region reaches.
        for (family in listOf("nds", "3ds", "n64", "nes")) {
            for (phone in listOf(proPortrait, proLandscape)) {
                val layout = stage(family, phone)
                val controls = layout.controls
                val game = layout.viewport
                assertEquals(listOf(game), controls.gameScreens)
                for (point in listOf(game.center, Offset(game.left + 1f, game.bottom - 1f), Offset(game.right - 1f, game.bottom - 1f), Offset(game.left + 1f, game.top + 1f))) {
                    assertNull("$family ${phone.name} $point", controls.target(point))
                }
            }
        }
        // The d-pad fills its rail, 10 dp from the game: its grown disc reaches 4 dp over the game,
        // and a touch there goes to the game.
        val nes = stage("nes", proLandscape)
        val pad = requireNotNull(nes.controls.dpad)
        val onGame = Offset(nes.viewport.left + 2f, pad.center.y)
        assertTrue("The d-pad's grown disc reaches the game", PlayerTouchRules.hits(onGame, pad))
        assertNull(nes.controls.target(onGame))
        assertEquals(PlayerTouchTarget.Dpad, nes.controls.copy(gameScreens = emptyList()).target(onGame))
    }

    @Test fun mirroredLayoutsHitTheMovedControls() {
        val layout = stage("nes", proPortrait, mirrored = true).controls
        val pad = requireNotNull(layout.dpad)
        val aa = requireNotNull(layout.control("A"))
        assertEquals(PlayerTouchTarget.Dpad, layout.target(pad.center))
        assertEquals(PlayerTouchTarget.Buttons(listOf("A")), layout.target(aa.center))
        assertTrue("Nothing left where the d-pad was", layout.target(Offset(20f + 75f, pad.center.y)) != PlayerTouchTarget.Dpad)
    }
}
