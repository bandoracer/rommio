package io.github.bandoracer.rommio.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.data.migration.LegacyProgressRecovery
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LegacyProgressRecoveryContent(recovery: LegacyProgressRecovery) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var inventory by remember { mutableStateOf<LegacyProgressRecovery.Inventory?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var target by remember { mutableStateOf<DownloadedRomEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun scan() {
        scope.launch {
            busy = true; message = null; selected = emptySet(); target = null; inventory = null
            try { inventory = recovery.inventory() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "Unable to inspect legacy progress." }
            finally { busy = false }
        }
    }
    CompactPanel(title = "Recover older saves", subtitle = "Review preserved save files and copy selected progress to an installed game. Originals are retained.") {
        Button(onClick = { open = true; scan() }) { Text("Review older saves") }
        if (!open) message?.let { Text(it) }
    }
    if (open) AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text("Recover older saves") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Choose the original files and the account/game they belong to. Old filenames and profile references do not establish ownership.")
                    Text("In-game saves start on the next cold boot and cannot replace existing progress. States appear as recovery copies; they never become automatic resume. Compatibility with the installed core is unverified.")
                }
                if (busy) item { Text("Preparing recovery…") }
                message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                inventory?.let { result ->
                    item { Text("Source files", style = MaterialTheme.typography.titleMedium) }
                    if (result.artifacts.isEmpty()) item { Text("No legacy save files or records were found.") }
                    items(result.artifacts, key = { it.path }) { artifact ->
                        Row {
                            Checkbox(checked = artifact.path in selected, enabled = !busy && artifact.issue == null,
                                onCheckedChange = { checked -> selected = if (checked) selected + artifact.path else selected - artifact.path })
                            Column {
                                Text(artifact.label)
                                Text("${artifact.kind} · ${formatBytes(artifact.size)} · ${artifact.path}", style = MaterialTheme.typography.bodySmall)
                                artifact.provenance.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                artifact.issue?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    item { Text("Destination", style = MaterialTheme.typography.titleMedium) }
                    if (result.targets.isEmpty()) item { Text("No verified installed games for this account. Sign in and download the destination game, then scan again.") }
                    items(result.targets, key = { "${it.namespaceKey}/${it.romId}/${it.fileId}" }) { install ->
                        Row {
                            RadioButton(selected = target == install, enabled = !busy && recovery.targetIssue(install) == null, onClick = { target = install })
                            Column {
                                Text("${install.romName} — ${install.fileName}")
                                Text("${install.ownerBaseUrl} · account ${install.ownerAccountId}", style = MaterialTheme.typography.bodySmall)
                                Text("ROM ${install.romId} · file ${install.fileId}", style = MaterialTheme.typography.bodySmall)
                                recovery.targetIssue(install)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && selected.isNotEmpty() && target != null, onClick = {
                val destination = target ?: return@TextButton
                val sources = inventory?.artifacts?.filter { it.path in selected }.orEmpty()
                scope.launch {
                    busy = true; message = null
                    try {
                        val count = recovery.copy(sources, destination)
                        message = "Recovered $count files locally. Originals are retained; cloud synchronization remains unavailable."
                        open = false
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { message = error.message ?: "Recovery failed. Originals are retained." }
                    finally { busy = false }
                }
            }) { Text("Copy selected progress") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !busy, onClick = { scan() }) { Text("Scan again") }
                TextButton(enabled = !busy, onClick = { open = false }) { Text("Cancel") }
            }
        },
    )
}
