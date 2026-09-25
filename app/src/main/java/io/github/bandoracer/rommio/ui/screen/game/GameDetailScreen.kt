package io.github.bandoracer.rommio.ui.screen.game

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveKind
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.domain.stats.GamePlayTotal
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RomFileDto
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.LitPage
import io.github.bandoracer.rommio.ui.component.LoadingSkeletonPanel
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RommioProgressBar
import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.SegmentedChoice
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.formatBytes
import io.github.bandoracer.rommio.ui.component.formatPlayTime
import io.github.bandoracer.rommio.ui.theme.LitMaterialTheme
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl

/** One stop on the saves timeline (resume point, save state, earlier resume point), newest first. */
data class SaveStop(
    val entry: SaveEntry,
    /** "Automatic resume", "Save state · Slot 2", "Earlier resume point". */
    val kindTitle: String,
    val title: String,
) {
    val isResume: Boolean get() = entry.kind == SaveKind.RESUME_POINT && entry.isCurrentResume
}

/**
 * Everything both Game layouts draw (docs/theme-layouts.md "Game — two layouts"): the game, its
 * tier, where it stands, the action deck, the files, its saved progress (Android: from the local
 * saves reader, with previews), this device's play and the RomM details. Built by
 * [GameDetailViewModel] once per data change (never in composition); compared by value.
 */
@Immutable
data class GameContent(
    val state: GameDetailUiState,
    val rom: RomDto,
    val coverUrl: String?,
    val baseUrl: String?,
    val selectedFile: RomFileDto?,
    val selectedInstalled: Boolean,
    val stops: List<SaveStop>,
    val playedMs: Long,
    val sessionCount: Int,
    val lastPlayedAtEpochMs: Long?,
    /** "Super Nintendo · 1994" (the year when RomM knows it). */
    val platformLine: String,
    val tierLabel: String,
    /** Where the game stands on this device. */
    val statusText: String,
    val statusTone: Tone,
    /** "Played 1 h 05 min · 3 sessions · last 2 hours ago". */
    val playLine: String?,
    /** The first sentence of the summary, for standfirsts. */
    val standfirst: String?,
    /** The resume point's sync badge ("Synced · resume"), when it has one. */
    val resumeSync: SaveSyncStatus?,
    /** "1 resume point · 2 save states". */
    val savesCount: String,
    val hasSaves: Boolean,
    /** The conflict copy with its count ("… has two resume points. Choose which to keep playing."). */
    val problemText: String?,
    /** "Start options" (the Launch choice): the game can start and has both a resume point and game-save data. */
    val showsStartOptions: Boolean,
    val showsFilePicker: Boolean,
    val coreName: String?,
    val controllerNote: String?,
    val fileMeta: String?,
    /** RomM's details, in reading order. */
    val details: List<Pair<String, String>>,
) {
    val tier: EmbeddedSupportTier get() = state.supportTier

    companion object {
        fun build(
            state: GameDetailUiState,
            rom: RomDto,
            baseUrl: String?,
            selectedFile: RomFileDto?,
            selectedInstalled: Boolean,
            played: GamePlayTotal?,
            now: Long = System.currentTimeMillis(),
        ): GameContent {
            val sessionCount = played?.sessionCount ?: 0
            val playedMs = played?.playedMs ?: 0L
            val lastPlayed = played?.lastPlayedAtEpochMs
            val summary = rom.summary?.trim()?.takeIf { it.isNotEmpty() }
            return GameContent(
                state = state,
                rom = rom,
                coverUrl = resolveRemoteAssetUrl(baseUrl, rom.urlCover),
                baseUrl = baseUrl,
                selectedFile = selectedFile,
                selectedInstalled = selectedInstalled,
                stops = saveStops(state),
                playedMs = playedMs,
                sessionCount = sessionCount,
                lastPlayedAtEpochMs = lastPlayed,
                platformLine = listOfNotNull(rom.platformName.takeIf { it.isNotBlank() }, rom.metadata.releaseYear?.toString()).joinToString(" · "),
                tierLabel = io.github.bandoracer.rommio.ui.component.supportTierLabel(state.supportTier),
                statusText = when {
                    state.isLocalOnly -> "On this device · removed from RomM"
                    selectedInstalled -> "On this device"
                    state.downloadRecord?.status == DownloadStatus.RUNNING -> "Downloading"
                    state.downloadRecord?.status == DownloadStatus.QUEUED -> "Queued"
                    state.downloadRecord?.status == DownloadStatus.FAILED -> "Download failed"
                    else -> "Not downloaded"
                },
                statusTone = when {
                    selectedInstalled -> Tone.OK
                    state.downloadRecord?.status == DownloadStatus.RUNNING || state.downloadRecord?.status == DownloadStatus.QUEUED -> Tone.LIVE
                    state.downloadRecord?.status == DownloadStatus.FAILED -> Tone.BAD
                    else -> Tone.MUTE
                },
                playLine = if (sessionCount <= 0) null else listOfNotNull(
                    "Played ${formatPlayTime(playedMs)}",
                    "$sessionCount session${if (sessionCount == 1) "" else "s"}",
                    lastPlayed?.let { "last ${PlayFormat.relative(it, now)}" },
                ).joinToString(" · "),
                standfirst = summary?.let {
                    val sentence = it.substringBefore(". ", it).let { first -> if (first.length < it.length) "$first." else first }
                    if (sentence.length > 180) sentence.take(177) + "…" else sentence
                },
                resumeSync = state.saves.resume?.sync?.takeIf { it != SaveSyncStatus.UNKNOWN },
                savesCount = listOfNotNull(
                    state.saves.resume?.let { "1 resume point" },
                    state.saves.saveStates.size.takeIf { it > 0 }?.let { "$it save state${if (it == 1) "" else "s"}" },
                ).joinToString(" · "),
                hasSaves = !state.saves.isEmpty,
                problemText = state.saves.conflict?.prompt(rom.displayName),
                showsStartOptions = state.saves.launchOptions != null && state.actionPresentation.primary?.kind == GameDetailActionKind.PLAY && selectedInstalled,
                showsFilePicker = rom.files.size in 2..4,
                coreName = state.support?.runtimeProfile?.displayName,
                controllerNote = state.support?.message?.takeIf { it.isNotBlank() },
                fileMeta = selectedFile?.let { "${it.effectiveFileExtension.ifBlank { "file" }.uppercase()} · ${formatBytes(it.fileSizeBytes)}" },
                details = metadataDetails(rom.metadata),
            )
        }
    }
}

fun SaveSyncStatus.tone(): Tone = when (this) {
    SaveSyncStatus.SYNCED -> Tone.OK
    SaveSyncStatus.SYNCING -> Tone.LIVE
    SaveSyncStatus.WAITING, SaveSyncStatus.SIGN_IN_NEEDED -> Tone.WAIT
    SaveSyncStatus.CONFLICT -> Tone.BAD
    SaveSyncStatus.LOCAL_ONLY, SaveSyncStatus.UNKNOWN -> Tone.MUTE
}

/** What the Game layouts can do. */
class GameActions(
    val perform: (GameDetailAction) -> Unit,
    val selectFile: (Int) -> Unit,
    val browseSaves: () -> Unit,
    /** Opens the Conflict sheet (two resume points, or two versions of the game-save data). */
    val chooseResume: () -> Unit = {},
    /** Opens the Launch choice ("Start options"). */
    val startOptions: () -> Unit = {},
)

internal fun saveStops(content: GameDetailUiState): List<SaveStop> {
    val saves = content.saves
    val stops = buildList {
        saves.resume?.let { add(SaveStop(it, "Automatic resume", it.label ?: "Resume point")) }
        saves.saveStates.forEach { add(SaveStop(it, it.slotNumber?.let { n -> "Save state · Slot $n" } ?: "Save state", it.label ?: it.title)) }
        saves.history.take(6).forEach { add(SaveStop(it, "Earlier resume point", it.label ?: SavedProgressReason.of(it))) }
    }
    return stops.sortedByDescending { it.entry.capturedAtEpochMs }.take(10)
}

private object SavedProgressReason {
    fun of(entry: SaveEntry): String = io.github.bandoracer.rommio.domain.playdata.SavedProgressRules.reasonLabel(entry.captureReason)
}

/**
 * Game (docs/theme-layouts.md "Game — two layouts"): reached from Play, Library, Search and
 * Stats. Aurora: an art-forward hero lit by the cover, with the game's saves as a timeline. Riso:
 * an article with where you left off and a details box. The page draws its own title under a
 * bar that is clear over the art. The page is built by [GameDetailViewModel] ([GamePage]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(
    container: AppContainer,
    romId: Int,
    onBack: () -> Unit,
    /** Starts the game; the Conflict sheet and the Launch choice come first when needed. */
    launchFlow: io.github.bandoracer.rommio.ui.screen.LaunchFlow,
) {
    val viewModel: GameDetailViewModel = viewModel(
        key = "game-$romId",
        factory = viewModelFactory {
            initializer { GameDetailViewModel(container.repository, romId, container.savedProgress, container.playDataChanges, container.playActivity) }
        },
    )
    val page by viewModel.page.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val content = page.content

    // The installed file this page would start, and the game as the launch flow sees it (read when
    // an action runs, not while drawing).
    val actions = remember(viewModel, launchFlow, romId) {
        fun target(): io.github.bandoracer.rommio.ui.screen.LaunchTarget? {
            val state = viewModel.uiState.value
            val rom = state.rom ?: return null
            val baseUrl = viewModel.page.value.content?.baseUrl
            val installedFileId = viewModel.selectedInstall()?.let { viewModel.selectedFile()?.id }
            return io.github.bandoracer.rommio.ui.screen.LaunchTarget(rom.id, rom.displayName, installedFileId, resolveRemoteAssetUrl(baseUrl, rom.urlCover), platformSlug = rom.platformSlug)
        }
        // Play with a conflict asks which to keep first (Conflict sheet); a resume point this device
        // can't load asks how to start (Launch choice).
        val play: (Int, Int) -> Unit = { _, _ -> target()?.let { launchFlow.play(it, known = viewModel.uiState.value.saves) } }
        GameScreenActions(
            play = play,
            game = GameActions(
                perform = { action -> handleAction(action.kind, viewModel, romId, viewModel.selectedFile()?.id, play) },
                selectFile = viewModel::selectFile,
                browseSaves = { showSheet = true },
                chooseResume = { target()?.let { launchFlow.chooseConflict(it, known = viewModel.uiState.value.saves) } },
                startOptions = { target()?.let { launchFlow.startOptions(it, known = viewModel.uiState.value.saves) } },
            ),
        )
    }

    val riso = RommioTheme.isRiso
    LitPage(if (riso) null else content?.coverUrl, if (riso) PageFieldStyle.QUIET else PageFieldStyle.PAGE) {
        Box(Modifier.fillMaxSize().testTag("shell.gameList")) {
            if (content == null) {
                GameUnavailable(page)
            } else if (riso) {
                RisoGameLayout(content, actions.game, scroll)
            } else {
                AuroraGameLayout(content, actions.game, scroll)
            }
            GameTopBar(page.primary, scroll, onBack, onToolbar = actions.game.perform)
        }

        if (showSheet && content != null) {
            // A stock sheet: its drag handle and scrim take the page's lit scheme.
            LitMaterialTheme {
                ModalBottomSheet(
                    onDismissRequest = { showSheet = false },
                    containerColor = RommioTheme.colors.groundRaised,
                ) {
                    SavedProgressSheet(
                        gameTitle = content.rom.displayName,
                        saves = content.state.saves,
                        coverUrl = content.coverUrl,
                        canPlay = content.state.actionPresentation.primary?.kind == GameDetailActionKind.PLAY,
                        onResume = {
                            showSheet = false
                            viewModel.selectedFile()?.id?.let { actions.play(romId, it) }
                        },
                        onRename = { entry, name -> viewModel.renameSave(entry.key, name) },
                        onClose = { showSheet = false },
                    )
                }
            }
        }
    }
}

private class GameScreenActions(val play: (Int, Int) -> Unit, val game: GameActions)

@Composable
private fun GameUnavailable(page: GamePage) {
    val theme = RommioTheme.current
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = 56.dp).padding(horizontal = theme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.section),
    ) {
        if (page.isLoading) LoadingSkeletonPanel(showArtwork = true, lines = 5)
        page.errorMessage?.let { message ->
            EmptyState(
                title = "Game unavailable",
                message = "$message If it was removed from RomM, you can still manage any copy on this device from Downloads.",
            )
        }
    }
}

/**
 * The Game page's bar: back and, when the main action is Play or Download, that action. Clear
 * over the art; the page ground once the content scrolls under it (it recomposes only when the
 * scroll crosses that point, not on every scroll frame).
 */
@Composable
private fun GameTopBar(primary: GameDetailAction?, scroll: ScrollState, onBack: () -> Unit, onToolbar: (GameDetailAction) -> Unit) {
    val threshold = with(LocalDensity.current) { 180.dp.toPx() }
    val scrolled by remember(scroll, threshold) { derivedStateOf { scroll.value > threshold } }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (scrolled) Modifier.background(RommioTheme.light.ground) else Modifier)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Over the art the bar's buttons sit on the theme's icon-button face (glass / paper) to stay legible.
        io.github.bandoracer.rommio.ui.component.IconCircleButton(
            Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "Back",
            onClick = onBack,
            size = 40.dp,
            modifier = Modifier.testTag("shell.back"),
        )
        Box(Modifier.weight(1f))
        if (primary != null && (primary.kind == GameDetailActionKind.PLAY || primary.kind == GameDetailActionKind.DOWNLOAD_NOW)) {
            val play = primary.kind == GameDetailActionKind.PLAY
            io.github.bandoracer.rommio.ui.component.IconCircleButton(
                if (play) Icons.Outlined.PlayArrow else Icons.Outlined.Download,
                contentDescription = if (play) "Play" else "Download",
                onClick = { onToolbar(primary) },
                enabled = primary.enabled,
                size = 40.dp,
                modifier = Modifier.testTag(if (play) "game.toolbar.play" else "game.toolbar.download"),
            )
        }
    }
}

/** The page height the hero scales with (the whole window, the hero runs under the bar). */
@Composable
internal fun heroHeight(fraction: Float): Dp {
    val page = LocalConfiguration.current.screenHeightDp.dp
    return (page * fraction).coerceIn(220.dp, 400.dp)
}

/**
 * The cover across the top of the Game page. Aurora: it dissolves into the lit field (opaque to
 * 35 %, 45 % at 70 %, clear at the bottom). Riso: printed, with a 1.5 dp ink rule under it.
 */
@Composable
internal fun GameArtHero(coverUrl: String?, title: String, height: Dp) {
    val theme = RommioTheme.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .semantics(mergeDescendants = true) { contentDescription = "Cover art for $title" }
            .testTag("gameDetail.hero"),
    ) {
        GameCover(
            coverUrl,
            title,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (theme.isGlass) {
                        Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    Brush.verticalGradient(
                                        0f to Color.Black,
                                        0.35f to Color.Black,
                                        0.7f to Color.Black.copy(alpha = 0.45f),
                                        1f to Color.Transparent,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                    } else {
                        Modifier
                    },
                ),
            elevated = false,
            cornerRadius = 0.dp,
            aspect = null,
        )
        if (theme.isRiso) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.5.dp).background(theme.colors.ink))
        }
    }
}

/** The main action and the quieter ones for the selected file, with progress and notes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GameActionDeck(content: GameContent, actions: GameActions, showsNotes: Boolean = true) {
    val theme = RommioTheme.current
    val c = theme.colors
    val deck = content.state.actionPresentation
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        deck.primary?.let { primary ->
            PrimaryButton(
                text = primary.label,
                onClick = { actions.perform(primary) },
                enabled = primary.enabled,
                icon = when (primary.kind) {
                    GameDetailActionKind.PLAY -> Icons.Outlined.PlayArrow
                    GameDetailActionKind.DOWNLOAD_NOW, GameDetailActionKind.DOWNLOAD_CORE, GameDetailActionKind.QUEUE -> Icons.Outlined.Download
                    else -> Icons.Outlined.Refresh
                },
                modifier = Modifier.fillMaxWidth().testTag("game.primaryAction"),
            )
        }
        content.state.downloadRecord?.takeIf { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }?.let { record ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RommioProgressBar(record.progressPercent / 100f, tone = Tone.LIVE)
                RoleText(
                    "${record.progressPercent}% · ${formatBytes(record.bytesDownloaded)} of ${formatBytes(maxOf(record.totalBytes, record.fileSizeBytes))}",
                    theme.type.caption,
                    color = c.inkSecondary,
                )
            }
        }
        if (deck.secondary.isNotEmpty() || content.showsStartOptions) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (content.showsStartOptions) {
                    SecondaryButton(
                        text = "Start options",
                        onClick = actions.startOptions,
                        icon = Icons.Outlined.Tune,
                        modifier = Modifier.testTag("game.startOptions"),
                    )
                }
                deck.secondary.forEach { action ->
                    SecondaryButton(
                        text = action.label,
                        onClick = { actions.perform(action) },
                        enabled = action.enabled,
                        destructive = action.kind == GameDetailActionKind.DELETE_LOCAL || action.kind == GameDetailActionKind.CANCEL,
                        modifier = Modifier.testTag("game.secondary.${action.kind.name.lowercase()}"),
                    )
                }
            }
        }
        if (showsNotes) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                content.coreName?.let { RoleText("Runs on $it", theme.type.caption, color = c.inkTertiary) }
                content.state.coreMessage?.let { RoleText(it, theme.type.caption, color = c.inkSecondary) }
                content.state.downloadRecord?.lastError?.takeIf { it.isNotBlank() }?.let { RoleText(it, theme.type.caption, color = c.badText) }
            }
        }
    }
}

/** The file picker when a game has a few files (more are chosen in the file list). */
@Composable
internal fun GameFilePicker(content: GameContent, actions: GameActions) {
    if (!content.showsFilePicker) return
    val files = content.rom.files
    SegmentedChoice(
        options = files.map { it.id },
        selected = content.selectedFile?.id ?: files.first().id,
        label = { id -> files.first { it.id == id }.fileName.substringBeforeLast('.').take(18) },
        onSelect = actions.selectFile,
        modifier = Modifier.testTag("gameDetail.filePicker"),
    )
}

private fun handleAction(
    kind: GameDetailActionKind,
    viewModel: GameDetailViewModel,
    romId: Int,
    selectedFileId: Int?,
    onPlay: (romId: Int, fileId: Int) -> Unit,
) {
    when (kind) {
        GameDetailActionKind.PLAY -> if (selectedFileId != null) onPlay(romId, selectedFileId)
        GameDetailActionKind.DOWNLOAD_NOW -> viewModel.downloadNow()
        GameDetailActionKind.DOWNLOAD_CORE -> viewModel.downloadRecommendedCore()
        GameDetailActionKind.QUEUE -> viewModel.enqueueDownload()
        GameDetailActionKind.DELETE_LOCAL -> viewModel.deleteLocal()
        GameDetailActionKind.CANCEL -> viewModel.cancelDownload()
        GameDetailActionKind.RETRY -> viewModel.retryDownload()
        GameDetailActionKind.SYNC -> viewModel.syncNow()
    }
}
