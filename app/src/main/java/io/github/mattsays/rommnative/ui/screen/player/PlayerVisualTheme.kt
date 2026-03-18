package io.github.mattsays.rommnative.ui.screen.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.mattsays.rommnative.domain.input.PlayerControlsState
import io.github.mattsays.rommnative.ui.theme.BrandCanvas
import io.github.mattsays.rommnative.ui.theme.BrandPanel
import io.github.mattsays.rommnative.ui.theme.BrandPanelAlt
import io.github.mattsays.rommnative.ui.theme.BrandSeed
import io.github.mattsays.rommnative.ui.theme.BrandText

data class PlayerControlStyle(
    val fillColor: Color,
    val pressedFillColor: Color,
    val borderColor: Color,
    val pressedBorderColor: Color,
    val contentColor: Color,
)

data class PlayerVisualTheme(
    val canvasColor: Color,
    val panelColor: Color,
    val panelAltColor: Color,
    val textColor: Color,
    val accentColor: Color,
    val neutralControlStyle: PlayerControlStyle,
    val faceControlStyles: Map<String, PlayerControlStyle>,
) {
    fun faceStyle(label: String): PlayerControlStyle = faceControlStyles[label.uppercase()] ?: neutralControlStyle
}

fun resolvePlayerVisualTheme(controls: PlayerControlsState?): PlayerVisualTheme {
    val oledBlackMode = controls?.preferences?.oledBlackModeEnabled == true
    val consoleColorsEnabled = controls?.preferences?.consoleColorsEnabled == true
    val familyId = controls?.platformProfile?.familyId
    val canvasColor = if (oledBlackMode) Color.Black else BrandCanvas
    val panelColor = if (oledBlackMode) Color.Black else BrandPanel
    val panelAltColor = if (oledBlackMode) Color(0xFF080808) else BrandPanelAlt
    val accentColor = if (consoleColorsEnabled && familyId != null) {
        consoleNeutralAccent(familyId)
    } else {
        BrandSeed
    }
    val neutralControlStyle = neutralStyle(
        baseColor = panelAltColor,
        accentColor = accentColor,
    )
    val faceStyles = if (consoleColorsEnabled && familyId != null) {
        consoleFaceColors(familyId)
            .mapValues { (_, color) -> coloredStyle(color) }
    } else {
        emptyMap()
    }
    return PlayerVisualTheme(
        canvasColor = canvasColor,
        panelColor = panelColor,
        panelAltColor = panelAltColor,
        textColor = BrandText,
        accentColor = accentColor,
        neutralControlStyle = neutralControlStyle,
        faceControlStyles = faceStyles,
    )
}

private fun neutralStyle(
    baseColor: Color,
    accentColor: Color,
): PlayerControlStyle {
    return PlayerControlStyle(
        fillColor = baseColor.copy(alpha = 0.92f),
        pressedFillColor = accentColor.copy(alpha = 0.38f),
        borderColor = accentColor.copy(alpha = 0.20f),
        pressedBorderColor = accentColor.copy(alpha = 0.64f),
        contentColor = BrandText,
    )
}

private fun coloredStyle(color: Color): PlayerControlStyle {
    val contentColor = if (color.luminance() > 0.55f) Color(0xFF151515) else BrandText
    return PlayerControlStyle(
        fillColor = color.copy(alpha = 0.90f),
        pressedFillColor = color.copy(alpha = 1f),
        borderColor = color.copy(alpha = 0.96f),
        pressedBorderColor = BrandText.copy(alpha = 0.75f),
        contentColor = contentColor,
    )
}

private fun consoleFaceColors(familyId: String): Map<String, Color> {
    return when (familyId) {
        "snes" -> mapOf(
            "Y" to Color(0xFF46B06E),
            "B" to Color(0xFFF1C94B),
            "A" to Color(0xFFDA5A58),
            "X" to Color(0xFF5F7DDE),
        )
        "psx" -> mapOf(
            "SQUARE" to Color(0xFFD06AAF),
            "CROSS" to Color(0xFF4E8EEA),
            "CIRCLE" to Color(0xFFE05C4B),
            "TRIANGLE" to Color(0xFF55B96D),
        )
        "sega16" -> mapOf(
            "A" to Color(0xFF4A78D0),
            "B" to Color(0xFF4FB56B),
            "C" to Color(0xFFF0C14A),
            "X" to Color(0xFFD85E4B),
        )
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
        "atari" -> mapOf(
            "1" to Color(0xFFE08C3E),
            "2" to Color(0xFFD85E4B),
        )
        else -> emptyMap()
    }
}

private fun consoleNeutralAccent(familyId: String): Color {
    return when (familyId) {
        "snes" -> Color(0xFF7F6BC4)
        "psx" -> Color(0xFF5E82D8)
        "sega16" -> Color(0xFF4A78D0)
        "arcade" -> Color(0xFFD85E4B)
        "nes" -> Color(0xFFB54845)
        "gb" -> Color(0xFF8A4FA8)
        "gba" -> Color(0xFF7D70D6)
        "tg16" -> Color(0xFF4479CF)
        "atari" -> Color(0xFFE08C3E)
        else -> BrandSeed
    }
}
