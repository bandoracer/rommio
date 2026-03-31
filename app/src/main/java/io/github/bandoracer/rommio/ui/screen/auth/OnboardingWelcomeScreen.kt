package io.github.bandoracer.rommio.ui.screen.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingWelcomeScreen(
    hasProfile: Boolean,
    onStart: () -> Unit,
    onResume: (() -> Unit)? = null,
) {
    OnboardingFrame(
        step = OnboardingStep.Welcome,
        title = "A native RomM front end built for the couch.",
        subtitle = "Connect your server, verify protected access, and bring browsing, downloads, and local play into one Android-native shell.",
    ) {
        OnboardingPanelCard {
            Text(
                text = "What changes here",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Browse a platform-first library with collection highlights and install-aware cards.")
                Text("Track queued, active, failed, and completed downloads from anywhere in the app.")
                Text("Keep account, server, storage, and control preferences in one settings hub.")
            }
        }

        OnboardingPanelCard {
            Text(
                text = if (hasProfile) "Resume setup" else "First connection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (hasProfile) {
                    "A saved server profile was found. Resume configuration or start over."
                } else {
                    "Validate server reachability first, then authenticate against RomM."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = if (hasProfile && onResume != null) onResume else onStart,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (hasProfile) "Resume setup" else "Start setup")
                }
                if (hasProfile) {
                    OutlinedButton(
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Reconfigure")
                    }
                }
            }
        }

        Text(
            text = "Server access and RomM sign-in semantics are unchanged. This flow only makes the steps explicit and resumable.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
