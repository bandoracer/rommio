package io.github.bandoracer.rommio.data.stats

import io.github.bandoracer.rommio.data.auth.VerifiedAccount
import io.github.bandoracer.rommio.domain.progress.ProgressProtocol
import io.github.bandoracer.rommio.domain.progress.ProgressStore
import io.github.bandoracer.rommio.domain.stats.PlayCheckpoint
import io.github.bandoracer.rommio.domain.stats.PlayStats
import io.github.bandoracer.rommio.domain.stats.PlayStatsCalculator
import io.github.bandoracer.rommio.domain.stats.RecordedPlaySession
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Play activity for the active account on this device. Sessions come from the local
 * play-sessions table (Phase 4); play from before the first recorded session is derived from
 * committed local checkpoints (Stats v1) by the merge rule in [PlayStatsCalculator.merge].
 * Checkpoints synced down from other devices are not counted. Nothing is written here.
 */
class PlayActivityRepository(
    private val storeProvider: suspend () -> ProgressStore,
    private val accountProvider: suspend () -> VerifiedAccount?,
    private val localDeviceId: suspend () -> String?,
    private val recordedSessions: suspend (serverKey: String, accountId: String) -> List<RecordedPlaySession> = { _, _ -> emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow(PlayStats())
    val stats: StateFlow<PlayStats> = state.asStateFlow()

    /** Recomputes from the store. Failures (e.g. storage busy during an import) keep the last value. */
    suspend fun refresh(): PlayStats = mutex.withLock {
        val next = try {
            withContext(Dispatchers.IO) { compute() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withLock state.value
        }
        state.value = next
        next
    }

    private suspend fun compute(): PlayStats {
        val account = accountProvider() ?: return PlayStats()
        val deviceId = localDeviceId() ?: return PlayStats()
        val store = storeProvider()
        val accountId = account.userId.toString()
        val checkpoints = store.identities()
            .filter { it.binding.serverKey == account.baseUrl && it.binding.accountId == accountId }
            .flatMap { identity ->
                store.operationPayloads(identity).flatMap { payload ->
                    runCatching { ProgressProtocol.decode(payload).checkpoints }.getOrDefault(emptyList())
                }
            }
            .map { checkpoint ->
                PlayCheckpoint(
                    checkpointId = checkpoint.checkpointId,
                    romId = checkpoint.binding.romId,
                    sessionId = checkpoint.sessionId,
                    deviceId = checkpoint.deviceId,
                    createdAtEpochMs = checkpoint.createdAtEpochMs,
                    captureReason = checkpoint.captureReason,
                )
            }
        val recorded = recordedSessions(account.baseUrl, accountId)
        return PlayStatsCalculator.merge(recorded, checkpoints, clock(), zone(), onlyDeviceId = deviceId)
    }
}
