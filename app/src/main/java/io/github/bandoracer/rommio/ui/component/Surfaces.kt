package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bandoracer.rommio.ui.theme.RoleStyle
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.ui.theme.RommioThemeValue

/** A text style at another point size, keeping its line-height ratio and tracking. */
fun TextStyle.sized(size: Float): TextStyle {
    val ratio = if (fontSize.isSp && lineHeight.isSp && fontSize.value > 0f) lineHeight.value / fontSize.value else 1.2f
    return copy(fontSize = size.sp, lineHeight = (size * ratio).sp)
}

/** A role at another point size (the spec's "`role` 12.5"). */
fun RoleStyle.sized(size: Float): RoleStyle = copy(style = style.sized(size))

/**
 * Text in one of the theme's type roles; applies the role's uppercase rule. [size] overrides the
 * role's size (docs "`label` 10"); [italic] sets Riso's italic captions and deks.
 */
@Composable
fun RoleText(
    text: String,
    role: RoleStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
    size: Float? = null,
    italic: Boolean = false,
) {
    var style = if (size != null) role.style.sized(size) else role.style
    if (italic) style = style.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
    CasedText(
        text = text,
        uppercase = role.uppercase,
        style = style,
        modifier = modifier,
        color = color,
        maxLines = maxLines,
        minLines = minLines,
        overflow = overflow,
        textAlign = textAlign,
    )
}

/**
 * Text that may be set in capitals (Riso's display and mono roles). Capitals are presentation
 * only: accessibility and test selectors keep the original words ("Save snapshot", not
 * "SAVE SNAPSHOT"), so changing theme never changes what a screen reader or a test sees.
 */
@Composable
fun CasedText(
    text: String,
    uppercase: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
) {
    if (!uppercase) {
        Text(
            text = text,
            modifier = modifier,
            style = style,
            color = color,
            maxLines = maxLines,
            minLines = minLines,
            overflow = overflow,
            textAlign = textAlign,
            textDecoration = textDecoration,
        )
        return
    }
    Box(
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
        propagateMinConstraints = true,
    ) {
        Text(
            text = text.uppercase(Locale.getDefault()),
            style = style,
            color = color,
            maxLines = maxLines,
            minLines = minLines,
            overflow = overflow,
            textAlign = textAlign,
            textDecoration = textDecoration,
        )
    }
}

/**
 * True inside a card. Nested surfaces drop their own edge and shadow so cards never stack
 * borders (Riso) or glass on glass (Aurora); Riso lists inside a card keep the card's padding.
 */
val LocalInCard = staticCompositionLocalOf { false }

// ---- Materials ---------------------------------------------------------------------------------

/**
 * A soft drop shadow like CSS `box-shadow: 0 [offsetY] [blur] [spread] color`, drawn only outside
 * the shape (so translucent glass is never darkened from below). Works on every API level: the
 * blur is layered rounded rectangles, whose summed alpha falls off linearly across [blur].
 */
fun Modifier.softShadow(
    color: Color,
    cornerRadius: Dp,
    offsetY: Dp,
    blur: Dp,
    spread: Dp = 0.dp,
    layers: Int = 10,
): Modifier = drawBehind { drawSoftShadow(color, cornerRadius, offsetY, blur, spread, layers) }

/** [softShadow] for a shape of [size] at the origin of this scope. */
internal fun DrawScope.drawSoftShadow(
    color: Color,
    cornerRadius: Dp,
    offsetY: Dp,
    blur: Dp,
    spread: Dp = 0.dp,
    layers: Int = 10,
    size: Size = this.size,
) {
    if (color.alpha <= 0f) return
    val radius = minOf(cornerRadius.toPx(), size.minDimension / 2f)
    val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))) }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && spread.value == 0f) {
        // A true Gaussian (hardware shadow layers, API 28+): CSS blur B is sigma B/2; Skia turns a
        // shadow radius r into sigma 0.57735 r + 0.5.
        val sigma = blur.toPx() / 2f
        val shadowRadius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f)
        val argb = color.toArgb()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = argb
            setShadowLayer(shadowRadius, 0f, offsetY.toPx(), argb)
        }
        clipPath(clip, ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
            }
        }
        return
    }
    clipPath(clip, ClipOp.Difference) {
        val step = color.copy(alpha = color.alpha / layers)
        val blurPx = blur.toPx()
        for (i in 0 until layers) {
            val grow = spread.toPx() + blurPx * ((i + 0.5f) / layers - 0.5f)
            val w = size.width + grow * 2
            val h = size.height + grow * 2
            if (w <= 0f || h <= 0f) continue
            drawRoundRect(
                color = step,
                topLeft = Offset(-grow, -grow + offsetY.toPx()),
                size = Size(w, h),
                cornerRadius = CornerRadius((radius + grow).coerceAtLeast(0f)),
            )
        }
    }
}

/**
 * Aurora glass edge (docs/theme-layouts.md §1.6): a 0.5 dp white keyline (70 % by day, 8 % by
 * night) and a 1 dp white top rim (95 % / 16 %) fading to clear by 12 % of the height.
 */
internal fun DrawScope.glassEdge(radiusPx: Float, dark: Boolean, strength: Float = 1f, size: Size = this.size) {
    val r = minOf(radiusPx, size.minDimension / 2f)
    val keyline = 0.5.dp.toPx()
    drawRoundRect(
        color = Color.White.copy(alpha = (if (dark) 0.08f else 0.7f) * strength),
        topLeft = Offset(keyline / 2, keyline / 2),
        size = Size(size.width - keyline, size.height - keyline),
        cornerRadius = CornerRadius((r - keyline / 2).coerceAtLeast(0f)),
        style = Stroke(keyline),
    )
    val rim = 1.dp.toPx()
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = (if (dark) 0.16f else 0.95f) * strength),
            0.12f to Color.White.copy(alpha = 0f),
            startY = 0f,
            endY = size.height,
        ),
        topLeft = Offset(rim / 2, rim / 2),
        size = Size(size.width - rim, size.height - rim),
        cornerRadius = CornerRadius((r - rim / 2).coerceAtLeast(0f)),
        style = Stroke(rim),
    )
}

/** Aurora glass shadow (§1.6): black 7 % r18 y9 by day, 34 % r20 y12 by night. */
internal data class GlassShadow(val color: Color, val radius: Dp, val y: Dp)

internal fun RommioThemeValue.glassShadow(): GlassShadow =
    if (isDark) GlassShadow(Color.Black.copy(alpha = 0.34f), 20.dp, 12.dp) else GlassShadow(Color.Black.copy(alpha = 0.07f), 18.dp, 9.dp)

/**
 * A SwiftUI-style `shadow(color, radius, y)`: a Gaussian-looking drop shadow drawn outside the
 * shape. [radius] is the blur radius (the shadow spreads about twice as far as it).
 */
fun Modifier.dropShadow(color: Color, cornerRadius: Dp, radius: Dp, y: Dp): Modifier =
    softShadow(color, cornerRadius, offsetY = y, blur = radius * 2, spread = 0.dp)

/**
 * The theme's card material as a modifier (clips to [cornerRadius]):
 * - Aurora glass (§1.6): on API 31+ the page field redrawn under the surface and blurred by
 *   `material.glassBlur` ([glassBackdropBlur]; below 31 the field shows through), then translucent
 *   `surface`, the glass edge, and the soft glass shadow.
 * - Riso: a paper block with the 1.5 dp ink keyline ([keyline]) or a 1 dp hairline.
 * [nested] drops the shadow and edge (a surface inside a card).
 */
fun Modifier.themedSurface(
    theme: RommioThemeValue,
    cornerRadius: Dp,
    fill: Color = theme.colors.surface,
    nested: Boolean = false,
    selected: Boolean = false,
    keyline: Boolean = true,
    /** Aurora's selected outline: the page light's `mark1` ([RommioTheme.light]). */
    selectedEdge: Color = Color.Unspecified,
): Modifier {
    val shape = if (cornerRadius >= 999.dp) {
        androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)
    } else {
        androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
    }
    val radius = if (cornerRadius >= 999.dp) 999.dp else cornerRadius
    return if (theme.isRiso) {
        val edge = when {
            selected -> theme.colors.ink
            nested -> Color.Transparent
            keyline -> theme.colors.keyline
            else -> theme.colors.hairline
        }
        val width = when {
            selected -> 2.dp
            keyline -> theme.strokeWidth
            else -> 1.dp
        }
        this
            .clip(shape)
            .background(if (nested) Color.Transparent else fill)
            .then(if (edge.alpha > 0f) Modifier.border(width, edge, shape) else Modifier)
    } else {
        val shadow = theme.glassShadow()
        this
            .then(if (nested) Modifier else Modifier.dropShadow(shadow.color, radius, shadow.radius, shadow.y))
            .clip(shape)
            .then(if (nested) Modifier else Modifier.glassBackdropBlur())
            .background(fill)
            .drawBehind { glassEdge(radius.toPx(), theme.isDark, if (nested) 0.5f else 1f) }
            .then(if (selected && selectedEdge.isSpecified) Modifier.border(1.5.dp, selectedEdge, shape) else Modifier)
    }
}

/**
 * Riso's offset plate: a solid copy of the shape printed off register behind the element
 * (e.g. `.rb.ink`: ink button with a pink plate 2 × misregistration down and right).
 */
fun Modifier.plateOffset(color: Color, offset: Dp, cornerRadius: Dp = 0.dp): Modifier = drawBehind {
    val o = offset.toPx()
    drawRoundRect(color, topLeft = Offset(o, o), size = size, cornerRadius = CornerRadius(cornerRadius.toPx()))
}

// ---- Surfaces ----------------------------------------------------------------------------------

/**
 * The theme's card. Aurora: glass (translucent surface, 0.5 dp rim, top highlight, soft
 * shadow). Riso: a white paper block with a 1.5 dp ink keyline. Inside another card it drops its
 * edge so borders never stack.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = RommioTheme.radius.card,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    selected: Boolean = false,
    strong: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = RommioTheme.current
    val nested = LocalInCard.current
    val fill = if (strong) theme.colors.surfaceStrong else theme.colors.surface
    // Only a selected surface reads the page light (its outline is the light's mark).
    val selectedEdge = if (selected && !theme.isRiso) RommioTheme.light.mark1 else Color.Unspecified
    CompositionLocalProvider(LocalContentColor provides theme.colors.ink, LocalInCard provides true) {
        Column(
            modifier = modifier
                .themedSurface(theme, cornerRadius, fill = fill, nested = nested, selected = selected, selectedEdge = selectedEdge)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** A Box on the theme's card material, for layered content (art with captions). */
@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = RommioTheme.radius.card,
    content: @Composable BoxScope.() -> Unit,
) {
    val theme = RommioTheme.current
    val nested = LocalInCard.current
    CompositionLocalProvider(LocalInCard provides true) {
        Box(
            modifier = modifier.themedSurface(theme, cornerRadius, nested = nested),
            content = content,
        )
    }
}
