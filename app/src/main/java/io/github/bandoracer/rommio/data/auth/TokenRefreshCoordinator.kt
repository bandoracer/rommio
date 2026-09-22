package io.github.bandoracer.rommio.data.auth

import io.github.bandoracer.rommio.model.TokenBundle
import java.time.Instant
import io.github.bandoracer.rommio.domain.storage.StorageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** One refresh owner per profile, independent of the request that first notices expiry. */
internal class TokenRefreshCoordinator(
    private val read: (String) -> TokenBundle?,
    private val write: (String, TokenBundle) -> Unit,
    private val clear: (String) -> Unit,
    private val refresh: suspend (String, TokenBundle) -> TokenBundle,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val generations = mutableMapOf<String, Long>()
    private val flights = mutableMapOf<String, Deferred<Unit>>()

    fun generation(profileId: String): Long = synchronized(lock) { generations[profileId] ?: 0 }

    fun invalidate(profileId: String, mutation: () -> Unit = {}): Long = synchronized(lock) {
        val generation = (generations[profileId] ?: 0) + 1
        generations[profileId] = generation
        flights.remove(profileId)?.cancel()
        mutation()
        generation
    }

    fun mutateSession(profileId: String, generation: Long, mutation: () -> Unit) = synchronized(lock) {
        if ((generations[profileId] ?: 0) != generation) throw CancellationException("Session changed")
        mutation()
    }

    suspend fun prepare(profileId: String, rejectedAuthorization: String? = null) = StorageAccess.normal {
        val flight = synchronized(lock) {
            val bundle = read(profileId) ?: return@normal
            if (rejectedAuthorization != null && rejectedAuthorization != "${bundle.tokenType} ${bundle.accessToken}") return@normal
            flights[profileId]?.let { return@synchronized it }
            val expiry = bundle.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            if (bundle.refreshToken.isNullOrBlank() ||
                (rejectedAuthorization == null && (expiry == null || expiry - now() > 90_000))) return@normal
            val generation = generations[profileId] ?: 0
            val (leaseContext, lease) = StorageAccess.fork()
            scope.async(context = leaseContext, start = CoroutineStart.LAZY) {
                try {
                    val next = refresh(profileId, bundle)
                    synchronized(lock) {
                        if ((generations[profileId] ?: 0) != generation || read(profileId) != bundle) {
                            throw CancellationException("Session changed")
                        }
                        write(profileId, next)
                    }
                } catch (error: TokenRefreshException) {
                    synchronized(lock) {
                        if (error.reason == TokenRefreshException.Reason.INVALID_CREDENTIALS &&
                            (generations[profileId] ?: 0) == generation && read(profileId) == bundle) clear(profileId)
                    }
                    throw error
                }
            }.also { flight ->
                flight.invokeOnCompletion { lease.close() }
                flights[profileId] = flight
            }
        }
        try {
            flight.await()
        } finally {
            synchronized(lock) {
                if (flight.isCompleted && flights[profileId] === flight) flights.remove(profileId)
            }
        }
    }
}
