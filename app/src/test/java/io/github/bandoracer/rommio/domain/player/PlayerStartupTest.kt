package io.github.bandoracer.rommio.domain.player

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

class PlayerStartupTest {
    @Test fun delayedNativeReadinessCannotRestoreOrStartGameplayEarly() = runBlocking {
        val ready = CompletableDeferred<Unit>(); val restored = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val launch = launch {
            startPreparedPlayer({ ready.await(); events.add("ready") }, {
                events.add("restore"); restored.await(); true
            }, { events.add("play") })
        }
        yield(); assertTrue(events.isEmpty())
        ready.complete(Unit); yield(); assertEquals(listOf("ready", "restore"), events)
        restored.complete(Unit); launch.join(); assertEquals(listOf("ready", "restore", "play"), events)
    }

    @Test fun rejectedRestoreAndNativeFailureNeverStartGameplay() = runBlocking {
        var played = false
        try { startPreparedPlayer({}, { false }, { played = true }); fail() }
        catch (_: IllegalStateException) { }
        assertFalse(played)
        try { startPreparedPlayer({ error("load failed") }, { fail(); true }, { played = true }); fail() }
        catch (_: IllegalStateException) { }
        assertFalse(played)
    }

    @Test fun coldBootStillWaitsForReadinessAndCanceledStartupCannotBeginPlay() = runBlocking {
        val ready = CompletableDeferred<Unit>(); var played = false
        val job = launch { startPreparedPlayer({ ready.await() }, null, { played = true }) }
        yield(); job.cancelAndJoin(); ready.complete(Unit); assertFalse(played)
        startPreparedPlayer({ ready.await() }, null, { played = true }); assertTrue(played)
    }

    @Test fun nativeExceptionsReturnToCallerWithoutHangingEmulationQueue() = runBlocking {
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val queue = CoreCommandQueue(post = { callbacks.add(it) }, timeoutMs = 1000)
        queue.markReady()
        supervisorScope {
            val first = async { queue.execute<Unit> { error("serialization failed") } }
            yield(); callbacks.remove().invoke()
            try { first.await(); fail() } catch (e: IllegalStateException) { assertEquals("serialization failed", e.message) }
        }
        val next = async { queue.execute { 42 } }
        yield(); callbacks.remove().invoke(); assertEquals(42, next.await())
    }

    @Test fun canceledQueuedCommandDoesNotRunWhenGlThreadEventuallyDrains() = runBlocking {
        val callbacks = LinkedBlockingQueue<() -> Unit>(); var mutated = false
        val queue = CoreCommandQueue(post = { callbacks.add(it) })
        queue.markReady()
        val job = launch { queue.execute { mutated = true } }
        yield(); job.cancelAndJoin(); callbacks.remove().invoke(); assertFalse(mutated)
    }

    @Test fun closingRejectsQueuedAndFutureCommandsEvenAfterLateReady() = runBlocking {
        val callbacks = LinkedBlockingQueue<() -> Unit>(); var mutated = false
        val queue = CoreCommandQueue(post = { callbacks.add(it) })
        queue.markReady()
        supervisorScope {
            val job = async { queue.execute { mutated = true } }
            yield(); queue.fail(IllegalStateException("closed")); callbacks.remove().invoke()
            try { job.await(); fail() } catch (_: IllegalStateException) { }
        }
        queue.markReady()
        try { queue.execute { mutated = true }; fail() } catch (_: IllegalStateException) { }
        assertFalse(mutated)
    }

    @Test fun readinessAndCommandTimeoutsFailClosedWithoutBlockingCaller() = runBlocking {
        val unready = CoreCommandQueue(post = { fail("Not ready") }, timeoutMs = 20)
        try { unready.awaitReady(); fail() } catch (_: IllegalStateException) { }
        unready.markReady()
        try { unready.execute { fail() }; fail() } catch (_: IllegalStateException) { }
        val callbacks = LinkedBlockingQueue<() -> Unit>(); var mutated = false
        val queue = CoreCommandQueue(post = { callbacks.add(it) }, timeoutMs = 20)
        queue.markReady()
        try { queue.execute { mutated = true }; fail() } catch (_: IllegalStateException) { }
        callbacks.remove().invoke(); assertFalse(mutated)
    }

    @Test fun processNativeOwnershipPreventsDifferentGamesFromReplacingActiveRuntime() {
        val first = NativePlayerOwnership.acquire()
        try { assertThrows(IllegalStateException::class.java) { NativePlayerOwnership.acquire() } }
        finally { first.close() }
        val second = NativePlayerOwnership.acquire()
        first.close() // stale duplicate close must not release the new owner
        try { assertThrows(IllegalStateException::class.java) { NativePlayerOwnership.acquire() } }
        finally { second.close() }
        NativePlayerOwnership.acquire().close()
    }
}
