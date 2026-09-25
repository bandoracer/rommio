package io.github.bandoracer.rommio.ui.screen.player

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bandoracer.rommio.ThemeUnderTest
import io.github.bandoracer.rommio.domain.input.PlatformControlProfileResolver
import io.github.bandoracer.rommio.domain.player.NativePlayerTestActivity
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Review screenshots of touch controls v2 for the families the owner plays (NES two-button, SNES
 * diamond, GBA with shoulders, PlayStation expansion) in portrait and landscape, and the stick
 * consoles (docs/player-controls.md, "Analog sticks and per-console layouts": Nintendo 64, 3DS,
 * PSP, PlayStation with Analog controls on) in landscape, at rest and with the stick held at full
 * tilt, on the real stage (bias light from a stand-in cover, title strip, Menu). Saved to the
 * app's external files as `controls-<name>-<theme>-<scheme>-<orientation>.png`. The game picture
 * is a stand-in frame; the live-player shots come from the NES fixture in `PlayerScreenProgressTest`
 * and the app.
 */
class PlayerControlsShotsTest {
    @get:Rule val ui = createAndroidComposeRule<NativePlayerTestActivity>()

    private data class Game(
        val family: String,
        val title: String,
        val detail: String,
        val analog: Boolean = false,
        val name: String = family,
        val aspect: Float? = null,
        /** Also shot with this stick held at full tilt (the ring brightened, the knob at the output). */
        val held: String? = null,
    )

    private val games = listOf(
        Game("nes", "Kirby's Adventure", "Nintendo Entertainment System · 25 min played"),
        Game("snes", "Super Metroid", "Super Nintendo · 1 h 05 min played"),
        Game("gba", "Metroid Fusion", "Game Boy Advance · 40 min played"),
        Game("psx", "Symphony of the Night", "PlayStation · 2 h 10 min played"),
    )

    /** The stick consoles: landscape (their orientation policy). */
    private val stickGames = listOf(
        Game("n64", "Super Mario 64", "Nintendo 64 · 3 h 20 min played", held = "stick0"),
        Game("3ds", "Super Mario 3D Land", "Nintendo 3DS · 55 min played", aspect = 400f / 480f, held = "circlePad"),
        Game("psp", "Lumines", "PlayStation Portable · 1 h 15 min played", held = "stick0"),
        Game("psx", "Ape Escape", "PlayStation · Analog controls on", analog = true, name = "psx-analog", held = "stick0"),
        Game("psx", "Ape Escape", "PlayStation · Analog controls off", name = "psx-analog-off"),
    )
    private var layout: PlayerTouchLayout? = null

    /** A small, bright stand-in game frame: sky, hills, blocks (pixel art at 256 × 224). */
    private fun standInFrame(): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 224, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.shader = LinearGradient(0f, 0f, 0f, 224f, 0xFF3A1D6E.toInt(), 0xFFE0689A.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 256f, 224f, paint)
        paint.shader = null
        paint.color = 0xFFFFD166.toInt()
        canvas.drawCircle(196f, 58f, 22f, paint)
        paint.color = 0xFF2B8A6E.toInt()
        canvas.drawCircle(60f, 230f, 90f, paint)
        paint.color = 0xFF1E6B58.toInt()
        canvas.drawCircle(200f, 250f, 100f, paint)
        paint.color = 0xFF6B3A2A.toInt()
        canvas.drawRect(0f, 192f, 256f, 224f, paint)
        paint.color = 0xFFF28C38.toInt()
        for (x in listOf(96f, 112f, 128f)) canvas.drawRect(x, 120f, x + 14f, 134f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(40f, 176f, 52f, 192f, paint)
        return bitmap
    }

    @Test fun controlsOnTheStage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val frame = standInFrame()
        val cover = File(context.cacheDir, "controls-shot-cover.png").apply { outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        var game by mutableStateOf(games.first())
        ui.activity.runOnUiThread {
            WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
        ui.setContent {
            RommioTheme(ThemeUnderTest.preferences) {
                val visual = resolvePlayerVisualTheme(null, game.family, RommioTheme.current)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val aspect = game.aspect ?: PlatformControlProfileResolver().supportedProfiles().first { it.familyId == game.family }.preferredViewportAspectRatio
                    val landscape = maxWidth > maxHeight
                    val insets = if (landscape) PlayerStageInsets(bottom = 16f, left = 24f, right = 24f) else PlayerStageInsets(top = 32f, bottom = 24f)
                    val stage = resolvePlayerStage(maxWidth.value, maxHeight.value, insets, aspect, PlayerControlPlan.of(game.family, game.analog))
                    layout = stage.controls
                    PlayerStageBackdrop(visual, "file://${cover.path}", Modifier.fillMaxSize())
                    Image(
                        frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.offset(stage.viewport.left.dp, stage.viewport.top.dp).size(stage.viewport.width.dp, stage.viewport.height.dp),
                    )
                    PlayerControlsOverlay(stage.controls, visual, PlayerStyle.DEFAULT_OPACITY, showControls = true, haptics = true,
                        onInput = {}, onMenu = {}, title = game.title, detail = game.detail)
                }
            }
        }
        val out = context.getExternalFilesDir(null)
        fun save(name: String, orientation: String) {
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(out, "controls-$name-${ThemeUnderTest.label}-$orientation.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        for (orientation in listOf("portrait", "landscape")) {
            ui.activity.requestedOrientation = if (orientation == "portrait") ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            for (entry in games + if (orientation == "landscape") stickGames else emptyList()) {
                ui.runOnIdle { game = entry }
                ui.waitForIdle()
                Thread.sleep(1500)
                ui.waitForIdle()
                save(entry.name, orientation)
                val held = entry.held?.let { id -> layout?.control(id) } ?: continue
                // Held: touch down left of the zone's centre and push past full travel to the upper
                // right, so the centre floats, follows and the ring brightens.
                val density = ui.activity.resources.displayMetrics.density
                val start = Offset(held.center.x - held.travel / 2, held.center.y)
                val end = Offset(start.x + held.travel * 1.1f, start.y - held.travel * 0.6f)
                ui.onRoot().performTouchInput {
                    down(Offset(start.x * density, start.y * density))
                    moveTo(Offset(end.x * density, end.y * density))
                }
                ui.waitForIdle()
                Thread.sleep(400)
                save("${entry.name}-held", orientation)
                ui.onRoot().performTouchInput { up() }
                ui.waitForIdle()
            }
        }
        ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
