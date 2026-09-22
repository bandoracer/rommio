package io.github.bandoracer.rommio.data.migration

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.data.local.*
import io.github.bandoracer.rommio.domain.progress.*
import java.io.File
import java.util.Base64
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProgressBundleTest {
    @get:Rule val temp = TemporaryFolder()
    private val namespace = UUID.randomUUID().toString()
    private val content = UUID.randomUUID().toString()
    private val raw = javaClass.classLoader!!.getResourceAsStream("coherent.json")!!.use { it.readBytes() }
    private val op = ProgressProtocol.decode(raw)
    private fun row(operation: ProgressOperation, sequence: Long, bytes: ByteArray = ProgressProtocol.encode(operation)) =
        MigrationProgressOperation(namespace, content, operation.operationId, Base64.getEncoder().encodeToString(bytes), ProgressProtocol.sha256(bytes), sequence)
    private fun fixture() = MigrationProgressSnapshot(
        namespaces = listOf(ProgressNamespaceRow(namespace, op.binding.serverKey, op.binding.accountId)),
        contents = listOf(ProgressContentRow(content, namespace, ProgressProtocol.bindingKey(op.binding))),
        operations = listOf(row(op, 1, raw)),
        heads = op.changes.map { ProgressHeadRow(namespace, content, it.reference, op.operationId) },
        outbox = listOf(ProgressOutboxRow(namespace, content, op.operationId, ProgressProtocol.sha256(raw), "LOCAL_ONLY")),
        history = listOf(ProgressHistoryRow(64, namespace, content, op.checkpoints.single().checkpointId, op.operationId, 1, false, pinned = true)),
    )
    private fun reject(snapshot: MigrationProgressSnapshot) {
        try { ProgressBundle.validate(snapshot); fail("Invalid snapshot accepted") } catch (_: Exception) { }
    }

    @Test fun snapshotJsonPreservesExactPayloadAndOpaqueUnknownQuarantine() {
        val unknown = "{\"schemaVersion\":999,\"unreadable\":true}".toByteArray()
        val snapshot = fixture().copy(quarantine = listOf(MigrationProgressQuarantine(namespace, content,
            ProgressProtocol.sha256(unknown), Base64.getEncoder().encodeToString(unknown), "requires update")))
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(MigrationProgressSnapshot::class.java)
        val roundTrip = requireNotNull(adapter.fromJson(adapter.toJson(snapshot)))
        ProgressBundle.validate(roundTrip)
        assertEquals(snapshot, roundTrip)
        assertArrayEquals(raw, Base64.getDecoder().decode(roundTrip.operations.single().payloadBase64))
        assertArrayEquals(unknown, Base64.getDecoder().decode(roundTrip.quarantine.single().payloadBase64))
    }

    @Test fun ownershipDigestDuplicateSequenceAndPublicationIntentAreValidated() {
        val original = fixture(); ProgressBundle.validate(original)
        reject(original.copy(namespaces = original.namespaces.map { it.copy(accountId = "other") }))
        reject(original.copy(contents = original.contents.map { it.copy(bindingKey = "0".repeat(64)) }))
        reject(original.copy(operations = original.operations.map { it.copy(payloadHash = "0".repeat(64)) }))
        reject(original.copy(operations = original.operations + original.operations))
        reject(original.copy(heads = emptyList()))
        reject(original.copy(outbox = emptyList()))
        reject(original.copy(outbox = original.outbox.map { it.copy(status = "DONE") }))
        reject(original.copy(outbox = original.outbox.map { it.copy(status = "LEASED") }))
        reject(original.copy(history = original.history.map { it.copy(sequence = 1) }))
        reject(original.copy(historyIndex = listOf(ProgressHistoryIndexRow(namespace, content, 2))))
        reject(original.copy(version = 2))
    }

    @Test fun concurrentBranchAndTombstoneHeadsSurviveWhileMissingParentsReject() {
        val original = fixture()
        val a = op.copy(operationId = UUID.randomUUID().toString(), changes = op.changes.map { it.copy(parents = listOf(op.operationId)) })
        val b = a.copy(operationId = UUID.randomUUID().toString(), checkpoints = emptyList(),
            changes = a.changes.map { it.copy(checkpointId = null, tombstone = true) })
        val extra = listOf(row(a, 2), row(b, 3))
        val snapshot = original.copy(operations = original.operations + extra,
            heads = listOf(a, b).flatMap { branch -> branch.changes.map { ProgressHeadRow(namespace, content, it.reference, branch.operationId) } },
            outbox = original.outbox + extra.map { ProgressOutboxRow(namespace, content, it.operationId, it.payloadHash, "PENDING") })
        ProgressBundle.validate(snapshot)
        reject(snapshot.copy(operations = extra))
        reject(snapshot.copy(operations = original.operations + listOf(extra[0].copy(localSequence = 1), extra[1])))
        reject(snapshot.copy(heads = snapshot.heads.filter { it.operationId != b.operationId }))
    }

    @Test fun fileInventoryPreservesEmptyDirectoriesAndCorruptRecoveryBytes() {
        val root = temp.newFolder()
        File(root, ".owner.lock").writeBytes(byteArrayOf())
        File(root, ImportRecoveryJournal.GENERATION).writeText(UUID.randomUUID().toString())
        File(root, "sessions/empty").mkdirs()
        val file = File(root, "sessions/capture/raw").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(0, -1, 22)) }
        val entries = ProgressBundle.inventory(root)
        assertEquals(4, entries.size)
        assertEquals(MigrationProgressEntry("sessions/empty", 0, null), entries.single { it.path == "sessions/empty" })
        assertEquals(ProgressProtocol.sha256(file.readBytes()), entries.single { it.path.endsWith("/raw") }.sha256)
        ProgressBundle.verifyEntries(entries, entries.reversed())
        assertThrows(IllegalArgumentException::class.java) { ProgressBundle.verifyEntries(entries, entries.dropLast(1)) }
        file.writeText("changed")
        assertThrows(IllegalArgumentException::class.java) { ProgressBundle.verifyEntries(entries, ProgressBundle.inventory(root)) }
    }

    @Test fun unsafePathsSymlinksAndFileDirectoryCollisionsReject() {
        for (path in listOf("../other", "a/../other", "/absolute", ".owner.lock", "${ImportRecoveryJournal.GENERATION}/child")) {
            reject(fixture().copy(entries = listOf(MigrationProgressEntry(path, 0, ProgressProtocol.sha256(byteArrayOf())))))
        }
        reject(fixture().copy(entries = listOf(MigrationProgressEntry("a", 0, "0".repeat(64)), MigrationProgressEntry("a/b", 0, null))))
        reject(fixture().copy(entries = listOf(MigrationProgressEntry("missing/child", 0, null))))
        val root = temp.newFolder(); val outside = temp.newFile().apply { writeText("outside") }
        java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { ProgressBundle.inventory(root) }
        assertEquals("outside", outside.readText())
    }

    @Test fun missingArtifactsRemainExplicitRecoveryIssuesAndEvictedHistoryIsNotMisreported() {
        val snapshot = fixture()
        ProgressBundle.validate(snapshot) // Raw backup can preserve a pre-existing damaged checkpoint.
        val summary = ProgressBundle.summary(snapshot)
        assertEquals(1, summary.checkpoints)
        assertEquals(op.checkpoints.single().artifacts.map { it.sha256 }.distinct().size, summary.artifactIssues)
        assertEquals(0, ProgressBundle.summary(snapshot.copy(history = snapshot.history.map { it.copy(evicted = true) })).artifactIssues)
    }
}
