package io.github.bandoracer.rommio.domain.progress

import android.system.Os
import android.system.OsConstants
import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import java.io.File
import java.nio.file.Files

data class PendingWorkspaceCheckpoint(val captureId: String, val operationId: String?, val problem: String?)
internal data class PendingCheckpointReplay(val payload: ByteArray, val operation: ProgressOperation,
    val sources: Map<String, File>, val publish: Boolean)

/** The ready marker follows flushed blobs and exact operation bytes. No re-encoding on recovery. */
internal object PendingCheckpointJournal {
    fun write(directory: File, payload: ByteArray, publish: Boolean) {
        AtomicFiles.writeBytes(File(directory, "operation.json"), payload)
        AtomicFiles.writeBytes(File(directory, "ready"),
            "rommio-capture-v1\n${ProgressProtocol.sha256(payload)}\n${if (publish) "publish" else "local"}\n".toByteArray())
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
        val parent = Os.open(requireNotNull(directory.parentFile).absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(parent) } finally { Os.close(parent) }
    }

    fun read(directory: File, identity: ProgressIdentity, sessionId: String): PendingCheckpointReplay {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val marker = readBounded(File(directory, "ready"), 128).toString(Charsets.UTF_8).split('\n')
        require(marker.size == 4 && marker[0] == "rommio-capture-v1" && marker[2] in setOf("publish", "local") && marker[3].isEmpty()) {
            "Incomplete checkpoint journal; bytes retained for recovery."
        }
        val payload = readBounded(File(directory, "operation.json"), ProgressProtocol.MAX_PAYLOAD_BYTES)
        require(ProgressProtocol.sha256(payload) == marker[1]) { "Checkpoint journal checksum mismatch." }
        val operation = ProgressProtocol.decode(payload)
        require(operation.binding == identity.binding && operation.sessionId == sessionId)
        require(operation.checkpoints.all { it.sessionId == sessionId })
        val sources = operation.checkpoints.flatMap { it.artifacts }.associate { it.sha256 to File(directory, it.sha256) }
        // The store verifies all artifact lengths/hashes before a commit or idempotent retry succeeds.
        return PendingCheckpointReplay(payload, operation, sources, marker[2] == "publish")
    }

    private fun readBounded(file: File, max: Int): ByteArray {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..max.toLong())
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= max)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
}
