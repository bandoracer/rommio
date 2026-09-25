package io.github.bandoracer.rommio.ui.screen.downloads

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.model.DownloadRecord
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.formatBytes
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val records: List<DownloadRecord> = emptyList(),
    /** Cover paths by ROM id, from the cached catalogue. */
    val covers: Map<Int, String?> = emptyMap(),
    val isOffline: Boolean = false,
    /** The last download action that couldn't reach the queue; the list shown is the saved one. */
    val staleMessage: String? = null,
)

/** A download as the page draws it. */
@Immutable
data class DownloadItem(
    val key: String,
    val record: DownloadRecord,
    val coverUrl: String?,
    /** "SNES · 16 MB". */
    val subtitle: String,
    /** "12 MB of 16 MB". */
    val sizes: String,
    val status: String,
    val tone: Tone,
)

/** The Downloads page: what's downloading, what's next, what needs attention, what's finished. */
@Immutable
data class DownloadsPage(
    val current: DownloadItem? = null,
    val upNext: List<DownloadItem> = emptyList(),
    val attention: List<DownloadItem> = emptyList(),
    val finished: List<DownloadItem> = emptyList(),
    val queuedCount: Int = 0,
    val isOffline: Boolean = false,
    val isEmpty: Boolean = true,
    val staleMessage: String? = null,
) {
    companion object {
        fun build(state: DownloadsUiState, baseUrl: String?): DownloadsPage {
            val records = state.records
            var n = 0
            fun item(record: DownloadRecord): DownloadItem {
                val (status, tone) = when (record.status) {
                    DownloadStatus.QUEUED -> "Up next" to Tone.MUTE
                    DownloadStatus.RUNNING -> "${record.progressPercent}%" to Tone.LIVE
                    DownloadStatus.COMPLETED -> "Downloaded" to Tone.OK
                    DownloadStatus.FAILED -> "Failed" to Tone.BAD
                    DownloadStatus.CANCELED -> "Canceled" to Tone.WAIT
                }
                return DownloadItem(
                    key = "${record.romId}:${record.fileId}:${n++}",
                    record = record,
                    coverUrl = resolveRemoteAssetUrl(baseUrl, state.covers[record.romId]),
                    subtitle = "${record.platformSlug.uppercase()} · ${formatBytes(maxOf(record.totalBytes, record.fileSizeBytes))}",
                    sizes = "${formatBytes(record.bytesDownloaded)} of ${formatBytes(maxOf(record.totalBytes, record.fileSizeBytes))}",
                    status = status,
                    tone = tone,
                )
            }
            val running = records.filter { it.status == DownloadStatus.RUNNING }
            val queued = records.filter { it.status == DownloadStatus.QUEUED }
            return DownloadsPage(
                current = running.firstOrNull()?.let(::item),
                upNext = (running.drop(1) + queued).map(::item),
                attention = records.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED }.map(::item),
                finished = records.filter { it.status == DownloadStatus.COMPLETED }.map(::item),
                queuedCount = queued.size,
                isOffline = state.isOffline,
                isEmpty = records.isEmpty(),
                staleMessage = state.staleMessage,
            )
        }
    }
}

class DownloadsViewModel(
    private val repository: RommRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    /** The page; published only when it changes. */
    val page: StateFlow<DownloadsPage> = combine(
        _uiState,
        repository.activeProfileFlow().map { it?.baseUrl }.distinctUntilChanged(),
    ) { state, baseUrl -> DownloadsPage.build(state, baseUrl) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, DownloadsPage())

    init {
        viewModelScope.launch {
            combine(
                repository.observeDownloadHistory(),
                repository.observeCachedRoms().map { roms -> roms.associate { rom -> rom.id to rom.urlCover } }.distinctUntilChanged(),
                repository.observeOfflineState().map { it.isOffline }.distinctUntilChanged(),
            ) { records, covers, offline -> Triple(records, covers, offline) }
                .collect { (records, covers, offline) ->
                    _uiState.update { it.copy(records = records, covers = covers, isOffline = offline) }
                }
        }
    }

    fun cancel(record: DownloadRecord) = act { repository.cancelDownload(record) }

    fun retry(record: DownloadRecord) = act { repository.retryDownload(record) }

    fun downloadNow(record: DownloadRecord) = act { repository.downloadNow(record) }

    fun deleteLocal(record: DownloadRecord) = act { repository.deleteLocalDownload(record) }

    /** Runs a queue action; a failure keeps the saved list and says why (the "stale" group). */
    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                _uiState.update { it.copy(staleMessage = null) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(staleMessage = error.message ?: "Couldn't update downloads.") }
            }
        }
    }
}
