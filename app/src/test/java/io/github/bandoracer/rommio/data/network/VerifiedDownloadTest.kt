package io.github.bandoracer.rommio.data.network

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifiedDownloadTest {
    @get:Rule val directory = TemporaryFolder()
    private val client = OkHttpClient()

    @Test fun rejectedResponsesAndTruncationNeverReplaceAnExistingInstall() {
        val target = directory.newFile("game.rom").apply { writeText("last valid ROM") }
        MockWebServer().use { server ->
            for (response in listOf(
                MockResponse().setResponseCode(401).setBody("login"),
                MockResponse().setResponseCode(500).setBody("error"),
                MockResponse().setBody("  <!doctype html><html>login</html>"),
                MockResponse().setBody("0".repeat(10_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )) {
                server.enqueue(response)
                assertThrows(Exception::class.java) {
                    runBlocking { downloadVerifiedFile(client, Request.Builder().url(server.url("/rom")).build(), target) }
                }
                assertEquals("last valid ROM", target.readText())
                assertEquals(listOf("game.rom"), directory.root.list()!!.toList())
            }
        }
    }

    @Test fun cancellationPreservesOldInstallAndSuccessfulRetryReplacesIt() {
        val target = directory.newFile("game.rom").apply { writeText("old") }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("new ROM"))
            val request = Request.Builder().url(server.url("/rom")).build()
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    downloadVerifiedFile(client, request, target) { _, _ -> throw CancellationException("user canceled") }
                }
            }
            assertEquals("old", target.readText())
            server.enqueue(MockResponse().setBody("new ROM"))
            runBlocking { downloadVerifiedFile(client, request, target, expectedSize = 7) }
            assertEquals("new ROM", target.readText())
            assertEquals(listOf("game.rom"), directory.root.list()!!.toList())
        }
    }
}
