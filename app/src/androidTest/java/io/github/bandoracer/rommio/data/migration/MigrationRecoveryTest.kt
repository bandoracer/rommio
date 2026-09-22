package io.github.bandoracer.rommio.data.migration

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.domain.progress.*
import io.github.bandoracer.rommio.data.auth.AuthSecretStore
import io.github.bandoracer.rommio.data.cache.ThumbnailCacheStore
import io.github.bandoracer.rommio.data.input.PlayerControlsPreferencesStore
import io.github.bandoracer.rommio.data.local.*
import io.github.bandoracer.rommio.domain.storage.*
import io.github.bandoracer.rommio.model.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationRecoveryTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val identity = UUID.randomUUID().toString()
    private val importedId = "new-$identity"
    private val priorId = "old-$identity"
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var secrets: AuthSecretStore
    private lateinit var controls: PlayerControlsPreferencesStore
    private lateinit var controlsScope: CoroutineScope
    private lateinit var library: LibraryStore
    private lateinit var manager: MigrationBundleManager
    private lateinit var progressStorage: io.github.bandoracer.rommio.domain.progress.ApplicationProgressStorage
    private var failureAt: String? = null
    private fun openDatabase() = Room.databaseBuilder(base, AppDatabase::class.java, "import-$identity").build()
    private fun manager(): MigrationBundleManager = MigrationBundleManager(
        context = context, database = db,
        serverProfileDao = db.serverProfileDao(), downloadedRomDao = db.downloadedRomDao(), downloadRecordDao = db.downloadRecordDao(),
        saveStateDao = db.saveStateDao(), touchLayoutProfileDao = db.touchLayoutProfileDao(), hardwareBindingProfileDao = db.hardwareBindingProfileDao(),
        cachedPlatformDao = db.cachedPlatformDao(), cachedRomDao = db.cachedRomDao(), cachedCollectionDao = db.cachedCollectionDao(),
        cachedCollectionRomDao = db.cachedCollectionRomDao(), cachedHomeEntryDao = db.cachedHomeEntryDao(), profileCacheStateDao = db.profileCacheStateDao(),
        pendingRemoteActionDao = db.pendingRemoteActionDao(), mediaCacheEntryDao = db.mediaCacheEntryDao(), gameSyncJournalDao = db.gameSyncJournalDao(),
        saveStateSyncJournalDao = db.saveStateSyncJournalDao(), recoveryStateDao = db.recoveryStateDao(),
        secretStore = secrets, controlsPreferencesStore = controls, thumbnailCacheStore = ThumbnailCacheStore(context, db.mediaCacheEntryDao()),
        libraryStore = library, closeProgressForReplacement = { progressStorage.closeForReplacement() },
        importCheckpoint = { if (failureAt == it) throw IllegalStateException("Injected interruption: $it") },
    )
    @Before fun setup() = runBlocking {
        root = File(base.cacheDir, "import-$identity").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "protected").apply { mkdirs() }
        }
        db = openDatabase(); secrets = AuthSecretStore(context)
        controlsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        controls = PlayerControlsPreferencesStore(context, controlsScope)
        library = AppManagedLibraryStore(context); library.ensureRootLayout()
        progressStorage = io.github.bandoracer.rommio.domain.progress.ApplicationProgressStorage(db, File(context.noBackupFilesDir, "progress-v2"))
        manager = manager()
    }
    @After fun cleanup() = runBlocking<Unit> {
        StorageAccess.requireRecovery(false)
        StorageAccess.exclusive { progressStorage.closeForReplacement() }
        secrets.clearAll(importedId); secrets.clearAll(priorId)
        File(context.noBackupFilesDir, "library-imports").listFiles()?.filter { it.isDirectory }?.forEach {
            secrets.removeImportSecrets(it.name); secrets.removeImportSecrets("previous_${it.name}")
        }
        controlsScope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); base.deleteDatabase("import-$identity"); root.deleteRecursively()
    }
    private fun profile(id: String) = ServerProfileEntity(id, id, "https://$id.example", "NONE", "ROMM_BEARER_PASSWORD", "{}", "{}", "{}", true, "CONNECTED", null, "now", "now")
    private fun save() = File(library.rootDirectory(), "roms/game.nes")
    private suspend fun bundleAndPriorState(): File {
        save().writeText("new bytes")
        db.serverProfileDao().upsert(profile(importedId))
        db.downloadedRomDao().upsert(DownloadedRomEntity(10, 20, "nes", "Imported", "game.nes", save().absolutePath, 9, 1))
        db.pendingRemoteActionDao().upsert(PendingRemoteActionEntity(importedId, "MANUAL_SLOT_UPLOAD", "pending-save", "{}", "PENDING", 1, 1, null))
        secrets.storeTokenBundle(importedId, TokenBundle("new-token", null, "Bearer", null))
        controls.setOledBlackModeEnabled(true)
        val bundle = File(root, "transfer.zip")
        manager.exportMigrationBundle(Uri.fromFile(bundle))
        db.serverProfileDao().deleteAll(); db.downloadedRomDao().deleteAll(); db.pendingRemoteActionDao().deleteByProfile(importedId)
        db.serverProfileDao().upsert(profile(priorId))
        secrets.clearAll(importedId); secrets.storeTokenBundle(priorId, TokenBundle("old-token", null, "Bearer", null))
        controls.setOledBlackModeEnabled(false); save().writeText("old bytes")
        return bundle
    }
    private suspend fun assertImported() {
        assertEquals(listOf(importedId), db.serverProfileDao().listAll().map { it.id })
        assertEquals("new bytes", save().readText())
        val importedProfile = requireNotNull(db.serverProfileDao().getById(importedId))
        assertEquals(AuthStatus.REAUTH_REQUIRED_ORIGIN.name, importedProfile.status)
        assertFalse(io.github.bandoracer.rommio.data.auth.AuthJsonCodec().decodeSessionState(importedProfile.sessionStateJson).hasOriginSession)
        assertNotNull(db.downloadedRomDao().getByIds(10, 20))
        assertEquals(1, db.pendingRemoteActionDao().listByProfile(importedId).size)
        assertEquals("new-token", secrets.getTokenBundle(importedId)?.accessToken)
        assertNull(secrets.getTokenBundle(priorId)); assertTrue(controls.snapshot().oledBlackModeEnabled)
        val retained = File(context.noBackupFilesDir, "library-imports").walkTopDown().first { it.name == "previous-library" }
        assertEquals("old bytes", File(retained, "roms/game.nes").readText())
        val metadata = retained.parentFile!!.walkTopDown().filter { it.isFile && it.extension == "json" }.joinToString { it.readText() }
        assertFalse(metadata.contains("new-token")); assertFalse(metadata.contains("old-token"))
        assertNull(manager.recoveryError.value)
        StorageAccess.normal { assertEquals("new bytes", save().readText()) }
    }

    @Test fun databaseCommitInterruptionRecoversFilesSecretsPreferencesAndIntent() = runBlocking {
        val bundle = bundleAndPriorState(); failureAt = "DATABASE_COMMIT"
        try { manager.importMigrationBundle(Uri.fromFile(bundle), true); fail("Expected interruption") } catch (_: IllegalStateException) { }
        assertEquals("new bytes", save().readText())
        assertEquals("old-token", secrets.getTokenBundle(priorId)?.accessToken)
        try { StorageAccess.normal { fail("Mixed state became usable") }; fail("Expected maintenance block") } catch (_: IllegalStateException) { }
        db.close(); db = openDatabase(); secrets = AuthSecretStore(context); failureAt = null; manager = manager()
        manager.recoverInterruptedImport(); assertImported()
        manager.recoverInterruptedImport(); assertImported()
        // Re-export skips internal generation metadata and remains a valid bundle.
        val exported = File(root, "again.zip"); manager.exportMigrationBundle(Uri.fromFile(exported))
        assertEquals(1, manager.inspectMigrationBundle(Uri.fromFile(exported)).profileCount)
    }

    @Test fun fileActivationAndSecretCommitInterruptionsFinishForward() = runBlocking {
        val bundle = bundleAndPriorState(); failureAt = "FILES_INSTALLED"
        try { manager.importMigrationBundle(Uri.fromFile(bundle), true); fail("Expected interruption") } catch (_: IllegalStateException) { }
        assertEquals(listOf(priorId), db.serverProfileDao().listAll().map { it.id })
        failureAt = "SECRETS_APPLIED"
        try { manager.recoverInterruptedImport(); fail("Expected secret checkpoint interruption") } catch (_: IllegalStateException) { }
        assertEquals("new-token", secrets.getTokenBundle(importedId)?.accessToken)
        assertFalse(controls.snapshot().oledBlackModeEnabled)
        failureAt = null; manager = manager(); manager.recoverInterruptedImport(); assertImported()
    }

    @Test fun bundleRoundTripPreservesCollidingAccountsAndScopedManualSlots() = runBlocking {
        val installs = listOf(importedId, priorId).mapIndexed { index, id ->
            val profile = profile(id).copy(isActive = index == 0)
            db.serverProfileDao().upsert(profile)
            val file = File(library.rootDirectory(), "roms/$index.nes").apply { writeText("rom $index") }
            val digest = io.github.bandoracer.rommio.data.repository.OwnedDownloadQueue.sha256(file)
            val scope = InstallIdentity.progress(profile.baseUrl, 1, 10, 20, digest)
            val install = DownloadedRomEntity(10, 20, "nes", "Game $index", file.name, file.absolutePath, file.length(), 1,
                id, profile.baseUrl, 1, digest, progressScope = scope)
            db.downloadedRomDao().upsert(install)
            val state = library.saveStateFile(install, 2).apply { parentFile!!.mkdirs(); writeText("slot $index") }
            db.saveStateDao().upsert(SaveStateEntity(10, 2, "Named $index", state.absolutePath, null, 1, scope))
            install
        }
        val bundle = File(root, "scoped.zip")
        assertEquals(3, manager.exportMigrationBundle(Uri.fromFile(bundle)).formatVersion)
        assertEquals(2, manager.inspectMigrationBundle(Uri.fromFile(bundle)).installedGameCount)
        manager.importMigrationBundle(Uri.fromFile(bundle), true)
        assertEquals(2, db.downloadedRomDao().listAll().size)
        val states = db.saveStateDao().listAll()
        assertEquals(2, states.size)
        installs.forEachIndexed { index, install ->
            val restored = requireNotNull(db.downloadedRomDao().getByIds(10, 20, install.namespaceKey))
            assertEquals(install.progressScope, restored.progressScope)
            val state = states.single { it.progressScope == install.progressScope }
            assertEquals("Named $index", state.label); assertEquals("slot $index", File(state.localPath).readText())
        }
    }

    @Test fun versionOneBundleStillImportsWithoutInventingSaveOwnership() = runBlocking {
        val source = bundleAndPriorState(); val old = File(root, "v1.zip")
        ZipOutputStream(old.outputStream()).use { output -> ZipInputStream(source.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name.startsWith("progress-v2/")) continue
                output.putNextEntry(ZipEntry(entry.name))
                val bytes = input.readBytes()
                val converted = when (entry.name) {
                    "manifest.json" -> org.json.JSONObject(bytes.decodeToString()).put("formatVersion", 1).toString().toByteArray()
                    "database.json" -> org.json.JSONObject(bytes.decodeToString()).apply { remove("saveStates"); remove("progress") }.toString().toByteArray()
                    else -> bytes
                }
                output.write(converted); output.closeEntry()
            }
        } }
        manager.importMigrationBundle(Uri.fromFile(old), true)
        assertEquals(InstallIdentity.LEGACY, db.downloadedRomDao().getByIds(10, 20)?.progressScope)
        assertImported()
    }

    @Test fun readOnlyPathLookupsCannotRecreateLibraryDuringDirectorySwap() = runBlocking {
        val install = DownloadedRomEntity(10, 20, "nes", "Game", "game.nes", "unused", 4, 1)
        assertTrue(library.rootDirectory().deleteRecursively())
        library.romDirectory("nes"); library.romTarget("nes", "game.nes")
        library.biosDirectory(); library.coresDirectory(); library.systemDirectory()
        library.saveRamFile(install); library.saveStatesDirectory(install)
        library.saveStateFile(install, 0); library.continuityResumeStateFile(install)
        library.screenshotsDirectory(install); library.resolveCoreLibrary(context, "uninstalled.so")
        assertFalse(library.rootDirectory().exists())
    }

    @Test fun invalidMetadataPathRejectsBeforeReplacingExistingData() = runBlocking {
        val bundle = bundleAndPriorState(); val invalid = File(root, "invalid.zip")
        ZipOutputStream(invalid.outputStream()).use { output -> ZipInputStream(bundle.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                output.putNextEntry(ZipEntry(entry.name))
                val bytes = input.readBytes()
                output.write(if (entry.name == "database.json") bytes.decodeToString().replace("roms/game.nes", "../outside.nes").toByteArray() else bytes)
                output.closeEntry()
            }
        } }
        try { manager.importMigrationBundle(Uri.fromFile(invalid), true); fail("Accepted escaping metadata path") } catch (_: IllegalArgumentException) { }
        assertEquals("old bytes", save().readText()); assertEquals(listOf(priorId), db.serverProfileDao().listAll().map { it.id })
        assertEquals("old-token", secrets.getTokenBundle(priorId)?.accessToken)
        assertFalse(controls.snapshot().oledBlackModeEnabled)
        assertFalse(File(context.noBackupFilesDir, "library-imports/pending").exists())
        assertTrue(File(context.noBackupFilesDir, "library-imports").listFiles().orEmpty().isEmpty())
    }

    @Test fun identityWithoutCommittedProgressRoundTripsAndReplacementRetainsOriginal() = runBlocking {
        val empty = bundleAndPriorState()
        val namespaceId = UUID.randomUUID().toString()
        db.progressDao().insertNamespace(ProgressNamespaceRow(namespaceId, "https://example.test", "1"))
        val saved = File(root, "identity.zip")
        assertEquals(3, manager.exportMigrationBundle(Uri.fromFile(saved)).formatVersion)
        manager.importMigrationBundle(Uri.fromFile(empty), true)
        assertNull(db.progressDao().namespace("https://example.test", "1"))
        val prior = File(context.noBackupFilesDir, "library-imports").walkTopDown().filter { it.name == "previous-database.json" }.toList()
        assertTrue(prior.any { it.readText().contains(namespaceId) })
        manager.importMigrationBundle(Uri.fromFile(saved), true)
        assertEquals(namespaceId, db.progressDao().namespace("https://example.test", "1")?.namespaceId)
    }

    @Test fun orphanCaptureWithoutDatabaseRowsRoundTripsAndOriginalIsRetained() = runBlocking {
        val empty = bundleAndPriorState()
        val relative = "orphan/sessions/capture/operation.json"
        val capture = File(context.noBackupFilesDir, "progress-v2/$relative")
            .apply { parentFile!!.mkdirs(); writeText("uncommitted original operation") }
        val saved = File(root, "orphan.zip")
        manager.exportMigrationBundle(Uri.fromFile(saved))
        assertEquals(capture.length(), manager.inspectMigrationBundle(Uri.fromFile(saved)).progressBytes)
        manager.importMigrationBundle(Uri.fromFile(empty), true)
        assertFalse(capture.exists())
        val previous = File(context.noBackupFilesDir, "library-imports").walkTopDown().first { it.name == "previous-progress-v2" }
        assertEquals("uncommitted original operation", File(previous, relative).readText())
        manager.importMigrationBundle(Uri.fromFile(saved), true)
        assertEquals("uncommitted original operation", capture.readText())
        assertTrue(db.progressDao().exportOperations().isEmpty())
    }

    @Test fun emptyProgressOwnerLockIsReplacedAndNeverExported() = runBlocking {
        val bundle = bundleAndPriorState()
        val originalStore = progressStorage.get()
        val lock = File(context.noBackupFilesDir, "progress-v2/.owner.lock")
        val exported = File(root, "empty-progress.zip")
        manager.exportMigrationBundle(Uri.fromFile(exported))
        assertNotSame(originalStore, progressStorage.get())
        ZipInputStream(exported.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                assertNotEquals("progress-v2/.owner.lock", entry.name)
            }
        }
        manager.importMigrationBundle(Uri.fromFile(bundle), true)
        assertImported()
        assertNotSame(originalStore, progressStorage.get())
        assertTrue(lock.isFile)
    }

    @Test fun twoRootJournalRecoversRealFilesystemAndRoomAcrossRenameInterruptions() = runBlocking {
        for (boundary in listOf("library:BACKED_UP", "library:INSTALLED", "progress-v2:BACKED_UP", "progress-v2:INSTALLED")) {
            val fixture = File(root, "journal-${UUID.randomUUID()}").apply { mkdirs() }
            val imports = File(fixture, "imports").apply { mkdirs() }
            val oldLibrary = File(fixture, "library").apply { mkdirs() }
            val oldProgress = File(fixture, "progress-v2").apply { mkdirs() }
            AtomicFiles.writeBytes(File(oldLibrary, "old"), "old library".toByteArray())
            AtomicFiles.writeBytes(File(oldProgress, "capture"), "old pending capture".toByteArray())
            val id = UUID.randomUUID().toString()
            val payload = File(imports, id).apply { mkdirs() }
            AtomicFiles.writeBytes(File(payload, "library/new"), "new library".toByteArray())
            AtomicFiles.writeBytes(File(payload, "progress-v2/blobs/new"), "new checkpoint".toByteArray())
            AtomicFiles.writeBytes(File(payload, "database.json"), "{}".toByteArray())
            AtomicFiles.writeBytes(File(payload, "controls.json"), "{}".toByteArray())
            db.serverProfileDao().deleteAll(); db.serverProfileDao().upsert(profile(priorId))
            val participants = object : ImportRecoveryJournal.Participants {
                override suspend fun applyDatabase(payload: File) {
                    db.withTransaction {
                        db.serverProfileDao().deleteAll(); db.serverProfileDao().upsert(profile(importedId))
                    }
                }
                override suspend fun applySecrets(importId: String) = Unit
                override suspend fun applyPreferences(payload: File) = Unit
                override suspend fun releaseSecrets(importId: String) = Unit
            }
            val journal = ImportRecoveryJournal(imports, oldLibrary, participants, progress = oldProgress,
                fileCheckpoint = { if (it == boundary) error("interrupted rename") })
            journal.prepare(id, payload)
            try { journal.recover(); fail("Missed $boundary") } catch (_: IllegalStateException) { }
            db.close(); db = openDatabase()
            assertEquals(listOf(priorId), db.serverProfileDao().listAll().map { it.id })
            val recreated = ImportRecoveryJournal(imports, oldLibrary, participants, progress = oldProgress)
            recreated.recover()
            assertEquals(listOf(importedId), db.serverProfileDao().listAll().map { it.id })
            assertEquals("new library", File(oldLibrary, "new").readText())
            assertEquals("new checkpoint", File(oldProgress, "blobs/new").readText())
            assertEquals("old library", File(payload, "previous-library/old").readText())
            assertEquals("old pending capture", File(payload, "previous-progress-v2/capture").readText())
            assertNull(recreated.recover())
        }
    }

    private fun fixtureBytes(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
    private fun sources(operation: ProgressOperation): Map<String, File> = operation.checkpoints.flatMap { it.artifacts }.associate { artifact ->
        artifact.sha256 to File(root, "sources/${artifact.sha256}").also { file ->
            file.parentFile!!.mkdirs(); file.writeBytes(fixtureBytes(artifact.relativePath))
        }
    }
    private val progressRoot get() = File(context.noBackupFilesDir, "progress-v2")
    private suspend fun snapshot() = StorageAccess.exclusive { ProgressBundle(db.progressDao()).snapshot(progressRoot) }
    private fun child(parent: ProgressOperation, reason: String = "periodic"): ProgressOperation {
        val checkpoint = parent.checkpoints.single().copy(checkpointId = UUID.randomUUID().toString(), captureReason = reason)
        return parent.copy(operationId = UUID.randomUUID().toString(), checkpoints = listOf(checkpoint),
            changes = parent.changes.map { it.copy(parents = listOf(parent.operationId), checkpointId = checkpoint.checkpointId) })
    }
    private fun rewriteBundle(source: File, name: String, transform: (String, ByteArray) -> ByteArray?): File = File(root, name).also { target ->
        ZipOutputStream(target.outputStream()).use { output -> ZipInputStream(source.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = transform(entry.name, input.readBytes()) ?: continue
                output.putNextEntry(ZipEntry(entry.name)); output.write(bytes); output.closeEntry()
            }
        } }
    }

    @Test fun completeProgressRoundTripPreservesBranchesTombstonesHistoryQuarantineAndExactBytes() = runBlocking {
        bundleAndPriorState()
        val store = progressStorage.get()
        val originalBytes = fixtureBytes("coherent.json"); val original = ProgressProtocol.decode(originalBytes)
        val identity = store.resolveIdentity(original.binding); val files = sources(original)
        store.commit(identity, originalBytes, files)
        val published = requireNotNull(store.leaseNext(identity, 10, 100))
        assertTrue(store.acknowledge(published, ProgressProtocol.sha256(originalBytes)))
        val slot = "slot/${UUID.randomUUID()}"
        val a = child(original, "manual").let { it.copy(changes = it.changes + ProgressRefChange(slot, emptyList(), it.checkpoints.single().checkpointId, false)) }
        store.commit(identity, ProgressProtocol.encode(a), files)
        assertEquals(a.operationId, store.leaseNext(identity, 11, 100)?.operationId)
        val b = child(original)
        store.commit(identity, ProgressProtocol.encode(b), files)
        db.progressDao().updateOutbox(requireNotNull(db.progressDao().outboxOperation(identity.namespaceId, identity.contentId, b.operationId)).copy(status = "PARKED_AUTH", lastError = "sign in"))
        val deletion = original.copy(operationId = UUID.randomUUID().toString(), checkpoints = emptyList(),
            changes = listOf(ProgressRefChange(slot, listOf(a.operationId), null, true)))
        store.commit(identity, ProgressProtocol.encode(deletion), emptyMap())
        val local = child(original, "manual").let { it.copy(changes = listOf(ProgressRefChange("slot/${UUID.randomUUID()}", emptyList(), it.checkpoints.single().checkpointId, false))) }
        store.commit(identity, ProgressProtocol.encode(local), files, publish = false)
        store.pinCheckpoint(identity, a.checkpoints.single().checkpointId, true)
        val originalHistory = requireNotNull(db.progressDao().historyCheckpoint(identity.namespaceId, identity.contentId, original.checkpoints.single().checkpointId))
        db.progressDao().updateHistory(originalHistory.copy(evicted = true))
        store.quarantine(identity, fixtureBytes("unknown-version.json"), "future format")
        val expected = snapshot()
        val bundle = File(root, "complete-v3.zip")
        manager.exportMigrationBundle(Uri.fromFile(bundle))
        val inspection = manager.inspectMigrationBundle(Uri.fromFile(bundle))
        assertEquals(4, inspection.progressCheckpointCount); assertEquals(0, inspection.progressArtifactIssues)
        assertTrue(inspection.progressBytes > 0)
        val later = child(local)
        progressStorage.get().commit(identity, ProgressProtocol.encode(later), files, publish = false)
        manager.importMigrationBundle(Uri.fromFile(bundle), true)
        val actual = snapshot()
        assertEquals(expected.copy(outbox = expected.outbox.map {
            it.copy(status = if (it.status == "LEASED") "PENDING" else it.status, leaseId = null, leaseUntil = null)
        }), actual)
        val restored = progressStorage.get()
        assertEquals(setOf(a.operationId, b.operationId), restored.heads(identity, "save/current").toSet())
        assertEquals(listOf(deletion.operationId), restored.heads(identity, slot))
        assertArrayEquals(originalBytes, db.progressDao().operation(identity.namespaceId, identity.contentId, original.operationId)?.payload)
        val loaded = restored.materializeCheckpoint(identity, a.checkpoints.single().checkpointId, UUID.randomUUID().toString())
        a.checkpoints.single().artifacts.forEach { assertArrayEquals(fixtureBytes(it.relativePath), File(loaded, it.relativePath).readBytes()) }
        val retained = File(context.noBackupFilesDir, "library-imports").walkTopDown().filter { it.name == "previous-database.json" }.toList()
        assertTrue(retained.any { it.readText().contains(later.operationId) })
    }

    @Test fun importedInterruptedCaptureReplaysOriginalOperationWithoutInventingProgress() = runBlocking {
        bundleAndPriorState()
        val operation = ProgressProtocol.decode(fixtureBytes("coherent.json"))
        val store = progressStorage.get(); val identity = store.resolveIdentity(operation.binding)
        val workspace = File(progressRoot, "${identity.namespaceId}/${identity.contentId}/sessions/${operation.sessionId}")
        val captureId = UUID.randomUUID().toString()
        val capture = File(workspace, "captures/$captureId").apply { mkdirs() }
        sources(operation).forEach { (hash, source) -> source.copyTo(File(capture, hash)) }
        val record = ProgressWorkspaceRecord(sessionId = operation.sessionId, identity = identity, coreSha256 = "0".repeat(64),
            runtimeId = "fixture", contentPath = "content/game.nes", firmware = emptyMap(), phase = "selected")
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(ProgressWorkspaceRecord::class.java)
        AtomicFiles.writeBytes(File(workspace, "session.json"), adapter.toJson(record).toByteArray())
        PendingCheckpointJournal.write(capture, fixtureBytes("coherent.json"), publish = false)
        val bundle = File(root, "capture-v3.zip")
        manager.exportMigrationBundle(Uri.fromFile(bundle))
        manager.importMigrationBundle(Uri.fromFile(bundle), true)
        assertTrue(db.progressDao().exportOperations().isEmpty())
        val restored = progressStorage.get()
        assertEquals(operation.sessionId, restored.recoverWorkingSessions(identity).single().sessionId)
        assertEquals(operation.operationId, restored.pendingWorkspaceCheckpoints(identity, operation.sessionId).single().operationId)
        assertEquals(operation.operationId, restored.recoverWorkspaceCheckpoint(identity, operation.sessionId, captureId))
        assertArrayEquals(fixtureBytes("coherent.json"), db.progressDao().operation(identity.namespaceId, identity.contentId, operation.operationId)?.payload)
        assertEquals("LOCAL_ONLY", restored.outbox(identity).single().status)
    }

    @Test fun damagedCheckpointCanBeBackedUpAndRestoredWithoutClaimingHealthyProgress() = runBlocking {
        val store = progressStorage.get(); val operation = ProgressProtocol.decode(fixtureBytes("coherent.json"))
        val identity = store.resolveIdentity(operation.binding)
        store.commit(identity, fixtureBytes("coherent.json"), sources(operation))
        val artifact = operation.checkpoints.single().artifacts.first()
        val damaged = File(progressRoot, "${identity.namespaceId}/${identity.contentId}/blobs/${artifact.sha256}")
        damaged.writeText("damaged original retained")
        val bundle = File(root, "damaged-v3.zip")
        manager.exportMigrationBundle(Uri.fromFile(bundle))
        assertEquals(1, manager.inspectMigrationBundle(Uri.fromFile(bundle)).progressArtifactIssues)
        assertEquals(1, manager.importMigrationBundle(Uri.fromFile(bundle), true).progressArtifactIssues)
        assertEquals("damaged original retained", damaged.readText())
        assertFalse(progressStorage.get().inspectCheckpoint(identity, operation.checkpoints.single().checkpointId).isLocallyAvailable)
    }

    @Test fun malformedV3PackageCannotChangeActiveProgressOrCredentials() = runBlocking {
        bundleAndPriorState()
        val store = progressStorage.get(); val operation = ProgressProtocol.decode(fixtureBytes("coherent.json"))
        val identity = store.resolveIdentity(operation.binding)
        store.commit(identity, fixtureBytes("coherent.json"), sources(operation), publish = false)
        val expected = snapshot()
        val bundle = File(root, "valid-v3.zip"); manager.exportMigrationBundle(Uri.fromFile(bundle))
        for (kind in listOf("missing", "changed", "owner", "heads", "future", "disguised-v2")) {
            val invalid = rewriteBundle(bundle, "$kind.zip") { name, bytes ->
                when {
                    kind == "missing" && name.contains("/blobs/") && !name.endsWith('/') -> null
                    kind == "changed" && name.contains("/blobs/") && !name.endsWith('/') -> "changed".toByteArray()
                    name == "database.json" && kind in setOf("owner", "heads", "future") -> org.json.JSONObject(bytes.decodeToString()).apply {
                        val progress = getJSONObject("progress")
                        when (kind) {
                            "owner" -> progress.getJSONArray("namespaces").getJSONObject(0).put("accountId", "another-account")
                            "heads" -> progress.put("heads", org.json.JSONArray())
                            "future" -> progress.put("version", 2)
                        }
                    }.toString().toByteArray()
                    name == "manifest.json" && kind == "disguised-v2" -> org.json.JSONObject(bytes.decodeToString()).put("formatVersion", 2).toString().toByteArray()
                    else -> bytes
                }
            }
            try { manager.importMigrationBundle(Uri.fromFile(invalid), true); fail("Accepted $kind") } catch (_: Exception) { }
            assertEquals(expected, snapshot()); assertNotSame(store, progressStorage.get())
            assertEquals("old-token", secrets.getTokenBundle(priorId)?.accessToken)
            assertFalse(File(context.noBackupFilesDir, "library-imports/pending").exists())
        }
    }

    @Test fun v3ImportRecoversConsistentlyAfterEachActivationPhase() = runBlocking {
        bundleAndPriorState()
        val store = progressStorage.get(); val operation = ProgressProtocol.decode(fixtureBytes("coherent.json"))
        val identity = store.resolveIdentity(operation.binding)
        store.commit(identity, fixtureBytes("coherent.json"), sources(operation), publish = false)
        val expected = snapshot()
        val bundle = File(root, "restart-v3.zip"); manager.exportMigrationBundle(Uri.fromFile(bundle))
        for (boundary in listOf("PREPARED", "FILES_INSTALLED", "PROGRESS_INSTALLED", "DATABASE_COMMIT", "SECRETS_APPLIED", "PREFERENCES_APPLIED", "COMMITTED")) {
            failureAt = boundary
            try { manager.importMigrationBundle(Uri.fromFile(bundle), true); fail("Missed $boundary") } catch (_: IllegalStateException) { }
            try { progressStorage.get(); fail("Mixed import became usable") } catch (_: IllegalStateException) { }
            db.close(); db = openDatabase()
            progressStorage = ApplicationProgressStorage(db, progressRoot)
            failureAt = null; manager = manager()
            manager.recoverInterruptedImport()
            assertEquals(expected, snapshot())
            assertArrayEquals(fixtureBytes("coherent.json"), db.progressDao().operation(identity.namespaceId, identity.contentId, operation.operationId)?.payload)
            manager.recoverInterruptedImport()
        }
    }

    @Test fun versionTwoReplacementRetainsV2ProgressInBackupAndClearsActiveRowsTogether() = runBlocking {
        val modern = bundleAndPriorState()
        val legacy = rewriteBundle(modern, "v2.zip") { name, bytes -> when {
            name.startsWith("progress-v2/") -> null
            name == "manifest.json" -> org.json.JSONObject(bytes.decodeToString()).put("formatVersion", 2).toString().toByteArray()
            name == "database.json" -> org.json.JSONObject(bytes.decodeToString()).apply { remove("progress") }.toString().toByteArray()
            else -> bytes
        } }
        val store = progressStorage.get(); val operation = ProgressProtocol.decode(fixtureBytes("coherent.json"))
        store.commit(store.resolveIdentity(operation.binding), fixtureBytes("coherent.json"), sources(operation))
        val expected = snapshot()
        manager.importMigrationBundle(Uri.fromFile(legacy), true)
        assertTrue(db.progressDao().exportOperations().isEmpty()); assertTrue(ProgressBundle.inventory(progressRoot).isEmpty())
        val previous = File(context.noBackupFilesDir, "library-imports").walkTopDown().first { it.name == "previous-progress-v2" }
        ProgressBundle.verifyEntries(expected.entries, ProgressBundle.inventory(previous))
        assertTrue(File(previous.parentFile, "previous-database.json").readText().contains(operation.operationId))
        assertImported()
    }

    @Test fun anotherStorageOwnerPreventsTransferBeforeDestinationChanges() = runBlocking {
        val bundle = bundleAndPriorState()
        val destination = File(root, "unchanged.zip").apply { writeText("existing backup") }
        ProgressStorageLock.acquire(progressRoot).use {
            try { manager.exportMigrationBundle(Uri.fromFile(destination)); fail("Export ignored another owner") }
            catch (_: java.nio.channels.OverlappingFileLockException) { }
            try { manager.importMigrationBundle(Uri.fromFile(bundle), true); fail("Import ignored another owner") }
            catch (_: java.nio.channels.OverlappingFileLockException) { }
        }
        assertEquals("existing backup", destination.readText())
        assertEquals("old bytes", save().readText())
        assertEquals(listOf(priorId), db.serverProfileDao().listAll().map { it.id })
        assertFalse(File(context.noBackupFilesDir, "library-imports/pending").exists())
        manager.importMigrationBundle(Uri.fromFile(bundle), true)
        assertImported()
    }

    @Test fun importedRomCannotClaimAContentIdentityForDifferentBytes() = runBlocking {
        val profile = profile(importedId)
        db.serverProfileDao().upsert(profile)
        save().writeText("original content")
        val hash = io.github.bandoracer.rommio.domain.player.CoreBinary.sha256(save())
        val scope = InstallIdentity.progress(profile.baseUrl, 1, 10, 20, hash)
        db.downloadedRomDao().upsert(DownloadedRomEntity(10, 20, "nes", "Game", "game.nes", save().absolutePath,
            save().length(), 1, importedId, profile.baseUrl, 1, hash, progressScope = scope))
        val bundle = File(root, "owned-content.zip"); manager.exportMigrationBundle(Uri.fromFile(bundle))
        val changed = rewriteBundle(bundle, "wrong-content.zip") { name, bytes ->
            if (name == "library/roms/game.nes") "different bytes!".toByteArray() else bytes
        }
        try { manager.importMigrationBundle(Uri.fromFile(changed), true); fail("Wrong ROM content accepted") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("content identity")) }
        assertEquals("original content", save().readText())
        assertEquals(hash, db.downloadedRomDao().listAll().single().contentSha256)
        assertFalse(File(context.noBackupFilesDir, "library-imports/pending").exists())
    }

    @Test fun legacyPlayerCannotBypassImportedCheckpointOwnership() = runBlocking {
        val operation = ProgressProtocol.decode(fixtureBytes("coherent.json"))
        val binding = operation.binding
        val content = binding.files.single()
        val install = DownloadedRomEntity(binding.romId.toInt(), content.fileId.toInt(), "nes", "Game", "game.nes", save().absolutePath, 1, 1,
            importedId, binding.serverKey, binding.accountId.toInt(), content.contentSha256,
            progressScope = ProgressProtocol.bindingKey(binding))
        io.github.bandoracer.rommio.data.repository.requireLegacyPlayerProgress(db.progressDao(), install)
        val store = progressStorage.get(); store.resolveIdentity(binding)
        try {
            io.github.bandoracer.rommio.data.repository.requireLegacyPlayerProgress(db.progressDao(), install)
            fail("Legacy player ignored owned checkpoint namespace")
        } catch (_: IllegalStateException) { }
        val other = install.copy(ownerAccountId = install.ownerAccountId!! + 1)
        io.github.bandoracer.rommio.data.repository.requireLegacyPlayerProgress(db.progressDao(), other)
        try {
            io.github.bandoracer.rommio.data.repository.requireLegacyPlayerProgress(db.progressDao(), install.copy(progressScope = InstallIdentity.LEGACY))
            fail("Unowned legacy launch guessed progress ownership")
        } catch (_: IllegalStateException) { }
    }
}
