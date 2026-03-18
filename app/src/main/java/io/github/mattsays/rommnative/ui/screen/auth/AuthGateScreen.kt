package io.github.mattsays.rommnative.ui.screen.auth

import androidx.compose.foundation.background
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
import io.github.mattsays.rommnative.ui.navigation.NavRoutes
import io.github.mattsays.rommnative.ui.theme.BrandCanvas

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
            onResolved(resolveDestination(activeProfile ?: initialProfile))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCanvas)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Preparing authentication…",
            style = MaterialTheme.typography.titleMedium,
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

private fun resolveDestination(profile: ServerProfile?): String {
    return when {
        profile == null -> NavRoutes.SERVER_ACCESS
        profile.serverAccess.status != ServerAccessStatus.READY -> NavRoutes.SERVER_ACCESS
        profile.status == AuthStatus.CONNECTED -> NavRoutes.HOME
        profile.status == AuthStatus.REAUTH_REQUIRED_EDGE -> NavRoutes.SERVER_ACCESS
        else -> NavRoutes.LOGIN
    }
}
