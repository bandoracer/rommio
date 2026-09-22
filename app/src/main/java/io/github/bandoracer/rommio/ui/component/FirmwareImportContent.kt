package io.github.bandoracer.rommio.ui.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.domain.player.FirmwareStatus
import io.github.bandoracer.rommio.domain.player.FirmwareStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun FirmwareImportContent(store: FirmwareStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(emptyList<FirmwareStatus>()) }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open) {
        if (open) {
            busy = true
            try { rows = store.status() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "Unable to inspect firmware." }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val path = selection
        selection = null
        if (uri != null && path != null) scope.launch {
            busy = true; message = null
            try {
                store.import(path) { requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot read the selected document." } }
                rows = store.status(); message = "BIOS imported and verified."
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "BIOS import failed." }
            finally { busy = false }
        }
    }
    CompactPanel(title = "BIOS and firmware", subtitle = "Import your BIOS files and verify that the emulator can find them.") {
        Button(onClick = { open = true }) { Text("Manage BIOS files") }
    }
    if (open) AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text("BIOS files") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Select the BIOS entry, then choose your file. Its contents are verified before installation; any differing older copy is retained. Sega CD currently requires all three regional BIOS files because the game region is not yet verified.") }
                message?.let { item { Text(it) } }
                if (busy) item { Text("Verifying BIOS…") }
                items(rows, key = { it.definition.path }) { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.definition.label, style = MaterialTheme.typography.titleSmall)
                        Text("${entry.definition.path} · ${if (entry.installed) "Verified" else "Not installed"}")
                        entry.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row {
                            TextButton(enabled = !busy, onClick = { selection = entry.definition.path; picker.launch(arrayOf("*/*")) }) { Text("Choose file") }
                            if (!entry.installed && entry.legacyCopies.isNotEmpty()) TextButton(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true; message = null
                                    try {
                                        store.useLegacyCopy(entry.definition.path, entry.legacyCopies.first())
                                        rows = store.status(); message = "Older BIOS copied and verified. Its original is retained."
                                    } catch (error: CancellationException) { throw error }
                                    catch (error: Exception) { message = error.message ?: "Unable to copy older BIOS." }
                                    finally { busy = false }
                                }
                            }) { Text("Use existing BIOS") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("Done") } },
    )
}
