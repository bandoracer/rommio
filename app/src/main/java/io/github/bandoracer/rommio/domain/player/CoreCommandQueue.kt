package io.github.bandoracer.rommio.domain.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Coroutine bridge to the GL execution thread. Never waits on a native CountDownLatch. */
internal class CoreCommandQueue(
    private val post: (() -> Unit) -> Unit,
    private val timeoutMs: Long = 15_000,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val ready = CompletableDeferred<Unit>()
    private val terminal = AtomicReference<Throwable?>(null)
    private val pending = mutableSetOf<CompletableDeferred<*>>()

    fun markReady() { if (terminal.get() == null) ready.complete(Unit) }

    fun fail(error: Throwable, report: Boolean = true) {
        if (!terminal.compareAndSet(null, error)) return
        if (report) onFailure(error)
        ready.completeExceptionally(error)
        synchronized(pending) { pending.toList() }.forEach { it.completeExceptionally(error) }
    }

    fun offer(block: () -> Unit) {
        if (!ready.isCompleted || terminal.get() != null) return
        post {
            if (terminal.get() == null) {
                try { block() } catch (error: Throwable) { fail(error) }
            }
        }
    }

    suspend fun awaitReady() {
        terminal.get()?.let { throw it }
        try { withTimeout(timeoutMs) { ready.await() } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val failure = IllegalStateException("The core did not become ready in time.", error)
            fail(failure); throw failure
        }
        terminal.get()?.let { throw it }
    }

    suspend fun <T> execute(block: () -> T): T {
        awaitReady()
        val result = CompletableDeferred<T>()
        val state = AtomicInteger(0) // queued, running, finished, canceled
        synchronized(pending) { pending.add(result) }
        try {
            terminal.get()?.let { throw it }
            post {
                if (state.compareAndSet(0, 1) && terminal.get() == null) {
                    try { result.complete(block()) }
                    catch (error: Throwable) { result.completeExceptionally(error) }
                    finally { state.compareAndSet(1, 2) }
                }
            }
            return try { withTimeout(timeoutMs) { result.await() } }
            catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                val failure = IllegalStateException("The core stopped responding. Close this player before trying again.", error)
                fail(failure); throw failure
            }
        } catch (error: CancellationException) {
            // A canceled queued command must not mutate a later session. If native work already
            // began, its result is unknown to its caller: do not permit further core operations.
            if (state.getAndSet(3) == 1 && !result.isCompleted) fail(IllegalStateException("A native operation was interrupted.", error))
            throw error
        } finally {
            state.set(3)
            result.cancel()
            synchronized(pending) { pending.remove(result) }
        }
    }
}
