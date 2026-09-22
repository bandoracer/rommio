package io.github.bandoracer.rommio.ui.screen.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.domain.progress.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ProgressPlayerGate(owner: ApplicationProgressSession, state: ApplicationProgressState,
    onBack: () -> Unit, onMessage: (String) -> Unit) {
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
    if (state.phase == ApplicationProgressPhase.CLOSED) {
        LaunchedEffect(owner) { onBack() }
        return
    }
    if (state.phase !in setOf(ApplicationProgressPhase.AWAITING_CHOICE, ApplicationProgressPhase.FAILED) && !saveFailed) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (saveFailed) "Progress could not be saved" else "Choose saved progress") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let { Text(it) }
                if (saveFailed) {
                    Text(state.progress?.error ?: "The game is paused. Retry saving before leaving.")
                    Button(enabled = !busy, onClick = { action { val result = owner.retrySave().await(); onMessage(result.stateError ?: "Progress saved on this device.") } }) { Text("Retry save") }
                    TextButton(enabled = !busy, onClick = { confirmAbandon = true }) { Text("Exit without latest progress") }
                } else when (val decision = state.decision) {
                    is ProgressLaunchDecision.ContinueLocal -> {
                        Text(decision.reason)
                        Button(enabled = !busy, onClick = { action { owner.continueLocal().await() } }) { Text("Continue with local progress") }
                    }
                    is ProgressLaunchDecision.OfferColdBoot -> {
                        Text(decision.reason)
                        Button(enabled = !busy, onClick = { action { owner.select(selectedBranch?.let { ProgressLaunchRequest.ContinueBranch(it, false) } ?: ProgressLaunchRequest.ColdBootCurrent).await() } }) { Text("Start with in-game save") }
                    }
                    is ProgressLaunchDecision.ChooseBranch -> {
                        Text("More than one saved branch exists. Choose which to use as current. The other checkpoints are pinned in history.")
                        (if (decision.resume.isNotEmpty()) decision.resume else decision.current).forEachIndexed { index, branch ->
                            Button(enabled = !busy, onClick = { action { selectedBranch = branch.operationId; if (branch.checkpoint?.schemaVersion == 3) owner.resolveBranch(branch.operationId, decision.resume.isNotEmpty()).await() else owner.select(ProgressLaunchRequest.ContinueBranch(branch.operationId, true)).await() } }) {
                                Text("Continue branch ${index + 1}")
                            }
                        }
                    }
                    is ProgressLaunchDecision.UnsupportedFormat -> Text(decision.reason)
                    is ProgressLaunchDecision.RecoveryRequired -> {
                        Text(decision.reason)
                        interrupted.forEachIndexed { index, (session, captures) ->
                            Text("Interrupted session ${index + 1}")
                            session.problem?.let { Text(it) }
                            captures.forEachIndexed { captureIndex, capture ->
                                if (capture.problem == null) Button(enabled = !busy, onClick = { action {
                                    owner.recoverCapture(session.sessionId, capture.captureId).await()
                                    recoveryRevision++
                                    onMessage("Captured progress recovered. You can now remove the interrupted session files.")
                                } }) { Text("Recover captured save ${captureIndex + 1}") }
                                else Text(capture.problem)
                            }
                            TextButton(enabled = !busy, onClick = { discardSession = session.sessionId }) { Text("Remove interrupted files") }
                        }
                        if (interrupted.isEmpty() && legacy.isNotEmpty()) {
                            Text("Import an old save to continue. Original files are kept. Old states may be incompatible with this core build.")
                            legacy.forEach { file ->
                                Button(enabled = !busy, onClick = { action { owner.importLegacy(file).await() } }) {
                                    Text(if (file.extension == "state") "Import ${file.name}" else "Import in-game save")
                                }
                            }
                            TextButton(enabled = !busy, onClick = { confirmFresh = true }) { Text("Start fresh and keep old files") }
                        }
                    }
                    else -> Text("Unable to start this session. Saved files are preserved.")
                }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {},
        dismissButton = {
            if (!saveFailed) TextButton(enabled = !busy, onClick = { action { owner.exitWithoutLatestProgress().await() } }) { Text("Back to library") }
        },
    )
    discardSession?.let { sessionId -> AlertDialog(
        onDismissRequest = { discardSession = null },
        title = { Text("Remove interrupted session files?") },
        text = { Text("Recover any captured saves first. This permanently removes the remaining interrupted files. Successfully saved checkpoints are kept.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            discardSession = null
            action { owner.discardInterruptedSession(sessionId).await(); recoveryRevision++ }
        }) { Text("Remove files") } },
        dismissButton = { TextButton(onClick = { discardSession = null }) { Text("Cancel") } },
    ) }
    if (confirmFresh || confirmAbandon) AlertDialog(
        onDismissRequest = { confirmFresh = false; confirmAbandon = false },
        title = { Text(if (confirmFresh) "Start a new game?" else "Leave without saving?") },
        text = { Text(if (confirmFresh) "Your old files will remain available, but this session will start from the beginning."
            else "Progress since the last successful save may be lost.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val fresh = confirmFresh
            confirmFresh = false; confirmAbandon = false
            action { if (fresh) owner.startFreshPreservingLegacy().await() else owner.exitWithoutLatestProgress().await() }
        }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { confirmFresh = false; confirmAbandon = false }) { Text("Cancel") } },
    )
}

@Composable
internal fun ProgressHistoryContent(owner: ApplicationProgressSession, onLoaded: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var entries by remember(owner) { mutableStateOf<List<ProgressHistoryEntry>>(emptyList()) }
    var cursor by remember(owner) { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rollbackCheckpoint by remember { mutableStateOf<String?>(null) }
    suspend fun load(before: Long = Long.MAX_VALUE) {
        try {
            val page = owner.history(before).await()
            entries = if (before == Long.MAX_VALUE) page.entries else entries + page.entries
            cursor = page.beforeSequence
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { onMessage(error.message ?: "Unable to read saved progress.") }
    }
    LaunchedEffect(owner) { load() }
    Column(Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Snapshots & game saves", style = MaterialTheme.typography.titleLarge)
        Text("Snapshots restore the emulator and their matching in-game save. Your current progress is preserved in a pinned safety snapshot first.")
        entries.forEach { entry ->
            Button(enabled = !busy && entry.retention == ProgressLocalRetention.RETAINED && entry.checkpoint.stateCompatibility != null,
                onClick = {
                    busy = true
                    scope.launch {
                        try { owner.restore(entry.checkpoint.checkpointId).await(); onLoaded() }
                        catch (error: GameSaveRollbackRequired) { rollbackCheckpoint = entry.checkpoint.checkpointId }
                        catch (error: kotlinx.coroutines.CancellationException) { throw error }
                        catch (error: Exception) { onMessage(error.message ?: "Unable to load saved progress.") }
                        finally { busy = false }
                    }
                }) {
                Text("${if (entry.checkpoint.stateCompatibility == null) "Game save" else "Snapshot"} · ${entry.checkpoint.captureReason.replaceFirstChar { it.uppercase() }} · ${DateFormat.getDateTimeInstance().format(Date(entry.checkpoint.createdAtEpochMs))}")
            }
        }
        if (entries.isEmpty()) Text("No saved progress yet.")
        cursor?.let { before -> TextButton(enabled = !busy, onClick = { scope.launch { load(before) } }) { Text("Older saves") } }
    }
    rollbackCheckpoint?.let { checkpointId -> AlertDialog(
        onDismissRequest = { rollbackCheckpoint = null },
        title = { Text("Replace the current game save too?") },
        text = { Text("This snapshot contains different in-game save data. Loading it replaces both the emulator state and game save. A pinned safety snapshot preserves your current progress. Cancel leaves both unchanged.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            rollbackCheckpoint = null; busy = true
            scope.launch {
                try { owner.restore(checkpointId, confirmGameRollback = true).await(); onLoaded() }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { onMessage(error.message ?: "Unable to restore snapshot.") }
                finally { busy = false }
            }
        }) { Text("Restore both") } },
        dismissButton = { TextButton(onClick = { rollbackCheckpoint = null }) { Text("Cancel") } },
    ) }

}
