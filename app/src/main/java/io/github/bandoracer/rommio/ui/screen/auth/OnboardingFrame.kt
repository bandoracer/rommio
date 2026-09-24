package io.github.bandoracer.rommio.ui.screen.auth

import io.github.bandoracer.rommio.ui.component.OverArt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.GlassSurface
import io.github.bandoracer.rommio.ui.component.PageField
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PrintRule
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RuleWeight
import io.github.bandoracer.rommio.ui.component.ThemedTextField
import io.github.bandoracer.rommio.ui.theme.RommioTheme

enum class OnboardingStep(
    val title: String,
    /** The step line of the step header ("Step 1 of 2"). */
    val stepLabel: String,
    /** What the step is about ("Your RomM server"). */
    val about: String,
) {
    Welcome("Welcome", "Welcome", "Rommio"),
    Server("Connect", "Step 1 of 2", "Your RomM server"),
    Login("Sign in", "Step 2 of 2", "Your account"),
    Finish("First sync", "First sync", "Your library"),
}

/**
 * A setup page (docs/theme-layouts.md "Setup — one layout"): the welcome field, a [StepHeader],
 * then the page's content in a readable column.
 */
@Composable
fun OnboardingFrame(
    step: OnboardingStep,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(top = 24.dp, bottom = 24.dp),
    stepSubtitle: String = step.about,
    /** Back to the previous setup page (the pages are pushed: Welcome → Connect → Sign in). */
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = RommioTheme.current
    val backdrop = if (theme.isGlass) io.github.bandoracer.rommio.ui.component.rememberGlassBackdrop() else null
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        PageField(modifier = Modifier.fillMaxSize(), style = PageFieldStyle.WELCOME, backdrop = backdrop)
        androidx.compose.runtime.CompositionLocalProvider(io.github.bandoracer.rommio.ui.component.LocalGlassBackdrop provides backdrop) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = theme.spacing.gutter + 4.dp)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            onBack?.let { back ->
                Row(
                    Modifier
                        .padding(start = 0.dp)
                        .clip(if (theme.isRiso) RoundedCornerShape(0.dp) else RoundedCornerShape(percent = 50))
                        .clickable(role = Role.Button, onClickLabel = backLabel, onClick = back)
                        .heightIn(min = 48.dp)
                        .padding(end = 12.dp)
                        .testTag("onboarding.back"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = theme.colors.ink, modifier = Modifier.size(20.dp))
                    RoleText(backLabel, theme.type.bodyEmphasis, color = theme.colors.ink, maxLines = 1)
                }
            }
            StepHeader(step = step.stepLabel, title = title, subtitle = stepSubtitle)
            if (subtitle.isNotBlank()) {
                RoleText(text = subtitle, role = theme.type.body, color = theme.colors.inkSecondary)
            }
            content()
        }
        }
    }
}

/**
 * A setup step's header. Aurora: the page title and a quiet step line ("Step 1 of 2 · Your RomM
 * server"). Riso: a `label` step rule over a misregistered `display` 54 headline.
 */
@Composable
fun StepHeader(step: String, title: String, subtitle: String?) {
    val theme = RommioTheme.current
    val c = theme.colors
    if (theme.isRiso) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
                    RoleText(step, theme.type.label, size = 10.5f, color = c.ink, modifier = Modifier.weight(1f))
                    subtitle?.let { RoleText(it, theme.type.label, size = 10.5f, color = c.inkSecondary) }
                }
                PrintRule(weight = RuleWeight.KEYLINE)
            }
            DisplayText(title, size = 54f, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RoleText(title, theme.type.title, color = c.ink, modifier = Modifier.semantics { heading() })
        RoleText(listOfNotNull(step, subtitle).joinToString(" · "), theme.type.secondary, color = c.inkSecondary)
    }
}

/** A caption over a field (Riso `label` caps; Aurora quiet `caption`). */
@Composable
fun FieldLabel(text: String) {
    val theme = RommioTheme.current
    RoleText(
        text,
        if (theme.isRiso) theme.type.label else theme.type.caption,
        size = if (theme.isRiso) 9.5f else 12.5f,
        color = if (theme.isRiso) theme.colors.ink else theme.colors.inkSecondary,
        modifier = Modifier.clearAndSetSemantics {},
    )
}

/** A labelled setup field: the [FieldLabel] over a themed field (glass, or paper in an ink box). */
@Composable
fun OnboardingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    enabled: Boolean = true,
) {
    // [modifier] (size, test tag) belongs to the field itself, so tests type into it.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        ThemedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            enabled = enabled,
        )
    }
}

/**
 * The app mark: the game-controller glyph on a tile in the page's light and the name (Aurora);
 * the name as a misregistered masthead (Riso, `display` 3.6 × [size], plate 2×).
 */
@Composable
fun AppMark(size: Dp = 30.dp) {
    val theme = RommioTheme.current
    Box(Modifier.semantics(mergeDescendants = true) { contentDescription = "Rommio" }) {
        if (theme.isRiso) {
            DisplayText("Rommio", size = size.value * 3.6f, plateOffset = 2f, maxLines = 1)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(size)
                        .clip(RoundedCornerShape(size * 0.28f))
                        .background(Brush.linearGradient(listOf(theme.mark1, theme.mark2))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = OverArt.rim, modifier = Modifier.size(size * 0.55f))
                }
                RoleText("Rommio", theme.type.headline, size = size.value * 0.7f, color = theme.colors.ink)
            }
        }
    }
}

@Composable
fun OnboardingPanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
