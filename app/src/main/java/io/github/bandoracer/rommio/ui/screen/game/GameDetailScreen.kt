package io.github.bandoracer.rommio.ui.screen.game

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
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
import io.github.bandoracer.rommio.domain.stats.PlayStats
import io.github.bandoracer.rommio.model.DownloadStatus
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RomFileDto
import io.github.bandoracer.rommio.ui.component.EmptyState
import io.github.bandoracer.rommio.ui.component.GameCover
import io.github.bandoracer.rommio.ui.component.LoadingSkeletonPanel
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.RoleText
import io.github.bandoracer.rommio.ui.component.RommioProgressBar
import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.SegmentedChoice
import io.github.bandoracer.rommio.ui.component.Tone
import io.github.bandoracer.rommio.ui.component.formatBytes
import io.github.bandoracer.rommio.ui.component.formatPlayTime
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat
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
 * saves reader, with previews), this device's play and the RomM details.
 */
class GameContent(
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
) {
    val tier: EmbeddedSupportTier get() = state.supportTier

    /** "Super Nintendo · 1994" (the year when RomM knows it). */
    val platformLine: String
        get() = listOfNotNull(rom.platformName.takeIf { it.isNotBlank() }, rom.metadata.releaseYear?.toString()).joinToString(" · ")
    val tierLabel: String get() = io.github.bandoracer.rommio.ui.component.supportTierLabel(tier)

    /** Where the game stands on this device. */
    val statusText: String
        get() = when {
            state.isLocalOnly -> "On this device · removed from RomM"
            selectedInstalled -> "On this device"
            state.downloadRecord?.status == DownloadStatus.RUNNING -> "Downloading"
            state.downloadRecord?.status == DownloadStatus.QUEUED -> "Queued"
            state.downloadRecord?.status == DownloadStatus.FAILED -> "Download failed"
            else -> "Not downloaded"
        }

    val statusTone: Tone
        get() = when {
            selectedInstalled -> Tone.OK
            state.downloadRecord?.status == DownloadStatus.RUNNING || state.downloadRecord?.status == DownloadStatus.QUEUED -> Tone.LIVE
            state.downloadRecord?.status == DownloadStatus.FAILED -> Tone.BAD
            else -> Tone.MUTE
        }

    /** "Played 1 h 05 min · 3 sessions · last 2 hours ago". */
    val playLine: String?
        get() {
            if (sessionCount <= 0) return null
            return listOfNotNull(
                "Played ${formatPlayTime(playedMs)}",
                "$sessionCount session${if (sessionCount == 1) "" else "s"}",
                lastPlayedAtEpochMs?.let { "last ${PlayFormat.relative(it)}" },
            ).joinToString(" · ")
        }

    /** The first sentence of the summary, for standfirsts. */
    val standfirst: String?
        get() {
            val summary = rom.summary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val sentence = summary.substringBefore(". ", summary).let { if (it.length < summary.length) "$it." else it }
            return if (sentence.length > 180) sentence.take(177) + "…" else sentence
        }

    /** The resume point's sync badge ("Synced · resume"), when it has one. */
    val resumeSync: SaveSyncStatus? get() = state.saves.resume?.sync?.takeIf { it != SaveSyncStatus.UNKNOWN }

    /** "1 resume point · 2 save states". */
    val savesCount: String
        get() = listOfNotNull(
            state.saves.resume?.let { "1 resume point" },
            state.saves.saveStates.size.takeIf { it > 0 }?.let { "$it save state${if (it == 1) "" else "s"}" },
        ).joinToString(" · ")

    val hasSaves: Boolean get() = !state.saves.isEmpty
    /** The conflict copy with its count ("… has two resume points. Choose which to keep playing."). */
    val problemText: String?
        get() = state.saves.conflict?.prompt(rom.displayName)

    /** "Start options" (the Launch choice): shown when the game can start and has both a resume point and game-save data. */
    val showsStartOptions: Boolean
        get() = state.saves.launchOptions != null && state.actionPresentation.primary?.kind == GameDetailActionKind.PLAY && selectedInstalled

    val showsFilePicker: Boolean get() = rom.files.size in 2..4
    val coreName: String? get() = state.support?.runtimeProfile?.displayName
    val controllerNote: String? get() = state.support?.message?.takeIf { it.isNotBlank() }
    val fileMeta: String?
        get() = selectedFile?.let { "${it.effectiveFileExtension.ifBlank { "file" }.uppercase()} · ${formatBytes(it.fileSizeBytes)}" }
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
 * bar that is clear over the art.
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
            initializer { GameDetailViewModel(container.repository, romId, container.savedProgress, container.playDataChanges) }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val stats by (container.playActivity.stats).collectAsStateWithLifecycle(initialValue = PlayStats())
    val baseUrl = container.repository.activeProfileFlow().collectAsStateWithLifecycle(initialValue = null).value?.baseUrl
    var showSheet by remember { mutableStateOf(false) }
    val theme = RommioTheme.current
    val rom = state.rom
    val selectedFile = viewModel.selectedFile()
    val scroll = rememberScrollState()

    // The installed file this page would start, and the game as the launch flow sees it.
    val installedFileId = viewModel.selectedInstall()?.let { selectedFile?.id }
    val target = rom?.let {
        io.github.bandoracer.rommio.ui.screen.LaunchTarget(it.id, it.displayName, installedFileId, resolveRemoteAssetUrl(baseUrl, it.urlCover), platformSlug = it.platformSlug)
    }
    // Play with a conflict asks which to keep first (Conflict sheet); a resume point this device
    // can't load asks how to start (Launch choice).
    val play: (Int, Int) -> Unit = { _, _ -> target?.let { launchFlow.play(it, known = state.saves) } }
    val actions = GameActions(
        perform = { action -> handleAction(action.kind, viewModel, romId, selectedFile?.id, play) },
        selectFile = viewModel::selectFile,
        browseSaves = { showSheet = true },
        chooseResume = { target?.let { launchFlow.chooseConflict(it, known = state.saves) } },
        startOptions = { target?.let { launchFlow.startOptions(it, known = state.saves) } },
    )

    Box(Modifier.fillMaxSize().testTag("shell.gameList")) {
        if (rom == null) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = 56.dp).padding(horizontal = theme.spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(theme.spacing.section),
            ) {
                if (state.isLoading) LoadingSkeletonPanel(showArtwork = true, lines = 5)
                state.errorMessage?.let { message ->
                    EmptyState(
                        title = "Game unavailable",
                        message = "$message If it was removed from RomM, you can still manage any copy on this device from Downloads.",
                    )
                }
            }
        } else {
            val played = stats.games.firstOrNull { it.romId.toInt() == rom.id }
            val content = GameContent(
                state = state,
                rom = rom,
                coverUrl = resolveRemoteAssetUrl(baseUrl, rom.urlCover),
                baseUrl = baseUrl,
                selectedFile = selectedFile,
                selectedInstalled = viewModel.selectedInstall() != null,
                stops = saveStops(state),
                playedMs = played?.playedMs ?: 0L,
                sessionCount = played?.sessionCount ?: 0,
                lastPlayedAtEpochMs = played?.lastPlayedAtEpochMs,
            )
            if (theme.isRiso) RisoGameLayout(content, actions, scroll) else AuroraGameLayout(content, actions, scroll)
        }
        GameTopBar(state, scroll, onBack, onToolbar = { action -> handleAction(action.kind, viewModel, romId, selectedFile?.id, play) })
    }

    if (showSheet && rom != null) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = theme.colors.groundRaised,
        ) {
            SavedProgressSheet(
                gameTitle = rom.displayName,
                saves = state.saves,
                coverUrl = resolveRemoteAssetUrl(baseUrl, rom.urlCover),
                canPlay = state.actionPresentation.primary?.kind == GameDetailActionKind.PLAY,
                onResume = {
                    showSheet = false
                    selectedFile?.id?.let { play(romId, it) }
                },
                onRename = { entry, name -> viewModel.renameSave(entry.key, name) },
                onClose = { showSheet = false },
            )
        }
    }
}

/**
 * The Game page's bar: back and, when the main action is Play or Download, that action. Clear
 * over the art; the page ground once the content scrolls under it.
 */
@Composable
private fun GameTopBar(state: GameDetailUiState, scroll: ScrollState, onBack: () -> Unit, onToolbar: (GameDetailAction) -> Unit) {
    val theme = RommioTheme.current
    val scrolled = scroll.value > with(LocalDensity.current) { 180.dp.toPx() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (scrolled) theme.colors.ground else Color.Transparent)
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
        val primary = state.actionPresentation.primary
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
internal fun GameArtHero(content: GameContent, height: Dp) {
    val theme = RommioTheme.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .semantics(mergeDescendants = true) { contentDescription = "Cover art for ${content.rom.displayName}" }
            .testTag("gameDetail.hero"),
    ) {
        GameCover(
            content.coverUrl,
            content.rom.displayName,
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
