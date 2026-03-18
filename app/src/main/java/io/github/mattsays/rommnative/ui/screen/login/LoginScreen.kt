package io.github.mattsays.rommnative.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.BuildConfig
import io.github.mattsays.rommnative.model.DirectLoginCredentials
import io.github.mattsays.rommnative.model.InteractiveSessionProvider
import io.github.mattsays.rommnative.model.OriginAuthMode
import io.github.mattsays.rommnative.model.ServerAccessStatus
import io.github.mattsays.rommnative.model.ServerProfile
import io.github.mattsays.rommnative.ui.screen.auth.OnboardingFrame
import io.github.mattsays.rommnative.ui.screen.auth.OnboardingPanelCard
import io.github.mattsays.rommnative.ui.screen.auth.OnboardingStep
import io.github.mattsays.rommnative.util.getServerSecurityNotice
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    container: AppContainer,
    onBackToServerAccess: () -> Unit,
    onInteractiveAuthRequested: (InteractiveSessionProvider) -> Unit,
    onLoginSuccess: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val activeProfile by container.repository.activeProfileFlow().collectAsStateWithLifecycle(initialValue = null)
    var initialProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var isResolvingProfile by remember { mutableStateOf(true) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val profile = activeProfile ?: initialProfile
    val originAuthMode = profile?.originAuthMode ?: OriginAuthMode.ROMM_BEARER_PASSWORD
    val requiresInteractiveOrigin = originAuthMode == OriginAuthMode.ROMM_OIDC_SESSION
    val needsDirectCredentials = originAuthMode == OriginAuthMode.ROMM_BEARER_PASSWORD ||
        originAuthMode == OriginAuthMode.ROMM_BASIC_LEGACY
    val securityNotice = getServerSecurityNotice(profile?.baseUrl.orEmpty())
    val hasDebugTestCredentials = BuildConfig.DEBUG &&
        BuildConfig.DEBUG_TEST_USERNAME.isNotBlank() &&
        BuildConfig.DEBUG_TEST_PASSWORD.isNotBlank()

    LaunchedEffect(Unit) {
        initialProfile = runCatching { container.repository.currentProfile() }.getOrNull()
        isResolvingProfile = false
    }

    LaunchedEffect(isResolvingProfile, profile?.id, profile?.serverAccess?.status) {
        if (isResolvingProfile) {
            return@LaunchedEffect
        }
        if (profile != null && profile.serverAccess.status != ServerAccessStatus.READY) {
            onBackToServerAccess()
        }
    }

    OnboardingFrame(
        step = OnboardingStep.Login,
        title = "Authenticate with RomM.",
        subtitle = "Server access is ready. Finish RomM sign-in and the app will open into the new library shell.",
    ) {
        if (hasDebugTestCredentials && needsDirectCredentials) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Debug shortcuts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = {
                            username = BuildConfig.DEBUG_TEST_USERNAME
                            password = BuildConfig.DEBUG_TEST_PASSWORD
                            errorMessage = null
                        },
                    ) {
                        Text("Use debug test account")
                    }
                    TextButton(
                        onClick = {
                            val active = profile ?: return@TextButton
                            scope.launch {
                                isSubmitting = true
                                errorMessage = null
                                runCatching {
                                    container.repository.loginWithDirectCredentials(
                                        active.id,
                                        DirectLoginCredentials(
                                            username = BuildConfig.DEBUG_TEST_USERNAME,
                                            password = BuildConfig.DEBUG_TEST_PASSWORD,
                                        ),
                                    )
                                }.fold(
                                    onSuccess = { onLoginSuccess() },
                                    onFailure = { error ->
                                        errorMessage = error.message ?: "Unable to sign in with the debug test account."
                                    },
                                )
                                isSubmitting = false
                            }
                        },
                        enabled = !isSubmitting,
                    ) {
                        Text("Sign in with debug test account")
                    }
                }
            }
        }

        OnboardingPanelCard {
            Text("Connection summary", style = MaterialTheme.typography.titleMedium)
            SummaryRow("Server", profile?.label ?: "Unknown")
            SummaryRow("URL", profile?.baseUrl ?: "Unavailable")
            SummaryRow("Server access", profile?.serverAccess?.status?.name ?: "Unknown")
            SummaryRow("RomM auth", originAuthLabel(originAuthMode))
            TextButton(onClick = onBackToServerAccess) {
                Text("Change server access")
            }
        }

        securityNotice?.let { notice ->
            Text(
                text = notice,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        OnboardingPanelCard {
            Text("Authentication", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when (originAuthMode) {
                    OriginAuthMode.ROMM_OIDC_SESSION -> "This RomM server uses interactive sign-in. Continue below to finish authentication."
                    OriginAuthMode.ROMM_BASIC_LEGACY -> "This server falls back to legacy Basic auth. Use the same username and password that work on the web."
                    OriginAuthMode.ROMM_BEARER_PASSWORD -> "Use the same username and password that work in the RomM web app."
                    OriginAuthMode.NONE -> "No RomM sign-in mechanism was detected. The app will try to validate the current session directly."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (needsDirectCredentials) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        errorMessage = null
                    },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            errorMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            if (requiresInteractiveOrigin) {
                Button(
                    onClick = { onInteractiveAuthRequested(InteractiveSessionProvider.ORIGIN) },
                    enabled = !isSubmitting && securityNotice == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue with RomM SSO")
                }
            } else {
                Button(
                    onClick = {
                        if (securityNotice != null) {
                            errorMessage = securityNotice
                            return@Button
                        }
                        val active = profile ?: return@Button
                        scope.launch {
                            isSubmitting = true
                            errorMessage = null
                            runCatching {
                                if (originAuthMode == OriginAuthMode.NONE) {
                                    val status = container.repository.validateProfile(active.id)
                                    require(status == io.github.mattsays.rommnative.model.AuthStatus.CONNECTED) {
                                        "The current session is not authenticated with RomM yet."
                                    }
                                } else {
                                    if (username.isBlank() || password.isBlank()) {
                                        error("Enter both your RomM username and password.")
                                    }
                                    container.repository.loginWithDirectCredentials(
                                        active.id,
                                        DirectLoginCredentials(
                                            username = username.trim(),
                                            password = password,
                                        ),
                                    )
                                }
                            }.fold(
                                onSuccess = { onLoginSuccess() },
                                onFailure = { error ->
                                    errorMessage = error.message ?: "Unable to sign in to RomM."
                                },
                            )
                            isSubmitting = false
                        }
                    },
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isSubmitting) "Signing in…" else "Sign in")
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun originAuthLabel(mode: OriginAuthMode): String = when (mode) {
    OriginAuthMode.NONE -> "None"
    OriginAuthMode.ROMM_BEARER_PASSWORD -> "Bearer password grant"
    OriginAuthMode.ROMM_BASIC_LEGACY -> "Legacy Basic auth"
    OriginAuthMode.ROMM_OIDC_SESSION -> "OIDC session"
}
