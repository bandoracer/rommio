package io.github.bandoracer.rommio.ui.screen.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.BuildConfig
import io.github.bandoracer.rommio.model.AuthDiscoveryResult
import io.github.bandoracer.rommio.model.CloudflareServiceCredentials
import io.github.bandoracer.rommio.model.EdgeAuthMode
import io.github.bandoracer.rommio.model.InteractiveSessionProvider
import io.github.bandoracer.rommio.model.ServerAccessStatus
import io.github.bandoracer.rommio.model.ServerProfile
import io.github.bandoracer.rommio.ui.screen.auth.OnboardingPanelCard
import io.github.bandoracer.rommio.util.getServerSecurityNotice
import io.github.bandoracer.rommio.util.normalizeServerUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ServerAccessScreen(
    container: AppContainer,
    onInteractiveAuthRequested: (InteractiveSessionProvider) -> Unit,
    onContinueToLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val activeProfile by container.repository.activeProfileFlow().collectAsStateWithLifecycle(initialValue = null)
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var edgeAuthModeName by rememberSaveable { mutableStateOf(EdgeAuthMode.NONE.name) }
    var manualEdgeMode by rememberSaveable { mutableStateOf(false) }
    var serviceClientId by rememberSaveable { mutableStateOf("") }
    var serviceClientSecret by rememberSaveable { mutableStateOf("") }
    var discovery by remember { mutableStateOf<AuthDiscoveryResult?>(null) }
    var discoveryError by rememberSaveable { mutableStateOf<String?>(null) }
    var isDiscovering by remember { mutableStateOf(false) }
    var isTestingAccess by remember { mutableStateOf(false) }
    var isLaunchingInteractive by remember { mutableStateOf(false) }
    var lastMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var discoverJob by remember { mutableStateOf<Job?>(null) }

    val edgeAuthMode = runCatching { EdgeAuthMode.valueOf(edgeAuthModeName) }.getOrDefault(EdgeAuthMode.NONE)
    val securityNotice = getServerSecurityNotice(serverUrl)
    val normalizedServerUrl = remember(serverUrl) {
        runCatching { normalizeServerUrl(serverUrl) }.getOrNull()
    }
    val activeMatchesUrl = activeProfile?.baseUrl?.removeSuffix("/") == normalizedServerUrl?.removeSuffix("/")
    val activeForUrl = activeProfile?.takeIf { activeMatchesUrl }
    val activeMatchesSelection = activeMatchesUrl && activeProfile?.edgeAuthMode == edgeAuthMode
    val serverAccess = activeForUrl?.serverAccess
    val serverAccessReady = activeForUrl?.serverAccess?.status == ServerAccessStatus.READY
    val requiresInteractiveEdge = edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION ||
        edgeAuthMode == EdgeAuthMode.GENERIC_COOKIE_SSO
    val hasDebugTestServer = BuildConfig.DEBUG &&
        BuildConfig.DEBUG_TEST_BASE_URL.isNotBlank() &&
        BuildConfig.DEBUG_TEST_CLIENT_ID.isNotBlank() &&
        BuildConfig.DEBUG_TEST_CLIENT_SECRET.isNotBlank()

    LaunchedEffect(activeProfile?.id, activeProfile?.baseUrl, activeProfile?.edgeAuthMode, activeProfile?.serverAccess?.status) {
        val profile = activeProfile ?: return@LaunchedEffect
        if (serverUrl.isBlank() || activeMatchesUrl) {
            serverUrl = profile.baseUrl
        }
        if ((!manualEdgeMode || activeMatchesSelection) || (activeMatchesUrl && profile.serverAccess.status == ServerAccessStatus.READY)) {
            edgeAuthModeName = profile.edgeAuthMode.name
            if (activeMatchesUrl && profile.serverAccess.status == ServerAccessStatus.READY) {
                manualEdgeMode = false
            }
        }
    }

    LaunchedEffect(serverUrl, manualEdgeMode) {
        discoverJob?.cancel()
        if (serverUrl.isBlank()) {
            discovery = null
            discoveryError = null
            isDiscovering = false
            return@LaunchedEffect
        }
        discoverJob = scope.launch {
            delay(350)
            isDiscovering = true
            runCatching { container.repository.discoverServer(serverUrl) }.fold(
                onSuccess = { result ->
                    discovery = result
                    discoveryError = null
                    if (activeForUrl?.serverAccess?.status == ServerAccessStatus.READY) {
                        edgeAuthModeName = activeForUrl.edgeAuthMode.name
                    } else if (!manualEdgeMode) {
                        edgeAuthModeName = result.recommendedEdgeAuthMode.name
                    }
                },
                onFailure = { error ->
                    discovery = null
                    discoveryError = error.message ?: "Unable to reach this server."
                },
            )
            isDiscovering = false
        }
    }

    suspend fun configureProfile(): ServerProfile {
        val discovered = discovery ?: container.repository.discoverServer(serverUrl)
        val profile = container.repository.configureServerProfile(
            baseUrl = serverUrl,
            edgeAuthMode = edgeAuthMode,
            originAuthMode = activeProfile?.takeIf { activeMatchesUrl }?.originAuthMode ?: discovered.recommendedOriginAuthMode,
            discoveryResult = discovered,
            makeActive = true,
        )

        if (edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE) {
            val trimmedClientId = serviceClientId.trim()
            val trimmedClientSecret = serviceClientSecret.trim()
            val hasStoredCredentials = container.repository.hasStoredCloudflareServiceCredentials(profile.id)
            if (trimmedClientId.isNotBlank() || trimmedClientSecret.isNotBlank()) {
                if (trimmedClientId.isBlank() || trimmedClientSecret.isBlank()) {
                    error("Enter both the Cloudflare Access client ID and client secret.")
                }
                container.repository.setCloudflareServiceCredentials(
                    profile.id,
                    CloudflareServiceCredentials(
                        clientId = trimmedClientId,
                        clientSecret = trimmedClientSecret,
                    ),
                )
            } else if (!hasStoredCredentials) {
                error("Enter the Cloudflare Access client ID and client secret.")
            }
        }

        return container.repository.currentProfile() ?: profile
    }

    OnboardingFrame(
        step = OnboardingStep.Server,
        title = "Verify native server access.",
        subtitle = "Choose the right edge policy, confirm that the app can reach RomM from native requests, then continue to sign-in.",
    ) {
        if (hasDebugTestServer) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Debug shortcuts",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = {
                            serverUrl = BuildConfig.DEBUG_TEST_BASE_URL
                            edgeAuthModeName = EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE.name
                            manualEdgeMode = true
                            serviceClientId = BuildConfig.DEBUG_TEST_CLIENT_ID
                            serviceClientSecret = BuildConfig.DEBUG_TEST_CLIENT_SECRET
                            lastMessage = null
                        },
                    ) {
                        Text("Use debug test server")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                isTestingAccess = true
                                runCatching {
                                    serverUrl = BuildConfig.DEBUG_TEST_BASE_URL
                                    edgeAuthModeName = EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE.name
                                    manualEdgeMode = true
                                    serviceClientId = BuildConfig.DEBUG_TEST_CLIENT_ID
                                    serviceClientSecret = BuildConfig.DEBUG_TEST_CLIENT_SECRET
                                    lastMessage = null
                                    val discovered = container.repository.discoverServer(BuildConfig.DEBUG_TEST_BASE_URL)
                                    val profile = container.repository.configureServerProfile(
                                        baseUrl = BuildConfig.DEBUG_TEST_BASE_URL,
                                        edgeAuthMode = EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
                                        originAuthMode = discovered.recommendedOriginAuthMode,
                                        discoveryResult = discovered,
                                        makeActive = true,
                                    )
                                    container.repository.setCloudflareServiceCredentials(
                                        profile.id,
                                        CloudflareServiceCredentials(
                                            clientId = BuildConfig.DEBUG_TEST_CLIENT_ID,
                                            clientSecret = BuildConfig.DEBUG_TEST_CLIENT_SECRET,
                                        ),
                                    )
                                    val result = container.repository.testServerAccess(profile.id)
                                    check(result.status == ServerAccessStatus.READY) { result.message }
                                }.fold(
                                    onSuccess = {
                                        onContinueToLogin()
                                    },
                                    onFailure = { error ->
                                        lastMessage = error.message ?: "Unable to run debug server access."
                                    },
                                )
                                isTestingAccess = false
                            }
                        },
                        enabled = !isTestingAccess && !isLaunchingInteractive,
                    ) {
                        Text("Run debug server access")
                    }
                }
            }
        }

        OnboardingPanelCard {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    lastMessage = null
                },
                label = { Text("Server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isDiscovering) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    Text("Checking server access policy…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            discovery?.let { result ->
                Text(
                    text = discoverySummary(result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            discoveryError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            securityNotice?.let { notice ->
                Text(
                    text = notice,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        OnboardingPanelCard {
            Text("Edge access mode", style = MaterialTheme.typography.titleMedium)
            discovery?.let { result ->
                Text(
                    text = "Recommended: ${edgeAuthLabel(result.recommendedEdgeAuthMode)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EdgeAuthMode.entries.forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(
                        selected = edgeAuthMode == option,
                        onClick = {
                            edgeAuthModeName = option.name
                            manualEdgeMode = true
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(edgeAuthLabel(option), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = edgeAuthDescription(option),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (discovery != null && manualEdgeMode) {
                TextButton(
                    onClick = {
                        edgeAuthModeName = discovery!!.recommendedEdgeAuthMode.name
                        manualEdgeMode = false
                    },
                ) {
                    Text("Use detected recommendation")
                }
            }
            if (edgeAuthMode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE) {
                HorizontalDivider()
                OutlinedTextField(
                    value = serviceClientId,
                    onValueChange = { serviceClientId = it },
                    label = { Text("Cloudflare client ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = serviceClientSecret,
                    onValueChange = { serviceClientSecret = it },
                    label = { Text("Cloudflare client secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (activeMatchesSelection && container.repository.hasStoredCloudflareServiceCredentials(activeProfile?.id.orEmpty())) {
                    Text(
                        text = "Stored service-token credentials are already available for this profile. Leave the fields blank to reuse them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OnboardingPanelCard {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            DiagnosticsRow(label = "Server", value = activeProfile?.label ?: "Not configured")
            DiagnosticsRow(label = "HTTP status", value = serverAccess?.lastHttpStatus?.toString() ?: "Not tested")
            DiagnosticsRow(label = "Response kind", value = serverAccess?.lastResponseKind?.name ?: "Unknown")
            DiagnosticsRow(
                label = "Cookies seen",
                value = serverAccess?.cookieNamesSeen?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None",
            )
            DiagnosticsRow(label = "Last test", value = serverAccess?.lastTestedAt ?: "Never")
            serverAccess?.lastError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (serverAccess.status == ServerAccessStatus.READY) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            lastMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (serverAccessReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (requiresInteractiveEdge) {
                Button(
                    onClick = {
                        if (securityNotice != null) {
                            lastMessage = securityNotice
                            return@Button
                        }
                        scope.launch {
                            isLaunchingInteractive = true
                            runCatching {
                                val profile = configureProfile()
                                container.repository.beginEdgeAccess(profile.id)
                            }.fold(
                                onSuccess = {
                                    onInteractiveAuthRequested(InteractiveSessionProvider.EDGE)
                                },
                                onFailure = { error ->
                                    lastMessage = error.message ?: "Unable to launch the protected-login flow."
                                },
                            )
                            isLaunchingInteractive = false
                        }
                    },
                    enabled = !isLaunchingInteractive && !isTestingAccess,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isLaunchingInteractive) "Opening…" else "Open protected login")
                }
            }
            Button(
                onClick = {
                    if (securityNotice != null) {
                        lastMessage = securityNotice
                        return@Button
                    }
                    scope.launch {
                        isTestingAccess = true
                        runCatching {
                            val profile = configureProfile()
                            container.repository.testServerAccess(profile.id)
                        }.fold(
                            onSuccess = { result ->
                                lastMessage = result.message
                            },
                            onFailure = { error ->
                                lastMessage = error.message ?: "Unable to test server access."
                            },
                        )
                        isTestingAccess = false
                    }
                },
                enabled = !isTestingAccess && !isLaunchingInteractive,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isTestingAccess) "Testing…" else "Test server access")
            }
        }

        Button(
            onClick = onContinueToLogin,
            enabled = serverAccessReady,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue to RomM sign-in")
        }
    }
}

@Composable
private fun DiagnosticsRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun discoverySummary(discovery: AuthDiscoveryResult): String {
    val parts = buildList {
        when {
            discovery.capabilities.cloudflareAccessDetected -> add("Cloudflare Access detected")
            discovery.capabilities.genericCookieSsoDetected -> add("Protected login detected")
            else -> add("Direct access detected")
        }
        if (discovery.capabilities.requiresPrivateOverlay) add("Private overlay recommended")
        add("RomM auth: ${originAuthLabel(discovery.recommendedOriginAuthMode.name)}")
    }
    return parts.joinToString(" • ")
}

private fun edgeAuthLabel(mode: EdgeAuthMode): String = when (mode) {
    EdgeAuthMode.NONE -> "Direct / no edge auth"
    EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION -> "Cloudflare Access session"
    EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE -> "Cloudflare service token"
    EdgeAuthMode.GENERIC_COOKIE_SSO -> "Generic cookie SSO"
}

private fun edgeAuthDescription(mode: EdgeAuthMode): String = when (mode) {
    EdgeAuthMode.NONE -> "Use this when the app can reach RomM directly with no extra proxy login."
    EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION -> "Open an in-app web login to capture a reusable Cloudflare Access session."
    EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE -> "Attach CF-Access service-token headers on every native request."
    EdgeAuthMode.GENERIC_COOKIE_SSO -> "Open an in-app protected login and reuse the returned cookies on API calls."
}

private fun originAuthLabel(modeName: String): String = when (modeName) {
    "ROMM_BEARER_PASSWORD" -> "Bearer password grant"
    "ROMM_OIDC_SESSION" -> "OIDC session"
    "ROMM_BASIC_LEGACY" -> "Legacy Basic auth"
    else -> "Unknown"
}
