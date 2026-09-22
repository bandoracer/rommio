package io.github.bandoracer.rommio.data.network

import io.github.bandoracer.rommio.model.RequestDecoration
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AuthRequestInterceptorTest {
    @Test fun rejectedReadRetriesOnceWithNewTokenButMutationsNeverReplay() {
        for (method in listOf("GET", "HEAD", "POST", "PUT", "DELETE")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(401))
                server.enqueue(MockResponse().setResponseCode(401))
                var token = "old"
                var refreshes = 0
                val client = OkHttpClient.Builder().addInterceptor(AuthRequestInterceptor(
                    decorate = { url -> RequestDecoration(url, mapOf("Authorization" to "Bearer $token"), credentialBaseUrl = server.url("/").toString()) },
                    refreshRejected = { _, rejected -> assertEquals("Bearer old", rejected); refreshes++; token = "new"; true },
                )).build()
                val request = Request.Builder().url(server.url("/api/resource"))
                    .method(method, if (method in listOf("POST", "PUT")) "body".toRequestBody() else null).build()
                client.newCall(request).execute().use { assertEquals(401, it.code) }
                val read = method in listOf("GET", "HEAD")
                assertEquals(if (read) 1 else 0, refreshes)
                assertEquals(if (read) 2 else 1, server.requestCount)
                assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
                if (read) assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
            }
        }
    }

    @Test fun edgeLoginPageDoesNotConsumeOriginRefreshToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "text/html").setBody("<html>Sign in</html>"))
            var refreshes = 0
            val client = OkHttpClient.Builder().addInterceptor(AuthRequestInterceptor(
                decorate = { url -> RequestDecoration(url, mapOf("Authorization" to "Bearer old"), credentialBaseUrl = server.url("/").toString()) },
                refreshRejected = { _, _ -> refreshes++; true },
            )).build()
            client.newCall(Request.Builder().url(server.url("/api/resource")).build()).execute().use { assertEquals(401, it.code) }
            assertEquals(0, refreshes)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun externalRedirectCannotTriggerOriginRefresh() {
        MockWebServer().use { origin ->
            MockWebServer().use { external ->
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", external.url("/image")))
                external.enqueue(MockResponse().setResponseCode(401))
                var refreshes = 0
                val client = OkHttpClient.Builder().addNetworkInterceptor(CredentialScopeInterceptor())
                    .addInterceptor(AuthRequestInterceptor(
                        decorate = { url -> RequestDecoration(url, mapOf("Authorization" to "Bearer old"), credentialBaseUrl = origin.url("/").toString()) },
                        refreshRejected = { _, _ -> refreshes++; true },
                    )).build()
                client.newCall(Request.Builder().url(origin.url("/asset")).build()).execute().use { assertEquals(401, it.code) }
                assertEquals(0, refreshes)
                assertNull(external.takeRequest().getHeader("Authorization"))
            }
        }
    }
}
