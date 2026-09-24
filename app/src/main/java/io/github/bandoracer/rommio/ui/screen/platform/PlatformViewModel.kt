package io.github.bandoracer.rommio.ui.screen.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.data.stats.PlayActivityRepository
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.stats.PlayStats
import io.github.bandoracer.rommio.model.ConnectivityState
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RomDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A platform's game with what this device knows about it. */
data class PlatformGame(
    val rom: RomDto,
    val installed: Boolean,
    val playedMs: Long,
    val lastPlayedAtEpochMs: Long?,
)

enum class PlatformFilter(val title: String) { ALL("All"), ON_DEVICE("On this device"), PLAYED("Played") }

enum class PlatformOrder { ALPHABETICAL, RECENTLY_PLAYED }

/** Everything both Platform layouts draw (docs/theme-layouts.md "Platform — two layouts"). */
data class PlatformUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val platform: PlatformDto? = null,
    val tier: EmbeddedSupportTier = EmbeddedSupportTier.UNSUPPORTED,
    val games: List<PlatformGame> = emptyList(),
    val errorMessage: String? = null,
    val staleMessage: String? = null,
) {
    val installedCount: Int get() = games.count { it.installed }
    val playedCount: Int get() = games.count { it.playedMs > 0 }
    val totalCount: Int get() = maxOf(platform?.romCount ?: 0, games.size)

    fun count(filter: PlatformFilter): Int = when (filter) {
        PlatformFilter.ALL -> games.size
        PlatformFilter.ON_DEVICE -> installedCount
        PlatformFilter.PLAYED -> playedCount
    }

    fun filtered(filter: PlatformFilter, order: PlatformOrder): List<PlatformGame> {
        val chosen = when (filter) {
            PlatformFilter.ALL -> games
            PlatformFilter.ON_DEVICE -> games.filter { it.installed }
            PlatformFilter.PLAYED -> games.filter { it.playedMs > 0 }
        }
        val byName = compareBy<PlatformGame, String>(String.CASE_INSENSITIVE_ORDER) { it.rom.displayName }
        return when (order) {
            PlatformOrder.ALPHABETICAL -> chosen.sortedWith(byName)
            PlatformOrder.RECENTLY_PLAYED -> chosen.sortedWith(
                compareByDescending<PlatformGame> { it.lastPlayedAtEpochMs ?: Long.MIN_VALUE }.then(byName),
            )
        }
    }
}

class PlatformViewModel(
    private val repository: RommRepository,
    private val platformId: Int,
    private val playActivity: PlayActivityRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlatformUiState())
    val uiState: StateFlow<PlatformUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeCachedPlatforms(),
                repository.observeCachedPlatformRoms(platformId),
                repository.observeInstalledLibrary(),
                playActivity?.stats ?: flowOf(PlayStats()),
            ) { platforms, roms, installed, stats ->
                val platform = platforms.firstOrNull { it.id == platformId }
                val installedIds = installed.map { it.romId }.toSet()
                val played = stats.games.associateBy { it.romId.toInt() }
                val games = roms.map { rom ->
                    PlatformGame(rom, rom.id in installedIds, played[rom.id]?.playedMs ?: 0L, played[rom.id]?.lastPlayedAtEpochMs)
                }
                val tier = platform?.let(repository::embeddedSupportTier)
                    ?: roms.firstOrNull()?.let(repository::embeddedSupportTier)
                    ?: EmbeddedSupportTier.UNSUPPORTED
                Triple(platform, tier, games)
            }.collect { (platform, tier, games) ->
                _uiState.update {
                    it.copy(
                        platform = platform ?: it.platform,
                        tier = tier,
                        games = games,
                        isLoading = if (games.isNotEmpty()) false else it.isLoading,
                    )
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.games.isEmpty(), isRefreshing = true, errorMessage = null) }
            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (it.games.isNotEmpty()) null else "Offline. This platform will appear after the profile sync completes.",
                    )
                }
                return@launch
            }
            runCatching { repository.refreshPlatformInBackground(platformId) }.fold(
                onSuccess = { _uiState.update { it.copy(isLoading = false, isRefreshing = false, errorMessage = null, staleMessage = null) } },
                onFailure = { error ->
                    _uiState.update {
                        val message = error.message ?: "Unable to load platform ROMs."
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = if (it.games.isEmpty()) message else null,
                            staleMessage = if (it.games.isNotEmpty()) message else null,
                        )
                    }
                },
            )
        }
    }
}
