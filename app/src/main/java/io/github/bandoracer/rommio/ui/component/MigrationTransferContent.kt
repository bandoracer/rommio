package io.github.bandoracer.rommio.ui.component

import io.github.bandoracer.rommio.ui.component.SecondaryButton
import io.github.bandoracer.rommio.ui.component.PrimaryButton
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bandoracer.rommio.data.repository.RommRepository
import io.github.bandoracer.rommio.model.MigrationBundleInspection
import io.github.bandoracer.rommio.model.MigrationImportSummary
import kotlinx.coroutines.launch

@Composable
fun MigrationExportContent(
    repository: RommRepository,
    buttonLabel: String,
    suggestedFileName: String = "rommio-legacy-migration.zip",
) {
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isWorking = true
            errorMessage = null
            successMessage = null
            runCatching {
                repository.exportMigrationBundle(uri)
            }.fold(
                onSuccess = { manifest ->
                    successMessage = "Bundle exported from ${manifest.sourcePackageId}."
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Unable to export the migration bundle."
                },
            )
            isWorking = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton(
    text = buttonLabel,
    onClick = { exportLauncher.launch(suggestedFileName) },
            enabled = !isWorking,
            modifier = Modifier.fillMaxWidth(),
)
        if (isWorking) {
            CircularProgressIndicator()
        }
        successMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        errorMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
fun MigrationImportContent(
    repository: RommRepository,
    buttonLabel: String,
    continueLabel: String,
    onContinueAfterSuccess: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var inspection by remember { mutableStateOf<MigrationBundleInspection?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var importSummary by remember { mutableStateOf<MigrationImportSummary?>(null) }

    fun launchImport(uri: Uri, replaceExisting: Boolean) {
        scope.launch {
            isWorking = true
            errorMessage = null
            inspection = null
            runCatching {
                repository.importMigrationBundle(uri, replaceExisting)
            }.fold(
                onSuccess = { summary ->
                    importSummary = summary
                    pendingUri = null
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Unable to import the selected migration bundle."
                },
            )
            isWorking = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isWorking = true
            errorMessage = null
            importSummary = null
            runCatching {
                repository.inspectMigrationBundle(uri)
            }.fold(
                onSuccess = { bundleInspection ->
                    if (bundleInspection.requiresReplace || bundleInspection.progressArtifactIssues > 0) {
                        pendingUri = uri
                        inspection = bundleInspection
                    } else {
                        launchImport(uri, replaceExisting = false)
                    }
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Unable to read the selected migration bundle."
                },
            )
            isWorking = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton(
    text = buttonLabel,
    onClick = { importLauncher.launch(arrayOf("application/zip")) },
            enabled = !isWorking,
            modifier = Modifier.fillMaxWidth(),
)
        if (isWorking) {
            CircularProgressIndicator()
        }
        importSummary?.let { summary ->
            Text(
                text = "Imported ${summary.profileCount} profiles, ${summary.installedFileCount} installed files, ${summary.manualSlotCount} manual slots, and ${summary.recoveryStateCount} recovery entries.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Library payload: ${formatBytes(summary.libraryBytes)} from ${summary.sourcePackageId}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProgressTransferSummary(summary.progressCheckpointCount, summary.progressBytes, summary.progressArtifactIssues)
            if (onContinueAfterSuccess != null) {
                SecondaryButton(
    text = continueLabel,
    onClick = onContinueAfterSuccess,
                    modifier = Modifier.fillMaxWidth(),
)
            }
        }
        errorMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    inspection?.let { bundleInspection ->
        AlertDialog(
            onDismissRequest = {
                inspection = null
                pendingUri = null
            },
            title = {
                Text(if (bundleInspection.requiresReplace) "Replace local app data?" else "Import recovery data?")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (bundleInspection.requiresReplace)
                            "Importing replaces this device's library, profiles, controls, saved progress, and pending sync work. Previous data is retained for recovery."
                        else "This backup contains progress that needs repair. Available files will be preserved.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Bundle contents",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${bundleInspection.profileCount} profiles, ${bundleInspection.installedFileCount} installed files, ${bundleInspection.manualSlotCount} manual slots, ${bundleInspection.recoveryStateCount} recovery entries",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Library payload: ${formatBytes(bundleInspection.libraryBytes)} from ${bundleInspection.manifest.sourcePackageId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ProgressTransferSummary(bundleInspection.progressCheckpointCount, bundleInspection.progressBytes, bundleInspection.progressArtifactIssues)
                }
            },
            confirmButton = {
                Button(shape = io.github.bandoracer.rommio.ui.theme.RommioTheme.radius.buttonShape,
                    onClick = {
                        val uri = pendingUri ?: return@Button
                        launchImport(uri, replaceExisting = bundleInspection.requiresReplace)
                    },
                ) {
                    Text(if (bundleInspection.requiresReplace) "Replace local data" else "Import available data")
                }
            },
            dismissButton = {
                OutlinedButton(shape = io.github.bandoracer.rommio.ui.theme.RommioTheme.radius.buttonShape,
                    onClick = {
                        inspection = null
                        pendingUri = null
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ProgressTransferSummary(checkpoints: Int, bytes: Long, issues: Int) {
    if (checkpoints > 0 || bytes > 0) Text(
        text = "$checkpoints progress checkpoints · ${formatBytes(bytes)} of progress and recovery files",
        style = MaterialTheme.typography.bodySmall,
    )
    if (issues > 0) Text(
        text = "$issues checkpoint files are missing or damaged. Affected checkpoints need repair before use.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
