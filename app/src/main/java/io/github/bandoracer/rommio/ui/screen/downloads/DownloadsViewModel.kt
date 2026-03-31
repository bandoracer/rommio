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
    }

    fun cancel(record: DownloadRecord) {
        viewModelScope.launch { repository.cancelDownload(record) }
    }

    fun retry(record: DownloadRecord) {
        viewModelScope.launch { repository.retryDownload(record) }
    }

    fun downloadNow(record: DownloadRecord) {
        viewModelScope.launch { repository.downloadNow(record) }
    }

    fun deleteLocal(record: DownloadRecord) {
        viewModelScope.launch { repository.deleteLocalDownload(record) }
    }
}
