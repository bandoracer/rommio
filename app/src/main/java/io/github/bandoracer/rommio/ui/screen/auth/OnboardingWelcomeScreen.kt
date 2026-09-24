package io.github.bandoracer.rommio.ui.screen.auth

import io.github.bandoracer.rommio.ui.component.OverArt
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.ui.component.PageField
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.TextAction
import io.github.bandoracer.rommio.ui.component.dropShadow
import io.github.bandoracer.rommio.ui.component.halftone
import io.github.bandoracer.rommio.ui.component.plateOffset
import io.github.bandoracer.rommio.ui.theme.RommioTheme

private const val PROMISE = "Every game on your server, right where you left it."
private const val DETAIL = "Browse your RomM library, play offline, and continue on any device."

/**
 * Welcome (docs/theme-layouts.md "Setup"): full screen, no tabs. Aurora: three glowing stand-in
 * covers fanned out, the app mark and the promise as a display headline. Riso: the masthead over
 * the promise, prints pinned over a yellow sun, the detail over a rule.
 */
@Composable
fun OnboardingWelcomeScreen(
    hasProfile: Boolean,
    onStart: () -> Unit,
    onResume: (() -> Unit)? = null,
    /** "Scan setup code": the Setup code sheet (one-touch setup). */
    onSetupCode: () -> Unit = {},
) {
    val theme = RommioTheme.current
    Box(Modifier.fillMaxSize().testTag("screen.welcome"), contentAlignment = Alignment.TopCenter) {
        PageField(Modifier.fillMaxSize(), style = PageFieldStyle.WELCOME)
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = theme.spacing.gutter + 4.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (theme.isRiso) RisoHero() else AuroraHero()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val resume = hasProfile && onResume != null
                PrimaryButton(
                    text = if (resume) "Continue setup" else "Connect to your server",
                    onClick = if (resume) onResume!! else onStart,
                    icon = null,
                    modifier = Modifier.fillMaxWidth().testTag("welcome.connect"),
                    trailingIcon = if (theme.isRiso) Icons.AutoMirrored.Outlined.ArrowForward else null,
                )
                SecondaryButton(
                    text = "Scan setup code",
                    icon = Icons.Outlined.QrCodeScanner,
                    onClick = onSetupCode,
                    modifier = Modifier.fillMaxWidth().testTag("welcome.setupCode"),
                )
                if (hasProfile) {
                    TextAction(
                        text = "Connect to a different server",
                        onClick = onStart,
                        modifier = Modifier.align(Alignment.CenterHorizontally).testTag("welcome.reconfigure"),
                    )
                }
            }
            RoleText(
                "Rommio doesn't include any games. It plays the library on a RomM server you run.",
                theme.type.caption,
                color = theme.colors.inkTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AuroraHero() {
    val theme = RommioTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Spacer(Modifier.height(24.dp))
        CoverFan(Modifier.padding(bottom = 8.dp))
        AppMark()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RoleText(PROMISE, theme.type.display, size = 40f, color = theme.colors.ink, modifier = Modifier.semantics { heading() })
            RoleText(DETAIL, theme.type.body, color = theme.colors.inkSecondary)
        }
    }
}

@Composable
private fun RisoHero() {
    val theme = RommioTheme.current
    val c = theme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(top = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                RoleText("RomM", theme.type.label, size = 10.5f, color = c.ink, modifier = Modifier.weight(1f))
                RoleText("Your library", theme.type.label, size = 10.5f, color = c.ink)
            }
            PrintRule(weight = RuleWeight.KEYLINE)
        }
        AppMark(size = 32.dp)
        Column {
            RoleText(PROMISE, theme.type.body, size = 26f, italic = true, color = c.ink, modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
            PrintRule(weight = RuleWeight.HEAVY)
        }
        CoverFan(Modifier.padding(vertical = 6.dp))
        Column {
            PrintRule(weight = RuleWeight.FINE)
            RoleText(DETAIL, theme.type.body, size = 16f, color = c.ink, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * Three stand-in covers. Aurora: soft glowing cards in the page's light (138 × 184, −9° / +8°, the
 * centre at 112 %). Riso: halftone prints on white mounts, tilted, over a yellow sun.
 */
@Composable
private fun CoverFan(modifier: Modifier = Modifier) {
    val theme = RommioTheme.current
    val c = theme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(if (theme.isRiso) 236.dp else 230.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (theme.isRiso) {
            Box(Modifier.offset(x = 120.dp, y = 20.dp).size(210.dp).clip(CircleShape).background(c.accent3.copy(alpha = if (theme.isDark) 0.9f else 0.85f)))
            Plate(c.accent1, c.accent1.copy(alpha = 0.35f), Modifier.offset(x = (-104).dp, y = 18.dp).rotate(-8f))
            Plate(c.accent2, c.accent1.copy(alpha = 0.25f), Modifier.offset(x = 104.dp, y = 24.dp).rotate(9f))
            Plate(c.accent1, c.accent2, Modifier.rotate(2f).scale(1.1f).plateOffset(c.ink, 5.dp))
        } else {
            Card(listOf(c.accent1, c.accent3), Modifier.offset(x = (-92).dp, y = 14.dp).rotate(-9f))
            Card(listOf(c.accent2, c.accent1), Modifier.offset(x = 92.dp, y = 14.dp).rotate(8f))
            Card(listOf(c.accent3, c.accent2), Modifier.scale(1.12f))
        }
    }
}

@Composable
private fun Card(colors: List<Color>, modifier: Modifier) {
    val theme = RommioTheme.current
    val shape = RoundedCornerShape(theme.radius.cover)
    Box(modifier.size(138.dp, 184.dp), contentAlignment = Alignment.Center) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                Modifier
                    .size(138.dp, 184.dp)
                    .offset(y = 24.dp)
                    .graphicsLayer { alpha = if (theme.isDark) 0.8f else 0.55f }
                    .blur(26.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded)
                    .clip(shape)
                    .background(Brush.linearGradient(colors)),
            )
        }
        Box(
            Modifier
                .size(138.dp, 184.dp)
                .dropShadow(OverArt.shade(if (theme.isDark) 0.5f else 0.18f), theme.radius.cover, 20.dp, 12.dp)
                .clip(shape)
                .background(Brush.linearGradient(colors))
                .border(0.5.dp, OverArt.rim.copy(alpha = if (theme.isDark) 0.12f else 0.5f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.SportsEsports, contentDescription = null, tint = theme.colors.onAccent.copy(alpha = 0.45f), modifier = Modifier.size(32.dp))
        }
    }
}

/** A halftone print on a white mount with a 1.5 dp ink keyline (Riso). */
@Composable
private fun Plate(dots: Color, ground: Color, modifier: Modifier) {
    val theme = RommioTheme.current
    Box(
        modifier
            .background(theme.colors.surface)
            .border(1.5.dp, theme.colors.ink)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 20.dp),
    ) {
        Box(
            Modifier
                .size(106.dp, 136.dp)
                .background(ground)
                .drawBehind { halftone(dots, 5.dp.toPx(), coverage = 0.9f) },
        )
    }
}
