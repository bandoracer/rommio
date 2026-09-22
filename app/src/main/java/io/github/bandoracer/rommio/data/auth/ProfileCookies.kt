package io.github.bandoracer.rommio.data.auth

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.bandoracer.rommio.data.network.CredentialScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.util.UUID

/** An immutable browser-store generation, attached to each native request. */
data class ProfileCookieContext(val profileId: String, val baseUrl: String, val storeName: String)

/** Native requests and the embedded browser share only this profile's WebView data store. */
class ProfileCookies(
    private val readName: (String, String) -> String?,
    private val writeName: (String, String, String) -> Unit,
) {
    private val managers = mutableMapOf<String, CookieManager>() // Main-thread owned.

    suspend fun context(profileId: String, baseUrl: String): ProfileCookieContext? = withContext(Dispatchers.Main) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return@withContext null
        val name = readName(profileId, baseUrl) ?: "rommio-${UUID.randomUUID()}".also { writeName(profileId, baseUrl, it) }
        manager(name)
        ProfileCookieContext(profileId, baseUrl, name)
    }

    suspend fun requireBrowserContext(profileId: String, baseUrl: String): ProfileCookieContext =
        requireNotNull(context(profileId, baseUrl)) { "Update Android System WebView to use isolated browser sign-in for this server." }

    suspend fun header(context: ProfileCookieContext?, url: HttpUrl): String? = withContext(Dispatchers.Main) {
        if (context == null || !isCurrent(context) || !CredentialScope(context.baseUrl).contains(url)) return@withContext null
        manager(context.storeName).getCookie(url.toString())?.takeIf { it.isNotBlank() }
    }

    suspend fun persist(context: ProfileCookieContext?, url: HttpUrl, cookies: List<String>) = withContext(Dispatchers.Main) {
        if (context == null || !isCurrent(context) || !CredentialScope(context.baseUrl).contains(url)) return@withContext
        val jar = manager(context.storeName)
        cookies.forEach { cookie ->
            suspendCancellableCoroutine<Unit> { continuation ->
                jar.setCookie(url.toString(), cookie) { if (continuation.isActive) continuation.resume(Unit) }
            }
        }
        jar.flush()
    }

    /** Rotate before clearing: late responses and old WebViews can only affect the retired generation. */
    suspend fun clear(profileId: String, baseUrl: String) = withContext(Dispatchers.Main) {
        val oldName = readName(profileId, baseUrl)
        writeName(profileId, baseUrl, "rommio-${UUID.randomUUID()}")
        if (oldName != null && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val old = ProfileStore.getInstance().getOrCreateProfile(oldName)
            old.cookieManager.removeAllCookies(null)
            old.cookieManager.flush()
            old.webStorage.deleteAllData()
            managers.remove(oldName)
        }
    }

    suspend fun requireCurrent(context: ProfileCookieContext) = withContext(Dispatchers.Main) {
        check(isCurrent(context)) { "This sign-in attempt expired. Start sign-in again." }
    }

    fun attach(webView: WebView, context: ProfileCookieContext) {
        check(isCurrent(context)) { "This sign-in attempt expired. Start sign-in again." }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.setProfile(webView, context.storeName)
            manager(context.storeName).setAcceptThirdPartyCookies(webView, true)
        } else { error("Isolated WebView profiles are unavailable") }
    }

    private fun isCurrent(context: ProfileCookieContext) = readName(context.profileId, context.baseUrl) == context.storeName
    private fun manager(name: String): CookieManager = managers.getOrPut(name) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile(name).cookieManager.apply { setAcceptCookie(true) }
        } else { error("Isolated WebView profiles are unavailable") }
    }
}
