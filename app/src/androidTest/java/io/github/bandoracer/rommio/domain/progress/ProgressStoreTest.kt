package io.github.bandoracer.rommio.domain.progress

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bandoracer.rommio.data.local.AppDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var root: File
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private lateinit var store: ProgressStore
    private lateinit var original: ProgressOperation
    private lateinit var identity: ProgressIdentity
    private lateinit var sources: Map<String, File>
    private fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).addMigrations(AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14).build()
    @Before fun setUp() = runBlocking {
        root = File(context.filesDir, "progress-test-${UUID.randomUUID()}").apply { mkdirs() }
        databaseName = "progress-test-${UUID.randomUUID()}.sqlite"
        db = openDatabase()
        store = ProgressStore(db, File(root, "store"))
        original = ProgressProtocol.decode(assets.open("coherent.json").use { it.readBytes() })
        identity = store.resolveIdentity(original.binding)
        sources = original.checkpoints.single().artifacts.associate { artifact ->
            artifact.sha256 to File(root, artifact.relativePath).also { file -> assets.open(artifact.relativePath).use { input -> file.outputStream().use { input.copyTo(it) } } }
        }
    }
    @After fun tearDown() { store.close(); db.close(); context.deleteDatabase(databaseName); root.deleteRecursively() }
    private suspend fun commit(op: ProgressOperation, fault: (ProgressCommitBoundary) -> Unit = {}) = store.commit(identity, ProgressProtocol.encode(op), sources, fault = fault)
    private fun child(parent: ProgressOperation, id: String = UUID.randomUUID().toString()): ProgressOperation = parent.copy(
        operationId = id, changes = parent.changes.map { it.copy(parents = listOf(parent.operationId)) },
    )
    private fun reopen() { store.close(); db.close(); db = openDatabase(); store = ProgressStore(db, File(root, "store")) }
    private suspend fun rejected(block: suspend () -> Unit) { var error: Exception? = null; try { block() } catch (e: Exception) { error = e }; assertNotNull("Expected operation rejection", error) }

    @Test fun everyInterruptedCommitRetainsAtomicHeadsAndOutbox() = runBlocking {
        commit(original)
        for (boundary in ProgressCommitBoundary.entries) {
            val next = child(original)
            rejected { commit(next) { if (it == boundary) throw IllegalStateException("Injected $boundary") } }
            reopen()
            val committed = boundary == ProgressCommitBoundary.TRANSACTION_COMMITTED
            assertEquals(listOf(if (committed) next.operationId else original.operationId), store.heads(identity, "save/current"))
            assertEquals(if (committed) 2 else 1, store.outbox(identity).size)
            assertEquals(original.checkpoints.single(), store.checkpoint(identity, "save/current"))
            if (committed) { commit(next); assertEquals(2, store.outbox(identity).size) }
        }
    }

    @Test fun concurrentBranchesRequireExplicitResolutionAndTombstonesCannotEraseThem() = runBlocking {
        commit(original)
        val a = child(original); val b = child(original)
        commit(a); commit(b)
        assertEquals(setOf(a.operationId, b.operationId), store.heads(identity, "save/current").toSet())
        rejected { store.checkpoint(identity, "save/current") }
        val deletion = b.copy(operationId = UUID.randomUUID().toString(), checkpoints = emptyList(), changes = listOf(ProgressRefChange("save/current", listOf(b.operationId), null, true)))
        commit(deletion)
        assertEquals(setOf(a.operationId, deletion.operationId), store.heads(identity, "save/current").toSet())
        val resolution = child(original).copy(changes = original.changes.map { change ->
            change.copy(parents = store.heads(identity, change.reference))
        })
        commit(resolution)
        assertEquals(listOf(resolution.operationId), store.heads(identity, "save/current"))
        assertNotNull(store.checkpoint(identity, "save/current"))
    }

    @Test fun newSaveInvalidatesOldResumeAndWorkingCopiesCannotMutateCheckpoints() = runBlocking {
        commit(original)
        val working = store.materialize(identity, "save/current", UUID.randomUUID().toString())!!
        File(working, "save.srm").writeText("changed by core")
        assertNotNull(store.checkpoint(identity, "save/current"))
        val saveOnly = ProgressProtocol.decode(assets.open("sram-only.json").use { it.readBytes() })
        val next = child(saveOnly).copy(checkpoints = saveOnly.checkpoints.map { it.copy(checkpointId = UUID.randomUUID().toString()) }).let { op -> op.copy(changes = op.changes.map { it.copy(checkpointId = op.checkpoints.single().checkpointId) }) }
        commit(next)
        assertNull(store.checkpoint(identity, original.changes[1].reference))
        assertNotNull(store.checkpoint(identity, "save/current"))
    }

    @Test fun leasesRecoverAndOnlyAcknowledgeTheirExactPayload() = runBlocking {
        commit(original)
        val first = store.leaseNext(identity, 100, 1000)!!
        val next = child(original); commit(next)
        assertNull(store.leaseNext(identity, 101, 1000)) // Child waits for verified ancestor publication.
        reopen()
        val retried = store.leaseNext(identity, 102, 1000)!!
        assertEquals(first.operationId, retried.operationId)
        assertFalse(store.acknowledge(first, first.payloadHash))
        rejected { store.acknowledge(retried, "0".repeat(64)) }
        assertTrue(store.acknowledge(retried, retried.payloadHash))
        assertEquals("PENDING", store.outbox(identity).single { it.operationId == next.operationId }.status)
        val second = store.leaseNext(identity, 103, 1000)!!
        assertEquals(next.operationId, second.operationId)
        assertTrue(store.retry(second, 200, "Sign in required", authBlocked = true))
        assertNull(store.leaseNext(identity, 300, 1000))
        val other = store.resolveIdentity(identity.binding.copy(accountId = "other"))
        store.resumeAuthenticatedWork(other)
        assertNull(store.leaseNext(identity, 300, 1000))
        store.resumeAuthenticatedWork(identity)
        assertEquals(next.operationId, store.leaseNext(identity, 300, 1000)!!.operationId)
    }

    @Test fun accountServerAndContentIdentitiesStaySeparateAcrossRestart() = runBlocking {
        commit(original)
        val variants = listOf(original.binding.copy(accountId = "43"), original.binding.copy(serverKey = "https://other.example/romm"), original.binding.copy(files = original.binding.files.map { it.copy(contentSha256 = "f".repeat(64)) }))
        for (binding in variants) {
            val other = store.resolveIdentity(binding)
            assertNotEquals(identity.contentId, other.contentId)
            assertNull(store.checkpoint(other, "save/current"))
            rejected { store.commit(other, ProgressProtocol.encode(original), sources) }
        }
        reopen(); assertEquals(identity, store.resolveIdentity(original.binding))
        rejected { store.checkpoint(identity.copy(namespaceId = UUID.randomUUID().toString()), "save/current") }
    }

    @Test fun corruptSourcesBlobsAndIdReuseCannotAdvanceReferences() = runBlocking {
        val source = sources.values.first(); val valid = source.readBytes(); source.writeText("truncated")
        rejected { commit(original) }; assertTrue(store.heads(identity, "save/current").isEmpty())
        source.writeBytes(valid); commit(original)
        val changed = original.copy(createdAtEpochMs = original.createdAtEpochMs + 1)
        rejected { commit(changed) }
        val artifact = original.checkpoints.single().artifacts.first()
        val blob = File(root, "store/${identity.namespaceId}/${identity.contentId}/blobs/${artifact.sha256}")
        blob.writeText("broken")
        rejected { store.materialize(identity, "save/current", UUID.randomUUID().toString()) }
        rejected { commit(child(original)) }
        assertEquals(listOf(original.operationId), store.heads(identity, "save/current"))
    }

    @Test fun additiveMigrationPreservesLegacyTablesAndCreatesValidatedRoomSchema() = runBlocking {
        store.close()
        val raw = db.openHelper.writableDatabase
        raw.execSQL("CREATE TABLE fixture_legacy_marker (value TEXT NOT NULL)")
        raw.execSQL("INSERT INTO fixture_legacy_marker VALUES ('retained')")
        for (table in listOf("namespaces", "contents", "operations", "heads", "outbox", "quarantine")) raw.execSQL("DROP TABLE progress_v2_$table")
        // Recreate the actual v8 column shape before exercising both additive migrations.
        io.github.bandoracer.rommio.data.local.restoreVersion10TableShapes(raw)
        for (table in listOf("download_records", "downloaded_roms")) {
            for (column in listOf("ownerProfileId", "ownerBaseUrl", "ownerAccountId", "contentSha256")) raw.execSQL("ALTER TABLE $table DROP COLUMN $column")
        }
        for (column in listOf("attemptId", "destinationRelativePath", "retryAfterEpochMs", "parkedForAuthentication")) raw.execSQL("ALTER TABLE download_records DROP COLUMN $column")
        raw.version = 8
        db.close(); db = openDatabase(); store = ProgressStore(db, File(root, "store"))
        identity = store.resolveIdentity(original.binding) // Opens Room and validates the current migrated schema.
        db.openHelper.readableDatabase.query("SELECT value FROM fixture_legacy_marker").use { assertTrue(it.moveToFirst()); assertEquals("retained", it.getString(0)) }
        commit(original); assertNotNull(store.checkpoint(identity, "save/current"))
    }

    @Test fun ownerLockAndSymlinkEscapeAreRejected() = runBlocking {
        rejected { ProgressStore(db, File(root, "store")).close() }
        commit(original)
        val blobs = File(root, "store/${identity.namespaceId}/${identity.contentId}/blobs")
        val moved = File(root, "moved-blobs")
        assertTrue(blobs.renameTo(moved))
        java.nio.file.Files.createSymbolicLink(blobs.toPath(), moved.toPath())
        rejected { store.checkpoint(identity, "save/current") }
        java.nio.file.Files.delete(blobs.toPath())
        assertTrue(moved.renameTo(blobs))
    }
}
