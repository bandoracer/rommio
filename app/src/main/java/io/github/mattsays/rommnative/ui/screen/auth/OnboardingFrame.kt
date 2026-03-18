package io.github.mattsays.rommnative.ui.screen.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mattsays.rommnative.ui.component.RommGradientBackdrop
import io.github.mattsays.rommnative.ui.theme.BrandMuted
import io.github.mattsays.rommnative.ui.theme.BrandPanel
import io.github.mattsays.rommnative.ui.theme.BrandPanelAlt
import io.github.mattsays.rommnative.ui.theme.BrandSeed
import io.github.mattsays.rommnative.ui.theme.BrandText

enum class OnboardingStep(
    val title: String,
) {
    Welcome("Welcome"),
    Server("Server"),
    Login("Sign in"),
    Finish("Finish"),
}

@Composable
fun OnboardingFrame(
    step: OnboardingStep,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(top = 24.dp, bottom = 24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        RommGradientBackdrop(
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Rommio setup",
                    style = MaterialTheme.typography.labelMedium,
                    color = BrandSeed,
                    fontWeight = FontWeight.SemiBold,
                )
                StepRow(current = step)
                Text(text = title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun StepRow(current: OnboardingStep) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OnboardingStep.entries.forEach { step ->
            val active = step.ordinal <= current.ordinal
            Surface(
                modifier = Modifier.fillMaxHeight(),
                color = if (active) BrandSeed.copy(alpha = 0.16f) else BrandPanel.copy(alpha = 0.72f),
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(
                    text = step.title,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (step == current) BrandSeed else if (active) BrandText else BrandMuted,
                )
            }
        }
    }
}

@Composable
fun OnboardingPanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = BrandPanelAlt.copy(alpha = 0.94f),
            contentColor = BrandText,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}
