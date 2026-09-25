package io.github.bandoracer.rommio.ui.screen.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.ui.component.ToolbarIconButton
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import io.github.bandoracer.rommio.util.resolveRemoteAssetUrl

/** What Play can do; the layouts only call these. */
class PlayActions(
    val resume: (InProgressGame) -> Unit,
    val open: (romId: Int) -> Unit,
    val openCollection: (RommCollectionDto) -> Unit,
    val openDownloads: () -> Unit,
    /** A Recent saves tile: its game, or the Conflict sheet when its resume point is in conflict. */
    val openSave: (RecentSaveItem) -> Unit = { open(it.rom.id) },
    /** Long press › "Start options": the Launch choice (resume or the game save). */
    val startOptions: (InProgressGame) -> Unit = resume,
) {
    /** A game's long-press menu: Resume, Start options (when it can start here) and Game details. */
    fun menu(game: InProgressGame, details: Boolean = true): List<io.github.bandoracer.rommio.ui.component.MenuAction> = listOfNotNull(
        io.github.bandoracer.rommio.ui.component.MenuAction("Resume") { resume(game) },
        game.playableFileId?.let { io.github.bandoracer.rommio.ui.component.MenuAction("Start options") { startOptions(game) } },
        if (details) io.github.bandoracer.rommio.ui.component.MenuAction("Game details") { open(game.rom.id) } else null,
    )
}

/** The installed file to start, when the game can be played on this device. */
val InProgressGame.playableFileId: Int?
    get() = installedFileId?.takeIf { supportTier != EmbeddedSupportTier.UNSUPPORTED }

/**
 * Play (docs/theme-layouts.md "Play — two layouts"): Continue playing first, then recent
 * activity. One content model ([PlayPage], built by [PlayViewModel]); Aurora pages through the
 * games in progress, lit by each, and Riso sets the page like a front page. Downloads are one tap
 * away in the toolbar.
 */
@Composable
fun PlayScreen(
    container: AppContainer,
    imageBaseUrl: String?,
    onOpenGame: (romId: Int) -> Unit,
    /** Starts games (asking first — Conflict sheet, Launch choice — when the launch planner would). */
    launchFlow: io.github.bandoracer.rommio.ui.screen.LaunchFlow,
    onOpenDownloads: () -> Unit,
    onOpenCollection: (RommCollectionDto) -> Unit = {},
) {
    val viewModel: PlayViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PlayViewModel(container.repository, container.playActivity, container.savedProgress, container.playDataChanges) }
        },
    )
    val page by viewModel.page.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshActivity() }
    val actions = remember(imageBaseUrl, onOpenGame, launchFlow, onOpenDownloads, onOpenCollection) {
        val cover: (RomDto) -> String? = { rom -> resolveRemoteAssetUrl(imageBaseUrl, rom.urlCover) }
        fun target(rom: RomDto, fileId: Int?) = io.github.bandoracer.rommio.ui.screen.LaunchTarget(rom.id, rom.displayName, fileId, cover(rom), platformSlug = rom.platformSlug)
        PlayActions(
            // A conflict asks which to keep first (Conflict sheet); a resume point this device can't
            // load asks how to start (Launch choice); otherwise the player opens.
            resume = { game ->
                val fileId = game.playableFileId
                if (fileId != null) launchFlow.play(target(game.rom, fileId)) else onOpenGame(game.rom.id)
            },
            open = onOpenGame,
            openCollection = onOpenCollection,
            openDownloads = onOpenDownloads,
            openSave = { item ->
                if (item.entry.sync == io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus.CONFLICT && item.entry.isCurrentResume) {
                    launchFlow.chooseConflict(target(item.rom, item.playableFileId)) { onOpenGame(item.rom.id) }
                } else {
                    onOpenGame(item.rom.id)
                }
            },
            startOptions = { game ->
                val fileId = game.playableFileId
                if (fileId != null) launchFlow.startOptions(target(game.rom, fileId)) else onOpenGame(game.rom.id)
            },
        )
    }
    if (RommioTheme.isRiso) {
        RisoPlayLayout(page, actions)
    } else {
        AuroraPlayLayout(page, actions)
    }
}

/** The Play toolbar: the Downloads button with its queue count at the trailing edge. */
@Composable
internal fun PlayToolbar(downloadCount: Int, openDownloads: () -> Unit, gutter: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = gutter - 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarIconButton(
            icon = Icons.Outlined.Download,
            contentDescription = "Downloads",
            onClick = openDownloads,
            badgeCount = downloadCount,
            modifier = Modifier.testTag("play.downloads"),
        )
    }
}
