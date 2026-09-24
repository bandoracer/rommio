package io.github.bandoracer.rommio.ui.screen.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.BuildConfig
import io.github.bandoracer.rommio.model.AuthDiscoveryResult
import io.github.bandoracer.rommio.model.CloudflareServiceCredentials
import io.github.bandoracer.rommio.model.EdgeAuthMode
import io.github.bandoracer.rommio.model.InteractiveSessionProvider
import io.github.bandoracer.rommio.model.ServerAccessResponseKind
import io.github.bandoracer.rommio.model.ServerAccessStatus
import io.github.bandoracer.rommio.model.ServerProfile
import io.github.bandoracer.rommio.ui.component.Chevron
import io.github.bandoracer.rommio.ui.component.ListDivider
import io.github.bandoracer.rommio.ui.component.ListGroup
import io.github.bandoracer.rommio.ui.component.ListRow
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RowIcon
import io.github.bandoracer.rommio.ui.component.TextAction
import io.github.bandoracer.rommio.ui.component.ThemedTextField
import io.github.bandoracer.rommio.ui.component.ToggleRow
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.themedSurface
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.util.getServerSecurityNotice
import io.github.bandoracer.rommio.util.normalizeServerUrl
import kotlinx.coroutines.launch

/** Access method names (iOS `accessProfileLabel`). */
fun accessMethodLabel(mode: EdgeAuthMode): String = when (mode) {
    EdgeAuthMode.NONE -> "Direct"
    EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION -> "Cloudflare login"
    EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE -> "Service token"
    EdgeAuthMode.GENERIC_COOKIE_SSO -> "Browser login"
}

/**
 * Connect (docs/theme-layouts.md "Setup"; iOS `ServerAccessStepView`): the server address, the
 * access method (the Access options sheet), one primary Continue, saved servers, and the setup code.
 * Continue discovers the server, stores the profile, checks RomM and goes on to Sign in — or opens
 * the browser login a protected server asks for. Connection details (the old diagnostics) sit
 * behind a quiet link.
 */
@Composable
fun ServerAccessScreen(
    container: AppContainer,
    onInteractiveAuthRequested: (InteractiveSessionProvider) -> Unit,
    onContinueToLogin: () -> Unit,
    /** A saved server was chosen: the gate routes to wherever it stands. */
    onProfileSelected: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val theme = RommioTheme.current
    val activeProfile by container.repository.activeProfileFlow().collectAsStateWithLifecycle(initialValue = null)
    var profiles by remember { mutableStateOf<List<ServerProfile>>(emptyList()) }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var edgeAuthModeName by rememberSaveable { mutableStateOf(EdgeAuthMode.NONE.name) }
    var manualEdgeMode by rememberSaveable { mutableStateOf(false) }
    var serviceClientId by rememberSaveable { mutableStateOf("") }
    var serviceClientSecret by rememberSaveable { mutableStateOf("") }
    var discovery by remember { mutableStateOf<AuthDiscoveryResult?>(null) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showsAccessOptions by rememberSaveable { mutableStateOf(false) }
    var showsSetupCode by rememberSaveable { mutableStateOf(false) }
    var showsDetails by rememberSaveable { mutableStateOf(false) }

    val edgeAuthMode = runCatching { EdgeAuthMode.valueOf(edgeAuthModeName) }.getOrDefault(EdgeAuthMode.NONE)
    val normalizedServerUrl = remember(serverUrl) { runCatching { normalizeServerUrl(serverUrl) }.getOrNull() }
    val activeMatchesUrl = activeProfile?.baseUrl?.removeSuffix("/") == normalizedServerUrl?.removeSuffix("/")
    val hasDebugTestServer = BuildConfig.DEBUG && BuildConfig.DEBUG_TEST_BASE_URL.isNotBlank() &&
        BuildConfig.DEBUG_TEST_CLIENT_ID.isNotBlank() && BuildConfig.DEBUG_TEST_CLIENT_SECRET.isNotBlank()

    LaunchedEffect(Unit) { profiles = runCatching { container.repository.listProfiles() }.getOrDefault(emptyList()) }
    LaunchedEffect(activeProfile?.id, activeProfile?.baseUrl, activeProfile?.edgeAuthMode) {
        val profile = activeProfile ?: return@LaunchedEffect
        if (serverUrl.isBlank()) serverUrl = profile.baseUrl
        if (!manualEdgeMode && activeMatchesUrl) edgeAuthModeName = profile.edgeAuthMode.name
    }

    suspend fun configureProfile(discovered: AuthDiscoveryResult, mode: EdgeAuthMode): ServerProfile {
        val profile = container.repository.configureServerProfile(
            baseUrl = serverUrl,
            edgeAuthMode = mode,
            originAuthMode = activeProfile?.takeIf { activeMatchesUrl }?.originAuthMode ?: discovered.recommendedOriginAuthMode,
            discoveryResult = discovered,
            makeActive = true,
        )
        if (mode == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE) {
            val id = serviceClientId.trim()
            val secret = serviceClientSecret.trim()
            if (id.isNotBlank() || secret.isNotBlank()) {
                if (id.isBlank() || secret.isBlank()) error("Enter both the Cloudflare Access client ID and client secret.")
                container.repository.setCloudflareServiceCredentials(profile.id, CloudflareServiceCredentials(id, secret))
                // Keep the token out of screen state once it is in the secret store.
                serviceClientId = ""
                serviceClientSecret = ""
            } else if (!container.repository.hasStoredCloudflareServiceCredentials(profile.id)) {
                error("Enter the Cloudflare Access client ID and client secret.")
            }
        }
        return container.repository.currentProfile() ?: profile
    }

    fun connect() {
        focus.clearFocus()
        if (serverUrl.isBlank()) {
            errorMessage = "Enter your RomM server address."
            return
        }
        getServerSecurityNotice(serverUrl)?.let { notice ->
            errorMessage = notice
            return
        }
        scope.launch {
            busy = true
            errorMessage = null
            runCatching {
                val discovered = discovery?.takeIf { it.baseUrl.removeSuffix("/") == normalizedServerUrl?.removeSuffix("/") }
                    ?: container.repository.discoverServer(serverUrl)
                discovery = discovered
                serverUrl = discovered.baseUrl
                val mode = if (manualEdgeMode) edgeAuthMode else {
                    profiles.firstOrNull { it.baseUrl.removeSuffix("/") == discovered.baseUrl.removeSuffix("/") }?.edgeAuthMode
                        ?: discovered.recommendedEdgeAuthMode
                }
                edgeAuthModeName = mode.name
                val profile = configureProfile(discovered, mode)
                val access = container.repository.testServerAccess(profile.id)
                val interactive = mode == EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION || mode == EdgeAuthMode.GENERIC_COOKIE_SSO
                when {
                    access.status == ServerAccessStatus.READY -> onContinueToLogin()
                    interactive && (access.responseKind == ServerAccessResponseKind.HTML || access.responseKind == ServerAccessResponseKind.UNAUTHORIZED) -> {
                        container.repository.beginEdgeAccess(profile.id)
                        onInteractiveAuthRequested(InteractiveSessionProvider.EDGE)
                    }
                    else -> errorMessage = "Could not connect to RomM. Check the address and your network, or configure an access profile."
                }
            }.onFailure { error ->
                errorMessage = error.message ?: "Could not connect to RomM. Check the address and your network, or configure an access profile."
            }
            busy = false
        }
    }

    OnboardingFrame(
        step = OnboardingStep.Server,
        title = "Connect",
        subtitle = "",
        onBack = onBack,
        modifier = Modifier.testTag("screen.serverAccess"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldLabel("Server address")
            ThemedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    discovery = null
                    errorMessage = null
                },
                placeholder = "romm.example.com",
                leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { connect() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Server address" }
                    .testTag("serverAccess.serverURL"),
            )
            AccessMethodRow(
                value = if (manualEdgeMode) accessMethodLabel(edgeAuthMode) else "Automatic",
                enabled = !busy,
                onClick = { focus.clearFocus(); showsAccessOptions = true },
            )
            OnboardingError(errorMessage)
            PrimaryButton(
                text = if (busy) "Connecting…" else "Continue",
                onClick = ::connect,
                enabled = serverUrl.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().testTag("serverAccess.connect"),
            )
        }

        if (profiles.isNotEmpty()) {
            ListGroup(title = "Saved servers", meta = profiles.size.toString(), modifier = Modifier.testTag("serverAccess.savedServers")) {
                profiles.forEachIndexed { index, profile ->
                    if (index > 0) ListDivider(inset = 58.dp)
                    ListRow(
                        title = profile.label,
                        subtitle = profile.baseUrl,
                        leading = { RowIcon(Icons.Outlined.Dns, Tone.LIVE) },
                        showChevron = true,
                        onClick = {
                            if (busy) return@ListRow
                            scope.launch {
                                busy = true
                                runCatching { container.repository.activateProfile(profile.id) }
                                    .onSuccess { onProfileSelected() }
                                    .onFailure { errorMessage = it.message ?: "Couldn't switch servers." }
                                busy = false
                            }
                        },
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoleText("Have a setup code from RomM?", theme.type.secondary, color = theme.colors.inkSecondary)
            TextAction(
                "Scan it",
                onClick = { focus.clearFocus(); showsSetupCode = true },
                enabled = !busy,
                modifier = Modifier.testTag("serverAccess.oneTouchSetup"),
            )
        }
        TextAction(
            "Connection details",
            onClick = { showsDetails = true },
            modifier = Modifier.align(Alignment.CenterHorizontally).testTag("serverAccess.details"),
        )

        if (hasDebugTestServer) {
            TextAction(
                "Use debug test server",
                onClick = {
                    serverUrl = BuildConfig.DEBUG_TEST_BASE_URL
                    edgeAuthModeName = EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE.name
                    manualEdgeMode = true
                    serviceClientId = BuildConfig.DEBUG_TEST_CLIENT_ID
                    serviceClientSecret = BuildConfig.DEBUG_TEST_CLIENT_SECRET
                    errorMessage = null
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    if (showsAccessOptions) {
        AccessOptionsSheet(
            automatic = !manualEdgeMode,
            mode = edgeAuthMode,
            clientId = serviceClientId,
            clientSecret = serviceClientSecret,
            hasStoredToken = activeMatchesUrl && container.repository.hasStoredCloudflareServiceCredentials(activeProfile?.id.orEmpty()),
            onDone = { automatic, mode, id, secret ->
                manualEdgeMode = !automatic
                edgeAuthModeName = (if (automatic) discovery?.recommendedEdgeAuthMode ?: EdgeAuthMode.NONE else mode).name
                serviceClientId = id
                serviceClientSecret = secret
                errorMessage = null
                showsAccessOptions = false
            },
            onDismiss = { showsAccessOptions = false },
        )
    }
    if (showsSetupCode) {
        SetupCodeSheet(
            apply = { applyOneTouchConfiguration(container, it) },
            onDone = { showsSetupCode = false; onContinueToLogin() },
            onDismiss = { showsSetupCode = false },
        )
    }
    if (showsDetails) {
        ConnectionDetailsSheet(activeProfile, discovery, onDismiss = { showsDetails = false })
    }
}

/** "Access options … Automatic ›": opens the Access options sheet (iOS wording). */
@Composable
private fun AccessMethodRow(value: String, enabled: Boolean, onClick: () -> Unit) {
    val theme = RommioTheme.current
    val c = theme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .themedSurface(theme, if (theme.isRiso) 0.dp else theme.radius.field, fill = if (theme.isRiso) c.surface else c.surface)
            .selectable(selected = false, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag("serverAccess.accessProfile"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Shield, contentDescription = null, tint = c.inkSecondary, modifier = Modifier.size(20.dp))
        RoleText("Access options", theme.type.body, color = c.ink, modifier = Modifier.weight(1f))
        RoleText(value, theme.type.body, color = c.inkSecondary, maxLines = 1)
        Chevron()
    }
}

/** An onboarding error in `badText`. */
@Composable
internal fun OnboardingError(message: String?) {
    val theme = RommioTheme.current
    message?.let { RoleText(it, theme.type.secondary, color = theme.colors.badText, modifier = Modifier.testTag("onboarding.error")) }
}

/**
 * Access options (sheet from Connect; iOS `AccessProfileSheet`): detect automatically, or choose
 * Direct / Cloudflare login / Service token / Browser login; a service token takes its client ID
 * and secret. Done applies; Cancel leaves the choice unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccessOptionsSheet(
    automatic: Boolean,
    mode: EdgeAuthMode,
    clientId: String,
    clientSecret: String,
    hasStoredToken: Boolean,
    onDone: (automatic: Boolean, mode: EdgeAuthMode, clientId: String, clientSecret: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = RommioTheme.current
    val c = theme.colors
    var auto by rememberSaveable { mutableStateOf(automatic) }
    var selected by rememberSaveable { mutableStateOf(if (automatic && mode == EdgeAuthMode.NONE) EdgeAuthMode.NONE else mode) }
    var id by rememberSaveable { mutableStateOf(clientId) }
    var secret by rememberSaveable { mutableStateOf(clientSecret) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.groundRaised,
        shape = if (theme.isRiso) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = theme.spacing.gutter)
                .padding(bottom = 24.dp)
                .testTag("serverAccess.accessOptions"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("Cancel", onClick = onDismiss)
                RoleText("Access options", theme.type.bodyEmphasis, size = 17f, color = c.ink, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                TextAction("Done", onClick = { onDone(auto, selected, id, secret) }, modifier = Modifier.testTag("serverAccess.accessDone"))
            }
            ListGroup(footer = "Most servers work automatically. Choose a method if your server needs extra access credentials.") {
                ToggleRow(title = "Detect automatically", checked = auto, onCheckedChange = { auto = it },
                    modifier = Modifier.testTag("serverAccess.detectAutomatically"))
            }
            if (!auto) {
                ListGroup(title = "Connection", modifier = Modifier.selectableGroup()) {
                    EdgeAuthMode.entries.forEachIndexed { index, option ->
                        if (index > 0) ListDivider()
                        ListRow(
                            title = accessMethodLabel(option),
                            subtitle = accessMethodDetail(option),
                            trailing = { io.github.bandoracer.rommio.ui.component.RadioMark(option == selected) },
                            modifier = Modifier
                                .selectable(selected = option == selected, role = Role.RadioButton, onClick = { selected = option })
                                .testTag("serverAccess.mode.${option.name.lowercase()}"),
                        )
                    }
                }
                if (selected == EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OnboardingTextField(
                            value = id,
                            onValueChange = { id = it },
                            label = "Client ID",
                            modifier = Modifier.fillMaxWidth().testTag("serverAccess.cloudflareClientID"),
                        )
                        OnboardingTextField(
                            value = secret,
                            onValueChange = { secret = it },
                            label = "Client secret",
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("serverAccess.cloudflareClientSecret"),
                        )
                        if (hasStoredToken) {
                            RoleText("A service token is already saved for this server. Leave the fields empty to keep it.",
                                theme.type.caption, color = c.inkSecondary)
                        }
                    }
                }
                if (selected == EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION || selected == EdgeAuthMode.GENERIC_COOKIE_SSO) {
                    RoleText("A browser will open if your server needs you to sign in before connecting.", theme.type.secondary, color = c.inkSecondary)
                }
            }
        }
    }
}

private fun accessMethodDetail(mode: EdgeAuthMode): String = when (mode) {
    EdgeAuthMode.NONE -> "Rommio reaches RomM directly."
    EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION -> "Sign in to Cloudflare Access in a browser."
    EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE -> "A Cloudflare Access service token on every request."
    EdgeAuthMode.GENERIC_COOKIE_SSO -> "Sign in to your server's login page in a browser."
}

/** Connection details (the old Diagnostics): what the last server check saw. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionDetailsSheet(profile: ServerProfile?, discovery: AuthDiscoveryResult?, onDismiss: () -> Unit) {
    val theme = RommioTheme.current
    val access = profile?.serverAccess
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.colors.groundRaised,
        shape = if (theme.isRiso) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = theme.spacing.gutter)
                .padding(bottom = 24.dp)
                .testTag("serverAccess.detailsSheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RoleText("Connection details", theme.type.headline, color = theme.colors.ink)
            ListGroup {
                ListRow(title = "Server", value = profile?.label ?: "Not set up")
                ListDivider()
                ListRow(title = "Access method", value = profile?.let { accessMethodLabel(it.edgeAuthMode) } ?: "—")
                ListDivider()
                ListRow(title = "Detected", value = discovery?.let { detected(it) } ?: "—")
                ListDivider()
                ListRow(title = "HTTP status", value = access?.lastHttpStatus?.toString() ?: "Not tested")
                ListDivider()
                ListRow(title = "Response", value = when (access?.lastResponseKind) {
                    ServerAccessResponseKind.JSON -> "JSON"
                    ServerAccessResponseKind.HTML -> "HTML"
                    ServerAccessResponseKind.NETWORK_ERROR -> "Network error"
                    ServerAccessResponseKind.UNAUTHORIZED -> "Unauthorized"
                    null -> "—"
                })
                ListDivider()
                ListRow(title = "Cookies seen", value = access?.cookieNamesSeen?.takeIf { it.isNotEmpty() }?.joinToString() ?: "None")
                ListDivider()
                ListRow(title = "Last checked", value = access?.lastTestedAt?.let { stamp ->
                    runCatching { io.github.bandoracer.rommio.ui.component.Fmt.relative(java.time.Instant.parse(stamp).toEpochMilli()) }.getOrDefault(stamp)
                } ?: "Never")
            }
            access?.lastError?.let {
                RoleText(it, theme.type.secondary, color = if (access.status == ServerAccessStatus.READY) theme.colors.inkSecondary else theme.colors.badText)
            }
        }
    }
}

private fun detected(discovery: AuthDiscoveryResult): String = when {
    discovery.capabilities.cloudflareAccessDetected -> "Cloudflare Access"
    discovery.capabilities.genericCookieSsoDetected -> "Login page"
    else -> "Direct"
}
