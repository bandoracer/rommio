package io.github.bandoracer.rommio.ui.screen.player

import io.github.bandoracer.rommio.ui.component.OverArt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.min

/**
 * The player style renderer. Input views (`ExpansionControlsOverlay`, `TouchControlsOverlay`) own
 * touch handling, hit areas, geometry, test tags and semantics; they hand each control's visual
 * box to these functions, which only draw.
 *
 * - Aurora (frosted): translucent control fill from the player tokens, a bright top highlight, a
 *   0.5 dp rim and a soft drop shadow; legends in the player's ink; thin chevrons.
 * - Riso (ink): flat shapes on black — yellow face buttons and blue shoulder tabs (rounded top 18,
 *   bottom 6) each with the pink plate printed off register, a two-plate d-pad (pink over blue,
 *   yellow pivot, dark triangles), a pink Menu pill and outlined mono Select/Start.
 */
enum class PlayerDirection { UP, DOWN, LEFT, RIGHT }

/** The visual theme available to control views that don't receive it as a parameter. */
val LocalPlayerVisual = staticCompositionLocalOf<PlayerVisualTheme?> { null }

/** A glyph drawn instead of a text legend. */
enum class PlayerGlyph { UP, DOWN, LEFT, RIGHT, MENU, SQUARE, CROSS, CIRCLE, TRIANGLE }

/** PlayStation-style symbol labels are drawn as shapes; C-buttons and arrows get arrow glyphs. */
fun glyphForLabel(label: String): PlayerGlyph? = when (label) {
    "Square" -> PlayerGlyph.SQUARE
    "Cross" -> PlayerGlyph.CROSS
    "Circle" -> PlayerGlyph.CIRCLE
    "Triangle" -> PlayerGlyph.TRIANGLE
    "Up" -> PlayerGlyph.UP
    "Down" -> PlayerGlyph.DOWN
    "Left" -> PlayerGlyph.LEFT
    "Right" -> PlayerGlyph.RIGHT
    else -> null
}

/**
 * Fades a control without an offscreen layer, so its shadow and off-register plate (drawn
 * outside the control's box) are never clipped.
 */
private fun Modifier.controlAlpha(alpha: Float): Modifier = if (alpha >= 1f) {
    this
} else {
    graphicsLayer {
        this.alpha = alpha
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** Draws one control's face filling the modifier's box. */
@Composable
fun PlayerControlGraphic(
    kind: PlayerControlKind,
    label: String,
    pressed: Boolean,
    visual: PlayerVisualTheme,
    modifier: Modifier = Modifier,
    opacity: Float = PlayerStyle.DEFAULT_OPACITY,
    glyph: PlayerGlyph? = if (kind == PlayerControlKind.MENU) PlayerGlyph.MENU else glyphForLabel(label),
    stick: Offset = Offset.Zero,
    rightSide: Boolean = false,
) {
    val style = visual.style
    val colors = visual.styleFor(kind, label)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.controlAlpha(style.controlAlpha(opacity)), contentAlignment = Alignment.Center) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val pressShift = if (style.isInk && pressed) with(density) { style.plateOffset.toPx() } else 0f
        Canvas(Modifier.fillMaxSize()) {
            val shape = controlShape(kind, style, size)
            if (style.frosted) {
                drawFrosted(shape, colors, style, pressed)
            } else {
                drawInk(shape, colors, style, pressed)
            }
            if (kind == PlayerControlKind.STICK) {
                drawStickKnob(style, colors, stick, pressed)
            }
            glyph?.let {
                translate(pressShift, pressShift) {
                    drawGlyph(it, colors.contentColor, style, shape.boundsCenter(), min(shape.width, shape.height))
                }
            }
        }
        if (glyph == null && kind != PlayerControlKind.STICK) {
            val legend = legendFor(kind, label, style, with(density) { min(w, h).toDp() }, with(density) { w.toDp() })
            Text(
                text = legend.text,
                style = legend.style,
                color = colors.contentColor,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset(
                        x = with(density) { pressShift.toDp() },
                        y = with(density) { pressShift.toDp() },
                    )
                    // Capitals are presentation: the control keeps its own words.
                    .clearAndSetSemantics { text = AnnotatedString(label) },
            )
        }
    }
}

private data class Legend(val text: String, val style: androidx.compose.ui.text.TextStyle)

private fun legendFor(kind: PlayerControlKind, label: String, style: PlayerStyle, box: Dp, width: Dp): Legend {
    val shown = when (label) {
        "C-Up" -> "C↑"
        "C-Down" -> "C↓"
        "C-Left" -> "C←"
        "C-Right" -> "C→"
        else -> label
    }
    return when (kind) {
        PlayerControlKind.FACE -> {
            val text = if (style.legendUppercase) shown.uppercase(Locale.getDefault()) else shown
            // Token size scaled from a 56 dp reference face (docs/theme-layouts.md "Player"), at least 9;
            // longer legends ("Start" on a face) still fit the face.
            val scaled = maxOf(9f, style.legend.fontSize.value * box.value / 56f)
            val size = if (text.length <= 2) scaled else min(scaled, box.value * 0.32f)
            Legend(text, style.legend.copy(fontSize = size.sp, lineHeight = size.sp))
        }
        PlayerControlKind.SHOULDER -> {
            val text = if (style.legendUppercase) shown.uppercase(Locale.getDefault()) else shown
            val size = min(if (style.isInk) 18f else 15f, box.value * 0.62f)
            Legend(text, style.legend.copy(fontSize = size.sp, lineHeight = size.sp, letterSpacing = if (style.isInk) (0.06f * size).sp else style.legend.letterSpacing))
        }
        else -> {
            val text = if (style.labelUppercase) shown.uppercase(Locale.getDefault()) else shown
            // Fit utility labels ("Difficulty A") into the pill: ~0.66 em per character.
            val fit = (width.value * 0.84f) / (text.length.coerceAtLeast(1) * 0.66f)
            val size = min(style.label.fontSize.value, fit).coerceAtLeast(7f)
            Legend(text, style.label.copy(fontSize = size.sp, lineHeight = size.sp))
        }
    }
}

/** The control's outline inside its box. */
private fun DrawScope.controlShape(kind: PlayerControlKind, style: PlayerStyle, box: Size): RoundRect {
    val w = box.width
    val h = box.height
    return when (kind) {
        PlayerControlKind.FACE, PlayerControlKind.STICK -> {
            val d = min(w, h) - if (style.isInk) style.plateOffset.toPx() else 0f
            val r = d / 2
            RoundRect((w - d) / 2, (h - d) / 2, (w + d) / 2, (h + d) / 2, CornerRadius(r))
        }
        PlayerControlKind.SHOULDER -> {
            val inset = if (style.isInk) style.plateOffset.toPx() else 0f
            val right = w - inset
            val bottom = h - inset
            val height = bottom
            if (style.shoulderIsTab) {
                // `.pill.sh`: border-radius 18 18 6 6 on a 36-tall tab.
                val top = CornerRadius(min(18.dp.toPx(), height / 2))
                val low = CornerRadius(min(6.dp.toPx(), height / 3))
                RoundRect(0f, 0f, right, bottom, top, top, low, low)
            } else {
                // `.a-sh`: 14 14 20 20 on a 40-tall shoulder.
                val top = CornerRadius(height * 0.35f)
                val low = CornerRadius(height * 0.5f)
                RoundRect(0f, 0f, right, bottom, top, top, low, low)
            }
        }
        PlayerControlKind.TERTIARY -> RoundRect(0f, 0f, w, h, CornerRadius(h / 2))
        PlayerControlKind.MENU -> if (style.isInk) {
            // The menu: a 46 × 32 pink pill (docs/theme-layouts.md "Player").
            val pw = min(w, 46.dp.toPx())
            val ph = min(h, 32.dp.toPx())
            RoundRect((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2, CornerRadius(ph / 2))
        } else {
            val d = min(w, h)
            RoundRect((w - d) / 2, (h - d) / 2, (w + d) / 2, (h + d) / 2, CornerRadius(d / 2))
        }
        PlayerControlKind.DPAD_ARM -> {
            val r = if (style.isInk) style.shape.cornerRadius.dp.toPx() else min(w, h) * 0.3f
            val inset = if (style.isInk) style.plateOffset.toPx() else 0f
            RoundRect(0f, 0f, w - inset, h - inset, CornerRadius(r))
        }
    }
}

private fun RoundRect.boundsCenter(): Offset = Offset((left + right) / 2, (top + bottom) / 2)

private fun RoundRect.path(): Path = Path().apply { addRoundRect(this@path) }

private fun RoundRect.shift(dx: Float, dy: Float): RoundRect =
    RoundRect(left + dx, top + dy, right + dx, bottom + dy, topLeftCornerRadius, topRightCornerRadius, bottomRightCornerRadius, bottomLeftCornerRadius)

/** Frosted glass: soft shadow outside, fill, top highlight and rim inside. */
private fun DrawScope.drawFrosted(shape: RoundRect, colors: PlayerControlStyle, style: PlayerStyle, pressed: Boolean) {
    val path = shape.path()
    // `--ctl-shadow`: 0 10 26 -12 (light) / 0 12 28 -14 (dark), clipped out of the control.
    clipPath(path, ClipOp.Difference) {
        val layers = 8
        val blur = 26.dp.toPx()
        val spread = (-12).dp.toPx()
        val dy = 10.dp.toPx()
        val step = style.shadow.copy(alpha = style.shadow.alpha / layers)
        for (i in 0 until layers) {
            val grow = spread + blur * ((i + 0.5f) / layers - 0.5f)
            val r = RoundRect(
                shape.left - grow, shape.top - grow + dy, shape.right + grow, shape.bottom + grow + dy,
                CornerRadius((shape.topLeftCornerRadius.x + grow).coerceAtLeast(0f)),
            )
            if (r.width > 0 && r.height > 0) drawPath(r.path(), step)
        }
    }
    drawPath(path, if (pressed) colors.pressedFillColor else colors.fillColor)
    clipPath(path) {
        drawRect(style.highlight, topLeft = Offset(shape.left, shape.top), size = Size(shape.width, 1.dp.toPx()))
    }
    val rim = if (pressed) colors.pressedBorderColor else colors.borderColor
    drawPath(path, rim, style = Stroke(if (pressed) 1.dp.toPx() else 0.75.dp.toPx()))
}

/** Flat ink: the off-register plate, then the ink shape (pressed shifts onto the plate). */
private fun DrawScope.drawInk(shape: RoundRect, colors: PlayerControlStyle, style: PlayerStyle, pressed: Boolean) {
    val offset = style.plateOffset.toPx()
    if (colors.plateColor.alpha > 0f && !pressed) {
        drawPath(shape.shift(offset, offset).path(), colors.plateColor)
    }
    val face = if (pressed) shape.shift(offset, offset) else shape
    val path = face.path()
    val fill = if (pressed) colors.pressedFillColor else colors.fillColor
    if (fill.alpha > 0f) drawPath(path, fill)
    val edge = if (pressed) colors.pressedBorderColor else colors.borderColor
    if (edge.alpha > 0f) {
        val w = 1.5.dp.toPx()
        val inset = face.let { RoundRect(it.left + w / 2, it.top + w / 2, it.right - w / 2, it.bottom - w / 2, CornerRadius((it.topLeftCornerRadius.x - w / 2).coerceAtLeast(0f))) }
        drawPath(inset.path(), edge, style = Stroke(w))
    }
}

private fun DrawScope.drawStickKnob(style: PlayerStyle, colors: PlayerControlStyle, stick: Offset, pressed: Boolean) {
    val d = min(size.width, size.height)
    val knob = d * 0.21f
    val center = Offset(size.width / 2 + stick.x * d * 0.32f, size.height / 2 + stick.y * d * 0.32f)
    // Knob: `controlInk` 90 % (Riso, on an outlined circle) / 50 % (Aurora, on frosted glass).
    if (style.isInk) {
        drawCircle(style.colors.controlInk.copy(alpha = 0.9f), radius = knob, center = center)
    } else {
        drawCircle(style.colors.controlInk.copy(alpha = if (pressed) 0.65f else 0.5f), radius = knob, center = center)
        drawCircle(style.highlight, radius = knob, center = center, style = Stroke(0.75.dp.toPx()))
    }
}

/** Direction, menu and PlayStation glyphs in the style's glyph set. */
private fun DrawScope.drawGlyph(glyph: PlayerGlyph, color: Color, style: PlayerStyle, center: Offset, extent: Float) {
    val s = extent * 0.2f
    val stroke = Stroke(width = (extent * 0.07f).coerceIn(1.6.dp.toPx(), 2.6.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (glyph) {
        PlayerGlyph.UP, PlayerGlyph.DOWN, PlayerGlyph.LEFT, PlayerGlyph.RIGHT -> {
            val dir = when (glyph) {
                PlayerGlyph.UP -> PlayerDirection.UP
                PlayerGlyph.DOWN -> PlayerDirection.DOWN
                PlayerGlyph.LEFT -> PlayerDirection.LEFT
                else -> PlayerDirection.RIGHT
            }
            drawArrow(dir, center, s, color, style.glyphs, stroke)
        }
        PlayerGlyph.MENU -> {
            val half = extent * 0.2f
            for (i in -1..1) {
                val y = center.y + i * extent * 0.13f
                drawLine(color, Offset(center.x - half, y), Offset(center.x + half, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
        PlayerGlyph.SQUARE -> drawRect(color, topLeft = center - Offset(s, s), size = Size(s * 2, s * 2), style = stroke)
        PlayerGlyph.CIRCLE -> drawCircle(color, radius = s * 1.1f, center = center, style = stroke)
        PlayerGlyph.CROSS -> {
            drawLine(color, center - Offset(s, s), center + Offset(s, s), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, center + Offset(-s, s), center + Offset(s, -s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        PlayerGlyph.TRIANGLE -> {
            val path = Path().apply {
                moveTo(center.x, center.y - s * 1.1f)
                lineTo(center.x + s * 1.15f, center.y + s * 0.85f)
                lineTo(center.x - s * 1.15f, center.y + s * 0.85f)
                close()
            }
            drawPath(path, color, style = stroke)
        }
    }
}

/** A chevron (Aurora) or solid triangle (Riso) pointing [dir], centred on [center]. */
private fun DrawScope.drawArrow(dir: PlayerDirection, center: Offset, s: Float, color: Color, glyphs: PlayerGlyphSet, stroke: Stroke) {
    fun rotate(x: Float, y: Float): Offset = when (dir) {
        PlayerDirection.UP -> Offset(x, y)
        PlayerDirection.DOWN -> Offset(-x, -y)
        PlayerDirection.LEFT -> Offset(y, -x)
        PlayerDirection.RIGHT -> Offset(-y, x)
    }
    if (glyphs == PlayerGlyphSet.TRIANGLES) {
        // `.dp-ar`: M50 12 l-7 10 h14 z — a 14 × 10 triangle.
        val path = Path().apply {
            val a = center + rotate(0f, -s * 0.72f)
            val b = center + rotate(-s * 0.7f, s * 0.48f)
            val c = center + rotate(s * 0.7f, s * 0.48f)
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(c.x, c.y)
            close()
        }
        drawPath(path, color)
    } else {
        val path = Path().apply {
            val a = center + rotate(-s * 0.62f, s * 0.3f)
            val b = center + rotate(0f, -s * 0.32f)
            val c = center + rotate(s * 0.62f, s * 0.3f)
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(c.x, c.y)
        }
        drawPath(path, color, style = stroke)
    }
}

/**
 * The whole d-pad filling its box, for views with one d-pad hit area (`ConsoleDPad`).
 * Aurora (`.a-dpad`): a frosted disc with a recessed cross and chevrons. Riso (`.dp`): pink cross
 * over the blue plate, a yellow pivot multiplied onto the pink, dark triangles. Held directions
 * light (Aurora) or press into the plate (Riso).
 */
@Composable
fun PlayerDpadGraphic(
    held: Set<PlayerDirection>,
    visual: PlayerVisualTheme,
    modifier: Modifier = Modifier,
    opacity: Float = PlayerStyle.DEFAULT_OPACITY,
) {
    val style = visual.style
    val colors = style.dpad
    Canvas(modifier.controlAlpha(style.controlAlpha(opacity))) {
        val s = min(size.width, size.height)
        val origin = Offset((size.width - s) / 2, (size.height - s) / 2)
        if (style.dpadIsTwoPlate) drawTwoPlateDpad(origin, s, held, style, colors) else drawDiscDpad(origin, s, held, style, colors, visual.accentColor)
    }
}

/**
 * A pressed arm (docs/theme-layouts.md "Player"): from the cross's edge to the centre square's
 * edge, the arm's width, radius min(8, arm × 0.2).
 */
private fun pressedArm(dir: PlayerDirection, cross: RoundRect, arm: Float, density: Float): RoundRect {
    val centre = Offset((cross.left + cross.right) / 2, (cross.top + cross.bottom) / 2)
    val reach = cross.width / 2
    val r = CornerRadius(min(8f * density, arm * 0.2f))
    return when (dir) {
        PlayerDirection.UP -> RoundRect(centre.x - arm / 2, cross.top, centre.x + arm / 2, cross.top + reach - arm / 2, r)
        PlayerDirection.DOWN -> RoundRect(centre.x - arm / 2, centre.y + arm / 2, centre.x + arm / 2, cross.bottom, r)
        PlayerDirection.LEFT -> RoundRect(cross.left, centre.y - arm / 2, cross.left + reach - arm / 2, centre.y + arm / 2, r)
        PlayerDirection.RIGHT -> RoundRect(centre.x + arm / 2, centre.y - arm / 2, cross.right, centre.y + arm / 2, r)
    }
}

/** A colour mixed with white by [amount] (the spec's "lightened 35 %"). */
private fun Color.lightened(amount: Float): Color = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha,
)

/** A plus shape: arms [thickness] wide spanning [bounds], corner radius [r]. */
private fun crossPath(left: Float, top: Float, span: Float, thickness: Float, r: Float): Path {
    val a = left + (span - thickness) / 2
    val b = a + thickness
    val c = top + (span - thickness) / 2
    val d = c + thickness
    val right = left + span
    val bottom = top + span
    return Path().apply {
        // Two overlapping rounded bars make the plus; filled with non-zero winding.
        addRoundRect(RoundRect(a, top, b, bottom, CornerRadius(r)))
        addRoundRect(RoundRect(left, c, right, d, CornerRadius(r)))
    }
}

/**
 * Riso d-pad (docs/theme-layouts.md "Player"): a cross (arm 34 % of the side, inset 2 %, radius
 * 5 %) in `dpadFill` over a `dpadAltFill` copy offset by the misregistration; a `pivot` circle
 * r = 0.32 × arm, multiplied; solid triangles in `faceInk` 80 %; pressed arms lightened 35 %.
 */
private fun DrawScope.drawTwoPlateDpad(origin: Offset, s: Float, held: Set<PlayerDirection>, style: PlayerStyle, colors: PlayerControlStyle) {
    val arm = s * 0.34f
    val inset = s * 0.02f
    val span = s - inset * 2
    val left = origin.x + inset
    val top = origin.y + inset
    val r = s * 0.05f
    val plate = style.plateOffset.toPx()
    drawPath(crossPath(left + plate, top + plate, span, arm, r), style.dpadAltFill)
    drawPath(crossPath(left, top, span, arm, r), colors.fillColor)
    val cross = RoundRect(left, top, left + span, top + span, CornerRadius.Zero)
    held.forEach { dir -> drawPath(pressedArm(dir, cross, arm, density).path(), colors.fillColor.lightened(0.35f)) }
    val center = Offset(left + span / 2, top + span / 2)
    drawCircle(style.pivot, radius = arm * 0.32f, center = center, blendMode = BlendMode.Multiply)
    val ink = style.colors.faceInk.copy(alpha = 0.8f)
    val arrow = arm * 0.42f
    val edge = arm * 0.55f
    listOf(
        PlayerDirection.UP to Offset(center.x, top + edge),
        PlayerDirection.DOWN to Offset(center.x, top + span - edge),
        PlayerDirection.LEFT to Offset(left + edge, center.y),
        PlayerDirection.RIGHT to Offset(left + span - edge, center.y),
    ).forEach { (dir, at) -> drawArrow(dir, at, arrow / 2, ink, PlayerGlyphSet.TRIANGLES, Stroke(1f)) }
}

/**
 * Aurora d-pad: a frosted disc; cross grooves (arm 32 % of the side, inset 13 %, radius 8) in
 * `controlInk` 16 %; chevrons; pressed arms in the accent at 45 %.
 */
private fun DrawScope.drawDiscDpad(origin: Offset, s: Float, held: Set<PlayerDirection>, style: PlayerStyle, colors: PlayerControlStyle, accent: Color) {
    val disc = RoundRect(origin.x, origin.y, origin.x + s, origin.y + s, CornerRadius(s / 2))
    drawFrosted(disc, colors, style, pressed = false)
    val inset = s * 0.13f
    val span = s - inset * 2
    val arm = s * 0.32f
    val r = 8.dp.toPx().coerceAtMost(arm / 2)
    val left = origin.x + inset
    val top = origin.y + inset
    drawPath(crossPath(left, top, span, arm, r), colors.contentColor.copy(alpha = 0.16f))
    val cross = RoundRect(left, top, left + span, top + span, CornerRadius.Zero)
    held.forEach { dir -> drawPath(pressedArm(dir, cross, arm, density).path(), accent.copy(alpha = 0.45f)) }
    val center = Offset(origin.x + s / 2, origin.y + s / 2)
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val glyph = arm * 0.5f / 2
    val edge = arm * 0.55f
    listOf(
        PlayerDirection.UP to Offset(center.x, top + edge),
        PlayerDirection.DOWN to Offset(center.x, top + span - edge),
        PlayerDirection.LEFT to Offset(left + edge, center.y),
        PlayerDirection.RIGHT to Offset(left + span - edge, center.y),
    ).forEach { (dir, at) -> drawArrow(dir, at, glyph, colors.contentColor, PlayerGlyphSet.CHEVRONS, stroke) }
}

/**
 * The stage behind the game. Riso and OLED black: flat black. Aurora: the player ground lit by
 * the game's light (its cover palette), pooled in the rails around the viewport, never over it.
 */
@Composable
fun PlayerStageBackdrop(
    visual: PlayerVisualTheme,
    coverUrl: String?,
    modifier: Modifier = Modifier,
) {
    val ground = visual.canvasColor
    val lit = coverUrl != null && visual.style.frosted && !visual.oledBlack
    val dark = visual.appTheme.isDark
    val context = androidx.compose.ui.platform.LocalContext.current
    // Bias light (docs/theme-layouts.md "Player"): the cover's §1.3 bake filling the canvas at
    // 85 %, under a radial vignette of the player ground at 55 % (40 → 520 dp).
    val bake by androidx.compose.runtime.produceState(
        initialValue = coverUrl?.takeIf { lit }?.let { io.github.bandoracer.rommio.ui.art.CoverArt.cachedBackdrop(it, dark) },
        coverUrl, lit, dark,
    ) {
        if (lit && value == null && coverUrl != null) value = io.github.bandoracer.rommio.ui.art.CoverArt.backdrop(context.applicationContext, coverUrl, dark)
    }
    Box(modifier.drawBehind { drawRect(ground) }) {
        val image = bake
        if (lit && image != null) {
            androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                alpha = 0.85f,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(Modifier.fillMaxSize()) {
                val start = 40.dp.toPx()
                val end = 520.dp.toPx()
                drawRect(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        (start / end) to Color.Transparent,
                        1f to ground.copy(alpha = 0.55f),
                        center = center,
                        radius = end,
                    ),
                )
            }
        }
    }
}

/**
 * A notice over the game (docs/theme-layouts.md "Player", Toast): a dark glass pill (`#0c0a14`
 * 62 %) with a white check and `playerLabel` 13 (Aurora), or a `toastFill` paper ticket with
 * `toastInk` caps, a green check chip and the yellow plate offset 3 (Riso).
 */
@Composable
fun PlayerToast(message: String, visual: PlayerVisualTheme, modifier: Modifier = Modifier, isError: Boolean = false) {
    val theme = visual.appTheme
    val style = visual.style
    val riso = style.isInk
    val ink = if (riso) style.colors.toastInk else OverArt.ink
    val shape = if (riso) androidx.compose.ui.graphics.RectangleShape else androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)
    androidx.compose.foundation.layout.Row(
        modifier
            .then(
                if (riso) {
                    Modifier.drawBehind {
                        val o = 3.dp.toPx()
                        drawRect(if (isError) theme.colors.accent1 else theme.colors.accent3, topLeft = Offset(o, o), size = size)
                        drawRect(style.colors.toastFill)
                    }
                } else {
                    Modifier
                        .clip(shape)
                        .drawBehind { drawRect(OverArt.toast) }
                },
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .then(if (riso) Modifier.drawBehind { drawRect(if (isError) theme.colors.accent1 else theme.colors.accent4) } else Modifier)
                .padding(if (riso) 3.dp else 0.dp),
        ) {
            androidx.compose.material3.Icon(
                if (isError) androidx.compose.material.icons.Icons.Outlined.WarningAmber else androidx.compose.material.icons.Icons.Outlined.Done,
                contentDescription = null,
                tint = if (riso) ink else if (isError) OverArt.bad else OverArt.ink,
                modifier = Modifier.size(12.dp),
            )
        }
        val label = style.label
        Text(
            text = if (riso && style.labelUppercase) message.uppercase(Locale.getDefault()) else message,
            style = if (riso) label else label.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = ink,
            modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(message) },
        )
    }
}
