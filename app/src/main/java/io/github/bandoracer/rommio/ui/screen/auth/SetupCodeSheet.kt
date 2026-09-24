package io.github.bandoracer.rommio.ui.screen.auth

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.BuildConfig
import io.github.bandoracer.rommio.data.setup.OneTouchSetupClient
import io.github.bandoracer.rommio.data.setup.OneTouchSetupException
import io.github.bandoracer.rommio.data.setup.OneTouchSetupService
import io.github.bandoracer.rommio.data.setup.OneTouchSetupSession
import io.github.bandoracer.rommio.data.setup.OneTouchSetupState
import io.github.bandoracer.rommio.data.setup.SetupConfiguration
import io.github.bandoracer.rommio.data.setup.SetupPairing
import io.github.bandoracer.rommio.model.AuthCapabilities
import io.github.bandoracer.rommio.model.AuthDiscoveryResult
import io.github.bandoracer.rommio.model.CloudflareServiceCredentials
import io.github.bandoracer.rommio.model.EdgeAuthMode
import io.github.bandoracer.rommio.model.OriginAuthMode
import io.github.bandoracer.rommio.model.ServerAccessStatus
import io.github.bandoracer.rommio.ui.component.DisplayText
import io.github.bandoracer.rommio.ui.component.OverArt
import io.github.bandoracer.rommio.ui.component.PageField
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.TextAction
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Stores an approved configuration the way manual setup does (the iOS app model's
 * `applyOneTouchConfiguration`): a service-token profile in the profile's secret store, then the
 * RomM heartbeat. Only a ready server counts; the caller moves on to Sign in.
 */
internal suspend fun applyOneTouchConfiguration(container: AppContainer, configuration: SetupConfiguration) {
    configuration.validate()
    val repository = container.repository
    val discovery = AuthDiscoveryResult(
        baseUrl = configuration.rommUrl,
        capabilities = AuthCapabilities(cloudflareAccessDetected = true, rommTokenAvailable = true),
        recommendedEdgeAuthMode = EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
        recommendedOriginAuthMode = OriginAuthMode.ROMM_BEARER_PASSWORD,
        warnings = emptyList(),
    )
    val profile = repository.configureServerProfile(
        baseUrl = configuration.rommUrl,
        edgeAuthMode = EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
        originAuthMode = OriginAuthMode.ROMM_BEARER_PASSWORD,
        discoveryResult = discovery,
        makeActive = true,
    )
    repository.setCloudflareServiceCredentials(profile.id, CloudflareServiceCredentials(configuration.clientId, configuration.clientSecret))
    val access = repository.testServerAccess(profile.id)
    if (access.status != ServerAccessStatus.READY) throw OneTouchSetupException(OneTouchSetupException.Kind.UNAVAILABLE)
}

/** This build's setup service: production in release builds, a local mock in debug builds. */
internal fun buildSetupService(): OneTouchSetupService? =
    OneTouchSetupClient.forBuild(BuildConfig.SETUP_SERVICE_URL, BuildConfig.SETUP_APPROVAL_ORIGIN, debug = BuildConfig.DEBUG)

private fun deviceName(context: Context): String =
    runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android"

/**
 * Setup code (docs/theme-layouts.md "Setup", sheet from Welcome and Connect): the setup service's
 * code, a QR code of its approval link and "Approve on This Device" (a Custom Tab); approval on any
 * signed-in device adds the server and its access token, checks RomM, then [onDone] moves on to Sign
 * in. Dismissing cancels the code; "Try Again" allocates a new one. Copy follows iOS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupCodeSheet(
    /** Stores an approved configuration (production: [applyOneTouchConfiguration]). */
    apply: suspend (SetupConfiguration) -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    service: OneTouchSetupService? = remember { buildSetupService() },
) {
    val context = LocalContext.current
    val theme = RommioTheme.current
    val c = theme.colors
    val currentDone by rememberUpdatedState(onDone)
    val currentApply by rememberUpdatedState(apply)
    // Its own scope: the cancel request must still go out after the sheet leaves the composition.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val session = remember(service) {
        service?.let { OneTouchSetupSession(it, scope, apply = { configuration -> currentApply(configuration) }) }
    }
    val state = session?.state?.collectAsStateWithLifecycle()?.value
        ?: OneTouchSetupState.Failed(OneTouchSetupException.Kind.UNAVAILABLE.message)
    val name = remember { deviceName(context) }
    LaunchedEffect(session) { session?.start(name) }
    DisposableEffect(session) { onDispose { session?.cancel() } }
    LaunchedEffect(state) { if (state is OneTouchSetupState.Done) currentDone() }
    val applying = state is OneTouchSetupState.Applying
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || session?.isApplying != true },
    )
    ModalBottomSheet(
        onDismissRequest = { if (session?.isApplying != true) { session?.cancel(); onDismiss() } },
        sheetState = sheetState,
        containerColor = c.ground,
        shape = if (theme.isRiso) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        // The welcome field runs under the handle too (no seam between the handle and the page).
        dragHandle = null,
    ) {
        Box(Modifier.fillMaxWidth().testTag("setupCode.sheet")) {
            PageField(Modifier.matchParentSize(), style = PageFieldStyle.WELCOME)
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BottomSheetDefaults.DragHandle(color = c.inkTertiary)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RoleText("Setup code", theme.type.bodyEmphasis, size = 17f, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                    TextAction(
                        "Cancel",
                        enabled = !applying,
                        onClick = { session?.cancel(); onDismiss() },
                        modifier = Modifier.testTag("oneTouch.cancel"),
                    )
                }
                AppMark(size = if (theme.isRiso) 16.dp else 36.dp)
                DisplayText(
                    "Set up this device",
                    role = if (theme.isRiso) theme.type.display else theme.type.title,
                    size = if (theme.isRiso) 40f else 30f,
                    maxLines = 2,
                )
                val pairing = when (state) {
                    is OneTouchSetupState.WaitingForApproval -> state.pairing
                    is OneTouchSetupState.Applying -> state.pairing
                    else -> null
                }
                if (pairing != null) {
                    // Short, wide sheets put the QR code beside the code (as iOS does).
                    BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
                        if (maxWidth >= 520.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { CodeColumn(pairing, applying, context) }
                                QrColumn(pairing)
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
                                CodeColumn(pairing, applying, context)
                                QrColumn(pairing)
                            }
                        }
                    }
                }
                if (state is OneTouchSetupState.Failed) {
                    RoleText(state.message, theme.type.secondary, color = c.badText, textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("oneTouch.error"))
                    PrimaryButton(
                        "Try Again",
                        onClick = { session?.retry(name) },
                        large = false,
                        enabled = session != null,
                        modifier = Modifier.testTag("oneTouch.retry"),
                    )
                } else if (state !is OneTouchSetupState.Done) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.inkSecondary)
                        RoleText(
                            when (state) {
                                is OneTouchSetupState.Applying -> "Connecting to RomM…"
                                is OneTouchSetupState.WaitingForApproval -> "Waiting for approval…"
                                else -> "Creating setup code…"
                            },
                            theme.type.secondary,
                            color = c.inkSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeColumn(pairing: SetupPairing, applying: Boolean, context: Context) {
    val theme = RommioTheme.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        RoleText(
            "Approve access to your server. Your connection settings will be added automatically.",
            theme.type.body,
            color = theme.colors.inkSecondary,
            textAlign = TextAlign.Center,
        )
        SelectionContainer {
            RoleText(pairing.code, theme.type.numeral, size = 32f, color = theme.colors.ink, modifier = Modifier.testTag("oneTouch.code"))
        }
        PrimaryButton(
            "Approve on This Device",
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            enabled = !applying,
            onClick = { openApproval(context, pairing.approveUrl) },
            modifier = Modifier.fillMaxWidth().testTag("oneTouch.approve"),
        )
    }
}

/** The approval link as a QR code on white (scanners need dark on light) and as text. */
@Composable
private fun QrColumn(pairing: SetupPairing) {
    val theme = RommioTheme.current
    val context = LocalContext.current
    val qr = remember(pairing.approveUrl) { qrBitmap(pairing.approveUrl, 512) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (qr != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(if (theme.isRiso) 0.dp else 12.dp))
                    .background(OverArt.rim)
                    .then(if (theme.isRiso) Modifier.border(1.5.dp, theme.colors.ink) else Modifier)
                    .padding(12.dp)
                    .semantics { contentDescription = "Scan on another device to approve setup" }
                    .testTag("oneTouch.qr"),
            ) {
                Image(qr, contentDescription = null, filterQuality = FilterQuality.None, modifier = Modifier.size(170.dp))
            }
        }
        RoleText("Or scan the code on another device.", theme.type.caption, color = theme.colors.inkSecondary, textAlign = TextAlign.Center)
        TextAction(pairing.approveUrl, onClick = { openApproval(context, pairing.approveUrl) })
    }
}

/** Opens the approval page in a Custom Tab (the browser when none is available). */
private fun openApproval(context: Context, url: String) {
    val uri = Uri.parse(url)
    runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }
        .onFailure { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) } }
}

/** A QR code of [text] (ZXing, error correction M, 1-module quiet zone). */
internal fun qrBitmap(text: String, size: Int): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix.get(index % matrix.width, index / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    android.graphics.Bitmap.createBitmap(pixels, matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()
