package io.github.bandoracer.rommio.domain.player

import org.junit.Assert.*
import org.junit.Test

class RetainedViewCommandsTest {
    @Test fun lostThreadQueueIsRepostedAndOnlyOneThreadCanClaimTheCommand() {
        val posted = mutableListOf<() -> Unit>(); var calls = 0
        val queue = RetainedViewCommands { posted.add(it) }
        queue.attached(); queue.post { calls++ }
        val oldThread = posted.removeAt(0)
        queue.detached(); queue.attached()
        val newThread = posted.removeAt(0)
        newThread(); oldThread()
        assertEquals(1, calls)
        queue.detached(); queue.attached()
        assertTrue(posted.isEmpty())
    }

    @Test fun detachedCommandsWaitAndClosedCallbacksCannotRun() {
        val posted = mutableListOf<() -> Unit>(); var calls = 0
        val queue = RetainedViewCommands { posted.add(it) }
        queue.post { calls++ }; assertTrue(posted.isEmpty())
        queue.attached(); assertEquals(1, posted.size)
        queue.close(); posted.single()()
        assertEquals(0, calls)
        assertNotNull(runCatching { queue.post { calls++ } }.exceptionOrNull())
    }
}
