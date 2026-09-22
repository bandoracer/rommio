package io.github.bandoracer.rommio.domain.storage

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coroutine-safe application storage barrier. Nested work inherits its existing lease. */
internal object StorageAccess {
    val busy = MutableStateFlow(false)
    private val lock = Any()
    private val writers = Mutex()
    private var generation = 0L
    private var readers = 0
    private var sessions = 0
    private var maintenance = false
    private var released = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var drained = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val pendingRecovery = mutableSetOf<String>()
    private val recoveryRequired get() = pendingRecovery.isNotEmpty()
    private class Lease(val exclusive: Boolean = false) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Lease> }

    suspend fun <T> normal(block: suspend () -> T): T {
        if (coroutineContext[Lease] != null) return block()
        val expectedGeneration = synchronized(lock) { generation }
        while (true) {
            val wait = synchronized(lock) {
                if (generation != expectedGeneration) throw kotlinx.coroutines.CancellationException("Library changed during this operation. Start it again using the current library.")
                check(!recoveryRequired) { "Interrupted library recovery must finish before using the library." }
                if (maintenance) released else {
                    if (readers++ == 0) drained = CompletableDeferred()
                    null
                }
            }
            if (wait == null) break
            wait.await()
        }
        try { return withContext(Lease()) { block() } }
        finally { synchronized(lock) { if (--readers == 0) drained.complete(Unit) } }
    }

    suspend fun <T> exclusive(block: suspend () -> T): T = writers.withLock {
        check(coroutineContext[Lease] == null) { "Cannot import from inside a normal storage operation." }
        val wait = synchronized(lock) {
            check(sessions == 0) { "Close the player and browser sign-in before changing library storage." }
            maintenance = true; busy.value = true; released = CompletableDeferred(); drained
        }
        try {
            wait.await()
            // Participants may use ordinary repositories while this exclusive owner holds the barrier.
            withContext(Lease(exclusive = true)) { block() }
        } finally {
            synchronized(lock) { maintenance = false; busy.value = false; released.complete(Unit) }
        }
    }

    /** Only the drained maintenance owner can dispose stores or swap their roots. */
    suspend fun checkExclusive() {
        check(coroutineContext[Lease]?.exclusive == true) { "Replacing progress storage requires exclusive ownership." }
    }

    /** Retain an existing normal operation for work that can outlive its initiating waiter. */
    fun fork(): Pair<CoroutineContext, AutoCloseable> = synchronized(lock) {
        check(readers > 0 && !recoveryRequired) { "A detached operation requires an existing storage lease." }
        readers++
        var closed = false
        Lease() to AutoCloseable { synchronized(lock) {
            if (!closed) { closed = true; if (--readers == 0) drained.complete(Unit) }
        } }
    }

    /** A native core/browser can outlive a coroutine; imports must never wait for its unbounded lifetime. */
    fun session(): AutoCloseable = synchronized(lock) {
        check(!maintenance && !recoveryRequired) { "Library import recovery is in progress." }
        sessions++
        var closed = false
        AutoCloseable { synchronized(lock) { if (!closed) { closed = true; sessions-- } } }
    }
    fun didReplaceLibrary() = synchronized(lock) { generation++ }
    fun checkRecovered() = synchronized(lock) { check(!recoveryRequired) { "Finish interrupted library recovery first." } }
    fun requireRecovery(required: Boolean, owner: String = "import") = synchronized(lock) {
        if (required) pendingRecovery.add(owner) else pendingRecovery.remove(owner)
        Unit
    }
}
