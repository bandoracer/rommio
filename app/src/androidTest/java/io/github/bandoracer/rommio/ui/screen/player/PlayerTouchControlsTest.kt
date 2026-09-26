package io.github.bandoracer.rommio.ui.screen.player

import android.content.pm.ActivityInfo
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import io.github.bandoracer.rommio.ThemeUnderTest
import io.github.bandoracer.rommio.domain.input.PlatformControlProfileResolver
import io.github.bandoracer.rommio.domain.player.NativePlayerTestActivity
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The player's touch surface on a device (docs/player-controls.md, Verification): touches at a
 * button's edge and between A and B register, a d-pad drag that leaves the pad keeps its
 * direction, quick taps never leave a button held, rolling follows the thumb, pause and the
 * overlay going away release everything, and haptics follow their setting. Inputs are read from
 * the overlay's output (what the player sends to the core) and from the debug [PlayerInputLog].
 */
class PlayerTouchControlsTest {
    @get:Rule val ui = createAndroidComposeRule<NativePlayerTestActivity>()

    private val outputs = mutableListOf<PlayerTouchOutput>()
    private var layout: PlayerTouchLayout? = null
    private var viewport: Rect? = null
    /** Touch-downs that reached the game (a view under the controls), unconsumed. */
    private val gameTouches = mutableListOf<Offset>()
    private var showControls by mutableStateOf(true)
    private var haptics by mutableStateOf(true)
    private var present by mutableStateOf(true)

    private val a = KeyEvent.KEYCODE_BUTTON_A
    private val b = KeyEvent.KEYCODE_BUTTON_B

    @Before fun clearLog() = PlayerInputLog.clear()

    @After fun portrait() {
        ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun render(family: String = "nes", portrait: Boolean = true) {
        ui.activity.requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        ui.setContent {
            RommioTheme(ThemeUnderTest.preferences) {
                val appTheme = RommioTheme.current
                BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0D1015))) {
                    val aspect = PlatformControlProfileResolver().supportedProfiles().first { it.familyId == family }.preferredViewportAspectRatio
                    val stage = resolvePlayerStage(maxWidth.value, maxHeight.value, PlayerStageInsets(top = 24f, bottom = 24f), aspect, PlayerControlPlan.of(family))
                    layout = stage.controls
                    viewport = stage.viewport
                    // The game: a view under the controls (as the player's game view is), recording the
                    // touches that reach it.
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    event.changes.forEach { if (it.pressed && !it.previousPressed && !it.isConsumed) gameTouches += it.position }
                                }
                            }
                        },
                    )
                    if (present) {
                        PlayerControlsOverlay(
                            layout = stage.controls,
                            visual = resolvePlayerVisualTheme(null, family, appTheme),
                            opacity = PlayerStyle.DEFAULT_OPACITY,
                            showControls = showControls,
                            haptics = haptics,
                            onInput = { outputs += it },
                            onMenu = {},
                        )
                    }
                }
            }
        }
        ui.waitForIdle()
    }

    /** A point of the layout (dp) in root pixels. */
    private fun px(point: Offset): Offset {
        val density = ui.activity.resources.displayMetrics.density
        return Offset(point.x * density, point.y * density)
    }

    private fun control(id: String) = requireNotNull(layout?.control(id)) { id }
    private fun digital(): List<Pair<Int, Boolean>> = outputs.filterIsInstance<PlayerTouchOutput.Digital>().map { it.key to it.pressed }
    private fun loggedDigital(): List<Pair<Int, Boolean>> = PlayerInputLog.snapshot().filterIsInstance<PlayerTouchOutput.Digital>().map { it.key to it.pressed }

    private fun reset() = ui.runOnIdle {
        outputs.clear()
        gameTouches.clear()
        PlayerInputLog.clear()
    }

    /**
     * docs/player-controls.md: the control surface never claims touches outside controls, and a
     * touch on the game goes to the game even where a control's grown hit region reaches over it.
     */
    @Test fun touchesThatMissEveryControlReachTheGame() {
        render("nes", portrait = true)
        reset()
        val game = requireNotNull(viewport)
        // On the game.
        ui.onRoot().performTouchInput { down(px(game.center)); up() }
        ui.runOnIdle {
            assertEquals(1, gameTouches.size)
            assertTrue("Nothing for the controls", outputs.isEmpty())
        }
        // On the band, between the clusters, off every control.
        val pad = control("dpad")
        ui.onRoot().performTouchInput { down(px(Offset(game.center.x, pad.center.y))); up() }
        ui.runOnIdle {
            assertEquals(2, gameTouches.size)
            assertTrue(outputs.isEmpty())
        }
        // On A: the controls take it, the game never sees it.
        ui.onRoot().performTouchInput { down(px(control("A").center)); up() }
        ui.runOnIdle {
            assertEquals(2, gameTouches.size)
            assertEquals(listOf(a to true, a to false), digital())
        }
        // Landscape: at the game's edge beside the d-pad (on a screen where the game fills the width
        // between the rails, inside the d-pad's grown disc: PlayerTouchModelTest), the game's.
        ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(10_000) { ui.onRoot().fetchSemanticsNode().size.let { it.width > it.height } }
        ui.waitForIdle()
        reset()
        val wide = requireNotNull(viewport)
        val landscapePad = control("dpad")
        val onGame = Offset(wide.left + 2f, landscapePad.center.y)
        println("Game edge inside the d-pad's grown disc: ${PlayerTouchRules.hits(onGame, landscapePad)}")
        ui.onRoot().performTouchInput { down(px(onGame)); up() }
        ui.runOnIdle {
            assertEquals(1, gameTouches.size)
            assertTrue("No d-pad input", outputs.isEmpty())
        }
    }

    @Test fun touchesAtAButtonsEdgeRegister() {
        render()
        val aa = control("A")
        val bb = control("B")
        // Inside the grown hit region: 12 dp above A's drawn edge, 12 dp left of B's.
        for ((point, key) in listOf(Offset(aa.center.x, aa.frame.top - 12f) to a, Offset(bb.frame.left - 12f, bb.center.y) to b,
            Offset(aa.frame.right + 10f, aa.center.y) to a, Offset(bb.center.x, bb.frame.bottom + 12f) to b)) {
            reset()
            ui.onRoot().performTouchInput { down(px(point)); advanceEventTime(50); up() }
            ui.runOnIdle {
                assertEquals("Edge at $point", listOf(key to true, key to false), digital())
                assertEquals("The debug log saw the same", digital(), loggedDigital())
            }
        }
        // Well outside every control: nothing.
        reset()
        ui.onRoot().performTouchInput { down(px(Offset(aa.center.x, aa.frame.top - 60f))); up() }
        ui.runOnIdle { assertTrue(digital().isEmpty()) }
    }

    @Test fun aTouchBetweenAAndBPressesBoth() {
        render()
        val ac = control("A").center
        val bc = control("B").center
        val mid = Offset((ac.x + bc.x) / 2, (ac.y + bc.y) / 2)
        reset()
        ui.onRoot().performTouchInput { down(px(mid)); advanceEventTime(60); up() }
        ui.runOnIdle {
            val events = digital()
            assertEquals(setOf(a to true, b to true), events.take(2).toSet())
            assertEquals(setOf(a to false, b to false), events.drop(2).toSet())
            assertEquals(4, events.size)
            assertEquals(events, loggedDigital())
        }
        // The gap between A and B off the chord band still hits the nearer button (no dead gap).
        reset()
        val nearA = Offset(mid.x + (ac.x - mid.x) * 0.6f, mid.y + (ac.y - mid.y) * 0.6f)
        ui.onRoot().performTouchInput { down(px(nearA)); up() }
        ui.runOnIdle { assertEquals(listOf(a to true, a to false), digital()) }
    }

    @Test fun aDpadDragThatLeavesThePadKeepsItsDirection() {
        render()
        val pad = requireNotNull(layout?.dpad).center
        reset()
        ui.onRoot().performTouchInput {
            down(px(Offset(pad.x + 40f, pad.y)))
            moveTo(px(Offset(pad.x + 120f, pad.y + 4f)))
            moveTo(px(Offset(pad.x + 230f, pad.y + 8f)))
        }
        ui.runOnIdle { assertEquals("Right, held far off the pad", listOf(KeyEvent.KEYCODE_DPAD_RIGHT to true), digital()) }
        ui.onNodeWithTag("player.control.Right").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Pressed"))
        ui.onRoot().performTouchInput { moveTo(px(Offset(pad.x + 200f, pad.y + 200f))) }
        ui.runOnIdle { assertEquals(KeyEvent.KEYCODE_DPAD_DOWN to true, digital().last()) }
        ui.onRoot().performTouchInput { up() }
        ui.runOnIdle {
            assertEquals(
                listOf(KeyEvent.KEYCODE_DPAD_RIGHT to true, KeyEvent.KEYCODE_DPAD_DOWN to true, KeyEvent.KEYCODE_DPAD_DOWN to false, KeyEvent.KEYCODE_DPAD_RIGHT to false),
                digital(),
            )
            assertEquals(digital(), loggedDigital())
        }
    }

    @Test fun quickTapsNeverLeaveAButtonHeld() {
        render("snes", portrait = false)
        val ids = listOf("A", "B", "X", "Y", "L", "R", "Select", "Start")
        reset()
        ui.onRoot().performTouchInput {
            // Single quick taps (no time between down and up), on centres and near edges.
            for (id in ids) {
                val c = control(id)
                down(px(c.center)); up()
                down(px(Offset(c.frame.left - 6f, c.center.y))); up()
            }
            // Two thumbs drumming: overlapping quick taps.
            repeat(6) {
                down(0, px(control("A").center)); down(1, px(control("B").center)); up(0); up(1)
                down(1, px(control("Y").center)); down(0, px(control("X").center)); up(1); up(0)
            }
            // A d-pad flick.
            val pad = requireNotNull(layout?.dpad).center
            down(px(Offset(pad.x, pad.y - 50f))); up()
        }
        ui.runOnIdle {
            val events = loggedDigital()
            assertEquals("The overlay and the log agree", digital(), events)
            assertTrue(events.isNotEmpty())
            for ((key, list) in events.groupBy { it.first }) {
                assertEquals("Key $key: every press is released", list.count { it.second }, list.count { !it.second })
                assertFalse("Key $key ends released", list.last().second)
            }
        }
        for (id in ids) {
            ui.onNodeWithTag("player.control.${control(id).spec.label}").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Released"))
        }
    }

    @Test fun rollingAcrossTheFaceFollowsTheThumb() {
        render()
        reset()
        ui.onRoot().performTouchInput {
            down(px(control("B").center))
            moveTo(px(control("A").center))
            up()
        }
        ui.runOnIdle { assertEquals(listOf(b to true, b to false, a to true, a to false), digital()) }
    }

    @Test fun pausingAndTheOverlayGoingAwayReleaseEverything() {
        render()
        reset()
        ui.onRoot().performTouchInput { down(0, px(control("A").center)); down(1, px(requireNotNull(layout?.dpad).center + Offset(0f, -50f))) }
        ui.runOnIdle { assertEquals(setOf(a to true, KeyEvent.KEYCODE_DPAD_UP to true), digital().toSet()) }
        // Pause hides the controls: both let go at once.
        ui.runOnIdle { showControls = false }
        ui.runOnIdle { assertEquals(setOf(a to false, KeyEvent.KEYCODE_DPAD_UP to false), digital().drop(2).toSet()) }
        ui.onRoot().performTouchInput { up(0); up(1) }
        ui.runOnIdle {
            assertEquals("Lifting after the release sends nothing more", 4, digital().size)
            showControls = true
        }
        reset()
        ui.onRoot().performTouchInput { down(px(control("B").center)) }
        ui.runOnIdle { assertEquals(listOf(b to true), digital()) }
        // The overlay leaving the screen lets go too.
        ui.runOnIdle { present = false }
        ui.runOnIdle { assertEquals(listOf(b to true, b to false), digital()) }
        ui.onRoot().performTouchInput { up() }
    }

    @Test fun hapticsFollowTheSetting() {
        render()
        val pad = requireNotNull(layout?.dpad).center
        reset()
        ui.onRoot().performTouchInput {
            down(px(control("A").center)); up()
            down(px(Offset(pad.x + 40f, pad.y))); moveTo(px(Offset(pad.x + 40f, pad.y + 40f))); up()
        }
        ui.runOnIdle {
            val log = PlayerInputLog.snapshot()
            assertEquals("A button press ticks once", 1, log.count { it == PlayerTouchOutput.PressHaptic })
            assertEquals("Each new d-pad direction ticks", 2, log.count { it == PlayerTouchOutput.DirectionHaptic })
            // Press edges only: the haptic follows the press, never a release.
            assertEquals(PlayerTouchOutput.PressHaptic, log[log.indexOf(PlayerTouchOutput.Digital(a, true)) + 1])
            haptics = false
        }
        reset()
        ui.onRoot().performTouchInput {
            down(px(control("A").center)); up()
            down(px(Offset(pad.x + 40f, pad.y))); up()
        }
        ui.runOnIdle {
            val log = PlayerInputLog.snapshot()
            assertTrue("Off: no haptics", log.none { it == PlayerTouchOutput.PressHaptic || it == PlayerTouchOutput.DirectionHaptic })
            assertEquals(4, log.size)
        }
    }
}
