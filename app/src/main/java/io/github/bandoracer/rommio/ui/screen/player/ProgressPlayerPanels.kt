package io.github.bandoracer.rommio.ui.screen.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SaveAlt
import io.github.bandoracer.rommio.domain.playdata.SaveConflict
import io.github.bandoracer.rommio.domain.playdata.SaveConflictKind
import io.github.bandoracer.rommio.ui.component.DialogHeading
import io.github.bandoracer.rommio.ui.component.DialogText
import io.github.bandoracer.rommio.ui.component.Fmt
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.SectionHeader
import io.github.bandoracer.rommio.ui.component.TextAction
import io.github.bandoracer.rommio.ui.component.ThemedDialog
import io.github.bandoracer.rommio.ui.component.formatClockTime
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.domain.progress.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The player's own start prompt: the fallback when a launch reaches a choice the Launch choice and
 * Conflict sheets did not make first (cloud not checked, resume unusable, several heads, recovery).
 * Drawn with the theme's components (docs/theme-layouts.md "Launch choice": the same labels,
 * "Resume" and "Start with game save", plus "Back to library"); the choices call the same session
 * operations as before.
 */
@Composable
internal fun ProgressPlayerGate(owner: ApplicationProgressSession, state: ApplicationProgressState,
    onBack: () -> Unit, onMessage: (String) -> Unit, keepResume: String? = null) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var legacy by remember(owner) { mutableStateOf<List<java.io.File>>(emptyList()) }
    var interrupted by remember(owner) { mutableStateOf<List<Pair<AbandonedProgressWorkspace, List<PendingWorkspaceCheckpoint>>>>(emptyList()) }
    var discardSession by remember { mutableStateOf<String?>(null) }
    var recoveryRevision by remember { mutableIntStateOf(0) }
    var selectedBranch by remember { mutableStateOf<String?>(null) }
    var confirmFresh by remember { mutableStateOf(false) }
    var confirmAbandon by remember { mutableStateOf(false) }
    val saveFailed = state.progress?.phase == ProgressSessionPhase.SAVE_FAILED
    LaunchedEffect(owner, state.phase, recoveryRevision) {
        if (state.phase == ApplicationProgressPhase.AWAITING_CHOICE) {
            try {
                legacy = owner.legacyChoices()
                interrupted = owner.interruptedSessions().await()
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { onMessage(error.message ?: "Unable to inspect recovery files.") }
        }
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { onMessage(error.message ?: "Unable to complete this action.") }
            finally { busy = false }
        }
    }
    fun choose(branch: ProgressReferenceHead, decision: ProgressLaunchDecision.ChooseBranch) = action {
        selectedBranch = branch.operationId
        if (branch.checkpoint?.schemaVersion == 3) owner.resolveBranch(branch.operationId, decision.resume.isNotEmpty()).await()
        else owner.select(ProgressLaunchRequest.ContinueBranch(branch.operationId, true)).await()
    }
    // The Conflict sheet (Play, Game) already chose a head: apply that choice once, exactly as
    // choosing its branch here would. `busy` is a key so a choice that arrives while another action
    // runs is applied when it finishes instead of being dropped. If the choice no longer matches a
    // head, the prompt below stays.
    var keepApplied by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(owner, state.decision, keepResume, busy) {
        val decision = state.decision as? ProgressLaunchDecision.ChooseBranch ?: return@LaunchedEffect
        if (keepResume == null || keepApplied || busy) return@LaunchedEffect
        val heads = if (decision.resume.isNotEmpty()) decision.resume else decision.current
        val branch = heads.firstOrNull { it.checkpoint?.checkpointId == keepResume || it.operationId == keepResume } ?: return@LaunchedEffect
        keepApplied = true
        choose(branch, decision)
    }
    if (state.phase == ApplicationProgressPhase.CLOSED) {
        LaunchedEffect(owner) { onBack() }
        return
    }
    if (state.phase !in setOf(ApplicationProgressPhase.AWAITING_CHOICE, ApplicationProgressPhase.FAILED) && !saveFailed) return
    // While a Conflict-sheet choice that matches a head is being applied, the prompt would only flash.
    val pending = state.decision as? ProgressLaunchDecision.ChooseBranch
    val keepMatches = keepResume != null && pending != null &&
        (if (pending.resume.isNotEmpty()) pending.resume else pending.current).any { it.checkpoint?.checkpointId == keepResume || it.operationId == keepResume }
    if (keepMatches && (!keepApplied || busy)) return
    val gameTitle = owner.workspace.session.romTitle
    ThemedDialog(onDismissRequest = {}, dismissible = false, modifier = Modifier.testTag("player.startPrompt")) {
        if (saveFailed) {
            DialogHeading("Progress could not be saved")
            DialogText(state.progress?.error ?: "The game is paused. Retry saving before leaving.")
            PrimaryButton("Retry save", enabled = !busy, large = false, modifier = Modifier.fillMaxWidth(), onClick = {
                action { val result = owner.retrySave().await(); onMessage(result.stateError ?: "Progress saved on this device.") }
            })
            TextAction("Exit without latest progress", enabled = !busy, destructive = true, onClick = { confirmAbandon = true })
        } else {
            when (val decision = state.decision) {
                is ProgressLaunchDecision.ContinueLocal -> {
                    // Cloud progress wasn't checked: start from this device's progress, as a
                    // resume or from the game's own save (the Launch choice's two labels).
                    DialogHeading(gameTitle, kicker = "Start")
                    DialogText(decision.reason)
                    PrimaryButton("Resume", icon = Icons.Outlined.PlayArrow, enabled = !busy, large = false,
                        modifier = Modifier.fillMaxWidth().testTag("player.start.resume"), onClick = {
                            action { owner.continueLocal().await() }
                        })
                    SecondaryButton("Start with game save", icon = Icons.Outlined.SaveAlt, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("player.start.gameSave"), onClick = {
                            action { owner.continueLocal(ProgressLaunchRequest.ColdBootCurrent).await() }
                        })
                }
                is ProgressLaunchDecision.OfferColdBoot -> {
                    DialogHeading(gameTitle, kicker = "Start")
                    DialogText(decision.reason)
                    PrimaryButton("Start with game save", icon = Icons.Outlined.SaveAlt, enabled = !busy, large = false,
                        modifier = Modifier.fillMaxWidth().testTag("player.start.gameSave"), onClick = {
                            action { owner.select(selectedBranch?.let { ProgressLaunchRequest.ContinueBranch(it, false) } ?: ProgressLaunchRequest.ColdBootCurrent).await() }
                        })
                }
                is ProgressLaunchDecision.ChooseBranch -> {
                    val heads = if (decision.resume.isNotEmpty()) decision.resume else decision.current
                    val kind = if (decision.resume.isNotEmpty()) SaveConflictKind.RESUME else SaveConflictKind.GAME_SAVE
                    DialogHeading(gameTitle, kicker = "Conflict")
                    DialogText(SaveConflict.prompt(gameTitle, kind, heads.size))
                    heads.sortedByDescending { it.checkpoint?.createdAtEpochMs ?: Long.MIN_VALUE }.forEachIndexed { index, branch ->
                        SecondaryButton(
                            text = branch.checkpoint?.let { "Keep ${Fmt.dayGroup(it.createdAtEpochMs).title}, ${formatClockTime(it.createdAtEpochMs)}" } ?: "Keep the deletion",
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("player.start.branch.$index"),
                            onClick = { choose(branch, decision) },
                        )
                    }
                    DialogText("The other ${kind.noun} stays in this game's saved progress.")
                }
                is ProgressLaunchDecision.UnsupportedFormat -> {
                    DialogHeading(gameTitle, kicker = "Start")
                    DialogText(decision.reason)
                }
                is ProgressLaunchDecision.RecoveryRequired -> {
                    DialogHeading("Recover saved progress", kicker = gameTitle)
                    DialogText(decision.reason)
                    interrupted.forEachIndexed { index, (session, captures) ->
                        SectionHeader(title = "Interrupted session ${index + 1}")
                        session.problem?.let { DialogText(it) }
                        captures.forEachIndexed { captureIndex, capture ->
                            if (capture.problem == null) {
                                SecondaryButton("Recover captured save ${captureIndex + 1}", enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                    action {
                                        owner.recoverCapture(session.sessionId, capture.captureId).await()
                                        recoveryRevision++
                                        onMessage("Captured progress recovered. You can now remove the interrupted session files.")
                                    }
                                })
                            } else {
                                DialogText(capture.problem)
                            }
                        }
                        TextAction("Remove interrupted files", enabled = !busy, destructive = true, onClick = { discardSession = session.sessionId })
                    }
                    if (interrupted.isEmpty() && legacy.isNotEmpty()) {
                        DialogText("Import an old save to continue. Original files are kept. Old states may be incompatible with this core build.")
                        legacy.forEach { file ->
                            SecondaryButton(if (file.extension == "state") "Import ${file.name}" else "Import in-game save", enabled = !busy,
                                modifier = Modifier.fillMaxWidth(), onClick = { action { owner.importLegacy(file).await() } })
                        }
                        TextAction("Start fresh and keep old files", enabled = !busy, onClick = { confirmFresh = true })
                    }
                }
                else -> {
                    DialogHeading(gameTitle, kicker = "Start")
                    DialogText(state.error ?: "Unable to start this session. Saved files are preserved.")
                }
            }
            if (state.decision != null) state.error?.let { DialogText(it) }
            TextAction("Back to library", enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("player.start.back"),
                onClick = { action { owner.exitWithoutLatestProgress().await() } })
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = io.github.bandoracer.rommio.ui.theme.RommioTheme.current.mark1)
        }
    }
    discardSession?.let { sessionId ->
        ThemedDialog(onDismissRequest = { discardSession = null }) {
            DialogHeading("Remove interrupted session files?")
            DialogText("Recover any captured saves first. This permanently removes the remaining interrupted files. Successfully saved checkpoints are kept.")
            SecondaryButton("Remove files", enabled = !busy, destructive = true, modifier = Modifier.fillMaxWidth(), onClick = {
                discardSession = null
                action { owner.discardInterruptedSession(sessionId).await(); recoveryRevision++ }
            })
            TextAction("Cancel", onClick = { discardSession = null }, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
    if (confirmFresh || confirmAbandon) ThemedDialog(onDismissRequest = { confirmFresh = false; confirmAbandon = false }) {
        DialogHeading(if (confirmFresh) "Start a new game?" else "Leave without saving?")
        DialogText(if (confirmFresh) "Your old files will remain available, but this session will start from the beginning."
            else "Progress since the last successful save may be lost.")
        SecondaryButton("Continue", enabled = !busy, destructive = confirmAbandon, modifier = Modifier.fillMaxWidth(), onClick = {
            val fresh = confirmFresh
            confirmFresh = false; confirmAbandon = false
            action { if (fresh) owner.startFreshPreservingLegacy().await() else owner.exitWithoutLatestProgress().await() }
        })
        TextAction("Cancel", onClick = { confirmFresh = false; confirmAbandon = false }, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

/**
 * Pause › Save states: this game's save states, resume points and game-save data with their
 * previews, names, device and sync status. Loading one first keeps the current progress in a
 * safety copy (the coordinator's destructive path). Names are local (docs/play-data-local.md).
 */
@Composable
internal fun ProgressHistoryContent(
    owner: ApplicationProgressSession,
    saves: io.github.bandoracer.rommio.data.playdata.SavedProgressRepository,
    coverUrl: String?,
    onLoaded: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val theme = io.github.bandoracer.rommio.ui.theme.RommioTheme.current
    var entries by remember(owner) { mutableStateOf<List<ProgressHistoryEntry>>(emptyList()) }
    var described by remember(owner) { mutableStateOf<List<io.github.bandoracer.rommio.domain.playdata.SaveEntry>>(emptyList()) }
    var cursor by remember(owner) { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rollbackCheckpoint by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<io.github.bandoracer.rommio.domain.playdata.SaveEntry?>(null) }
    suspend fun describe(list: List<ProgressHistoryEntry>) {
        described = saves.describe(owner.workspace.identity, list)
    }
    suspend fun load(before: Long = Long.MAX_VALUE) {
        try {
            val page = owner.history(before).await()
            entries = if (before == Long.MAX_VALUE) page.entries else entries + page.entries
            cursor = page.beforeSequence
            describe(entries)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { onMessage(error.message ?: "Unable to read saved progress.") }
    }
    fun restore(checkpointId: String) {
        busy = true
        scope.launch {
            try { owner.restore(checkpointId).await(); onLoaded() }
            catch (error: GameSaveRollbackRequired) { rollbackCheckpoint = checkpointId }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { onMessage(error.message ?: "Unable to load saved progress.") }
            finally { busy = false }
        }
    }
    LaunchedEffect(owner) { load() }
    val states = described.filter { it.kind == io.github.bandoracer.rommio.domain.playdata.SaveKind.SAVE_STATE }
    val resumes = described.filter { it.kind == io.github.bandoracer.rommio.domain.playdata.SaveKind.RESUME_POINT }
    val gameSaves = described.filter { it.kind == io.github.bandoracer.rommio.domain.playdata.SaveKind.GAME_SAVE }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("player.states.list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        io.github.bandoracer.rommio.ui.component.RoleText("Save states", theme.type.headline, color = theme.colors.ink)
        io.github.bandoracer.rommio.ui.component.RoleText(
            "Loading a save state also loads the game-save data saved with it. Your current progress is kept in a safety copy first.",
            theme.type.secondary, color = theme.colors.inkSecondary,
        )
        val onRestore: (io.github.bandoracer.rommio.domain.playdata.SaveEntry) -> Unit = { restore(it.key.checkpointId) }
        val onRename: (io.github.bandoracer.rommio.domain.playdata.SaveEntry) -> Unit = { renaming = it }
        SaveStatesSection("Save states", states, "No save states yet. Save snapshot in the pause menu makes one.", coverUrl, busy, onRestore, onRename)
        SaveStatesSection("Resume points", resumes, null, coverUrl, busy, onRestore, onRename)
        SaveStatesSection("Game-save data", gameSaves.take(GAME_SAVE_ROWS), null, coverUrl, busy, onRestore, onRename)
        if (gameSaves.size > GAME_SAVE_ROWS) {
            io.github.bandoracer.rommio.ui.component.RoleText(
                "${io.github.bandoracer.rommio.ui.component.pluralize(gameSaves.size - GAME_SAVE_ROWS, "earlier capture")} of game-save data kept on this device.",
                theme.type.caption, color = theme.colors.inkSecondary, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (described.isEmpty() && entries.isNotEmpty()) {
            // The store answered but decoration failed: still offer the plain list.
            entries.forEach { entry ->
                Button(shape = io.github.bandoracer.rommio.ui.theme.RommioTheme.radius.buttonShape, enabled = !busy && entry.retention == ProgressLocalRetention.RETAINED && entry.checkpoint.stateCompatibility != null,
                    onClick = { restore(entry.checkpoint.checkpointId) }) {
                    Text("${if (entry.checkpoint.stateCompatibility == null) "Game save" else "Snapshot"} · ${entry.checkpoint.captureReason.replaceFirstChar { it.uppercase() }} · ${DateFormat.getDateTimeInstance().format(Date(entry.checkpoint.createdAtEpochMs))}")
                }
            }
        }
        if (entries.isEmpty()) io.github.bandoracer.rommio.ui.component.RoleText("No saved progress yet.", theme.type.secondary, color = theme.colors.inkSecondary)
        cursor?.let { before -> io.github.bandoracer.rommio.ui.component.TextAction("Older saves", onClick = { scope.launch { load(before) } }, enabled = !busy) }
    }
    renaming?.let { entry ->
        io.github.bandoracer.rommio.ui.component.RenameSaveDialog(
            current = entry.label,
            tagPrefix = "player.states",
            onDismiss = { renaming = null },
            onSave = { text ->
                renaming = null
                scope.launch {
                    try { saves.rename(entry.key, text); describe(entries) }
                    catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (error: Exception) { onMessage(error.message ?: "Unable to rename this save state.") }
                }
            },
        )
    }
    // The game-save rollback confirmation (iOS wording, docs/theme-layouts.md §4).
    rollbackCheckpoint?.let { checkpointId ->
        ThemedDialog(onDismissRequest = { rollbackCheckpoint = null }, modifier = Modifier.testTag("player.states.rollback")) {
            DialogHeading("Replace game-save data?")
            DialogText("This restore contains different game-save data. Your current game-save data and emulator state will be preserved together before anything is replaced.")
            SecondaryButton("Preserve a safety checkpoint and restore", enabled = !busy, destructive = true,
                modifier = Modifier.fillMaxWidth().testTag("player.states.rollbackConfirm"), onClick = {
                    rollbackCheckpoint = null; busy = true
                    scope.launch {
                        try { owner.restore(checkpointId, confirmGameRollback = true).await(); onLoaded() }
                        catch (error: kotlinx.coroutines.CancellationException) { throw error }
                        catch (error: Exception) { onMessage(error.message ?: "Unable to restore snapshot.") }
                        finally { busy = false }
                    }
                })
            TextAction("Cancel", onClick = { rollbackCheckpoint = null }, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }

}

private const val GAME_SAVE_ROWS = 3

@Composable
private fun SaveStatesSection(
    title: String,
    list: List<io.github.bandoracer.rommio.domain.playdata.SaveEntry>,
    empty: String?,
    coverUrl: String?,
    busy: Boolean,
    onRestore: (io.github.bandoracer.rommio.domain.playdata.SaveEntry) -> Unit,
    onRename: (io.github.bandoracer.rommio.domain.playdata.SaveEntry) -> Unit,
) {
    if (list.isEmpty() && empty == null) return
    val theme = io.github.bandoracer.rommio.ui.theme.RommioTheme.current
    io.github.bandoracer.rommio.ui.component.SectionHeader(title = title, modifier = Modifier.padding(top = 4.dp))
    if (list.isEmpty()) {
        io.github.bandoracer.rommio.ui.component.RoleText(empty.orEmpty(), theme.type.secondary, color = theme.colors.inkSecondary,
            modifier = Modifier.padding(horizontal = 4.dp))
        return
    }
    io.github.bandoracer.rommio.ui.component.ListGroup {
        list.forEachIndexed { index, entry ->
            if (index > 0) io.github.bandoracer.rommio.ui.component.ListDivider(inset = 92.dp)
            io.github.bandoracer.rommio.ui.component.ListRow(
                title = entry.title,
                subtitle = io.github.bandoracer.rommio.ui.component.gameSaveDetail(entry),
                leading = { io.github.bandoracer.rommio.ui.component.SavePreview(entry.thumbnailPath, coverUrl, entry.title) },
                trailing = if (entry.kind == io.github.bandoracer.rommio.domain.playdata.SaveKind.SAVE_STATE) { {
                    io.github.bandoracer.rommio.ui.component.RenameSaveButton(entry.label, onClick = { onRename(entry) },
                        modifier = Modifier.testTag("player.states.rename"))
                } } else null,
                onClick = if (entry.loadable && !busy) { { onRestore(entry) } } else null,
                modifier = Modifier.testTag("player.states.row"),
            )
        }
    }
}
