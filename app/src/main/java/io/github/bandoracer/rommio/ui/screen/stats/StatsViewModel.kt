package io.github.bandoracer.rommio.ui.screen.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.data.stats.PlayActivityRepository
import io.github.bandoracer.rommio.domain.stats.GamePlayTotal
import io.github.bandoracer.rommio.domain.stats.PlaySession
import io.github.bandoracer.rommio.domain.stats.PlayStats
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.screen.GameIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsGame(
    val total: GamePlayTotal,
    val rom: RomDto,
    /** The installed file to start when the game can be played here (Resume on the Stats lead). */
    val playableFileId: Int? = null,
)
data class StatsSession(val session: PlaySession, val rom: RomDto)

data class StatsUiState(
    val isLoading: Boolean = true,
    val stats: PlayStats = PlayStats(),
    val games: List<StatsGame> = emptyList(),
    val sessions: List<StatsSession> = emptyList(),
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class StatsViewModel(
    private val repository: RommRepository,
    private val playActivity: PlayActivityRepository,
    playDataChanges: kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.emptyFlow(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                playActivity.stats,
                repository.observeCachedRoms(),
                repository.observeInstalledLibrary(),
            ) { stats, cached, installed ->
                val index = GameIndex(cached, installed)
                fun rom(id: Long): RomDto = index[id.toInt()] ?: RomDto(id = id.toInt(), name = "Game $id")
                StatsUiState(
                    isLoading = false,
                    stats = stats,
                    games = stats.games.take(GAME_LIMIT).map { total ->
                        val rom = rom(total.romId)
                        val fileId = index.installedFileId(rom.id)
                            ?.takeIf { repository.embeddedSupportTier(rom) != io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier.UNSUPPORTED }
                        StatsGame(total, rom, fileId)
                    },
                    sessions = stats.sessions.take(SESSION_LIMIT).map { StatsSession(it, rom(it.romId)) },
                )
            }.collect { next -> _uiState.update { next.copy(isLoading = it.isLoading && next.stats.isEmpty) } }
        }
        viewModelScope.launch {
            playDataChanges.drop(1).debounce(400).collect { playActivity.refresh() }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            playActivity.refresh()
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    companion object {
        const val GAME_LIMIT = 8
        const val SESSION_LIMIT = 40
    }
}
