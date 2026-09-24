package io.github.bandoracer.rommio.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.domain.playdata.GameSaves
import io.github.bandoracer.rommio.domain.playdata.LaunchOptions
import io.github.bandoracer.rommio.domain.playdata.LaunchStart
import io.github.bandoracer.rommio.domain.playdata.SaveConflict
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.ui.component.LaunchChoiceSheet
import io.github.bandoracer.rommio.ui.component.ResumeConflictSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Opens the player route: the Conflict sheet's choice (`keep`) and the Launch choice (`start`) ride along. */
fun interface PlayerOpener {
    fun open(romId: Int, fileId: Int, keep: String?, start: LaunchStart?)
}

/**
 * A game about to start. [fileId] is the installed file (null when the game isn't on this device);
 * [progressScope] picks the install's content generation when known (the Game page).
 */
data class LaunchTarget(
    val romId: Int,
    val title: String,
    val fileId: Int?,
    val coverUrl: String?,
    val progressScope: String? = null,
    val platformSlug: String? = null,
)

/**
 * Starting a game from Play, Game, Search or Stats (docs/theme-layouts.md "Launch choice" and
 * "Conflict"). The saves reader is read first, so the choices the launch planner would otherwise
 * stop for inside the player are made before it opens:
 * - two or more heads of the resume point or of the game-save data → the Conflict sheet;
 * - a resume point this device can't load while game-save data exists → the Launch choice;
 * - otherwise the player opens at once.
 * "Start options" opens the Launch choice on request. The sheets write nothing; the player applies
 * the choice with the existing progress rules.
 */
@Stable
class LaunchFlow internal constructor(
    private val scope: CoroutineScope,
    private val readSaves: suspend (romId: Long, progressScope: String?) -> GameSaves,
    private val opener: PlayerOpener,
) {
    data class ConflictRequest(val target: LaunchTarget, val conflict: SaveConflict)
    data class ChoiceRequest(val target: LaunchTarget, val options: LaunchOptions)

    /** The Conflict sheet, when open. */
    var conflict by mutableStateOf<ConflictRequest?>(null)
        private set

    /** The Launch choice sheet, when open. */
    var choice by mutableStateOf<ChoiceRequest?>(null)
        private set

    /** Play / Resume: asks first only when the planner would. [known] skips the read (the Game page has it). */
    fun play(target: LaunchTarget, known: GameSaves? = null) {
        val fileId = target.fileId ?: return
        scope.launch {
            val saves = known ?: readSaves(target.romId.toLong(), target.progressScope)
            val options = saves.launchOptions
            when {
                saves.conflict != null -> conflict = ConflictRequest(target, saves.conflict)
                options != null && options.resumeUnavailable -> choice = ChoiceRequest(target, options)
                else -> opener.open(target.romId, fileId, null, null)
            }
        }
    }

    /** "Start options": the Launch choice (a conflict still goes to the Conflict sheet). */
    fun startOptions(target: LaunchTarget, known: GameSaves? = null) {
        val fileId = target.fileId ?: return
        scope.launch {
            val saves = known ?: readSaves(target.romId.toLong(), target.progressScope)
            val options = saves.launchOptions
            when {
                saves.conflict != null -> conflict = ConflictRequest(target, saves.conflict)
                options != null -> choice = ChoiceRequest(target, options)
                else -> opener.open(target.romId, fileId, null, null)
            }
        }
    }

    /**
     * "Choose resume point" and a conflicted Recent saves tile: the Conflict sheet (also when the
     * game isn't on this device yet); [otherwise] runs when there is no longer a conflict.
     */
    fun chooseConflict(target: LaunchTarget, known: GameSaves? = null, otherwise: () -> Unit = {}) {
        scope.launch {
            val saves = known ?: readSaves(target.romId.toLong(), target.progressScope)
            val found = saves.conflict
            if (found == null) otherwise() else conflict = ConflictRequest(target, found)
        }
    }

    /** The Conflict sheet's Keep playing. */
    fun keep(entry: SaveEntry) {
        val request = conflict ?: return
        conflict = null
        request.target.fileId?.let { opener.open(request.target.romId, it, entry.key.checkpointId, null) }
    }

    /** The Launch choice's primary button. */
    fun start(start: LaunchStart) {
        val request = choice ?: return
        choice = null
        request.target.fileId?.let { opener.open(request.target.romId, it, null, start) }
    }

    fun dismiss() {
        conflict = null
        choice = null
    }
}

@Composable
fun rememberLaunchFlow(container: AppContainer, opener: PlayerOpener): LaunchFlow {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(opener)
    return remember(container, scope) {
        LaunchFlow(scope, { romId, progressScope -> container.savedProgress.gameSaves(romId, progressScope) }) { romId, fileId, keep, start ->
            current.open(romId, fileId, keep, start)
        }
    }
}

/** The flow's sheets; the shell draws them once above every page. */
@Composable
fun LaunchFlowSheets(flow: LaunchFlow) {
    flow.conflict?.let { request ->
        ResumeConflictSheet(
            gameTitle = request.target.title,
            heads = request.conflict.choices,
            coverUrl = request.target.coverUrl,
            canPlay = request.target.fileId != null,
            onKeep = flow::keep,
            onDismiss = flow::dismiss,
            kind = request.conflict.kind,
        )
    }
    flow.choice?.let { request ->
        LaunchChoiceSheet(
            gameTitle = request.target.title,
            options = request.options,
            coverUrl = request.target.coverUrl,
            onStart = flow::start,
            onDismiss = flow::dismiss,
            platformSlug = request.target.platformSlug,
        )
    }
}
