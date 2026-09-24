package io.github.bandoracer.rommio.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.BuildConfig
import io.github.bandoracer.rommio.model.AuthStatus
import io.github.bandoracer.rommio.model.DirectLoginCredentials
import io.github.bandoracer.rommio.model.InteractiveSessionProvider
import io.github.bandoracer.rommio.model.OriginAuthMode
import io.github.bandoracer.rommio.model.ServerAccessStatus
import io.github.bandoracer.rommio.model.ServerProfile
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.RowIcon
import io.github.bandoracer.rommio.ui.component.TextAction
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.screen.auth.OnboardingError
import io.github.bandoracer.rommio.ui.screen.auth.OnboardingFrame
import io.github.bandoracer.rommio.ui.screen.auth.OnboardingStep
import io.github.bandoracer.rommio.ui.screen.auth.OnboardingTextField
import io.github.bandoracer.rommio.util.getServerSecurityNotice
import kotlinx.coroutines.launch

/**
 * Sign in (docs/theme-layouts.md "Setup"; iOS `LoginStepView`): the server as a row group, then a
 * password form, "Continue with single sign-on" (the in-app browser sign-in that keeps RomM's
 * session cookies) or Continue when the server needs no sign-in. Pushed from Connect; back returns
 * to it.
 */
@Composable
fun LoginScreen(
    container: AppContainer,
    onBackToServerAccess: () -> Unit,
    onInteractiveAuthRequested: (InteractiveSessionProvider) -> Unit,
    onLoginSuccess: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val activeProfile by container.repository.activeProfileFlow().collectAsStateWithLifecycle(initialValue = null)
    var initialProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var isResolvingProfile by remember { mutableStateOf(true) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val profile = activeProfile ?: initialProfile
    val originAuthMode = profile?.originAuthMode ?: OriginAuthMode.ROMM_BEARER_PASSWORD
    val securityNotice = getServerSecurityNotice(profile?.baseUrl.orEmpty())
    val hasDebugTestCredentials = BuildConfig.DEBUG && BuildConfig.DEBUG_TEST_USERNAME.isNotBlank() && BuildConfig.DEBUG_TEST_PASSWORD.isNotBlank()

    LaunchedEffect(Unit) {
        initialProfile = runCatching { container.repository.currentProfile() }.getOrNull()
        isResolvingProfile = false
    }
    LaunchedEffect(isResolvingProfile, profile?.id, profile?.serverAccess?.status) {
        if (isResolvingProfile) return@LaunchedEffect
        if (profile != null && profile.serverAccess.status != ServerAccessStatus.READY) onBackToServerAccess()
    }

    fun signIn(user: String = username, secret: String = password) {
        focus.clearFocus()
        securityNotice?.let {
            errorMessage = it
            return
        }
        val active = profile ?: return
        scope.launch {
            isSubmitting = true
            errorMessage = null
            runCatching {
                if (originAuthMode == OriginAuthMode.NONE) {
                    val status = container.repository.validateProfile(active.id)
                    require(status == AuthStatus.CONNECTED) { "The current session is not authenticated with RomM yet." }
                } else {
                    if (user.isBlank() || secret.isBlank()) error("Enter both your RomM username and password.")
                    container.repository.loginWithDirectCredentials(active.id, DirectLoginCredentials(username = user.trim(), password = secret))
                }
            }.fold(
                onSuccess = { onLoginSuccess() },
                onFailure = { error -> errorMessage = error.message ?: "Unable to sign in to RomM." },
            )
            isSubmitting = false
        }
    }

    OnboardingFrame(
        step = OnboardingStep.Login,
        title = "Sign in",
        subtitle = "",
        onBack = onBackToServerAccess,
        backLabel = "Server",
        modifier = Modifier.testTag("screen.login"),
    ) {
        ListGroup {
            ListRow(
                title = profile?.label ?: "RomM",
                subtitle = profile?.baseUrl,
                leading = { RowIcon(Icons.Outlined.Dns, Tone.LIVE) },
                modifier = Modifier.testTag("login.server"),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (originAuthMode) {
                OriginAuthMode.ROMM_BEARER_PASSWORD, OriginAuthMode.ROMM_BASIC_LEGACY -> {
                    OnboardingTextField(
                        value = username,
                        onValueChange = { username = it; errorMessage = null },
                        label = "Username",
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                        enabled = !isSubmitting,
                        modifier = Modifier.fillMaxWidth().testTag("login.username"),
                    )
                    OnboardingTextField(
                        value = password,
                        onValueChange = { password = it; errorMessage = null },
                        label = "Password",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { signIn() }),
                        enabled = !isSubmitting,
                        modifier = Modifier.fillMaxWidth().testTag("login.password"),
                    )
                    OnboardingError(errorMessage)
                    PrimaryButton(
                        text = if (isSubmitting) "Signing in…" else "Sign in",
                        onClick = { signIn() },
                        enabled = !isSubmitting && username.isNotBlank() && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("login.signIn"),
                    )
                }
                OriginAuthMode.ROMM_OIDC_SESSION -> {
                    OnboardingError(errorMessage ?: securityNotice)
                    PrimaryButton(
                        text = "Continue with single sign-on",
                        onClick = { onInteractiveAuthRequested(InteractiveSessionProvider.ORIGIN) },
                        enabled = !isSubmitting && securityNotice == null,
                        modifier = Modifier.fillMaxWidth().testTag("login.continueWithSSO"),
                    )
                }
                OriginAuthMode.NONE -> {
                    OnboardingError(errorMessage)
                    PrimaryButton(
                        text = if (isSubmitting) "Checking…" else "Continue",
                        onClick = { signIn() },
                        enabled = !isSubmitting,
                        modifier = Modifier.fillMaxWidth().testTag("login.validateCurrentSession"),
                    )
                }
            }
        }
        if (hasDebugTestCredentials && (originAuthMode == OriginAuthMode.ROMM_BEARER_PASSWORD || originAuthMode == OriginAuthMode.ROMM_BASIC_LEGACY)) {
            TextAction(
                "Sign in with debug test account",
                onClick = { signIn(BuildConfig.DEBUG_TEST_USERNAME, BuildConfig.DEBUG_TEST_PASSWORD) },
                enabled = !isSubmitting,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
