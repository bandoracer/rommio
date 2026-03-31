package io.github.bandoracer.rommio.ui.screen.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingSuccessScreen(
    onContinue: () -> Unit,
) {
    OnboardingFrame(
        step = OnboardingStep.Finish,
        title = "Connection ready.",
        subtitle = "Your server access and RomM session are active. The new shell is ready to sync, browse, and download.",
    ) {
        OnboardingPanelCard {
            Text(
                text = "What you can do now",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Jump straight into the new dashboard and library layout.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Browse Home, Library, Collections, and Settings from the bottom bar.")
                Text("Open the downloads manager from the top bar to monitor transfers and retries.")
                Text("Launch a game from the library once a compatible file and runtime are available locally.")
            }
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Enter app")
        }
    }
}
