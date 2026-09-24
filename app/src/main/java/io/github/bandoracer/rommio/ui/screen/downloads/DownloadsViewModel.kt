package io.github.bandoracer.rommio.ui.screen.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.model.DownloadRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class DownloadsViewModel(
    private val repository: RommRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeDownloadHistory().collect { records ->
                _uiState.update { it.copy(records = records) }
            }
        }
        viewModelScope.launch {
            repository.observeCachedRoms().collect { roms ->
                _uiState.update { it.copy(covers = roms.associate { rom -> rom.id to rom.urlCover }) }
            }
        }
        viewModelScope.launch {
            repository.observeOfflineState().collect { offline ->
                _uiState.update { it.copy(isOffline = offline.isOffline) }
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
