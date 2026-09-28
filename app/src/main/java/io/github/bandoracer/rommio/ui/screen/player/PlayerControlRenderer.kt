package io.github.bandoracer.rommio.ui.screen.player

import io.github.bandoracer.rommio.ui.component.OverArt
import io.github.bandoracer.rommio.ui.component.drawSoftShadow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.sqrt

/**
 * The player style renderer. The touch surface (`PlayerControlsOverlay`) owns touch handling, hit
 * regions, geometry, test tags and semantics; it hands each control's drawn frame to these
 * functions, which only draw (docs/player-controls.md, Look).
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
    rightSide: Boolean = false,
    /** A shoulder at a portrait corner: flat against this screen edge ([PlayerTouchShape.BUMPER_LEADING] or `_TRAILING`). */
    bumper: PlayerTouchShape? = null,
    /** A stick's gate ([PlayerStickProfile.diagonalReach]): the ring draws as its octagon or near-square. */
    gateReach: Double? = null,
) {
    val style = visual.style
    val colors = visual.styleFor(kind, label)
    val density = LocalDensity.current
    // A face fades only its body with the opacity setting: its legend and console ring stay at
    // full ink, so the legend keeps its contrast even at the lowest face opacity (0.5).
    val face = kind == PlayerControlKind.FACE
    val alpha = style.controlAlpha(opacity, face = face)
    BoxWithConstraints(modifier.controlAlpha(if (face) 1f else alpha), contentAlignment = Alignment.Center) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val pressShift = if (style.isInk && pressed) with(density) { style.plateOffset.toPx() } else 0f
        Canvas(Modifier.fillMaxSize()) {
            val shape = controlShape(kind, style, size, bumper)
            // A stick's ring takes its gate's shape (the N64's octagon, the DualShock's near-square).
            val gate = gateReach?.takeIf { kind == PlayerControlKind.STICK && PlayerStickModel.gateOutline(it) != null }
            if (style.frosted) {
                drawFrosted(
                    shape, colors, style, pressed,
                    round = kind == PlayerControlKind.FACE || kind == PlayerControlKind.STICK || kind == PlayerControlKind.MENU,
                    bodyAlpha = if (face) alpha else 1f,
                    gate = gate,
                )
            } else {
                drawInk(shape, colors, style, pressed, gate)
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
            // The token size is for a 64 dp two-button face (22 in Aurora; about 20 on a 56 dp
            // diamond face), at least 9 (docs/player-controls.md, Look; iOS `legendSize`); longer
            // legends ("Fire" on a face) still fit the face.
            val scaled = maxOf(9f, style.legend.fontSize.value * (box.value + 24f) / (REFERENCE_FACE + 24f))
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

/** Legend sizes in the tokens are for a face this wide (a two-button face). */
private const val REFERENCE_FACE = 64f

/**
 * The control's outline: its whole box (the box is the drawn frame the touch model hit-tests;
 * Riso's plate prints outside it).
 */
private fun DrawScope.controlShape(kind: PlayerControlKind, style: PlayerStyle, box: Size, bumper: PlayerTouchShape? = null): RoundRect {
    val w = box.width
    val h = box.height
    return when (kind) {
        PlayerControlKind.FACE, PlayerControlKind.STICK -> {
            val d = min(w, h)
            val r = d / 2
            RoundRect((w - d) / 2, (h - d) / 2, (w + d) / 2, (h + d) / 2, CornerRadius(r))
        }
        PlayerControlKind.SHOULDER -> {
            val right = w
            val bottom = h
            val height = bottom
            val (top, low) = if (style.shoulderIsTab) {
                // `.pill.sh`: border-radius 18 18 6 6 on a 36-tall tab.
                CornerRadius(min(18.dp.toPx(), height / 2)) to CornerRadius(min(6.dp.toPx(), height / 3))
            } else {
                // `.a-sh`: 14 14 20 20 on a 40-tall shoulder.
                CornerRadius(height * 0.35f) to CornerRadius(height * 0.5f)
            }
            // A bumper is square against its screen edge.
            when (bumper) {
                PlayerTouchShape.BUMPER_LEADING -> RoundRect(0f, 0f, right, bottom, CornerRadius.Zero, top, low, CornerRadius.Zero)
                PlayerTouchShape.BUMPER_TRAILING -> RoundRect(0f, 0f, right, bottom, top, CornerRadius.Zero, CornerRadius.Zero, low)
                else -> RoundRect(0f, 0f, right, bottom, top, top, low, low)
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

/** The outline in this box: the rounded rect, or a stick's gate ([gatePath]) when [gate] is set. */
private fun RoundRect.outline(gate: Double?): Path = if (gate == null) path() else gatePath(left, top, right, bottom, gate)

/**
 * A stick gate's outline in a square ([PlayerStickModel.gateOutline]), each corner rounded by a
 * tenth of the radius (as iOS `PlayerGatePath`); a circle when the gate is round.
 */
internal fun gatePath(left: Float, top: Float, right: Float, bottom: Float, reach: Double): Path {
    val radius = min(right - left, bottom - top) / 2
    val centre = Offset((left + right) / 2, (top + bottom) / 2)
    val unit = PlayerStickModel.gateOutline(reach)
        ?: return Path().apply { addOval(androidx.compose.ui.geometry.Rect(centre, radius)) }
    val points = unit.map { centre + it * radius }
    val round = radius * 0.1f
    return Path().apply {
        points.forEachIndexed { i, corner ->
            fun toward(other: Offset): Offset {
                val d = other - corner
                val length = d.getDistance().coerceAtLeast(0.001f)
                return corner + d * (min(round, length / 2) / length)
            }
            val entry = toward(points[(i + points.size - 1) % points.size])
            val exit = toward(points[(i + 1) % points.size])
            if (i == 0) moveTo(entry.x, entry.y) else lineTo(entry.x, entry.y)
            quadraticTo(corner.x, corner.y, exit.x, exit.y)
        }
        close()
    }
}

private fun RoundRect.shift(dx: Float, dy: Float): RoundRect =
    RoundRect(left + dx, top + dy, right + dx, bottom + dy, topLeftCornerRadius, topRightCornerRadius, bottomRightCornerRadius, bottomLeftCornerRadius)

/**
 * Frosted glass (docs/player-controls.md, Look; §1.6 glass edge): a soft shadow outside, the
 * `controlFill` (`controlPressed` when held), a console colour's 2 dp ring, a 0.5 dp keyline and a
 * 1 dp top rim fading by 12 % of the height.
 */
private fun DrawScope.drawFrosted(
    shape: RoundRect,
    colors: PlayerControlStyle,
    style: PlayerStyle,
    pressed: Boolean,
    round: Boolean = true,
    /** Fades the body (shadow, fill, keyline, rim) but not the console ring. */
    bodyAlpha: Float = 1f,
    /** A stick's gate: the outline is its octagon or near-square ([gatePath]). */
    gate: Double? = null,
) {
    fun Color.body() = if (bodyAlpha >= 1f) this else copy(alpha = alpha * bodyAlpha)
    val path = shape.outline(gate)
    // Glass shadow: black 55 % r14 y10 by night, #28143c 28 % r13 y9 by day (iOS `glassShadow`).
    val dark = style.dark
    translate(shape.left, shape.top) {
        drawSoftShadow(
            color = (if (dark) Color.Black.copy(alpha = 0.55f) else Color(0xFF28143C).copy(alpha = 0.28f)).body(),
            cornerRadius = (if (round) min(shape.width, shape.height) / 2 else shape.bottomLeftCornerRadius.x).toDp(),
            offsetY = if (dark) 10.dp else 9.dp,
            blur = if (dark) 28.dp else 26.dp,
            size = Size(shape.width, shape.height),
        )
    }
    drawPath(path, (if (pressed) colors.pressedFillColor else colors.fillColor).body())
    if (colors.ring.alpha > 0f) {
        val w = 2.dp.toPx()
        val inner = RoundRect(shape.left + w / 2, shape.top + w / 2, shape.right - w / 2, shape.bottom - w / 2,
            CornerRadius((shape.topLeftCornerRadius.x - w / 2).coerceAtLeast(0f)))
        drawPath(inner.outline(gate), colors.ring, style = Stroke(w))
    }
    val edge = (if (pressed) colors.pressedBorderColor else colors.borderColor).body()
    drawPath(path, edge, style = Stroke(if (pressed) 1.dp.toPx() else 0.5.dp.toPx()))
    val rim = 1.dp.toPx()
    val rimShape = RoundRect(shape.left + rim / 2, shape.top + rim / 2, shape.right - rim / 2, shape.bottom - rim / 2,
        shape.topLeftCornerRadius, shape.topRightCornerRadius, shape.bottomRightCornerRadius, shape.bottomLeftCornerRadius)
    drawPath(
        rimShape.outline(gate),
        androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to style.highlight.body(),
            0.12f to style.highlight.copy(alpha = 0f),
            startY = shape.top,
            endY = shape.bottom,
        ),
        style = Stroke(rim),
    )
}

/** Flat ink: the off-register plate, then the ink shape (pressed shifts onto the plate). */
private fun DrawScope.drawInk(shape: RoundRect, colors: PlayerControlStyle, style: PlayerStyle, pressed: Boolean, gate: Double? = null) {
    val offset = style.plateOffset.toPx()
    if (colors.plateColor.alpha > 0f && !pressed) {
        drawPath(shape.shift(offset, offset).outline(gate), colors.plateColor)
    }
    val face = if (pressed) shape.shift(offset, offset) else shape
    val path = face.outline(gate)
    val fill = if (pressed) colors.pressedFillColor else colors.fillColor
    if (fill.alpha > 0f) drawPath(path, fill)
    val edge = if (pressed) colors.pressedBorderColor else colors.borderColor
    if (edge.alpha > 0f) {
        val w = 1.5.dp.toPx()
        val inset = face.let { RoundRect(it.left + w / 2, it.top + w / 2, it.right - w / 2, it.bottom - w / 2, CornerRadius((it.topLeftCornerRadius.x - w / 2).coerceAtLeast(0f))) }
        drawPath(inset.outline(gate), edge, style = Stroke(w))
    }
}

/**
 * A stick's ring at full tilt, brightened: a 2.5 dp `controlInk` rim over the travel ring (as iOS),
 * in the gate's shape ([gateReach]; round when null or a circle).
 */
@Composable
fun PlayerStickFullTiltRing(visual: PlayerVisualTheme, modifier: Modifier = Modifier, gateReach: Double? = null) {
    val style = visual.style
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val ink = style.colors.controlInk.copy(alpha = if (style.isInk) 1f else 0.85f)
        val d = min(size.width, size.height)
        val left = (size.width - d) / 2 + stroke / 2
        val top = (size.height - d) / 2 + stroke / 2
        drawPath(gatePath(left, top, left + d - stroke, top + d - stroke, gateReach ?: sqrt(0.5)), ink, style = Stroke(stroke, join = StrokeJoin.Round))
    }
}

/**
 * A stick's knob, filling the modifier's box (travel × 0.95 across; the travel ring is drawn by
 * [PlayerControlGraphic] with [PlayerControlKind.STICK]): `controlInk` at 90 % (Riso) or 55 % with
 * a highlight edge (Aurora, on frosted glass).
 */
@Composable
fun PlayerStickKnob(visual: PlayerVisualTheme, modifier: Modifier = Modifier, opacity: Float = PlayerStyle.DEFAULT_OPACITY) {
    val style = visual.style
    Canvas(modifier.controlAlpha(style.controlAlpha(opacity, face = false))) {
        val radius = min(size.width, size.height) / 2
        if (style.isInk) {
            drawCircle(style.colors.controlInk.copy(alpha = 0.9f), radius = radius)
        } else {
            drawCircle(style.colors.controlInk.copy(alpha = 0.55f), radius = radius)
            drawCircle(style.highlight, radius = radius, style = Stroke(0.75.dp.toPx()))
        }
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
        if (style.dpadIsTwoPlate) drawTwoPlateDpad(origin, s, held, style, colors) else drawGlassCrossDpad(origin, s, held, style, colors, visual.accentColor)
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
 * Aurora d-pad (revised 2026-09-26: the arms sat small inside a frosted disc): a frosted-glass cross
 * filling the square like Riso's (arm 34 % of the side, inset 2 %, radius 8 %) — its soft shadow,
 * `controlFill`, the 0.5 dp keyline and top rim on the cross's one outline; chevrons in `controlInk`;
 * pressed arms in the accent at 45 %.
 */
private fun DrawScope.drawGlassCrossDpad(origin: Offset, s: Float, held: Set<PlayerDirection>, style: PlayerStyle, colors: PlayerControlStyle, accent: Color) {
    val arm = s * 0.34f
    val inset = s * 0.02f
    val span = s - inset * 2
    val left = origin.x + inset
    val top = origin.y + inset
    val r = s * 0.08f
    val a = left + (span - arm) / 2
    val c = top + (span - arm) / 2
    // The two bars' union: one outline, so the keyline never crosses the middle.
    val outline = Path().apply {
        op(
            Path().apply { addRoundRect(RoundRect(a, top, a + arm, top + span, CornerRadius(r))) },
            Path().apply { addRoundRect(RoundRect(left, c, left + span, c + arm, CornerRadius(r))) },
            PathOperation.Union,
        )
    }
    val dark = style.dark
    listOf(RoundRect(a, top, a + arm, top + span, CornerRadius(r)), RoundRect(left, c, left + span, c + arm, CornerRadius(r))).forEach { bar ->
        translate(bar.left, bar.top) {
            drawSoftShadow(
                color = if (dark) Color.Black.copy(alpha = 0.55f) else Color(0xFF28143C).copy(alpha = 0.28f),
                cornerRadius = r.toDp(),
                offsetY = if (dark) 10.dp else 9.dp,
                blur = if (dark) 28.dp else 26.dp,
                size = Size(bar.width, bar.height),
            )
        }
    }
    drawPath(outline, colors.fillColor)
    drawPath(outline, colors.borderColor, style = Stroke(0.5.dp.toPx()))
    drawPath(
        outline,
        androidx.compose.ui.graphics.Brush.verticalGradient(0f to style.highlight, 0.12f to style.highlight.copy(alpha = 0f), startY = top, endY = top + span),
        style = Stroke(1.dp.toPx()),
    )
    val cross = RoundRect(left, top, left + span, top + span, CornerRadius.Zero)
    held.forEach { dir -> drawPath(pressedArm(dir, cross, arm, density).path(), accent.copy(alpha = 0.45f)) }
    val center = Offset(left + span / 2, top + span / 2)
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
