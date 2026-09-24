package io.github.bandoracer.rommio.ui.screen.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.domain.input.PlayerControlsState
import io.github.bandoracer.rommio.ui.theme.PlayerColorTokens
import io.github.bandoracer.rommio.ui.theme.PlayerDpadStyle
import io.github.bandoracer.rommio.ui.theme.PlayerShapeTokens
import io.github.bandoracer.rommio.ui.theme.PlayerShoulderStyle
import io.github.bandoracer.rommio.ui.theme.PlayerTertiaryStyle
import io.github.bandoracer.rommio.ui.theme.RommioThemeValue

/** Colours of one control: its fill, edge and legend, and (Riso) the plate printed behind it. */
data class PlayerControlStyle(
    val fillColor: Color,
    val pressedFillColor: Color,
    val borderColor: Color,
    val pressedBorderColor: Color,
    val contentColor: Color,
    /** Riso's off-register plate behind the control; transparent in Aurora. */
    val plateColor: Color = Color.Transparent,
)

/** What a drawn control is, which decides its shape in each player style. */
enum class PlayerControlKind { FACE, SHOULDER, TERTIARY, MENU, STICK, DPAD_ARM }

/** Direction glyphs: Aurora's thin chevrons, Riso's solid printed triangles. */
enum class PlayerGlyphSet { CHEVRONS, TRIANGLES }

/**
 * How controls are drawn (the player's half of the theme): shapes, legend and label type, glyphs,
 * colours per kind of control, and the frosted (Aurora) or flat ink (Riso) finish. Touch handling,
 * hit areas and geometry never read this.
 */
@Immutable
data class PlayerStyle(
    val frosted: Boolean,
    val shape: PlayerShapeTokens,
    val colors: PlayerColorTokens,
    val legend: TextStyle,
    val legendUppercase: Boolean,
    val label: TextStyle,
    val labelUppercase: Boolean,
    val glyphs: PlayerGlyphSet,
    /** Distance of Riso's offset plate (0 in Aurora). */
    val plateOffset: Dp,
    /** Aurora's frosted-glass edge: top highlight, inner rim and drop shadow colours. */
    val highlight: Color,
    val rim: Color,
    val shadow: Color,
    /** Shoulder buttons: blue tabs in Riso, frosted in Aurora. */
    val shoulder: PlayerControlStyle,
    /** Select, Start and other utility pills: outlined mono caps in Riso. */
    val tertiary: PlayerControlStyle,
    val menu: PlayerControlStyle,
    val dpad: PlayerControlStyle,
    /** Riso d-pad: second (blue) plate and the yellow pivot. */
    val dpadAltFill: Color,
    val pivot: Color,
) {
    val isInk: Boolean get() = !frosted
    val shoulderIsTab: Boolean get() = shape.shoulder == PlayerShoulderStyle.TAB
    val dpadIsTwoPlate: Boolean get() = shape.dpad == PlayerDpadStyle.TWO_PLATE_CROSS
    val tertiaryIsOutlined: Boolean get() = shape.tertiary == PlayerTertiaryStyle.OUTLINED_CAPSULE

    /** Alpha of a control for the user's opacity setting (Riso prints at full ink at the default). */
    fun controlAlpha(opacity: Float): Float = if (frosted) {
        opacity.coerceIn(0.2f, 1f)
    } else {
        (opacity / DEFAULT_OPACITY).coerceIn(0.2f, 1f)
    }

    companion object {
        const val DEFAULT_OPACITY = 0.72f
    }
}

data class PlayerVisualTheme(
    val canvasColor: Color,
    val panelColor: Color,
    val panelAltColor: Color,
    val textColor: Color,
    val accentColor: Color,
    val neutralControlStyle: PlayerControlStyle,
    val faceControlStyles: Map<String, PlayerControlStyle>,
    /** Content on [accentColor] fills (primary pause actions). */
    val onAccentColor: Color = Color.White,
    val style: PlayerStyle,
    /** The face-button look when console colours don't name the button. */
    val faceDefault: PlayerControlStyle = neutralControlStyle,
    /** The app theme the player was resolved from (sheets take its colours and type). */
    val appTheme: RommioThemeValue = RommioThemeValue.Default,
    /** OLED black: a pure black stage (no ambient light). */
    val oledBlack: Boolean = false,
) {
    fun faceStyle(label: String): PlayerControlStyle = faceControlStyles[label.uppercase()] ?: faceDefault

    /** The style a control of [kind] labelled [label] is drawn with (console colours win for buttons). */
    fun styleFor(kind: PlayerControlKind, label: String): PlayerControlStyle {
        faceControlStyles[label.uppercase()]?.let { if (kind != PlayerControlKind.MENU && kind != PlayerControlKind.DPAD_ARM) return it }
        return when (kind) {
            PlayerControlKind.FACE -> faceDefault
            PlayerControlKind.SHOULDER -> style.shoulder
            PlayerControlKind.TERTIARY -> style.tertiary
            PlayerControlKind.MENU -> style.menu
            PlayerControlKind.STICK -> neutralControlStyle
            PlayerControlKind.DPAD_ARM -> style.dpad
        }
    }
}

/**
 * Player colours and style from the active theme's player tokens. OLED black forces a black stage
 * with the dark palette; console colours keep tinting face buttons. Geometry and test tags are
 * unaffected.
 */
fun resolvePlayerVisualTheme(
    controls: PlayerControlsState?,
    familyOverride: String? = null,
    appTheme: RommioThemeValue = RommioThemeValue.Default,
): PlayerVisualTheme {
    val oledBlackMode = controls?.preferences?.oledBlackModeEnabled == true
    val consoleColorsEnabled = controls?.preferences?.consoleColorsEnabled ?: true
    val familyId = familyOverride ?: controls?.platformProfile?.familyId
    // A black stage always uses the dark palette so controls and text stay legible.
    val theme = if (oledBlackMode && !appTheme.isDark) RommioThemeValue.of(appTheme.theme, dark = true) else appTheme
    val colors = theme.colors
    val player = theme.player
    val shape = theme.playerShape
    val ink = !shape.frosted
    val canvasColor = if (oledBlackMode) Color.Black else player.ground
    val panelColor = if (oledBlackMode) Color.Black else colors.groundRaised
    val panelAltColor = if (oledBlackMode) {
        Color(0xFF101014)
    } else {
        colors.surfaceStrong.compositeOver(colors.groundRaised)
    }
    val accentColor = if (consoleColorsEnabled && familyId != null) consoleNeutralAccent(familyId) ?: colors.tint else colors.tint
    val neutralControlStyle = PlayerControlStyle(
        fillColor = player.controlFill,
        pressedFillColor = player.controlPressed,
        borderColor = player.controlStroke,
        pressedBorderColor = if (ink) player.controlInk else accentColor.copy(alpha = 0.64f),
        contentColor = player.controlInk,
    )
    val plate = player.offsetPlate
    fun inkStyle(fill: Color, content: Color) = PlayerControlStyle(
        fillColor = fill,
        pressedFillColor = lerp(fill, Color.Black, 0.18f),
        borderColor = Color.Transparent,
        pressedBorderColor = Color.Transparent,
        contentColor = content,
        plateColor = plate,
    )
    fun frostedStyle(fill: Color, content: Color) = PlayerControlStyle(
        fillColor = fill,
        pressedFillColor = player.controlPressed,
        borderColor = player.controlStroke,
        pressedBorderColor = accentColor.copy(alpha = 0.64f),
        contentColor = content,
    )
    val faceDefault = if (ink) inkStyle(player.faceFill, player.faceInk) else frostedStyle(player.faceFill, player.faceInk)
    val shoulder = if (ink) inkStyle(player.shoulderFill, player.faceInk) else frostedStyle(player.shoulderFill, player.controlInk)
    val tertiary = if (ink) {
        PlayerControlStyle(
            fillColor = Color.Transparent,
            pressedFillColor = player.controlPressed,
            borderColor = player.controlStroke,
            pressedBorderColor = player.controlInk,
            contentColor = player.controlInk,
        )
    } else {
        frostedStyle(player.controlFill, player.controlInk)
    }
    val menu = if (ink) {
        inkStyle(player.menuFill, player.menuInk).copy(plateColor = Color.Transparent)
    } else {
        frostedStyle(player.menuFill, player.menuInk)
    }
    val dpad = if (ink) inkStyle(player.dpadFill, player.faceInk) else frostedStyle(player.dpadFill, player.controlInk)
    val dark = theme.isDark || oledBlackMode
    val faceStyles = if (consoleColorsEnabled && familyId != null) {
        consoleFaceColors(familyId).mapKeys { it.key.uppercase() }.mapValues { (_, color) ->
            if (ink) inkStyle(color, if (color.luminance() > 0.4f) Color(0xFF1B1B1B) else Color(0xFFF2F0EA)) else coloredStyle(color, player, dark)
        }
    } else {
        emptyMap()
    }
    val type = theme.type
    val style = PlayerStyle(
        frosted = shape.frosted,
        shape = shape,
        colors = player,
        legend = type.playerLegend.style,
        legendUppercase = shape.legendUppercase || type.playerLegend.uppercase,
        label = type.playerLabel.style,
        labelUppercase = type.playerLabel.uppercase,
        glyphs = if (ink) PlayerGlyphSet.TRIANGLES else PlayerGlyphSet.CHEVRONS,
        plateOffset = shape.misregistration.dp,
        highlight = Color.White.copy(alpha = if (dark) 0.22f else 0.95f),
        rim = Color.White.copy(alpha = if (dark) 0.10f else 0.6f),
        shadow = if (dark) Color.Black.copy(alpha = 0.7f) else Color(0x66281440),
        shoulder = shoulder,
        tertiary = tertiary,
        menu = menu,
        dpad = dpad,
        dpadAltFill = player.dpadAltFill,
        pivot = player.pivot,
    )
    return PlayerVisualTheme(
        canvasColor = canvasColor,
        panelColor = panelColor,
        panelAltColor = panelAltColor,
        textColor = colors.ink,
        accentColor = accentColor,
        neutralControlStyle = neutralControlStyle,
        faceControlStyles = faceStyles,
        onAccentColor = if (accentColor.luminance() > 0.45f) Color(0xFF151515) else Color.White,
        style = style,
        faceDefault = faceDefault,
        appTheme = theme,
        oledBlack = oledBlackMode,
    )
}

private fun coloredStyle(color: Color, player: PlayerColorTokens, dark: Boolean): PlayerControlStyle {
    val content = if (dark) color else lerp(color, Color.Black, 0.35f)
    return PlayerControlStyle(
        fillColor = if (dark) player.controlFill else color.copy(alpha = 0.18f).compositeOver(player.ground).copy(alpha = 0.85f),
        pressedFillColor = color.copy(alpha = .38f),
        borderColor = color.copy(alpha = .55f),
        pressedBorderColor = player.controlInk.copy(alpha = 0.75f),
        contentColor = content,
    )
}

private fun consoleFaceColors(familyId: String): Map<String, Color> {
    return when (familyId) {
        "snes", "nds" -> mapOf(
            "Y" to Color(0xFF46B06E),
            "B" to Color(0xFFF1C94B),
            "A" to Color(0xFFDA5A58),
            "X" to Color(0xFF5F7DDE),
        )
        "psx", "psp" -> mapOf(
            "SQUARE" to Color(0xFFD06AAF),
            "CROSS" to Color(0xFF4E8EEA),
            "CIRCLE" to Color(0xFFE05C4B),
            "TRIANGLE" to Color(0xFF55B96D),
        )
        "sega16" -> listOf("A", "B", "C", "X", "Y", "Z").associateWith { Color(0xFFBBC6D8) } + ("START" to Color(0xFFE17C79))
        "n64" -> mapOf("A" to Color(0xFF619CF4), "B" to Color(0xFF60CF92)) +
            listOf("C-UP", "C-DOWN", "C-LEFT", "C-RIGHT").associateWith { Color(0xFFEBC861) }
        "sms", "gamegear" -> mapOf("1" to Color(0xFF6BB9D0), "2" to Color(0xFF6BB9D0))
        "arcade" -> mapOf(
            "1" to Color(0xFF4A78D0),
            "2" to Color(0xFF4FB56B),
            "3" to Color(0xFFF0C14A),
            "4" to Color(0xFFD85E4B),
        )
        "nes" -> mapOf(
            "A" to Color(0xFFD85C56),
            "B" to Color(0xFFB54845),
        )
        "gb" -> mapOf(
            "A" to Color(0xFF8A4FA8),
            "B" to Color(0xFFB45AA2),
        )
        "gba" -> mapOf(
            "A" to Color(0xFF7D70D6),
            "B" to Color(0xFF9887E4),
        )
        "tg16" -> mapOf(
            "I" to Color(0xFF4479CF),
            "II" to Color(0xFFE08C3E),
        )
        "atari2600" -> mapOf(
            "Fire" to Color(0xFFE08C3E),
            "Trigger" to Color(0xFFD85E4B),
        )
        "atari7800" -> mapOf(
            "1" to Color(0xFFE08C3E),
            "2" to Color(0xFFD85E4B),
        )
        "lynx" -> mapOf("A" to Color(0xFFE08C3E), "B" to Color(0xFFD85E4B))
        else -> emptyMap()
    }
}

private fun consoleNeutralAccent(familyId: String): Color? {
    return when (familyId) {
        "snes", "nds" -> Color(0xFF7F6BC4)
        "psx", "psp" -> Color(0xFF5E82D8)
        "sega16" -> Color(0xFF4A78D0)
        "n64" -> Color(0xFF60CF92)
        "sms", "gamegear" -> Color(0xFF6BB9D0)
        "arcade" -> Color(0xFFD85E4B)
        "nes" -> Color(0xFFB54845)
        "gb" -> Color(0xFF8A4FA8)
        "gba" -> Color(0xFF7D70D6)
        "tg16" -> Color(0xFF4479CF)
        "atari2600", "atari7800", "lynx" -> Color(0xFFE08C3E)
        else -> null
    }
}
