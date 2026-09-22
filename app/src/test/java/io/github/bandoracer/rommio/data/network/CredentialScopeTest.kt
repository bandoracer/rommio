package io.github.bandoracer.rommio.data.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class CredentialScopeTest {
    @Test fun deploymentBoundaryIncludesSchemeHostPortAndPath() {
        val scope = CredentialScope("https://ROMM.example:443/romm/")
        assertTrue(scope.contains("https://romm.example/romm/api/me".toHttpUrl()))
        for (url in listOf(
            "http://romm.example/romm/api/me", "https://romm.example:8443/romm/api/me",
            "https://images.example/romm/api/me", "https://romm.example/romm-other/api/me",
            "https://romm.example/romm/../other", "https://romm.example/romm/a%2f..%2fother",
            "https://user:password@romm.example/romm/api/me",
        )) assertFalse(url, scope.contains(url.toHttpUrl()))
    }

    @Test fun realRedirectDoesNotForwardCredentialsToAnotherOrigin() {
        MockWebServer().use { trusted ->
            MockWebServer().use { external ->
                external.enqueue(MockResponse().setBody("image"))
                trusted.enqueue(MockResponse().setResponseCode(302).addHeader("Location", external.url("/image")))
                val request = credentialed(trusted.url("/romm/cover").toString(), trusted.url("/romm").toString())
                val client = OkHttpClient.Builder().addNetworkInterceptor(CredentialScopeInterceptor()).build()
                client.newCall(request).execute().use { assertEquals("image", it.body!!.string()) }
                val originRequest = trusted.takeRequest(2, TimeUnit.SECONDS)!!
                val externalRequest = external.takeRequest(2, TimeUnit.SECONDS)!!
                for (name in CredentialScope.CREDENTIAL_HEADERS) {
                    assertEquals(name, "secret", originRequest.getHeader(name))
                    assertNull(name, externalRequest.getHeader(name))
                }
            }
        }
    }

    @Test fun sameHostRedirectOutsideDeploymentAlsoStripsCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/another-app")))
            server.enqueue(MockResponse().setBody("public"))
            val client = OkHttpClient.Builder().addNetworkInterceptor(CredentialScopeInterceptor()).build()
            client.newCall(credentialed(server.url("/romm/api/me").toString(), server.url("/romm").toString()))
                .execute().close()
            server.takeRequest(2, TimeUnit.SECONDS)
            val redirected = server.takeRequest(2, TimeUnit.SECONDS)!!
            CredentialScope.CREDENTIAL_HEADERS.forEach { assertNull(it, redirected.getHeader(it)) }
        }
    }

    @Test fun sensitiveBodyCannotLeaveScopeEvenIfHeadersCouldBeRemoved() {
        val scope = CredentialScope("https://romm.example")
        val request = Request.Builder().url("https://elsewhere.example/api/token")
            .post("password=private".toRequestBody()).build()
        assertThrows(IOException::class.java) { scope.sanitize(request) }
    }

    private fun credentialed(url: String, base: String): Request = Request.Builder().url(url)
        .tag(CredentialScope::class.java, CredentialScope(base))
        .apply { CredentialScope.CREDENTIAL_HEADERS.forEach { header(it, "secret") } }.build()
}
