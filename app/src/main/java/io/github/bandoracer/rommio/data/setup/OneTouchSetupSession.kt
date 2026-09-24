package io.github.bandoracer.rommio.data.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a setup attempt stands (the Setup code sheet draws it). */
sealed interface OneTouchSetupState {
    data object Idle : OneTouchSetupState

    /** Asking the service for a code. */
    data object Starting : OneTouchSetupState

    /** Showing [pairing]'s code and QR code; polling until someone approves it. */
    data class WaitingForApproval(val pairing: SetupPairing) : OneTouchSetupState

    /** Approved: storing the service token and checking RomM. Can't be cancelled. */
    data class Applying(val pairing: SetupPairing) : OneTouchSetupState

    /** The server is set up; the caller moves on to Sign in. */
    data object Done : OneTouchSetupState

    data class Failed(val message: String, val pairing: SetupPairing? = null) : OneTouchSetupState
}

/**
 * One-touch setup (setup-service/README.md), the same state machine as iOS `OneTouchSetupModel`:
 * start → show the code → poll every `pollIntervalSeconds` until approved, denied or expired →
 * on approval [apply] stores the service-token profile and verifies RomM → only then `complete`.
 * Dismissing sends `cancel`; retry allocates a fresh code. [now] and [sleep] are injected so tests
 * drive time.
 */
class OneTouchSetupSession(
    private val service: OneTouchSetupService,
    private val scope: CoroutineScope,
    private val apply: suspend (SetupConfiguration) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutableState = MutableStateFlow<OneTouchSetupState>(OneTouchSetupState.Idle)
    val state: StateFlow<OneTouchSetupState> = mutableState.asStateFlow()

    private var job: Job? = null
    private var generation = 0L
    private var pairing: SetupPairing? = null

    val isApplying: Boolean get() = mutableState.value is OneTouchSetupState.Applying

    fun start(deviceName: String) {
        if (job?.isActive == true) return
        val current = ++generation
        pairing = null
        mutableState.value = OneTouchSetupState.Starting
        job = scope.launch {
            var allocated: SetupPairing? = null
            try {
                val next = service.start(deviceName)
                allocated = next
                if (generation != current) {
                    service.cancel(next)
                    return@launch
                }
                pairing = next
                mutableState.value = OneTouchSetupState.WaitingForApproval(next)
                val deadline = now() + next.expiresInSeconds * 1000L
                while (now() < deadline) {
                    sleep(next.pollIntervalSeconds * 1000L)
                    val result = service.poll(next)
                    if (generation != current) return@launch
                    when (result) {
                        SetupPollResult.Pending -> continue
                        SetupPollResult.Expired -> throw OneTouchSetupException(OneTouchSetupException.Kind.EXPIRED)
                        SetupPollResult.Denied -> throw OneTouchSetupException(OneTouchSetupException.Kind.DENIED)
                        is SetupPollResult.Approved -> {
                            result.configuration.validate()
                            mutableState.value = OneTouchSetupState.Applying(next)
                            // The profile and its token are durable before acknowledgement; a lost
                            // acknowledgement just lets the code expire on the server.
                            withContext(NonCancellable) { apply(result.configuration) }
                            try { service.complete(next) } catch (error: CancellationException) { throw error } catch (_: Exception) {}
                            pairing = null
                            mutableState.value = OneTouchSetupState.Done
                            return@launch
                        }
                    }
                }
                throw OneTouchSetupException(OneTouchSetupException.Kind.EXPIRED)
            } catch (error: CancellationException) {
                // Dismissal is not a failed setup.
                throw error
            } catch (error: Exception) {
                if (generation == current) {
                    val message = (error as? OneTouchSetupException)?.message
                        ?: "Could not finish setup. Check your connection and try again."
                    mutableState.value = OneTouchSetupState.Failed(message, allocated)
                }
            }
        }
    }

    /** Dismissal: stops polling and releases the code on the server. Ignored while applying. */
    fun cancel() {
        if (isApplying) return
        generation++
        job?.cancel()
        job = null
        val released = pairing ?: (mutableState.value as? OneTouchSetupState.Failed)?.pairing
        pairing = null
        if (released != null) scope.launch { withContext(NonCancellable) { service.cancel(released) } }
        if (mutableState.value !is OneTouchSetupState.Done) mutableState.value = OneTouchSetupState.Idle
    }

    /** "Try again": a fresh code. */
    fun retry(deviceName: String) {
        cancel()
        start(deviceName)
    }
}
