package io.github.bandoracer.rommio.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bandoracer.rommio.data.auth.*
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.data.network.DownloadClient
import io.github.bandoracer.rommio.data.network.DownloadHttpException
import io.github.bandoracer.rommio.domain.storage.AppManagedLibraryStore
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import io.github.bandoracer.rommio.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnedDownloadQueueTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "owned-download-${UUID.randomUUID()}"
    private lateinit var db: AppDatabase
    private lateinit var secrets: AuthSecretStore
    private lateinit var auth: AuthManager
    private lateinit var server: MockWebServer
    private lateinit var profile: ServerProfile
    private lateinit var library: LibraryStore
    private lateinit var root: File
    private val profiles = mutableListOf<String>()
    @Volatile private var contentStatus = 200
    @Volatile private var downloadAuthorization: String? = null
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
        .addMigrations(AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13).build()
    private fun manager() = AuthManager(db.serverProfileDao(), secrets, AuthDiscovery(), AuthJsonCodec())
    private fun queue(transfer: DownloadTransfer? = null): OwnedDownloadQueue = if (transfer == null) {
        OwnedDownloadQueue(db, auth, library, DownloadClient(auth))
    } else OwnedDownloadQueue(db, auth, library, DownloadClient(auth), transfer)
    private val writeBytes: DownloadTransfer = { _, _, target, progress ->
        target.parentFile!!.mkdirs(); target.writeBytes(bytes); progress(4, 4)
    }
    private fun namespace() = InstallIdentity.namespace(profile.baseUrl, 1)
    private suspend fun row() = requireNotNull(db.downloadRecordDao().getByIds(10, 20, namespace()) ?: db.downloadRecordDao().getByIds(10, 20))
    private suspend fun enqueue(queue: OwnedDownloadQueue, replace: Boolean = false) =
        queue.enqueue(10, 20, "Game", "nes", "game.nes", 4, replace, false)
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private suspend fun configure(base: String): ServerProfile {
        val p = auth.configureServerProfile(base, discoveryResult = AuthDiscoveryResult(base, AuthCapabilities(), EdgeAuthMode.NONE, OriginAuthMode.ROMM_BEARER_PASSWORD, emptyList()))
        profiles.add(p.id)
        val row = requireNotNull(db.serverProfileDao().getById(p.id))
        db.serverProfileDao().upsert(row.copy(serverAccessJson = AuthJsonCodec().encodeServerAccess(ServerAccessState(status = ServerAccessStatus.READY))))
        auth.loginWithDirectCredentials(p.id, DirectLoginCredentials("player", "password"))
        return p
    }
    @Before fun setup() = runBlocking {
        db = openDatabase(); secrets = AuthSecretStore(context); auth = manager()
        root = File(context.cacheDir, databaseName).apply { mkdirs() }
        val fixtureFiles = root
        library = AppManagedLibraryStore(object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = fixtureFiles
        })
        root = library.rootDirectory().apply { mkdirs() }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/api/token") == true -> json("""{"access_token":"download-token","refresh_token":"refresh","token_type":"Bearer","expires_in":3600}""")
                request.path?.endsWith("/api/heartbeat") == true -> json("""{"SYSTEM":{"VERSION":"test","SHOW_SETUP_WIZARD":false}}""")
                request.path?.endsWith("/api/users/me") == true -> json("""{"id":1,"username":"player","email":"player@example.com","role":"admin"}""")
                request.path?.contains("/api/roms/10/content/") == true -> {
                    downloadAuthorization = request.getHeader("Authorization")
                    MockResponse().setResponseCode(contentStatus).setHeader("Content-Type", "application/octet-stream").setBody(okio.Buffer().write(bytes))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        profile = configure(server.url("/").toString().removeSuffix("/"))
    }
    @After fun cleanup() {
        profiles.forEach { secrets.clearAll(it) }; db.close(); context.deleteDatabase(databaseName)
        root.deleteRecursively(); server.shutdown()
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        var error: Exception? = null
        try { block() } catch (caught: Exception) { error = caught }
        assertNotNull("Expected rejection", error)
    }

    @Test fun productionHttpUsesOriginalProfileAfterSwitchAndRestart() = runBlocking {
        val q = queue(); enqueue(q)
        val original = row()
        configure(server.url("/other").toString())
        db.close(); db = openDatabase(); auth = manager()
        val restored = queue(); restored.recover(); restored.execute(requireNotNull(restored.next()), "worker")
        val installed = requireNotNull(db.downloadedRomDao().getByIds(10, 20, namespace()))
        assertEquals("Bearer download-token", downloadAuthorization)
        assertEquals(profile.id, installed.ownerProfileId)
        assertEquals(original.destinationRelativePath, File(installed.localPath).relativeTo(root).path)
        assertArrayEquals(bytes, File(installed.localPath).readBytes())
        assertEquals(OwnedDownloadQueue.sha256(File(installed.localPath)), installed.contentSha256)
        assertEquals(DownloadStatus.COMPLETED.name, row().status)
        rejected { restored.verifyInstall(installed) }
        auth.setActiveProfile(profile.id); restored.verifyInstall(installed)
    }

    @Test fun collidingAccountsKeepDistinctJobsInstallsAndSavePaths() = runBlocking {
        val q = queue(writeBytes); enqueue(q); val original = row()
        val other = configure(server.url("/other").toString())
        enqueue(q, replace = true)
        val otherKey = InstallIdentity.namespace(other.baseUrl, 1)
        val otherJob = requireNotNull(db.downloadRecordDao().getByIds(10, 20, otherKey))
        assertEquals(original, row()); assertNotEquals(original.namespaceKey, otherJob.namespaceKey)
        q.execute(original, "first"); q.execute(otherJob, "second")
        val first = requireNotNull(db.downloadedRomDao().getByIds(10, 20, namespace()))
        val second = requireNotNull(db.downloadedRomDao().getByIds(10, 20, otherKey))
        assertNotEquals(first.localPath, second.localPath); assertNotEquals(first.progressScope, second.progressScope)
        assertNotEquals(library.saveRamFile(first), library.saveRamFile(second))
        assertNotEquals(library.saveStateFile(first, 0), library.saveStateFile(second, 0))
        q.removeInstall(10, 20, first.localPath, first.namespaceKey)
        assertNotNull(db.downloadedRomDao().getByIds(10, 20, otherKey)); assertTrue(File(second.localPath).exists())
    }

    @Test fun scopedSaveSlotsAndTombstonesDoNotCrossAccounts() = runBlocking {
        val q = queue(writeBytes); enqueue(q); q.execute(row(), "first")
        val first = requireNotNull(db.downloadedRomDao().getByIds(10, 20, namespace()))
        val other = configure(server.url("/other").toString()); enqueue(q)
        val secondJob = requireNotNull(db.downloadRecordDao().getByIds(10, 20, InstallIdentity.namespace(other.baseUrl, 1)))
        q.execute(secondJob, "second")
        val second = requireNotNull(db.downloadedRomDao().getByIds(10, 20, secondJob.namespaceKey))
        val bridge = io.github.bandoracer.rommio.domain.sync.RommSyncBridge(
            { auth.getActiveProfile()?.id }, library, db.saveStateDao(), db.gameSyncJournalDao(), db.saveStateSyncJournalDao(), db.recoveryStateDao(), "fixture")
        val firstState = library.saveStateFile(first, 1).apply { parentFile!!.mkdirs(); writeText("first state") }
        val secondState = library.saveStateFile(second, 1).apply { parentFile!!.mkdirs(); writeText("second state") }
        bridge.recordManualState(first, 1, firstState) // Active profile is already the second account.
        bridge.recordManualState(second, 1, secondState)
        assertEquals(2, db.saveStateDao().listAll().size)
        assertEquals(profile.id, db.saveStateSyncJournalDao().getByKey(profile.id, 10, 20, 1, first.progressScope)?.profileId)
        bridge.markManualStateDeleted(first, 1)
        assertFalse(firstState.exists()); assertEquals("second state", secondState.readText())
        assertEquals(listOf(second.progressScope), db.saveStateDao().listAll().map { it.progressScope })
        assertEquals(true, db.saveStateSyncJournalDao().getByKey(profile.id, 10, 20, 1, first.progressScope)?.deleted)
        assertEquals(false, db.saveStateSyncJournalDao().getByKey(other.id, 10, 20, 1, second.progressScope)?.deleted)
    }

    @Test fun replacementContentGetsNewProgressWhileIdenticalBytesRetainIt() = runBlocking {
        val q = queue(writeBytes); enqueue(q); q.execute(row(), "first")
        val first = requireNotNull(db.downloadedRomDao().getByIds(10, 20, namespace()))
        val save = library.saveRamFile(first).apply { parentFile!!.mkdirs(); writeText("completed level") }
        enqueue(q, replace = true); q.execute(row(), "same")
        assertEquals(first.progressScope, db.downloadedRomDao().getByIds(10, 20, namespace())?.progressScope)
        enqueue(q, replace = true)
        queue { _, _, target, _ -> target.parentFile!!.mkdirs(); target.writeBytes(byteArrayOf(4, 3, 2, 1)) }.execute(row(), "different")
        val changed = requireNotNull(db.downloadedRomDao().getByIds(10, 20, namespace()))
        assertNotEquals(first.progressScope, changed.progressScope)
        assertFalse(library.saveRamFile(changed).exists()); assertEquals("completed level", save.readText())
    }

    @Test fun version10OwnershipMigratesKeysWithoutAssigningLegacyProgress() = runBlocking {
        val q = queue(writeBytes); enqueue(q); q.execute(row(), "first")
        val before = requireNotNull(db.downloadedRomDao().getByIds(10, 20, namespace()))
        val legacy = before.copy(progressScope = InstallIdentity.LEGACY)
        val save = library.saveRamFile(legacy).apply { parentFile!!.mkdirs(); writeText("unassigned history") }
        val raw = db.openHelper.writableDatabase
        io.github.bandoracer.rommio.data.local.restoreVersion10TableShapes(raw)
        raw.version = 10; db.close(); db = openDatabase()
        val migrated = requireNotNull(db.downloadedRomDao().getByIds(10, 20, namespace()))
        assertEquals(before.localPath, migrated.localPath); assertEquals(before.contentSha256, migrated.contentSha256)
        assertEquals(InstallIdentity.LEGACY, migrated.progressScope)
        assertEquals(save, library.saveRamFile(migrated)); assertEquals("unassigned history", save.readText())
        assertEquals(before.namespaceKey, row().namespaceKey)
    }

    @Test fun canceledLateCompletionCannotReplaceRetriedInstall() = runBlocking {
        val started = CompletableDeferred<File>(); val finish = CompletableDeferred<Unit>()
        val old = queue { _, _, target, _ -> started.complete(target); finish.await(); target.parentFile!!.mkdirs(); target.writeBytes(bytes) }
        enqueue(old); val original = row()
        val job = launch { old.execute(original, "old") }; val staleFile = started.await()
        old.cancel(row().toModel())
        val fresh = queue(writeBytes); fresh.retry(row().toModel()); val replacement = row()
        fresh.execute(replacement, "new")
        finish.complete(Unit); job.join()
        assertEquals(replacement.attemptId, row().attemptId)
        assertEquals(DownloadStatus.COMPLETED.name, row().status)
        assertFalse(staleFile.exists())
        assertArrayEquals(bytes, File(requireNotNull(row().localPath)).readBytes())
    }

    @Test fun duplicateExecutorCannotRemoveClaimedFile() = runBlocking {
        val started = CompletableDeferred<File>(); val finish = CompletableDeferred<Unit>()
        val q = queue { _, _, target, _ -> target.parentFile!!.mkdirs(); target.writeBytes(bytes); started.complete(target); finish.await() }
        enqueue(q); val original = row()
        val job = launch { q.execute(original, "winner") }; val target = started.await()
        queue(writeBytes).execute(original, "loser")
        assertTrue("The losing claim deleted the winner's bytes", target.exists())
        finish.complete(Unit); job.join()
        assertEquals(DownloadStatus.COMPLETED.name, row().status)
    }

    @Test fun restartRecoversRunningEvenWhenWorkerIdIsReused() = runBlocking {
        val q = queue(writeBytes); enqueue(q); val original = row()
        db.downloadRecordDao().upsert(original.copy(status = DownloadStatus.RUNNING.name, workId = "same-worker"))
        db.close(); db = openDatabase(); auth = manager()
        val restored = queue(writeBytes); restored.recover()
        assertNotEquals(original.attemptId, row().attemptId)
        restored.execute(row(), "same-worker")
        assertEquals(DownloadStatus.COMPLETED.name, row().status)
    }

    @Test fun unownedLegacyQueueIsPreservedAndParked() = runBlocking {
        val legacy = DownloadRecordEntity(10, 20, "Old", "nes", "game.nes", 4, status = DownloadStatus.QUEUED.name,
            localPath = File(root, "original.nes").apply { writeBytes(bytes) }.absolutePath, enqueuedAtEpochMs = 1, updatedAtEpochMs = 1)
        db.downloadRecordDao().upsert(legacy)
        val q = queue(writeBytes); q.recover()
        assertEquals(DownloadStatus.FAILED.name, row().status)
        assertNull(row().ownerProfileId); assertEquals(legacy.localPath, row().localPath)
        assertArrayEquals(bytes, File(requireNotNull(legacy.localPath)).readBytes())
        enqueue(q, replace = true)
        assertEquals(legacy.localPath, db.downloadRecordDao().getByIds(10, 20)?.localPath)
        assertEquals(namespace(), row().namespaceKey)
    }

    @Test fun failedReplacementPreservesPriorInstallAndTamperingBlocksLaunch() = runBlocking {
        val q = queue(writeBytes); enqueue(q); q.execute(row(), "first")
        val installed = requireNotNull(db.downloadedRomDao().getByIds(10, 20, namespace()))
        enqueue(q, replace = true)
        queue { _, _, target, _ -> target.parentFile!!.mkdirs(); target.writeText("x") }.execute(row(), "bad")
        assertEquals(DownloadStatus.FAILED.name, row().status)
        assertEquals(installed, db.downloadedRomDao().getByIds(10, 20, namespace())); assertEquals(installed.localPath, row().localPath)
        q.verifyInstall(installed)
        File(installed.localPath).writeText("tampered"); rejected { q.verifyInstall(installed) }
    }

    @Test fun authenticationParkingResumesOnlyAfterOriginalProfileSignsIn() = runBlocking {
        val q = queue(writeBytes); enqueue(q)
        auth.logout(profile.id); q.execute(row(), "park")
        assertEquals(true, row().parkedForAuthentication)
        configure(server.url("/other").toString()); q.recover()
        assertEquals(DownloadStatus.FAILED.name, row().status)
        auth.loginWithDirectCredentials(profile.id, DirectLoginCredentials("player", "password"))
        q.recover(); assertEquals(DownloadStatus.QUEUED.name, row().status)
        q.execute(row(), "resumed"); assertEquals(DownloadStatus.COMPLETED.name, row().status)
    }

    @Test fun transientFailurePersistsDeadlineAndFreshAttemptAcrossRestart() = runBlocking {
        val q = queue { _, _, _, _ -> throw DownloadHttpException(503) }
        enqueue(q); val original = row(); q.execute(original, "first")
        assertEquals(DownloadStatus.QUEUED.name, row().status)
        assertNotEquals(original.attemptId, row().attemptId)
        assertTrue(requireNotNull(row().retryAfterEpochMs) > System.currentTimeMillis()); assertNull(q.next())
        db.close(); db = openDatabase(); auth = manager()
        val restored = queue(writeBytes); assertNull(restored.next()); assertTrue(restored.hasPending())
        db.downloadRecordDao().upsert(row().copy(retryAfterEpochMs = 1))
        restored.execute(requireNotNull(restored.next()), "retry"); assertEquals(DownloadStatus.COMPLETED.name, row().status)
    }

    @Test fun removalInvalidatesInflightReplacementBeforeUnlinking() = runBlocking {
        val q = queue(writeBytes); enqueue(q); q.execute(row(), "first")
        val installed = requireNotNull(db.downloadedRomDao().getByIds(10, 20, namespace()))
        enqueue(q, replace = true)
        val started = CompletableDeferred<File>(); val finish = CompletableDeferred<Unit>()
        val replacement = queue { _, _, target, _ -> started.complete(target); finish.await(); target.parentFile!!.mkdirs(); target.writeBytes(bytes) }
        val job = launch { replacement.execute(row(), "replacement") }; val target = started.await()
        q.removeInstall(10, 20, installed.localPath, namespace())
        finish.complete(Unit); job.join()
        assertNull(db.downloadedRomDao().getByIds(10, 20, namespace())); assertNull(row().localPath)
        assertEquals(DownloadStatus.CANCELED.name, row().status)
        assertFalse(File(installed.localPath).exists()); assertFalse(target.exists())
    }

    @Test fun workerCancellationQueuesFreshAttemptAndCleansPartialFile() = runBlocking {
        val started = CompletableDeferred<File>()
        val q = queue { _, _, target, _ ->
            target.parentFile!!.mkdirs(); target.writeBytes(bytes); started.complete(target); awaitCancellation()
        }
        enqueue(q); val original = row()
        val job = launch { q.execute(original, "interrupted") }; val target = started.await()
        job.cancelAndJoin()
        assertEquals(DownloadStatus.QUEUED.name, row().status)
        assertNotEquals(original.attemptId, row().attemptId)
        assertNotNull(row().retryAfterEpochMs); assertFalse(target.exists())
        val replacement = queue(writeBytes); replacement.retry(row().toModel())
        replacement.execute(row(), "next"); assertEquals(DownloadStatus.COMPLETED.name, row().status)
    }

    @Test fun staleRemovalAndEditedServerCannotRemoveOrLaunchCurrentInstall() = runBlocking {
        val q = queue(writeBytes); enqueue(q); q.execute(row(), "first")
        val installed = requireNotNull(db.downloadedRomDao().getByIds(10, 20, namespace()))
        val original = row()
        q.removeInstall(10, 20, null, namespace())
        q.removeInstall(10, 20, File(root, "old.nes").absolutePath, namespace())
        assertEquals(original, row()); assertEquals(installed, db.downloadedRomDao().getByIds(10, 20, namespace()))
        assertTrue(File(installed.localPath).exists())
        val profileRow = requireNotNull(db.serverProfileDao().getById(profile.id))
        db.serverProfileDao().upsert(profileRow.copy(baseUrl = "https://other.invalid"))
        rejected { q.verifyInstall(installed) }
        rejected { enqueue(q, replace = true) }
        assertEquals(original, row())
    }

    @Test fun staleExecutorAfterLogoutCannotChangeAnAlreadyClaimedAttempt() = runBlocking {
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val q = queue { _, _, target, _ -> started.complete(Unit); finish.await(); target.parentFile!!.mkdirs(); target.writeBytes(bytes) }
        enqueue(q); val original = row()
        val job = launch { q.execute(original, "winner") }; started.await()
        auth.logout(profile.id)
        val running = row()
        queue(writeBytes).execute(original, "stale")
        assertEquals(running, row())
        finish.complete(Unit); job.join()
        assertEquals(DownloadStatus.FAILED.name, row().status)
        assertEquals(true, row().parkedForAuthentication); assertNull(db.downloadedRomDao().getByIds(10, 20, namespace()))
    }

    @Test fun migrationPreservesLegacyRowsWithoutInferringOwner() = runBlocking {
        val path = File(root, "legacy.nes").apply { writeBytes(bytes) }.absolutePath
        db.downloadRecordDao().upsert(DownloadRecordEntity(10, 20, "Old", "nes", "game.nes", 4,
            status = DownloadStatus.QUEUED.name, localPath = path, enqueuedAtEpochMs = 1, updatedAtEpochMs = 1))
        db.downloadedRomDao().upsert(DownloadedRomEntity(10, 20, "nes", "Old", "game.nes", path, 4, 1))
        val raw = db.openHelper.writableDatabase
        io.github.bandoracer.rommio.data.local.restoreVersion10TableShapes(raw)
        for (table in listOf("download_records", "downloaded_roms")) {
            for (column in listOf("ownerProfileId", "ownerBaseUrl", "ownerAccountId", "contentSha256")) raw.execSQL("ALTER TABLE $table DROP COLUMN $column")
        }
        for (column in listOf("attemptId", "destinationRelativePath", "retryAfterEpochMs", "parkedForAuthentication")) raw.execSQL("ALTER TABLE download_records DROP COLUMN $column")
        raw.version = 9; db.close(); db = openDatabase()
        assertEquals(path, row().localPath); assertNull(row().ownerProfileId); assertNull(row().attemptId)
        assertEquals(path, db.downloadedRomDao().getByIds(10, 20)?.localPath)
        assertNull(db.downloadedRomDao().getByIds(10, 20)?.ownerAccountId)
        assertArrayEquals(bytes, File(path).readBytes())
    }
}
