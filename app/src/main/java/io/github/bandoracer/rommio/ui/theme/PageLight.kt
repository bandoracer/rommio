package io.github.bandoracer.rommio.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import io.github.bandoracer.rommio.ui.art.AuroraLight
import io.github.bandoracer.rommio.ui.art.AuroraLights

/**
 * The light of the page (docs/theme-layouts.md §1.2), separate from the base theme so a page
 * lighting up never invalidates what doesn't use it. Aurora: a lit page replaces `ground`, `tint`
 * and `accent1..3` with the game's lit ground, tint and lights l1..l3, and takes its marks k1..k3.
 * Riso never lights a page (its light is always [RommioThemeValue.unlit]).
 *
 * Every derived value is computed once, at construction; values are cached per theme, scheme and
 * light, so re-providing an equal light is free. Read it with [RommioTheme.light] only where lit
 * colours are drawn: the backdrop, tint text and buttons, marks, bars and glows.
 */
@Immutable
class PageLight internal constructor(
    val base: RommioThemeValue,
    /** The game's light, or null when the page keeps the theme's own light. */
    val light: AuroraLights?,
) {
    val isLit: Boolean get() = light != null

    /** The colour roles below this light: the base roles with the lit ground, tint and accents. */
    val colors: ThemeColorTokens = light?.let { lit ->
        base.colors.copy(
            ground = lit.ground.opaque(),
            tint = lit.tint.opaque(),
            accent1 = lit.l1.opaque(),
            accent2 = lit.l2.opaque(),
            accent3 = lit.l3.opaque(),
        )
    } ?: base.colors

    val ground: Color get() = colors.ground
    val tint: Color get() = colors.tint

    /**
     * The page's lights: the game's when lit, otherwise the theme's own (accent1..3 as lights,
     * marks from `tint`, `accent2`, `accent3` with the mark rule).
     */
    val lights: AuroraLights = light ?: base.colors.let { c ->
        AuroraLights(
            l1 = c.accent1.rgb(), l2 = c.accent2.rgb(), l3 = c.accent3.rgb(),
            k1 = AuroraLight.mark(AuroraLight.toHsl(c.tint.rgb()), base.isDark),
            k2 = AuroraLight.mark(AuroraLight.toHsl(c.accent2.rgb()), base.isDark),
            k3 = AuroraLight.mark(AuroraLight.toHsl(c.accent3.rgb()), base.isDark),
            tint = c.tint.rgb(),
            ground = c.ground.rgb(),
        )
    }

    /**
     * The tint for text and meaningful glyphs (text actions, links, tint pills, selected labels):
     * [ReadableTint.readableTint] of the page's tint on its ground (docs/theme-layouts.md §1.2a).
     * Fills, marks, glows and bars keep [tint].
     */
    val tintText: Color = Color(
        0xFF000000.toInt() or ReadableTint.readableTint(colors.tint.rgb(), colors.ground.rgb(), base.isDark),
    )

    /** Vivid marks in the page's light (dots, rules, bars, glows). Never text. */
    val mark1: Color = lights.k1.opaque()
    val mark2: Color = lights.k2.opaque()
    val mark3: Color = lights.k3.opaque()

    /** `surfaceStrong` made opaque over the page ground. */
    val solidSurfaceStrong: Color = colors.surfaceStrong.compositeOver(colors.ground)

    /** The Material scheme under this light, for stock components in sheets and dialogs ([LitMaterialTheme]). */
    val materialColorScheme: ColorScheme by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (light == null) base.materialColorScheme else colorSchemeOf(colors, base.isDark)
    }

    override fun equals(other: Any?): Boolean =
        other is PageLight && other.base == base && other.light == light

    override fun hashCode(): Int = base.hashCode() * 31 + (light?.hashCode() ?: 0)

    override fun toString(): String = "PageLight(${base.theme}, dark=${base.isDark}, lit=${light != null})"

    companion object {
        private val cache = object : java.util.LinkedHashMap<Pair<RommioThemeValue, AuroraLights>, PageLight>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<RommioThemeValue, AuroraLights>, PageLight>?): Boolean = size > 48
        }

        /** [base] lit by [light] (Aurora only; Riso prints on plain paper). Null keeps the theme's light. */
        fun of(base: RommioThemeValue, light: AuroraLights?): PageLight {
            val effective = light?.takeIf { base.isGlass } ?: return base.unlit
            return synchronized(cache) { cache.getOrPut(base to effective) { PageLight(base, effective) } }
        }
    }
}

/**
 * The page light below it. A `compositionLocalOf` (not static): only the composables that read
 * [RommioTheme.light] recompose when it changes. Null means the base theme's own light.
 */
val LocalPageLight = compositionLocalOf<PageLight?> { null }

/** Lights everything inside with [light] (Aurora); only its readers see the change. */
@Composable
fun ProvidePageLight(light: PageLight, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPageLight provides light, content = content)
}

/**
 * The page's Material colour scheme for stock components that draw lit roles (sheets, dialogs,
 * their text fields): the base scheme unless the page is lit. Use it around small subtrees only.
 */
@Composable
fun LitMaterialTheme(content: @Composable () -> Unit) {
    val light = RommioTheme.light
    if (!light.isLit) {
        content()
        return
    }
    MaterialTheme(
        colorScheme = light.materialColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

internal fun Int.opaque(): Color = Color(0xFF000000.toInt() or (this and 0xFFFFFF))

internal fun Color.rgb(): Int = toArgb() and 0xFFFFFF

/** The page light from a composable read ([RommioTheme.light]). */
internal val currentPageLight: PageLight
    @Composable @ReadOnlyComposable get() = LocalPageLight.current ?: LocalRommioTheme.current.unlit
