package io.github.bandoracer.rommio.domain.player

import java.util.concurrent.atomic.AtomicBoolean

/** GLSurfaceView replaces its GL thread on reattachment. Keep unclaimed work outside that thread's queue. */
internal class RetainedViewCommands(private val postToView: (() -> Unit) -> Unit) {
    private class Entry(val command: () -> Unit) { val claimed = AtomicBoolean(false) }
    private val pending = linkedSetOf<Entry>()
    private var attached = false
    private var closed = false

    fun post(command: () -> Unit) {
        val entry = Entry(command)
        val dispatch = synchronized(pending) {
            check(!closed) { "The player view has closed." }
            pending.add(entry); attached
        }
        if (dispatch) schedule(entry)
    }

    fun attached() {
        val entries = synchronized(pending) {
            if (closed) return
            attached = true; pending.toList()
        }
        entries.forEach(::schedule)
    }

    fun detached() { synchronized(pending) { attached = false } }

    fun close() { synchronized(pending) {
        closed = true; attached = false
        pending.forEach { it.claimed.set(true) }; pending.clear()
    } }

    private fun schedule(entry: Entry) {
        try {
            postToView {
                if (entry.claimed.compareAndSet(false, true)) {
                    synchronized(pending) { pending.remove(entry) }
                    entry.command()
                }
            }
        } catch (error: Throwable) {
            entry.claimed.set(true)
            synchronized(pending) { pending.remove(entry) }
            throw error
        }
    }
}
