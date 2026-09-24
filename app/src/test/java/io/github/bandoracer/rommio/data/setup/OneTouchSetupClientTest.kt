package io.github.bandoracer.rommio.data.setup

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The device API contract (setup-service/README.md) against a local mock; never the real service. */
class OneTouchSetupClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client(approvalOrigin: String = server.url("/").toString().removeSuffix("/"), local: Boolean = true) =
        OneTouchSetupClient(server.url("/"), approvalOrigin.toHttpUrl(), allowLocalHttp = local)

    private val code = "ABCDE2"
    private val secret = "S".repeat(32)
    private fun startBody(approve: String = server.url("/a/$code").toString()) =
        """{"code":"$code","secret":"$secret","approveUrl":"$approve","expiresInSeconds":600,"pollIntervalSeconds":3}"""

    private suspend fun expectFailure(kind: OneTouchSetupException.Kind, block: suspend () -> Unit) {
        try { block(); fail("Expected $kind") } catch (error: OneTouchSetupException) { assertEquals(kind, error.kind) }
    }

    @Test
    fun startSendsTheDeviceNameAndValidatesThePairing() = runBlocking {
        server.enqueue(MockResponse().setBody(startBody()))
        val pairing = client().start("Pixel 9")
        assertEquals(code, pairing.code)
        assertEquals(3, pairing.pollIntervalSeconds)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/device/start", request.path)
        assertEquals("""{"deviceName":"Pixel 9"}""", request.body.readUtf8())
    }

    @Test
    fun anApprovalLinkOnAnotherOriginIsRejected() = runBlocking {
        server.enqueue(MockResponse().setBody(startBody(approve = "https://evil.example/a/$code")))
        expectFailure(OneTouchSetupException.Kind.INVALID_RESPONSE) { client().start("Pixel") }
        server.enqueue(MockResponse().setBody(startBody(approve = server.url("/b/$code").toString())))
        expectFailure(OneTouchSetupException.Kind.INVALID_RESPONSE) { client().start("Pixel") }
    }

    @Test
    fun pollingKeepsTheSecretInTheBodyAndReadsEachState() = runBlocking {
        val pairing = SetupPairing(code, secret, server.url("/a/$code").toString(), 600, 3)
        server.enqueue(MockResponse().setBody("""{"state":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"state":"denied"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"state":"expired"}"""))
        server.enqueue(MockResponse().setBody(
            """{"state":"approved","rommUrl":"https://romm.example.com","accessProfile":{"type":"serviceToken","clientId":"id","clientSecret":"shh"}}""",
        ))
        val setup = client()
        assertEquals(SetupPollResult.Pending, setup.poll(pairing))
        assertEquals(SetupPollResult.Denied, setup.poll(pairing))
        assertEquals(SetupPollResult.Expired, setup.poll(pairing))
        val approved = setup.poll(pairing) as SetupPollResult.Approved
        assertEquals(SetupConfiguration("https://romm.example.com", "id", "shh"), approved.configuration)
        repeat(4) {
            val request = server.takeRequest()
            assertEquals("/device/poll", request.path)
            assertFalse("No secret in the URL", request.path!!.contains(secret))
            assertTrue(request.body.readUtf8().contains(secret))
        }
    }

    @Test
    fun anApprovalWithoutAServiceTokenIsInvalid() = runBlocking {
        val pairing = SetupPairing(code, secret, server.url("/a/$code").toString(), 600, 3)
        server.enqueue(MockResponse().setBody("""{"state":"approved","rommUrl":"https://romm.example.com","accessProfile":{"type":"password"}}"""))
        expectFailure(OneTouchSetupException.Kind.INVALID_RESPONSE) { client().poll(pairing) }
        server.enqueue(MockResponse().setBody("""{"state":"approved","rommUrl":"http://romm.example.com","accessProfile":{"type":"serviceToken","clientId":"a","clientSecret":"b"}}"""))
        expectFailure(OneTouchSetupException.Kind.INVALID_RESPONSE) { client().poll(pairing) }
    }

    @Test
    fun statusCodesMapToTheSetupErrors() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        expectFailure(OneTouchSetupException.Kind.RATE_LIMITED) { client().start("Pixel") }
        server.enqueue(MockResponse().setResponseCode(503))
        expectFailure(OneTouchSetupException.Kind.UNAVAILABLE) { client().start("Pixel") }
        val pairing = SetupPairing(code, secret, server.url("/a/$code").toString(), 600, 3)
        server.enqueue(MockResponse().setResponseCode(404))
        expectFailure(OneTouchSetupException.Kind.EXPIRED) { client().complete(pairing) }
        // Cancel never throws (dismissal is not an error).
        server.enqueue(MockResponse().setResponseCode(404))
        client().cancel(pairing)
        assertEquals("/device/start", server.takeRequest().path)
    }

    @Test
    fun releaseBuildsRequireHttps() = runBlocking {
        server.enqueue(MockResponse().setBody(startBody()))
        expectFailure(OneTouchSetupException.Kind.INVALID_RESPONSE) { client(local = false).start("Pixel") }
        assertEquals("Nothing is sent over plain HTTP", 0, server.requestCount)
    }
}
