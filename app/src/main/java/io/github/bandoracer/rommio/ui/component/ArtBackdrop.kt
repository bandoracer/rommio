package io.github.bandoracer.rommio.ui.component

import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import io.github.bandoracer.rommio.ui.art.ArtBake
import io.github.bandoracer.rommio.ui.art.AuroraLight
import io.github.bandoracer.rommio.ui.art.CoverArt
import io.github.bandoracer.rommio.ui.art.CoverPalette
import io.github.bandoracer.rommio.ui.art.rememberCoverPalette
import io.github.bandoracer.rommio.ui.theme.RommioThemeValue
import io.github.bandoracer.rommio.ui.theme.AuroraArtTokens
import io.github.bandoracer.rommio.ui.theme.PageLight
import io.github.bandoracer.rommio.ui.theme.ProvidePageLight
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp

/**
 * Where a page's lights sit (docs/theme-layouts.md §1.3, the studio's `.a-field` variants): the
 * room of Play, a page with a hero or list, the quieter settings and unlit pages, the welcome fan.
 */
enum class PageFieldStyle {
    ROOM, PAGE, QUIET, WELCOME;

    /** One radial light: centre and radii as fractions of the page; colour fades out at [fade] of the radius. */
    @Immutable
    data class Spot(val x: Float, val y: Float, val rx: Float, val ry: Float, val fade: Float)

    val spots: List<Spot>
        get() = when (this) {
            ROOM -> listOf(Spot(.26f, .24f, .9f, .46f, .7f), Spot(.96f, .06f, .7f, .34f, .7f), Spot(.4f, .86f, 1.1f, .4f, .72f))
            PAGE -> listOf(Spot(.08f, 0f, .8f, .3f, .7f), Spot(.96f, .04f, .7f, .3f, .7f), Spot(.5f, .26f, 1.1f, .26f, .72f))
            QUIET -> listOf(Spot(.1f, 0f, .8f, .26f, .7f), Spot(.96f, .02f, .6f, .24f, .7f), Spot(.5f, .2f, 1f, .18f, .72f))
            WELCOME -> listOf(Spot(.14f, .18f, .8f, .4f, .7f), Spot(.94f, .3f, .7f, .4f, .7f), Spot(.5f, .5f, 1f, .44f, .72f))
        }

    /** The veil that settles the light into the ground: (location, ground opacity). */
    val veil: List<Pair<Float, Float>>
        get() = when (this) {
            ROOM -> listOf(0f to 0f, .48f to 0f, .76f to .55f, 1f to .88f)
            PAGE -> listOf(0f to 0f, .3f to .4f, .58f to .82f, .86f to 1f, 1f to 1f)
            QUIET -> listOf(0f to 0f, .36f to .7f, .66f to 1f, 1f to 1f)
            WELCOME -> listOf(0f to 0f, .42f to .3f, .76f to .86f, 1f to 1f)
        }

    /** The whole field's opacity (quiet pages are dimmer). */
    val opacity: Float get() = if (this == QUIET) 0.62f else 1f
}

/** A page's subject game and field style. */
@Immutable
data class PageSubject(val coverUrl: String?, val style: PageFieldStyle)

/**
 * The game a page is about and how its field is lit. Pages declare it with [PageArtSubject]; the
 * shell draws [PageField] behind every page and lights the page with the subject's cover
 * (Aurora). The latest declaration wins; leaving a page removes it.
 */
@Stable
class PageArtState {
    private val entries = mutableStateListOf<Pair<Any, PageSubject>>()

    val subject: PageSubject? get() = entries.lastOrNull()?.second

    fun declare(owner: Any, subject: PageSubject) {
        val index = entries.indexOfFirst { it.first === owner }
        if (index >= 0 && index == entries.lastIndex && entries[index].second == subject) return
        entries.removeAll { it.first === owner }
        entries.add(owner to subject)
    }

    fun withdraw(owner: Any) {
        entries.removeAll { it.first === owner }
    }
}

val LocalPageArt = staticCompositionLocalOf<PageArtState?> { null }

/**
 * Declares this page's subject (its cover lights the page in Aurora; null for none) and the
 * page's field style, without lighting the page's own content. Prefer [LitPage].
 */
@Composable
fun PageArtSubject(coverUrl: String?, style: PageFieldStyle = PageFieldStyle.PAGE) {
    val state = LocalPageArt.current ?: return
    val owner = remember { Any() }
    val subject = PageSubject(coverUrl, style)
    DisposableEffect(state, subject) {
        state.declare(owner, subject)
        onDispose { state.withdraw(owner) }
    }
}

/**
 * A page about [coverUrl]: declares it to the shell (which lights the field behind the page and
 * the bars) and lights the page's own content with the same [PageLight]. The light is known on the
 * first frame when the cover's palette is in memory (covers warm it as they are shown in Aurora),
 * so the page opens lit; otherwise it arrives once and only its readers update. Riso and pages
 * without a subject pass null: the theme's own light.
 */
@Composable
fun LitPage(coverUrl: String?, style: PageFieldStyle = PageFieldStyle.PAGE, content: @Composable () -> Unit) {
    PageArtSubject(coverUrl, style)
    ProvidePageLight(rememberPageLight(coverUrl), content)
}

/**
 * The light a cover casts on an Aurora page in the current scheme: the theme's own light in Riso,
 * without art, or for a neutral (greyscale) palette. When the cover changes, the light already
 * shown stays until the new one is ready ([rememberHeldLight]).
 */
@Composable
fun rememberPageLight(coverUrl: String?): PageLight = rememberHeldLight(coverUrl).light

/** A page's light and the cover it was made from (blurred into the field when it is lit). */
@Immutable
data class HeldLight(val light: PageLight, val artUrl: String?)

/**
 * The light for [coverUrl], ready on the first frame when its palette and blurred cover are in
 * memory. Otherwise both load, and meanwhile the light already shown (in this theme) stays, so the
 * page never drops to its unlit ground on the way from one game's light to the next (iOS
 * `LitByModifier`); a page with nothing shown yet is unlit until then.
 */
@Composable
fun rememberHeldLight(coverUrl: String?): HeldLight {
    val theme = RommioTheme.current
    val context = LocalContext.current
    val url = if (theme.isGlass) coverUrl else null
    val memory = remember { LightMemory() }
    val loaded by produceState<HeldLight?>(initialValue = null, theme, url) {
        if (url == null || readyLight(theme, url) != null) return@produceState
        val app = context.applicationContext
        val palette = CoverArt.palette(app, url)
        if (palette != null && !CoverPalette.isNeutral(palette)) CoverArt.backdrop(app, url, theme.isDark)
        value = heldLight(theme, palette, url)
    }
    val ready = when {
        url == null -> HeldLight(theme.unlit, null)
        else -> readyLight(theme, url) ?: loaded?.takeIf { it.artUrl == url && it.light.base == theme }
    }
    SideEffect { if (ready != null) memory.last = ready }
    // Still loading: the light already shown in this theme rather than the unlit page.
    return ready ?: memory.last?.takeIf { it.light.base == theme } ?: HeldLight(theme.unlit, null)
}

/** The last light a page showed (not state: it only answers while the next one loads). */
private class LightMemory {
    var last: HeldLight? = null
}

/** The light when the cover's palette (and, when lit, its blurred cover) are in memory. */
private fun readyLight(theme: RommioThemeValue, url: String): HeldLight? {
    val palette = CoverArt.cachedPalette(url) ?: return null
    val light = heldLight(theme, palette, url)
    if (light.light.isLit && CoverArt.cachedBackdrop(url, theme.isDark) == null) return null
    return light
}

private fun heldLight(theme: RommioThemeValue, palette: List<Int>?, url: String): HeldLight =
    HeldLight(PageLight.of(theme, palette?.takeUnless(CoverPalette::isNeutral)?.let { AuroraLight.lights(it, theme.isDark) }), url)

/** The cover the shell's field blurs into its light: the held light's ([rememberHeldLight]). */
val LocalShellArt = compositionLocalOf<String?> { null }

/** A cover's vivid mark (`k1`) in the current scheme, or the page's own when it has no usable colour. */
@Composable
fun rememberCoverMark(coverUrl: String?): Color {
    val theme = RommioTheme.current
    val palette = rememberCoverPalette(if (theme.isGlass) coverUrl else null)
    val mark = remember(palette, theme.isDark) {
        palette?.takeUnless(CoverPalette::isNeutral)?.let { Color(0xFF000000.toInt() or AuroraLight.lights(it, theme.isDark).k1) }
    }
    // The page's own mark only when the cover has none (reading the light only then).
    return mark ?: RommioTheme.light.mark1
}

private fun Int.opaque(): Color = Color(0xFF000000.toInt() or this)

/**
 * The page background (docs/theme-layouts.md §1.3–1.4). Riso: paper with its grain. Aurora:
 * the ground, the subject's cover blurred into light (lit pages only), three radial lights
 * (screen-blended in dark) and a veil back to the ground. An unlit page always uses the quiet
 * field (except welcome) with the theme's own lights.
 *
 * Only this backdrop animates when the light (or the field's style) changes: the new field
 * dissolves over the old one ([LayeredCrossfade], 450 ms), never through the ground. The fade is
 * read while drawing, so it redraws the field (and the glass blur of it) without recomposing
 * anything. It sits on its own layer, so nothing else redraws it.
 */
@Composable
fun PageField(
    modifier: Modifier = Modifier,
    style: PageFieldStyle = PageFieldStyle.QUIET,
    coverUrl: String? = null,
    /** The light to draw; the shell passes the declared subject's. */
    light: PageLight = RommioTheme.light,
    /** Records the field for the glass surfaces above it to blur (§1.6; API 31+). */
    backdrop: GlassBackdrop? = null,
) {
    val theme = RommioTheme.current
    if (!theme.isGlass) {
        PaperGrain(modifier)
        return
    }
    val lit = light.isLit
    val effective = if (!lit && style != PageFieldStyle.WELCOME) PageFieldStyle.QUIET else style
    Box(
        modifier
            // Its own layer: the field (and its recording for the glass) redraws only when it changes.
            .graphicsLayer()
            .recordGlassBackdrop(backdrop, theme.material.glassBlur.dp)
            .clipToBounds()
            .clearAndSetSemantics {},
    ) {
        LayeredCrossfade(FieldKey(light, effective, if (lit) coverUrl else null), Modifier.fillMaxSize(), durationMillis = 450) { field ->
            FieldLayer(field, theme.isDark)
        }
    }
}

/** One field: a light, where its lights sit, and the cover blurred into it (lit pages only). */
@Immutable
private data class FieldKey(val light: PageLight, val style: PageFieldStyle, val artUrl: String?)

@Composable
private fun FieldLayer(field: FieldKey, dark: Boolean) {
    val ground = field.light.ground
    val lights = field.light.lights.let { listOf(it.l1.opaque(), it.l2.opaque(), it.l3.opaque()) }
    val lightOpacity = if (dark) AuroraArtTokens.lightOpacityDark else AuroraArtTokens.lightOpacityLight
    Box(Modifier.fillMaxSize().drawBehind { drawRect(ground) }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = field.style.opacity }) {
            field.artUrl?.let { BlurredCover(it, dark) }
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    val paint = Paint().apply {
                        alpha = lightOpacity
                        blendMode = if (dark) BlendMode.Screen else BlendMode.SrcOver
                    }
                    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
                    field.style.spots.zip(lights).forEach { (spot, colour) -> fieldLight(colour, spot) }
                    canvas.restore()
                }
                drawRect(Brush.verticalGradient(*field.style.veil.map { (at, a) -> at to ground.copy(alpha = a) }.toTypedArray()))
            }
        }
    }
}

/** An elliptical radial light like CSS `radial-gradient(rx ry at x y, colour, transparent fade)`. */
private fun DrawScope.fieldLight(color: Color, spot: PageFieldStyle.Spot) {
    val cx = size.width * spot.x
    val cy = size.height * spot.y
    val radiusX = (size.width * spot.rx).coerceAtLeast(1f)
    val radiusY = (size.height * spot.ry).coerceAtLeast(1f)
    // Draw a circle of radius rx and squash it vertically into the ellipse.
    scale(scaleX = 1f, scaleY = radiusY / radiusX, pivot = Offset(cx, cy)) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to color,
                spot.fade to color.copy(alpha = 0f),
                center = Offset(cx, cy),
                radius = radiusX,
            ),
            radius = radiusX,
            center = Offset(cx, cy),
        )
    }
}

/**
 * `.a-field-art`: the cover baked into light (§1.3 — saturate, brighten, Gaussian blur at 96 px),
 * framed from x −25 %, y −12 % at 150 % × 78 % of the page, fading from full at 55 % of its height
 * to clear at its bottom, at the backdrop opacity. Works on every API level (a software bake).
 */
@Composable
private fun BlurredCover(url: String, dark: Boolean) {
    val context = LocalContext.current
    val art by produceState(initialValue = CoverArt.cachedBackdrop(url, dark), url, dark) {
        if (value == null) value = CoverArt.backdrop(context.applicationContext, url, dark)
    }
    val bitmap = art ?: return
    val opacity = if (dark) AuroraArtTokens.backdropOpacityDark else AuroraArtTokens.backdropOpacityLight
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.High,
        modifier = Modifier
            .layout { measurable, constraints ->
                val w = constraints.maxWidth
                val h = if (constraints.hasBoundedHeight) constraints.maxHeight else w * 2
                val placeable = measurable.measure(Constraints.fixed((w * 1.5f).toInt(), (h * 0.78f).toInt()))
                layout(w, h) { placeable.place((-w * 0.25f).toInt(), (-h * 0.12f).toInt()) }
            }
            .graphicsLayer {
                alpha = opacity
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

/**
 * Riso paper stock (§1.4): the ground and a 180 px seeded grain tile, multiplied at 35 % by day;
 * by night the tile is inverted (black with light specks) and screened at 50 %.
 */
@Composable
fun PaperGrain(modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    val density = LocalDensity.current
    val dark = theme.isDark
    val brush = remember(dark, density.density) {
        val shader = BitmapShader(CoverArt.grain.asAndroidBitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        // The tile is drawn at 2 px per point, like iOS (`scale: 2`): 90 dp per tile.
        val scale = density.density / 2f
        shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale) })
        ShaderBrush(shader)
    }
    val invert = remember {
        androidx.compose.ui.graphics.ColorFilter.colorMatrix(
            androidx.compose.ui.graphics.ColorMatrix(
                floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f),
            ),
        )
    }
    Canvas(modifier.background(theme.colors.ground).clearAndSetSemantics {}) {
        if (dark) {
            drawRect(brush, alpha = 0.5f, colorFilter = invert, blendMode = BlendMode.Screen)
        } else {
            drawRect(brush, alpha = 0.35f, blendMode = BlendMode.Multiply)
        }
    }
}
