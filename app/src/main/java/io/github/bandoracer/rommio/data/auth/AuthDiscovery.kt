package io.github.bandoracer.rommio.data.auth

import android.util.Log
import io.github.bandoracer.rommio.model.AuthCapabilities
import io.github.bandoracer.rommio.model.AuthDiscoveryResult
import io.github.bandoracer.rommio.model.EdgeAuthMode
import io.github.bandoracer.rommio.model.OriginAuthMode
import io.github.bandoracer.rommio.util.normalizeServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

private data class ProbeResult(
    val status: Int,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

class AuthDiscovery(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    suspend fun discover(rawBaseUrl: String): AuthDiscoveryResult {
        return withContext(Dispatchers.IO) {
            val baseUrl = normalizeServerUrl(rawBaseUrl)
            val warnings = mutableListOf<String>()
            val capabilities = AuthCapabilities()

            val rootProbe = probe(baseUrl)
            val oidcProbe = probe("$baseUrl/api/login/openid")
            val tokenProbe = postProbe("$baseUrl/api/token")

            if (rootProbe == null && oidcProbe == null && tokenProbe == null) {
                error("Unable to reach the server.")
            }

            var nextCapabilities = capabilities

            rootProbe?.let { probe ->
                val body = probe.body.lowercase()
                val finalUrl = probe.url.lowercase()
                val serverHeader = probe.headers["server"].orEmpty().lowercase()
                if (finalUrl.contains("/cdn-cgi/access") ||
                    body.contains("cloudflare access") ||
                    body.contains("cdn-cgi/access") ||
                    body.contains("cf-access") ||
                    serverHeader.contains("cloudflare")
                ) {
                    nextCapabilities = nextCapabilities.copy(cloudflareAccessDetected = true)
                }

                if (!nextCapabilities.cloudflareAccessDetected &&
                    (probe.status == 401 || probe.status == 403 || containsAny(body, listOf("sign in", "log in", "single sign-on", "authentication required")))
                ) {
                    nextCapabilities = nextCapabilities.copy(genericCookieSsoDetected = true)
                }

                if (nextCapabilities.cloudflareAccessDetected &&
                    containsAny(body, listOf("warp", "device posture", "managed device", "private network"))
                ) {
                    nextCapabilities = nextCapabilities.copy(requiresPrivateOverlay = true)
                    warnings += "This server appears to require WARP or private-network posture. Use private-overlay access instead of clientless Cloudflare login."
                }
            } ?: warnings.add("The server could not be reached during discovery. Manual auth mode selection may be required.")

            if (oidcProbe != null && oidcProbe.status != 404) {
                nextCapabilities = nextCapabilities.copy(rommOidcAvailable = true)
            }
            if (tokenProbe != null) {
                nextCapabilities = nextCapabilities.copy(rommTokenAvailable = tokenProbe.status != 404)
            }

            val edgeMode = when {
                nextCapabilities.requiresPrivateOverlay -> EdgeAuthMode.NONE
                nextCapabilities.cloudflareAccessDetected -> EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION
                nextCapabilities.genericCookieSsoDetected -> EdgeAuthMode.GENERIC_COOKIE_SSO
                else -> EdgeAuthMode.NONE
            }
            val originMode = when {
                nextCapabilities.rommTokenAvailable -> OriginAuthMode.ROMM_BEARER_PASSWORD
                nextCapabilities.rommOidcAvailable -> OriginAuthMode.ROMM_OIDC_SESSION
                else -> OriginAuthMode.ROMM_BASIC_LEGACY
            }

            AuthDiscoveryResult(
                baseUrl = baseUrl,
                capabilities = nextCapabilities,
                recommendedEdgeAuthMode = edgeMode,
                recommendedOriginAuthMode = originMode,
                warnings = warnings,
            )
        }
    }

    private fun containsAny(body: String, snippets: List<String>): Boolean {
        return snippets.any { body.contains(it) }
    }

    private fun probe(url: String): ProbeResult? {
        return runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .get()
                    .build(),
            ).execute().use { response ->
                ProbeResult(
                    status = response.code,
                    url = response.header("location") ?: response.request.url.toString(),
                    headers = response.headers.toMultimap().mapValues { entry -> entry.value.lastOrNull().orEmpty() },
                    body = response.body?.string().orEmpty(),
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Discovery probe failed for $url", error)
        }.getOrNull()
    }

    private fun postProbe(url: String): ProbeResult? {
        return runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .post(FormBody.Builder().add("grant_type", "password").build())
                    .build(),
            ).execute().use { response ->
                ProbeResult(
                    status = response.code,
                    url = response.header("location") ?: response.request.url.toString(),
                    headers = response.headers.toMultimap().mapValues { entry -> entry.value.lastOrNull().orEmpty() },
                    body = response.body?.string().orEmpty(),
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Discovery token probe failed for $url", error)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "AuthDiscovery"
    }
}
