package io.github.bandoracer.rommio.ui.screen.auth

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
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.model.AuthStatus
import io.github.bandoracer.rommio.model.OfflineState
import io.github.bandoracer.rommio.model.ServerAccessStatus
import io.github.bandoracer.rommio.model.ServerProfile
import io.github.bandoracer.rommio.ui.component.RommGradientBackdrop
import io.github.bandoracer.rommio.ui.navigation.NavRoutes
import io.github.bandoracer.rommio.ui.theme.BrandSeed
import io.github.bandoracer.rommio.ui.theme.BrandText

@Composable
fun AuthGateScreen(
    container: AppContainer,
    onResolved: (String) -> Unit,
) {
    val activeProfile by container.repository.activeProfileFlow().collectAsStateWithLifecycle(initialValue = null)
    val offlineState by container.repository.observeOfflineState().collectAsStateWithLifecycle(initialValue = OfflineState())
    var isInitializing by remember { mutableStateOf(true) }
    var initialProfile by remember { mutableStateOf<ServerProfile?>(null) }

    LaunchedEffect(Unit) {
        runCatching { container.repository.initializeAuth() }
        initialProfile = runCatching { container.repository.currentProfile() }.getOrNull()
        isInitializing = false
    }

    LaunchedEffect(isInitializing, activeProfile, initialProfile) {
        if (!isInitializing) {
            onResolved(authRouteForProfile(activeProfile ?: initialProfile, offlineState))
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
                text = if (offlineState.isOffline) {
                    "No network detected. Looking for a hydrated profile that can open offline."
                } else {
                    "Checking server access, cached library readiness, and any resumable RomM session."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

internal fun authRouteForProfile(
    profile: ServerProfile?,
    offlineState: OfflineState = OfflineState(),
): String {
    return when {
        profile == null -> NavRoutes.ONBOARDING_WELCOME
        profile.serverAccess.status != ServerAccessStatus.READY -> NavRoutes.ONBOARDING_SERVER
        profile.status == AuthStatus.CONNECTED -> NavRoutes.APP
        offlineState.isOffline && offlineState.catalogReady && profile.sessionState.hasOriginSession -> NavRoutes.APP
        profile.status == AuthStatus.REAUTH_REQUIRED_EDGE -> NavRoutes.ONBOARDING_SERVER
        else -> NavRoutes.ONBOARDING_LOGIN
    }
}
