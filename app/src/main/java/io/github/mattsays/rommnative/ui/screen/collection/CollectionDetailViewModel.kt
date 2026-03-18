package io.github.mattsays.rommnative.ui.screen.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.mattsays.rommnative.data.repository.RommRepository
import io.github.mattsays.rommnative.model.CollectionKind
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.RommCollectionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionDetailUiState(
    val isLoading: Boolean = true,
    val collection: RommCollectionDto? = null,
    val supportedRoms: List<RomDto> = emptyList(),
    val unsupportedRoms: List<RomDto> = emptyList(),
    val errorMessage: String? = null,
)

class CollectionDetailViewModel(
    private val repository: RommRepository,
    private val kind: String,
    private val collectionId: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CollectionDetailUiState())
    val uiState: StateFlow<CollectionDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                val normalizedKind = runCatching { CollectionKind.valueOf(kind.uppercase()) }.getOrDefault(CollectionKind.REGULAR)
                val collection = repository.getCollections().firstOrNull { it.kind == normalizedKind && it.id == collectionId }
                    ?: error("This collection is no longer available.")
                collection to repository.getRomsForCollection(collection)
            }.fold(
                onSuccess = { (collection, roms) ->
                    val (unsupportedRoms, supportedRoms) = roms.partition(repository::isUnsupportedInApp)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            collection = collection,
                            supportedRoms = supportedRoms,
                            unsupportedRoms = unsupportedRoms,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Unable to load this collection.",
                        )
                    }
                },
            )
        }
    }
}
