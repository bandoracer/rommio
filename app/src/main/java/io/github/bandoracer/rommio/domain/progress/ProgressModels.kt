package io.github.bandoracer.rommio.domain.progress

data class ProgressContentFile(
    val fileId: Long,
    val discIndex: Int,
    val contentSha256: String,
    val containerSha256: String?,
    val memberPath: String?,
)

data class ProgressBinding(
    val serverKey: String,
    val accountId: String,
    val romId: Long,
    val files: List<ProgressContentFile>,
)

data class ProgressSaveCompatibility(
    val system: String,
    val format: String,
    val layoutVersion: String,
    val coreId: String?,
    val allowsEmpty: Boolean,
)

data class ProgressStateCompatibility(
    val coreId: String,
    val coreSha256: String,
    val stateFormat: String,
    val platform: String,
    val architecture: String,
    val options: Map<String, String>,
    val systemFiles: Map<String, String>,
)

data class ProgressArtifact(
    val sha256: String,
    val byteLength: Long,
    val role: String,
    val relativePath: String,
    val format: String,
)

data class ProgressCheckpoint(
    val schemaVersion: Int,
    val checkpointId: String,
    val binding: ProgressBinding,
    val saveCompatibility: ProgressSaveCompatibility,
    val stateCompatibility: ProgressStateCompatibility?,
    val saveDataComplete: Boolean,
    val artifacts: List<ProgressArtifact>,
    val deviceId: String,
    val sessionId: String,
    val captureReason: String,
    val createdAtEpochMs: Long,
    val gameSaveOperationId: String? = null,
)

data class ProgressRefChange(
    val reference: String,
    val parents: List<String>,
    val checkpointId: String?,
    val tombstone: Boolean,
)

data class ProgressOperation(
    val schemaVersion: Int,
    val operationId: String,
    val binding: ProgressBinding,
    val changes: List<ProgressRefChange>,
    val checkpoints: List<ProgressCheckpoint>,
    val deviceId: String,
    val sessionId: String,
    val createdAtEpochMs: Long,
)

data class ProgressIdentity(
    val namespaceId: String,
    val contentId: String,
    val binding: ProgressBinding,
)
