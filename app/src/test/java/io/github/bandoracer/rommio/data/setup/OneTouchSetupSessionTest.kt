package io.github.bandoracer.rommio.data.setup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The setup state machine against a fake transport (iOS `OneTouchSetupModelTests` plus timing). */
class OneTouchSetupSessionTest {
    private class FakeService(
        var polls: MutableList<SetupPollResult> = mutableListOf(),
        val holdPoll: CompletableDeferred<Unit>? = null,
    ) : OneTouchSetupService {
        var starts = 0
        var completions = 0
        val cancelled = mutableListOf<String>()
        var pollCount = 0

        override suspend fun start(deviceName: String): SetupPairing {
            starts += 1
            return SetupPairing("ABCDE$starts", "A".repeat(32), "https://approve.example/a/ABCDE$starts", 600, 3)
        }

        override suspend fun poll(pairing: SetupPairing): SetupPollResult {
            pollCount += 1
            holdPoll?.await()
            return if (polls.isEmpty()) SetupPollResult.Pending else polls.removeAt(0)
        }

        override suspend fun complete(pairing: SetupPairing) { completions += 1 }
        override suspend fun cancel(pairing: SetupPairing) { cancelled += pairing.code }
    }

    private val approved = SetupPollResult.Approved(SetupConfiguration("https://romm.example.com", "test-id", "test-secret"))

    /** A clock that only moves when the session sleeps. */
    private class Clock {
        var now = 1_000_000L
        val sleeps = mutableListOf<Long>()
        suspend fun sleep(ms: Long) { sleeps += ms; now += ms; delay(1) }
    }

    private fun session(service: OneTouchSetupService, scope: CoroutineScope, clock: Clock, apply: suspend (SetupConfiguration) -> Unit) =
        OneTouchSetupSession(service, scope, apply, now = { clock.now }, sleep = clock::sleep)

    private suspend fun OneTouchSetupSession.awaitState(predicate: (OneTouchSetupState) -> Boolean): OneTouchSetupState =
        withTimeout(5_000) {
            while (!predicate(state.value)) delay(5)
            state.value
        }

    @Test
    fun approvalIsAcknowledgedOnlyAfterTheConfigurationIsStored() = runBlocking {
        val service = FakeService(mutableListOf(SetupPollResult.Pending, approved))
        val clock = Clock()
        var stored: SetupConfiguration? = null
        val setup = session(service, this, clock) { configuration ->
            assertEquals("Not acknowledged before storing", 0, service.completions)
            stored = configuration
        }
        setup.start("Pixel")
        setup.awaitState { it is OneTouchSetupState.Done }
        assertEquals("https://romm.example.com", stored?.rommUrl)
        assertEquals("test-id", stored?.clientId)
        assertEquals(1, service.completions)
        assertEquals(2, service.pollCount)
        // Polls wait the service's interval.
        assertEquals(listOf(3_000L, 3_000L), clock.sleeps)
    }

    @Test
    fun failedStorageDoesNotAcknowledge() = runBlocking {
        val service = FakeService(mutableListOf(approved))
        val setup = session(service, this, Clock()) { throw IllegalStateException("RomM did not answer") }
        setup.start("Pixel")
        val failed = setup.awaitState { it is OneTouchSetupState.Failed } as OneTouchSetupState.Failed
        assertEquals("Could not finish setup. Check your connection and try again.", failed.message)
        assertEquals(0, service.completions)
        setup.cancel()
    }

    @Test
    fun cancelAndRestartCannotApplyTheOldApproval() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val service = FakeService(mutableListOf(approved, approved), holdPoll = hold)
        var applied = 0
        val setup = session(service, this, Clock()) { applied += 1 }
        setup.start("Old")
        val waiting = setup.awaitState { it is OneTouchSetupState.WaitingForApproval } as OneTouchSetupState.WaitingForApproval
        setup.cancel()
        assertEquals(OneTouchSetupState.Idle, setup.state.value)
        setup.awaitState { service.cancelled.contains(waiting.pairing.code) }
        hold.complete(Unit)
        setup.start("New")
        setup.awaitState { it is OneTouchSetupState.Done }
        assertEquals("Only the new code's approval applies", 1, applied)
        assertEquals(2, service.starts)
        assertEquals(listOf(waiting.pairing.code), service.cancelled)
    }

    @Test
    fun deniedIsARetryableError() = runBlocking {
        val service = FakeService(mutableListOf(SetupPollResult.Denied))
        var applied = 0
        val setup = session(service, this, Clock()) { applied += 1 }
        setup.start("Pixel")
        val failed = setup.awaitState { it is OneTouchSetupState.Failed } as OneTouchSetupState.Failed
        assertTrue(failed.message.contains("denied"))
        assertEquals(0, applied)
        // Try again allocates a fresh code and releases the old one.
        service.polls = mutableListOf(approved)
        setup.retry("Pixel")
        setup.awaitState { it is OneTouchSetupState.Done }
        assertEquals(2, service.starts)
        assertTrue(service.cancelled.contains("ABCDE1"))
        assertEquals(1, applied)
        assertFalse(service.cancelled.contains("ABCDE2"))
    }

    @Test
    fun theCodeExpiresAfterItsLifetime() = runBlocking {
        val service = FakeService()
        val clock = Clock()
        val setup = session(service, this, clock) { throw AssertionError("Expired setup applied") }
        setup.start("Pixel")
        val failed = setup.awaitState { it is OneTouchSetupState.Failed } as OneTouchSetupState.Failed
        assertEquals("This setup code expired. Start again for a new code.", failed.message)
        assertEquals("600 s of 3 s polls", 200, service.pollCount)
    }

    @Test
    fun theServerSayingExpiredEndsTheAttempt() = runBlocking {
        val service = FakeService(mutableListOf(SetupPollResult.Pending, SetupPollResult.Expired))
        val setup = session(service, this, Clock()) { throw AssertionError("Expired setup applied") }
        setup.start("Pixel")
        val failed = setup.awaitState { it is OneTouchSetupState.Failed } as OneTouchSetupState.Failed
        assertTrue(failed.message.contains("expired"))
        assertEquals(2, service.pollCount)
        setup.cancel()
    }

    @Test
    fun anInvalidConfigurationIsNeverApplied() = runBlocking {
        val insecure = SetupPollResult.Approved(SetupConfiguration("http://romm.example.com", "id", "secret"))
        val service = FakeService(mutableListOf(insecure))
        val setup = session(service, this, Clock()) { throw AssertionError("Insecure setup applied") }
        setup.start("Pixel")
        val failed = setup.awaitState { it is OneTouchSetupState.Failed } as OneTouchSetupState.Failed
        assertEquals("The setup service returned an invalid configuration.", failed.message)
        assertEquals(0, service.completions)
        setup.cancel()
    }
}
