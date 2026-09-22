package io.github.bandoracer.rommio.data.auth

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import io.github.bandoracer.rommio.data.network.CookiePersistenceInterceptor
import io.github.bandoracer.rommio.data.network.CredentialScope
import io.github.bandoracer.rommio.data.network.CredentialScopeInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProfileCookiesTest {
    private val names = mutableMapOf<Pair<String, String>, String>()
    private val cookies = ProfileCookies({ p, b -> names[p to b] }, { p, b, name -> names[p to b] = name })
    private val base = "https://romm.example/romm"
    private suspend fun context(profile: String) = cookies.requireBrowserContext(profile + UUID.randomUUID(), base)

    @Test fun sameHostProfilesAndBasePathsHaveSeparateCookies() = runBlocking {
        val a = context("A"); val b = context("B")
        try {
            cookies.persist(a, "$base/api/users/me".toHttpUrl(), listOf("session=A; Path=/; Secure; HttpOnly"))
            cookies.persist(b, "$base/api/users/me".toHttpUrl(), listOf("session=B; Path=/; Secure; HttpOnly"))
            assertEquals("session=A", cookies.header(a, "$base/api/users/me".toHttpUrl()))
            assertEquals("session=B", cookies.header(b, "$base/api/users/me".toHttpUrl()))
            assertNull(cookies.header(a, "https://romm.example/other/api/users/me".toHttpUrl()))
            assertNull(cookies.header(a, "http://romm.example/romm/api/users/me".toHttpUrl()))
            val otherPath = cookies.requireBrowserContext(a.profileId, "https://romm.example/other")
            assertNull(cookies.header(otherPath, "https://romm.example/other/api/users/me".toHttpUrl()))
        } finally { cookies.clear(a.profileId, base); cookies.clear(b.profileId, base) }
    }

    @Test fun logoutFencesLateResponsesAndDoesNotClearAnotherProfile() = runBlocking {
        val a = context("A"); val b = context("B")
        try {
            cookies.persist(a, "$base/api/users/me".toHttpUrl(), listOf("session=A; Path=/"))
            cookies.persist(b, "$base/api/users/me".toHttpUrl(), listOf("session=B; Path=/"))
            cookies.clear(a.profileId, base)
            cookies.persist(a, "$base/api/users/me".toHttpUrl(), listOf("session=LATE; Path=/"))
            val replacement = cookies.requireBrowserContext(a.profileId, base)
            assertNotEquals(a.storeName, replacement.storeName)
            assertNull(cookies.header(a, "$base/api/users/me".toHttpUrl()))
            assertNull(cookies.header(replacement, "$base/api/users/me".toHttpUrl()))
            assertEquals("session=B", cookies.header(b, "$base/api/users/me".toHttpUrl()))
        } finally { cookies.clear(a.profileId, base); cookies.clear(b.profileId, base) }
    }

    @Test fun browserUsesSameProfileJarAndRecreationRetainsOwnership() = runBlocking {
        val a = context("A")
        try {
            withContext(Dispatchers.Main) {
                val web = WebView(ApplicationProvider.getApplicationContext())
                try {
                    cookies.attach(web, a)
                    assertEquals(a.storeName, WebViewCompat.getProfile(web).name)
                } finally { web.destroy() }
            }
            cookies.persist(a, "$base/api/users/me".toHttpUrl(), listOf("session=A; Path=/; Max-Age=3600"))
            val recreated = ProfileCookies({ p, b -> names[p to b] }, { p, b, name -> names[p to b] = name })
            val same = recreated.requireBrowserContext(a.profileId, base)
            assertEquals(a.storeName, same.storeName)
            assertEquals("session=A", recreated.header(same, "$base/api/users/me".toHttpUrl()))
        } finally { cookies.clear(a.profileId, base) }
    }

    @Test fun realRedirectUsesNewCookieAndExternalResponseCannotPoisonProfile() = runBlocking {
        val origin = MockWebServer(); val external = MockWebServer()
        origin.start(); external.start()
        val serverBase = origin.url("/romm").toString()
        val a = cookies.requireBrowserContext(UUID.randomUUID().toString(), serverBase)
        try {
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/romm/api/me").addHeader("Set-Cookie", "session=A; Path=/romm"))
            origin.enqueue(MockResponse().setBody("OK"))
            val client = OkHttpClient.Builder().addNetworkInterceptor(CredentialScopeInterceptor()).addNetworkInterceptor(CookiePersistenceInterceptor(cookies)).build()
            fun request() = Request.Builder().url("$serverBase/start").tag(CredentialScope::class.java, CredentialScope(serverBase)).tag(ProfileCookieContext::class.java, a).build()
            withContext(Dispatchers.IO) { client.newCall(request()).execute().use { assertEquals(200, it.code) } }
            origin.takeRequest(); assertEquals("session=A", origin.takeRequest().getHeader("Cookie"))
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", external.url("/romm/api/me")))
            external.enqueue(MockResponse().setBody("OK").addHeader("Set-Cookie", "session=EVIL; Path=/"))
            withContext(Dispatchers.IO) { client.newCall(request()).execute().close() }
            assertNull(external.takeRequest().getHeader("Cookie"))
            assertEquals("session=A", cookies.header(a, "$serverBase/api/me".toHttpUrl()))
        } finally { cookies.clear(a.profileId, serverBase); origin.shutdown(); external.shutdown() }
    }
}
