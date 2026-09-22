package io.github.bandoracer.rommio.data.auth

import io.github.bandoracer.rommio.model.TokenBundle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class TokenRefreshCoordinatorTest {
    private val original = TokenBundle("original", "refresh-original", "Bearer", "2000-01-01T00:00:00Z")
    private val rotated = TokenBundle("rotated", "refresh-rotated", "Bearer", "2999-01-01T00:00:00Z")

    @Test fun concurrentRequestsShareRefreshAndRotateOnce() = runBlocking {
        val tokens = ConcurrentHashMap<String, TokenBundle>().apply { put("a", original) }
        val calls = AtomicInteger()
        val coordinator = coordinator(tokens) { _, _ -> calls.incrementAndGet(); rotated }
        (1..30).map { async(Dispatchers.Default) { coordinator.prepare("a") } }.awaitAll()
        assertEquals(1, calls.get())
        assertEquals(rotated, tokens["a"])
        coordinator.prepare("a", "Bearer original") // A delayed 401 from before rotation.
        assertEquals(1, calls.get())
    }

    @Test fun anotherAccountRefreshDoesNotWaitForBlockedAccount() = runBlocking {
        val tokens = ConcurrentHashMap<String, TokenBundle>().apply { put("a", original); put("b", original) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = coordinator(tokens) { id, _ ->
            if (id == "a") { started.complete(Unit); release.await() }
            rotated.copy(accessToken = id)
        }
        val first = async { coordinator.prepare("a") }
        started.await()
        coordinator.prepare("b")
        assertEquals("b", tokens["b"]?.accessToken)
        release.complete(Unit)
        first.await()
    }

    @Test fun canceledWaiterDoesNotCancelSharedRefresh() = runBlocking {
        val tokens = ConcurrentHashMap<String, TokenBundle>().apply { put("a", original) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val coordinator = coordinator(tokens) { _, _ -> calls.incrementAndGet(); started.complete(Unit); release.await(); rotated }
        val first = async { coordinator.prepare("a") }
        started.await()
        first.cancelAndJoin()
        val second = async { coordinator.prepare("a") }
        release.complete(Unit)
        second.await()
        assertEquals(1, calls.get())
        assertEquals(rotated, tokens["a"])
    }

    @Test fun signOutAndNewLoginInvalidateEvenUncancellableOldRefresh() = runBlocking {
        val tokens = ConcurrentHashMap<String, TokenBundle>().apply { put("a", original) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val coordinator = coordinator(tokens) { _, _ ->
            withContext(NonCancellable) { started.complete(Unit); release.await(); finished.complete(Unit); rotated }
        }
        val pending = async { runCatching { coordinator.prepare("a") } }
        started.await()
        val generation = coordinator.invalidate("a") { tokens.remove("a") }
        val replacement = rotated.copy(accessToken = "new-login")
        coordinator.mutateSession("a", generation) { tokens["a"] = replacement }
        release.complete(Unit)
        finished.await()
        assertTrue(pending.await().isFailure)
        assertEquals(replacement, tokens["a"])
        val oldGeneration = generation
        coordinator.invalidate("a") { tokens.remove("a") }
        assertTrue(runCatching { coordinator.mutateSession("a", oldGeneration) { tokens["a"] = original } }.isFailure)
        assertNull(tokens["a"])
    }

    @Test fun onlyConfirmedInvalidRefreshClearsThatAccountsCredentials() = runBlocking {
        for (reason in TokenRefreshException.Reason.entries) {
            val tokens = ConcurrentHashMap<String, TokenBundle>().apply { put("a", original); put("b", original) }
            val coordinator = coordinator(tokens) { _, _ -> throw TokenRefreshException(reason, 503) }
            val error = runCatching { coordinator.prepare("a") }.exceptionOrNull()
            assertTrue(error is TokenRefreshException)
            assertEquals(if (reason == TokenRefreshException.Reason.INVALID_CREDENTIALS) null else original, tokens["a"])
            assertEquals(original, tokens["b"])
        }
    }

    @Test fun unknownExpiryCanRefreshAfterRejection() = runBlocking {
        val tokens = ConcurrentHashMap<String, TokenBundle>().apply { put("a", original.copy(expiresAt = null)) }
        val calls = AtomicInteger()
        val coordinator = coordinator(tokens) { _, _ -> calls.incrementAndGet(); rotated }
        coordinator.prepare("a")
        assertEquals(0, calls.get())
        coordinator.prepare("a", "Bearer original")
        assertEquals(1, calls.get())
    }

    @Test fun tokenResponseClassifiesFailuresWithoutDeletingCredentialsForProxyFailures() {
        val cases = listOf(
            Triple(503, "unavailable", TokenRefreshException.Reason.UNAVAILABLE),
            Triple(401, "<html>login</html>", TokenRefreshException.Reason.ACCESS_REQUIRED),
            Triple(401, "{\"detail\":\"Unauthorized\"}", TokenRefreshException.Reason.UNAVAILABLE),
            Triple(200, "{\"access_token\":\"\"}", TokenRefreshException.Reason.INVALID_RESPONSE),
            Triple(200, "invalid json", TokenRefreshException.Reason.INVALID_RESPONSE),
            Triple(401, "{\"detail\":\"Invalid refresh token\"}", TokenRefreshException.Reason.INVALID_CREDENTIALS),
            Triple(400, "{\"error\":\"invalid_grant\"}", TokenRefreshException.Reason.INVALID_CREDENTIALS),
        )
        for ((status, body, expected) in cases) {
            val error = runCatching { TokenRefreshResponse.decode(status, "application/json", body, original) }.exceptionOrNull()
            assertEquals(expected, (error as? TokenRefreshException)?.reason)
        }
        val next = TokenRefreshResponse.decode(200, "application/json", "{\"access_token\":\"new\",\"expires\":3600}", original, 0)
        assertEquals("refresh-original", next.refreshToken)
        assertEquals("1970-01-01T01:00:00Z", next.expiresAt)
    }

    private fun coordinator(tokens: ConcurrentHashMap<String, TokenBundle>, refresh: suspend (String, TokenBundle) -> TokenBundle) =
        TokenRefreshCoordinator(read = tokens::get, write = { id, token -> tokens[id] = token }, clear = { tokens.remove(it) }, refresh = refresh)
}
