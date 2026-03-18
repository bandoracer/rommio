package io.github.mattsays.rommnative.ui.screen.auth

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.model.AuthStatus
import io.github.mattsays.rommnative.model.InteractiveSessionConfig
import io.github.mattsays.rommnative.model.InteractiveSessionProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractiveAuthScreen(
    container: AppContainer,
    provider: InteractiveSessionProvider,
    onCancel: () -> Unit,
    onEdgeFinished: () -> Unit,
    onOriginFinished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val activeProfile by container.repository.activeProfileFlow().collectAsStateWithLifecycle(initialValue = null)
    var config by remember { mutableStateOf<InteractiveSessionConfig?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isCompleting by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("Protected login") }
    var pageSubtitle by remember { mutableStateOf("Finish the login step in the web view, then return to the app.") }

    LaunchedEffect(activeProfile?.id, provider) {
        val profile = activeProfile
        if (profile == null) {
            onCancel()
            return@LaunchedEffect
        }
        runCatching {
            container.repository.getInteractiveSessionConfig(profile.id, provider)
        }.fold(
            onSuccess = { loaded ->
                config = loaded
                errorMessage = null
                pageTitle = loaded.title
                pageSubtitle = if (provider == InteractiveSessionProvider.EDGE) {
                    "Finish the protected server login here, then return to run the native access test."
                } else {
                    "Finish the RomM sign-in here. The app will validate the session when the flow returns."
                }
            },
            onFailure = { error ->
                errorMessage = error.message ?: "Unable to start the interactive authentication flow."
            },
        )
    }

    fun finishEdge() {
        val loadedConfig = config ?: return
        if (isCompleting) return
        scope.launch {
            isCompleting = true
            runCatching {
                container.repository.completeEdgeAccessAttempt(loadedConfig.profileId)
            }.fold(
                onSuccess = { onEdgeFinished() },
                onFailure = { error -> errorMessage = error.message ?: "Unable to finish the protected login flow." },
            )
            isCompleting = false
        }
    }

    fun finishOrigin() {
        val loadedConfig = config ?: return
        if (isCompleting) return
        scope.launch {
            isCompleting = true
            runCatching {
                container.repository.completeInteractiveLogin(loadedConfig.profileId, provider)
            }.fold(
                onSuccess = { status ->
                    if (status == AuthStatus.CONNECTED) {
                        onOriginFinished()
                    } else {
                        errorMessage = "RomM sign-in is still incomplete. Finish the login flow and try again."
                    }
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Unable to finish the RomM sign-in flow."
                },
            )
            isCompleting = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pageTitle) },
                actions = {
                    TextButton(onClick = onCancel, enabled = !isCompleting) {
                        Text("Cancel")
                    }
                },
            )
        },
    ) { padding ->
        val loadedConfig = config
        if (loadedConfig == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text(
                    text = errorMessage ?: "Preparing web authentication…",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                if (errorMessage != null) {
                    TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Back")
                    }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                text = pageSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        if (provider == InteractiveSessionProvider.EDGE) {
                            finishEdge()
                        } else {
                            finishOrigin()
                        }
                    },
                    enabled = !isCompleting,
                ) {
                    Text(if (provider == InteractiveSessionProvider.EDGE) "Return to access test" else "Done")
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                if (url == null) return
                                if (provider == InteractiveSessionProvider.ORIGIN && isInteractiveOriginSuccess(url, loadedConfig)) {
                                    finishOrigin()
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (url == null) return
                                if (provider == InteractiveSessionProvider.ORIGIN && isInteractiveOriginSuccess(url, loadedConfig)) {
                                    finishOrigin()
                                }
                            }
                        }
                        loadUrl(loadedConfig.startUrl)
                    }
                },
            )
        }
    }
}

private fun isInteractiveOriginSuccess(url: String, config: InteractiveSessionConfig): Boolean {
    val cleanUrl = url.removeSuffix("/").lowercase()
    val expectedBase = config.expectedBaseUrl.removeSuffix("/").lowercase()
    return cleanUrl == expectedBase ||
        (cleanUrl.startsWith(expectedBase) &&
            !cleanUrl.contains("/api/login") &&
            !cleanUrl.contains("/api/oauth") &&
            !cleanUrl.contains("/auth/"))
}
