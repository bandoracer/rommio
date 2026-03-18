package io.github.mattsays.rommnative.ui.screen.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.model.AuthStatus
import io.github.mattsays.rommnative.model.ServerAccessStatus
import io.github.mattsays.rommnative.model.ServerProfile
import io.github.mattsays.rommnative.ui.component.RommGradientBackdrop
import io.github.mattsays.rommnative.ui.navigation.NavRoutes
import io.github.mattsays.rommnative.ui.theme.BrandSeed
import io.github.mattsays.rommnative.ui.theme.BrandText

@Composable
fun AuthGateScreen(
    container: AppContainer,
    onResolved: (String) -> Unit,
) {
    val activeProfile by container.repository.activeProfileFlow().collectAsStateWithLifecycle(initialValue = null)
    var isInitializing by remember { mutableStateOf(true) }
    var initialProfile by remember { mutableStateOf<ServerProfile?>(null) }

    LaunchedEffect(Unit) {
        runCatching { container.repository.initializeAuth() }
        initialProfile = runCatching { container.repository.currentProfile() }.getOrNull()
        isInitializing = false
    }

    LaunchedEffect(isInitializing, activeProfile, initialProfile) {
        if (!isInitializing) {
            onResolved(authRouteForProfile(activeProfile ?: initialProfile))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        RommGradientBackdrop(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = BrandSeed)
            Text(
                text = "Preparing authentication…",
                style = MaterialTheme.typography.titleMedium,
                color = BrandText,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "Checking server access and any resumable RomM session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

internal fun authRouteForProfile(profile: ServerProfile?): String {
    return when {
        profile == null -> NavRoutes.ONBOARDING_WELCOME
        profile.serverAccess.status != ServerAccessStatus.READY -> NavRoutes.ONBOARDING_SERVER
        profile.status == AuthStatus.CONNECTED -> NavRoutes.APP
        profile.status == AuthStatus.REAUTH_REQUIRED_EDGE -> NavRoutes.ONBOARDING_SERVER
        else -> NavRoutes.ONBOARDING_LOGIN
    }
}
