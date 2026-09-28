package io.github.bandoracer.rommio.data.playdata

import io.github.bandoracer.rommio.data.stats.PlayActivityRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * This device's play (stats) and saves, read before anything waits on the server (iOS
 * `RommioAppModel.localStateReady`). Aurora's Home chooses its games from both, so its games wait
 * for them: from RomM's list alone the first game shown would switch a moment later as each read
 * landed. Both are read together once per account (again after a switch); later refreshes of
 * either update what is shown as usual.
 */
class LocalPlayState(
    private val playActivity: PlayActivityRepository,
    private val savedProgress: SavedProgressRepository,
    /** The account the reads belong to (the active profile), or null without one. */
    private val account: suspend () -> String?,
) {
    private val mutex = Mutex()
    private var readFor: String? = null
    private val readyState = MutableStateFlow(false)

    /** Stats and saves have been read for the active account. */
    val ready: StateFlow<Boolean> = readyState.asStateFlow()

    /** Reads stats and saves (together) unless they were read for the active account, then marks ready. */
    suspend fun ensureRead() = mutex.withLock {
        val key = account() ?: NO_ACCOUNT
        if (readyState.value && readFor == key) return@withLock
        readyState.value = false
        coroutineScope {
            launch { playActivity.refresh() }
            launch { savedProgress.refreshRecent() }
        }
        readFor = key
        readyState.value = true
    }

    private companion object {
        const val NO_ACCOUNT = ""
    }
}
