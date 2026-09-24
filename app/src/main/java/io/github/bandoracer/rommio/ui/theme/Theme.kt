package io.github.bandoracer.rommio.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.bandoracer.rommio.model.AppearancePreferences
import io.github.bandoracer.rommio.model.AppearanceScheme
import io.github.bandoracer.rommio.model.AppearanceTheme
import io.github.bandoracer.rommio.ui.art.AuroraLight
import io.github.bandoracer.rommio.ui.art.AuroraLights
import java.util.Locale

/**
 * One text role from the generated tokens as a Compose [TextStyle]. Compose has no
 * text-transform, so uppercase roles are applied by [format] (components call it).
 */
@Immutable
data class RoleStyle(val style: TextStyle, val uppercase: Boolean) {
    fun format(text: String): String = if (uppercase) text.uppercase(Locale.getDefault()) else text
}

@Immutable
data class RommioTypography(
    val display: RoleStyle,
    val title: RoleStyle,
    val headline: RoleStyle,
    val section: RoleStyle,
    val body: RoleStyle,
    val bodyEmphasis: RoleStyle,
    val secondary: RoleStyle,
    val caption: RoleStyle,
    val label: RoleStyle,
    val numeral: RoleStyle,
    val statValue: RoleStyle,
    val button: RoleStyle,
    val playerLegend: RoleStyle,
    val playerLabel: RoleStyle,
)

@Immutable
data class RommioRadii(
    val card: Dp,
    val cover: Dp,
    val row: Dp,
    val button: Dp,
    val chip: Dp,
    val sheet: Dp,
    val field: Dp,
) {
    val cardShape: CornerBasedShape get() = shapeFor(card)
    val coverShape: CornerBasedShape get() = shapeFor(cover)
    val rowShape: CornerBasedShape get() = shapeFor(row)
    val buttonShape: CornerBasedShape get() = shapeFor(button)
    val chipShape: CornerBasedShape get() = shapeFor(chip)
    val sheetShape: CornerBasedShape get() = shapeFor(sheet)
    val fieldShape: CornerBasedShape get() = shapeFor(this.field)
}

@Immutable
data class RommioSpacing(
    /** Page side margin. */
    val gutter: Dp,
    /** Gap between page sections. */
    val section: Dp,
    /** Gap between stacked items inside a section. */
    val stack: Dp,
    /** Gap between inline items (chips, icon and label). */
    val inline: Dp,
    /** Minimum list row height. */
    val rowHeight: Dp,
)

/**
 * The active theme: tokens for the chosen theme and scheme, as Compose values. Provided through
 * [LocalRommioTheme] and read with the [RommioTheme] accessor, like `MaterialTheme`.
 */
@Immutable
class RommioThemeValue private constructor(
    val theme: AppearanceTheme,
    val tokens: ThemeTokenSet,
    val isDark: Boolean,
    /**
     * Aurora: the light of the game the page is about (docs/theme-layouts.md §1.2). A lit page
     * replaces, below it, `ground` with the lit ground, `tint` with the lit tint and
     * `accent1..3` with the lights l1..l3, and takes the marks k1..k3. Riso never lights a page.
     */
    val light: AuroraLights? = null,
) {
    /** The unlit colour roles of this theme and scheme. */
    val baseColors: ThemeColorTokens = tokens.colors(isDark)
    val colors: ThemeColorTokens = light?.let { lit ->
        baseColors.copy(
            ground = lit.ground.opaque(),
            tint = lit.tint.opaque(),
            accent1 = lit.l1.opaque(),
            accent2 = lit.l2.opaque(),
            accent3 = lit.l3.opaque(),
        )
    } ?: baseColors

    /** Aurora draws surfaces as glass over the page light; Riso prints on paper. */
    val isGlass: Boolean get() = tokens.material.surfaceIsGlass

    /**
     * The page's lights: the game's when lit, otherwise the theme's own (accent1..3 as lights,
     * marks from `tint`, `accent2`, `accent3` with the mark rule).
     */
    val lights: AuroraLights = light ?: AuroraLights(
        l1 = baseColors.accent1.rgb(), l2 = baseColors.accent2.rgb(), l3 = baseColors.accent3.rgb(),
        k1 = AuroraLight.mark(AuroraLight.toHsl(baseColors.tint.rgb()), isDark),
        k2 = AuroraLight.mark(AuroraLight.toHsl(baseColors.accent2.rgb()), isDark),
        k3 = AuroraLight.mark(AuroraLight.toHsl(baseColors.accent3.rgb()), isDark),
        tint = baseColors.tint.rgb(),
        ground = baseColors.ground.rgb(),
    )

    /**
     * The tint for text and meaningful glyphs (text actions, links, tint pills, selected labels):
     * [ReadableTint.readableTint] of the page's tint on its ground (docs/theme-layouts.md §1.2a).
     * Fills, marks, glows and bars keep [ThemeColorTokens.tint].
     */
    val tintText: Color = Color(
        0xFF000000.toInt() or ReadableTint.readableTint(colors.tint.rgb(), colors.ground.rgb(), isDark),
    )

    /** Vivid marks in the page's light (dots, rules, bars, glows). Never text. */
    val mark1: Color = lights.k1.opaque()
    val mark2: Color = lights.k2.opaque()
    val mark3: Color = lights.k3.opaque()
    val type: RommioTypography = typographyOf(tokens.type)
    val radius: RommioRadii = tokens.radius.let {
        RommioRadii(it.card.dp, it.cover.dp, it.row.dp, it.button.dp, it.chip.dp, it.sheet.dp, it.field.dp)
    }
    val spacing: RommioSpacing = tokens.spacing.let {
        RommioSpacing(it.gutter.dp, it.section.dp, it.stack.dp, it.inline.dp, it.rowHeight.dp)
    }
    val material: ThemeMaterialTokens = tokens.material
    val player: PlayerColorTokens = tokens.player(isDark)
    val playerShape: PlayerShapeTokens = tokens.playerShape

    /** Riso's printed components (paper, keylines, stamps); otherwise Aurora's glass. */
    val isRiso: Boolean get() = theme == AppearanceTheme.RISO

    /** Card and control stroke: Aurora hairline glass edge, Riso printed rule. */
    val strokeWidth: Dp = material.strokeWidth.dp
    val strokeColor: Color = if (material.surfaceIsGlass) colors.hairline else colors.keyline

    /** Opaque versions of the translucent roles, for Material components that need solid fills. */
    val solidSurface: Color = colors.surface.compositeOver(colors.groundRaised)
    val solidSurfaceStrong: Color = colors.surfaceStrong.compositeOver(colors.ground)

    val materialColorScheme: ColorScheme = colorSchemeOf(colors, isDark)
    val materialTypography: Typography = materialTypographyOf(type)
    val materialShapes: Shapes = Shapes(
        extraSmall = shapeFor(minOf(radius.field, 8.dp)),
        small = radius.fieldShape,
        medium = radius.coverShape,
        large = radius.cardShape,
        extraLarge = radius.sheetShape,
    )

    /** This theme lit by a game (Aurora only; Riso prints on plain paper). Null unlights it. */
    fun lit(light: AuroraLights?): RommioThemeValue {
        val effective = light.takeIf { isGlass }
        if (effective == this.light) return this
        return if (effective == null) of(theme, isDark) else litCache(theme, isDark, effective)
    }

    override fun equals(other: Any?): Boolean =
        other is RommioThemeValue && other.theme == theme && other.isDark == isDark && other.light == light

    override fun hashCode(): Int = (theme.hashCode() * 31 + isDark.hashCode()) * 31 + (light?.hashCode() ?: 0)

    companion object {
        private val cache = HashMap<Pair<AppearanceTheme, Boolean>, RommioThemeValue>()
        private val litValues = object : java.util.LinkedHashMap<Triple<AppearanceTheme, Boolean, AuroraLights>, RommioThemeValue>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<AppearanceTheme, Boolean, AuroraLights>, RommioThemeValue>?): Boolean = size > 24
        }

        fun of(theme: AppearanceTheme, dark: Boolean): RommioThemeValue = synchronized(cache) {
            cache.getOrPut(theme to dark) { RommioThemeValue(theme, ThemeTokens.forKey(theme.key), dark) }
        }

        private fun litCache(theme: AppearanceTheme, dark: Boolean, light: AuroraLights): RommioThemeValue = synchronized(litValues) {
            litValues.getOrPut(Triple(theme, dark, light)) { RommioThemeValue(theme, ThemeTokens.forKey(theme.key), dark, light) }
        }

        /** Used where no theme has been provided (tests, previews): Aurora dark. */
        val Default: RommioThemeValue get() = of(AppearanceTheme.AURORA, dark = true)
    }
}

val LocalRommioTheme = staticCompositionLocalOf { RommioThemeValue.Default }

/** Accessor for the active theme, mirroring `MaterialTheme`. */
object RommioTheme {
    val current: RommioThemeValue
        @Composable @ReadOnlyComposable get() = LocalRommioTheme.current
    val colors: ThemeColorTokens
        @Composable @ReadOnlyComposable get() = LocalRommioTheme.current.colors
    val type: RommioTypography
        @Composable @ReadOnlyComposable get() = LocalRommioTheme.current.type
    val radius: RommioRadii
        @Composable @ReadOnlyComposable get() = LocalRommioTheme.current.radius
    val spacing: RommioSpacing
        @Composable @ReadOnlyComposable get() = LocalRommioTheme.current.spacing
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalRommioTheme.current.isDark
    val isRiso: Boolean
        @Composable @ReadOnlyComposable get() = LocalRommioTheme.current.isRiso
}

private fun Int.opaque(): Color = Color(0xFF000000.toInt() or (this and 0xFFFFFF))

private fun Color.rgb(): Int = toArgb() and 0xFFFFFF

/**
 * Lights everything inside with a game's light (Aurora; ignored by Riso): the lit colour roles for
 * Rommio components and the matching Material scheme for stock components.
 */
@Composable
fun ProvideLitTheme(light: AuroraLights?, content: @Composable () -> Unit) {
    val base = LocalRommioTheme.current
    val value = remember(base, light) { base.lit(light) }
    if (value === base) {
        content()
        return
    }
    CompositionLocalProvider(LocalRommioTheme provides value) {
        MaterialTheme(colorScheme = value.materialColorScheme, typography = value.materialTypography, shapes = value.materialShapes) {
            CompositionLocalProvider(LocalContentColor provides value.colors.ink, content = content)
        }
    }
}

fun AppearanceScheme.resolveDark(systemDark: Boolean): Boolean = when (this) {
    AppearanceScheme.SYSTEM -> systemDark
    AppearanceScheme.LIGHT -> false
    AppearanceScheme.DARK -> true
}

/** Applies the chosen theme and scheme to Rommio components and to stock Material components. */
@Composable
fun RommioTheme(
    preferences: AppearancePreferences = AppearancePreferences(),
    content: @Composable () -> Unit,
) {
    val dark = preferences.scheme.resolveDark(isSystemInDarkTheme())
    val value = remember(preferences.theme, dark) { RommioThemeValue.of(preferences.theme, dark) }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(value.colors.ground.toArgb()))
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalRommioTheme provides value) {
        MaterialTheme(
            colorScheme = value.materialColorScheme,
            typography = value.materialTypography,
            shapes = value.materialShapes,
        ) {
            CompositionLocalProvider(LocalContentColor provides value.colors.ink, content = content)
        }
    }
}

private fun shapeFor(radius: Dp): CornerBasedShape =
    if (radius >= 999.dp) RoundedCornerShape(percent = 50) else RoundedCornerShape(radius)

private fun roleOf(token: TypeRoleToken): RoleStyle = RoleStyle(
    style = TextStyle(
        fontFamily = token.family,
        fontWeight = token.fontWeight,
        fontStyle = token.fontStyle,
        fontSize = token.size.sp,
        lineHeight = (token.size * token.lineHeight).sp,
        letterSpacing = token.tracking.em,
        fontFeatureSettings = if (token.tabular) "tnum" else null,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    ),
    uppercase = token.uppercase,
)

private fun typographyOf(type: ThemeTypeTokens): RommioTypography = RommioTypography(
    display = roleOf(type.display),
    title = roleOf(type.title),
    headline = roleOf(type.headline),
    section = roleOf(type.section),
    body = roleOf(type.body),
    bodyEmphasis = roleOf(type.bodyEmphasis),
    secondary = roleOf(type.secondary),
    caption = roleOf(type.caption),
    label = roleOf(type.label),
    numeral = roleOf(type.numeral),
    statValue = roleOf(type.statValue),
    button = roleOf(type.button),
    playerLegend = roleOf(type.playerLegend),
    playerLabel = roleOf(type.playerLabel),
)

private fun TextStyle.resized(size: Float): TextStyle {
    val ratio = if (fontSize.value > 0f) lineHeight.value / fontSize.value else 1.2f
    return copy(fontSize = size.sp, lineHeight = (size * ratio).sp)
}

/** Stock Material components (dialogs, sheets, text fields, top bars) read these. */
private fun materialTypographyOf(type: RommioTypography): Typography = Typography(
    displayLarge = type.display.style,
    displayMedium = type.display.style.resized(type.display.style.fontSize.value * 0.82f),
    displaySmall = type.title.style,
    headlineLarge = type.title.style,
    headlineMedium = type.title.style.resized(28f),
    headlineSmall = type.headline.style,
    titleLarge = type.headline.style,
    titleMedium = type.bodyEmphasis.style.resized(16f),
    titleSmall = type.bodyEmphasis.style,
    bodyLarge = type.body.style,
    bodyMedium = type.secondary.style,
    bodySmall = type.caption.style,
    labelLarge = type.button.style,
    labelMedium = type.label.style,
    labelSmall = type.label.style.resized(11f),
)

private fun onColorFor(background: Color, ink: Color, paper: Color): Color =
    if (background.luminance() > 0.45f) ink else paper

/**
 * A complete Material 3 scheme derived from the theme tokens so stock components match in both
 * schemes. Translucent roles are composited over the page ground so Material gets solid colours.
 */
private fun colorSchemeOf(c: ThemeColorTokens, dark: Boolean): ColorScheme {
    val darkInk = if (dark) c.ground else c.ink
    val lightInk = if (dark) c.ink else c.groundRaised
    val tintContainer = c.tint.copy(alpha = if (dark) 0.24f else 0.16f).compositeOver(c.ground)
    val liveContainer = c.live.copy(alpha = if (dark) 0.22f else 0.14f).compositeOver(c.ground)
    val badContainer = c.bad.copy(alpha = if (dark) 0.22f else 0.14f).compositeOver(c.ground)
    val surfaceLow = c.surface.compositeOver(c.ground)
    val surfaceMid = c.surface.compositeOver(c.groundRaised)
    val surfaceHigh = c.surfaceStrong.compositeOver(c.ground)
    val surfaceHighest = c.surfaceStrong.compositeOver(c.groundRaised)
    val inkSecondary = c.inkSecondary.compositeOver(c.ground)
    val outline = c.keyline.compositeOver(c.ground)
    val outlineVariant = c.hairline.compositeOver(c.ground)
    return if (dark) {
        darkColorScheme(
            // Stock buttons use the theme's primary button; tint stays the accent for selection.
            primary = c.buttonFill, onPrimary = c.buttonInk,
            primaryContainer = tintContainer, onPrimaryContainer = c.ink,
            inversePrimary = c.tint,
            secondary = c.ink, onSecondary = c.ground,
            secondaryContainer = tintContainer, onSecondaryContainer = c.ink,
            tertiary = c.live, onTertiary = onColorFor(c.live, darkInk, lightInk),
            tertiaryContainer = liveContainer, onTertiaryContainer = c.ink,
            background = c.ground, onBackground = c.ink,
            surface = c.ground, onSurface = c.ink,
            surfaceVariant = surfaceHigh, onSurfaceVariant = inkSecondary,
            surfaceTint = c.ground,
            inverseSurface = c.ink, inverseOnSurface = c.ground,
            error = c.badText, onError = onColorFor(c.badText, darkInk, Color.White),
            errorContainer = badContainer, onErrorContainer = c.ink,
            outline = outline, outlineVariant = outlineVariant,
            scrim = c.scrim,
            surfaceBright = surfaceHighest, surfaceDim = c.ground,
            surfaceContainer = surfaceMid, surfaceContainerHigh = surfaceHigh,
            surfaceContainerHighest = surfaceHighest, surfaceContainerLow = surfaceLow,
            surfaceContainerLowest = c.ground,
        )
    } else {
        lightColorScheme(
            // Stock buttons use the theme's primary button; tint stays the accent for selection.
            primary = c.buttonFill, onPrimary = c.buttonInk,
            primaryContainer = tintContainer, onPrimaryContainer = c.ink,
            inversePrimary = c.tint,
            secondary = c.ink, onSecondary = c.groundRaised,
            secondaryContainer = tintContainer, onSecondaryContainer = c.ink,
            tertiary = c.live, onTertiary = onColorFor(c.live, darkInk, lightInk),
            tertiaryContainer = liveContainer, onTertiaryContainer = c.ink,
            background = c.ground, onBackground = c.ink,
            surface = c.ground, onSurface = c.ink,
            surfaceVariant = surfaceHigh, onSurfaceVariant = inkSecondary,
            surfaceTint = c.ground,
            inverseSurface = c.ink, inverseOnSurface = c.ground,
            error = c.badText, onError = onColorFor(c.badText, darkInk, Color.White),
            errorContainer = badContainer, onErrorContainer = c.ink,
            outline = outline, outlineVariant = outlineVariant,
            scrim = c.scrim,
            surfaceBright = c.groundRaised, surfaceDim = surfaceHigh,
            surfaceContainer = surfaceMid, surfaceContainerHigh = surfaceHigh,
            surfaceContainerHighest = surfaceHighest, surfaceContainerLow = c.groundRaised,
            surfaceContainerLowest = c.groundRaised,
        )
    }
}
