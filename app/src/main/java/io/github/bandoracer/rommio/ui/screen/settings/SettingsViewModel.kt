package io.github.bandoracer.rommio.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.input.AppearancePreferencesStore
import io.github.bandoracer.rommio.data.repository.PlayerControlsRepository
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.domain.input.PlayerControlsPreferences
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.model.AppearancePreferences
import io.github.bandoracer.rommio.model.AppearanceScheme
import io.github.bandoracer.rommio.model.AppearanceTheme
import io.github.bandoracer.rommio.model.OfflineState
import io.github.bandoracer.rommio.model.ServerProfile
import io.github.bandoracer.rommio.model.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoading: Boolean = true,
    val activeProfile: ServerProfile? = null,
    val profiles: List<ServerProfile> = emptyList(),
    val user: UserDto? = null,
    val controlsPreferences: PlayerControlsPreferences = PlayerControlsPreferences(),
    val appearance: AppearancePreferences = AppearancePreferences(),
    val installedGameCount: Int = 0,
    val installedFileCount: Int = 0,
    val storageBytes: Long = 0,
    val offlineState: OfflineState = OfflineState(),
    val libraryPath: String = "",
    val platformTiers: Map<EmbeddedSupportTier, Int> = emptyMap(),
    /** Every platform family's core on this device (Settings › Cores and BIOS). */
    val coreInventory: List<io.github.bandoracer.rommio.domain.player.CoreInventoryEntry> = emptyList(),
    /** Your server's platforms that no core in this build plays (browse only). */
    val browseOnlyPlatforms: List<String> = emptyList(),
    val errorMessage: String? = null,
)

class SettingsViewModel(
    private val repository: RommRepository,
    private val controlsRepository: PlayerControlsRepository,
    private val appearanceStore: AppearancePreferencesStore,
    libraryPath: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState(libraryPath = libraryPath))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.activeProfileFlow().collect { profile ->
                _uiState.update { it.copy(activeProfile = profile) }
            }
        }
        viewModelScope.launch {
            controlsRepository.observePreferences().collect { preferences ->
                _uiState.update { it.copy(controlsPreferences = preferences) }
            }
        }
        viewModelScope.launch {
            appearanceStore.preferencesFlow.collect { appearance ->
                _uiState.update { it.copy(appearance = appearance) }
            }
        }
        viewModelScope.launch {
            repository.observeLibraryStorageSummary().collect { summary ->
                _uiState.update {
                    it.copy(
                        installedGameCount = summary.installedGameCount,
                        installedFileCount = summary.installedFileCount,
                        storageBytes = summary.totalBytes,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeOfflineState().collect { offlineState ->
                _uiState.update { it.copy(offlineState = offlineState) }
            }
        }
        viewModelScope.launch {
            repository.observeCachedPlatforms().collect { platforms ->
                val tiers = platforms.groupingBy { repository.embeddedSupportTier(it) }.eachCount()
                val browseOnly = platforms.filter { repository.embeddedSupportTier(it) == EmbeddedSupportTier.UNSUPPORTED }
                    .map { it.name }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
                _uiState.update { it.copy(platformTiers = tiers, browseOnlyPlatforms = browseOnly) }
            }
        }
        refresh()
        refreshCores()
    }

    /** Re-resolves the core inventory (after a BIOS import, or when Cores and BIOS opens). */
    fun refreshCores() {
        viewModelScope.launch {
            val inventory = runCatching { repository.coreInventory() }.getOrDefault(emptyList())
            _uiState.update { it.copy(coreInventory = inventory) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                Pair(repository.listProfiles(), runCatching { repository.getCurrentUser() }.getOrNull())
            }.fold(
                onSuccess = { (profiles, user) ->
                    _uiState.update { it.copy(isLoading = false, profiles = profiles, user = user) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Couldn't load settings.") }
                },
            )
        }
    }

    fun setTheme(theme: AppearanceTheme) {
        viewModelScope.launch { appearanceStore.setTheme(theme) }
    }

    fun setScheme(scheme: AppearanceScheme) {
        viewModelScope.launch { appearanceStore.setScheme(scheme) }
    }

    fun setTouchControlsEnabled(enabled: Boolean) {
        viewModelScope.launch { controlsRepository.setTouchControlsEnabled(enabled) }
    }

    fun setAutoHideTouchOnController(enabled: Boolean) {
        viewModelScope.launch { controlsRepository.setAutoHideTouchOnController(enabled) }
    }

    fun setRumbleToDeviceEnabled(enabled: Boolean) {
        viewModelScope.launch { controlsRepository.setRumbleToDeviceEnabled(enabled) }
    }

    fun setOledBlackModeEnabled(enabled: Boolean) {
        viewModelScope.launch { controlsRepository.setOledBlackModeEnabled(enabled) }
    }

    fun setConsoleColorsEnabled(enabled: Boolean) {
        viewModelScope.launch { controlsRepository.setConsoleColorsEnabled(enabled) }
    }

    suspend fun deleteProfile(profileId: String): Boolean {
        val wasActive = _uiState.value.activeProfile?.id == profileId
        repository.deleteProfile(profileId)
        refresh()
        return wasActive
    }
}
