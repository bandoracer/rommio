package io.github.bandoracer.rommio.domain.progress

import io.github.bandoracer.rommio.data.auth.AuthManager
import io.github.bandoracer.rommio.data.network.RommServiceFactory
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Remote discoveries update the durable catalog only; a running core keeps its frozen parents. */
class ApplicationProgressSync(
    private val storeProvider: suspend () -> ProgressStore,
    private val auth: AuthManager,
    private val services: RommServiceFactory,
    private val staging: File,
    private val schedule: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val mutableMessages = MutableStateFlow<Map<String, String>>(emptyMap())
    val messages: StateFlow<Map<String, String>> = mutableMessages.asStateFlow()
    private val messageLock = Any()
    private var requested: Job? = null
    private fun message(key: String, value: String) = synchronized(messageLock) {
        mutableMessages.value = mutableMessages.value + (key to value)
    }
    @Synchronized fun request() {
        schedule()
        if (requested?.isActive != true) requested = scope.launch { drain() }
    }
    suspend fun beforeLaunch(binding: ProgressBinding): Boolean {
        val key = ProgressProtocol.bindingKey(binding)
        val checked = withTimeoutOrNull(3_000) { sync(binding) }
        if (checked == null) message(key, "Cloud progress not checked. Both game saves and snapshots remain local; any other-device progress will be preserved as a separate branch.")
        if (checked != true) request()
        return checked == true
    }
    suspend fun sync(binding: ProgressBinding): Boolean {
        val key = ProgressProtocol.bindingKey(binding)
        return locks.getOrPut(key) { Mutex() }.withLock {
            message(key, "Checking game saves and resume snapshots…")
            try {
                val profile = auth.listProfiles().firstOrNull { profile ->
                    val owner = auth.verifiedAccount(profile.id)
                    owner?.baseUrl == binding.serverKey && owner.userId.toString() == binding.accountId && profile.baseUrl == binding.serverKey
                } ?: error("Sign in to this progress's original account to sync.")
                val remote = RommProgressTransport(services.create(profile), binding) {
                    val owner = auth.verifiedAccount(profile.id)
                    check(auth.getProfile(profile.id)?.baseUrl == binding.serverKey && owner?.baseUrl == binding.serverKey && owner.userId.toString() == binding.accountId) {
                        "Account ownership changed; sync paused."
                    }
                }
                remote.qualify()
                val store = storeProvider()
                val identity = store.resolveIdentity(binding)
                ProgressUpgrade(store).upgrade(identity)
                val result = ProgressSync(store, staging).synchronize(identity, remote)
                message(key, result.message)
                result.gamePending == 0 && result.statePending == 0 && result.errors.isEmpty()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                message(key, "Game saves and resume snapshots remain local; sync pending. ${error.message ?: "Server unavailable."}")
                false
            }
        }
    }
    suspend fun drain(): Boolean {
        var success = true
        for (identity in storeProvider().identities()) if (!sync(identity.binding)) success = false
        return success
    }
}
