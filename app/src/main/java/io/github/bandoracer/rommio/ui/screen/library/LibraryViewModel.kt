package io.github.bandoracer.rommio.ui.screen.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.model.ConnectivityState
import io.github.bandoracer.rommio.model.InstalledPlatformSummary
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryPlatform(
    val platform: PlatformDto,
    val tier: EmbeddedSupportTier,
    val installed: InstalledPlatformSummary?,
)

data class LibraryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val platforms: List<LibraryPlatform> = emptyList(),
    val recentInstalled: List<RomDto> = emptyList(),
    val recentInstalledSupport: Map<Int, EmbeddedSupportTier> = emptyMap(),
    val installedGameCount: Int = 0,
    val collections: List<RommCollectionDto> = emptyList(),
    val collectionPreviewCoverUrls: Map<String, List<String>> = emptyMap(),
    val errorMessage: String? = null,
    /** The cached library, for browsing covers (Aurora). */
    val roms: List<RomDto> = emptyList(),
    /** Games in progress, most recent first (their covers lead the browse row). */
    val inProgress: List<RomDto> = emptyList(),
    /** Games with a copy on this device (for "N here"). */
    val installedRomIds: Set<Int> = emptySet(),
    /** The cached library grouped into eras by release year (Aurora's era ruler). */
    val eras: List<EraGroup> = emptyList(),
    /** A refresh failed while cached content is shown. */
    val staleMessage: String? = null,
) {
    fun installed(platform: LibraryPlatform): Int = platform.installed?.installedGameCount ?: 0

    /** "2,164 games · 22 platforms · 91 on this device". */
    val summary: String
        get() = "${java.text.NumberFormat.getIntegerInstance().format(totalGames)} game${if (totalGames == 1) "" else "s"} · " +
            "${platforms.size} platform${if (platforms.size == 1) "" else "s"} · $installedGameCount on this device"

    val totalGames: Int get() = platforms.sumOf { it.platform.romCount }
    fun platforms(tier: EmbeddedSupportTier?): List<LibraryPlatform> = if (tier == null) platforms else platforms.filter { it.tier == tier }
    fun count(tier: EmbeddedSupportTier): Int = platforms.count { it.tier == tier }
    val hasContent: Boolean get() = platforms.isNotEmpty() || recentInstalled.isNotEmpty()

    // Kept for existing callers.
    val touchSupportedPlatforms: List<PlatformDto> get() = platforms(EmbeddedSupportTier.TOUCH_SUPPORTED).map { it.platform }
    val controllerSupportedPlatforms: List<PlatformDto> get() = platforms(EmbeddedSupportTier.CONTROLLER_SUPPORTED).map { it.platform }
    val unsupportedPlatforms: List<PlatformDto> get() = platforms(EmbeddedSupportTier.UNSUPPORTED).map { it.platform }
}

class LibraryViewModel(
    private val repository: RommRepository,
    private val playActivity: io.github.bandoracer.rommio.data.stats.PlayActivityRepository? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeCachedPlatforms(),
                repository.observeInstalledPlatformSummaries(),
                repository.observeRecentInstalledRoms(limit = 12),
                repository.observeLibraryStorageSummary(),
            ) { platforms, summaries, recentInstalled, storage ->
                val entries = platforms
                    .sortedBy { it.name.lowercase() }
                    .map { platform ->
                        LibraryPlatform(
                            platform = platform,
                            tier = repository.embeddedSupportTier(platform),
                            installed = summaries.firstOrNull { it.platformSlug == platform.slug || it.platformSlug == platform.fsSlug },
                        )
                    }
                    .sortedWith(compareBy<LibraryPlatform> { it.tier.ordinalForLibrary() }.thenBy { it.platform.name.lowercase() })
                _uiState.update {
                    it.copy(
                        platforms = entries,
                        recentInstalled = recentInstalled,
                        recentInstalledSupport = recentInstalled.associate { rom -> rom.id to repository.embeddedSupportTier(rom) },
                        installedGameCount = storage.installedGameCount,
                        isLoading = if (entries.isNotEmpty() || recentInstalled.isNotEmpty()) false else it.isLoading,
                    )
                }
            }.collect {}
        }
        viewModelScope.launch {
            val stats = playActivity?.stats ?: kotlinx.coroutines.flow.flowOf(io.github.bandoracer.rommio.domain.stats.PlayStats())
            combine(repository.observeCachedRoms(), repository.observeCachedHome(), stats, repository.observeInstalledLibrary()) { roms, home, played, installed ->
                val byId = roms.associateBy { it.id }
                val local = played.games.sortedByDescending { it.lastPlayedAtEpochMs }.mapNotNull { byId[it.romId.toInt()] }
                val seen = local.map { it.id }.toMutableSet()
                LibraryGames(roms, local + home.continuePlaying.filter { seen.add(it.id) }, installed.map { it.romId }.toSet(), LibraryEras.group(roms))
            }.collect { games ->
                _uiState.update { it.copy(roms = games.roms, inProgress = games.inProgress, installedRomIds = games.installed, eras = games.eras) }
            }
        }
        viewModelScope.launch {
            repository.observeCachedCollections().collect { collections ->
                val ordered = collections.sortedWith(compareBy<RommCollectionDto> { it.kind.ordinal }.thenBy { it.name.lowercase() })
                val previews = ordered.take(COLLECTION_ROW_LIMIT).associate { collection ->
                    "${collection.kind}:${collection.id}" to runCatching { repository.getCollectionPreviewCoverUrls(collection) }.getOrDefault(emptyList())
                }
                _uiState.update { it.copy(collections = ordered, collectionPreviewCoverUrls = previews) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = !it.hasContent, isRefreshing = true, errorMessage = null) }
            if (repository.currentConnectivityState() != ConnectivityState.ONLINE) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = if (it.hasContent) null else "You're offline. Your library appears here after this server syncs once.",
                    )
                }
                return@launch
            }
            runCatching {
                repository.refreshPlatformsInBackground()
                repository.refreshCollectionsInBackground()
            }.fold(
                onSuccess = { _uiState.update { it.copy(isLoading = false, isRefreshing = false, errorMessage = null, staleMessage = null) } },
                onFailure = { error ->
                    _uiState.update {
                        val message = error.message ?: "Couldn't load the library."
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = if (it.hasContent) null else message,
                            staleMessage = if (it.hasContent) message else null,
                        )
                    }
                },
            )
        }
    }

    companion object {
        const val COLLECTION_ROW_LIMIT = 8
    }
}

private fun EmbeddedSupportTier.ordinalForLibrary(): Int = when (this) {
    EmbeddedSupportTier.TOUCH_SUPPORTED -> 0
    EmbeddedSupportTier.CONTROLLER_SUPPORTED -> 1
    EmbeddedSupportTier.UNSUPPORTED -> 2
}

private data class LibraryGames(val roms: List<RomDto>, val inProgress: List<RomDto>, val installed: Set<Int>, val eras: List<EraGroup>)
