package io.github.bandoracer.rommio.data.migration

import io.github.bandoracer.rommio.model.InstallIdentity
import io.github.bandoracer.rommio.model.MigrationSaveStateRecord
import android.content.Context
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import androidx.room.withTransaction
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.BuildConfig
import io.github.bandoracer.rommio.data.auth.AuthSecretStore
import io.github.bandoracer.rommio.data.cache.ThumbnailCacheStore
import io.github.bandoracer.rommio.data.input.PlayerControlsPreferencesStore
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.data.local.CachedCollectionDao
import io.github.bandoracer.rommio.data.local.CachedCollectionRomDao
import io.github.bandoracer.rommio.data.local.CachedHomeEntryDao
import io.github.bandoracer.rommio.data.local.CachedPlatformDao
import io.github.bandoracer.rommio.data.local.CachedRomDao
import io.github.bandoracer.rommio.data.local.DownloadRecordDao
import io.github.bandoracer.rommio.data.local.DownloadedRomDao
import io.github.bandoracer.rommio.data.local.GameSyncJournalDao
import io.github.bandoracer.rommio.data.local.HardwareBindingProfileDao
import io.github.bandoracer.rommio.data.local.MediaCacheEntryDao
import io.github.bandoracer.rommio.data.local.PendingRemoteActionDao
import io.github.bandoracer.rommio.data.local.ProfileCacheStateDao
import io.github.bandoracer.rommio.data.local.RecoveryStateDao
import io.github.bandoracer.rommio.data.local.RecoveryStateEntity
import io.github.bandoracer.rommio.data.local.SaveStateDao
import io.github.bandoracer.rommio.data.local.SaveStateSyncJournalDao
import io.github.bandoracer.rommio.data.local.SaveStateSyncJournalEntity
import io.github.bandoracer.rommio.data.local.ServerProfileDao
import io.github.bandoracer.rommio.data.local.TouchLayoutProfileDao
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import io.github.bandoracer.rommio.model.DownloadRecordEntity
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.MigrationAuthSecretsExport
import io.github.bandoracer.rommio.model.MigrationBundleInspection
import io.github.bandoracer.rommio.model.MigrationBundleManifest
import io.github.bandoracer.rommio.model.MigrationControlsPreferencesExport
import io.github.bandoracer.rommio.model.MigrationDatabaseExport
import io.github.bandoracer.rommio.model.MigrationDownloadRecord
import io.github.bandoracer.rommio.model.MigrationDownloadedRomRecord
import io.github.bandoracer.rommio.model.MigrationImportSummary
import io.github.bandoracer.rommio.model.MigrationProfileSecrets
import io.github.bandoracer.rommio.model.MigrationRecoveryStateRecord
import io.github.bandoracer.rommio.model.MigrationSaveStateSyncJournalRecord
import io.github.bandoracer.rommio.model.SaveStateEntity
import io.github.bandoracer.rommio.model.toMigrationRecord
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import io.github.bandoracer.rommio.domain.storage.StorageAccess
import io.github.bandoracer.rommio.domain.progress.ProgressStorageLock
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MigrationBundleManager(
    private val context: Context,
    private val database: AppDatabase,
    private val serverProfileDao: ServerProfileDao,
    private val downloadedRomDao: DownloadedRomDao,
    private val downloadRecordDao: DownloadRecordDao,
    private val saveStateDao: SaveStateDao,
    private val touchLayoutProfileDao: TouchLayoutProfileDao,
    private val hardwareBindingProfileDao: HardwareBindingProfileDao,
    private val cachedPlatformDao: CachedPlatformDao,
    private val cachedRomDao: CachedRomDao,
    private val cachedCollectionDao: CachedCollectionDao,
    private val cachedCollectionRomDao: CachedCollectionRomDao,
    private val cachedHomeEntryDao: CachedHomeEntryDao,
    private val profileCacheStateDao: ProfileCacheStateDao,
    private val pendingRemoteActionDao: PendingRemoteActionDao,
    private val mediaCacheEntryDao: MediaCacheEntryDao,
    private val gameSyncJournalDao: GameSyncJournalDao,
    private val saveStateSyncJournalDao: SaveStateSyncJournalDao,
    private val recoveryStateDao: RecoveryStateDao,
    private val secretStore: AuthSecretStore,
    private val controlsPreferencesStore: PlayerControlsPreferencesStore,
    private val thumbnailCacheStore: ThumbnailCacheStore,
    private val libraryStore: LibraryStore,
    private val closeProgressForReplacement: suspend () -> Unit = {},
    private val importCheckpoint: (String) -> Unit = {},
) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val manifestAdapter = moshi.adapter(MigrationBundleManifest::class.java)
    private val databaseAdapter = moshi.adapter(MigrationDatabaseExport::class.java)
    private val authSecretsAdapter = moshi.adapter(MigrationAuthSecretsExport::class.java)
    private val controlsAdapter = moshi.adapter(MigrationControlsPreferencesExport::class.java)

    private val importsDirectory = File(context.noBackupFilesDir, "library-imports")
    private val progressDirectory = File(context.noBackupFilesDir, "progress-v2")
    private val progressBundle = ProgressBundle(database.progressDao())
    val recoveryError = MutableStateFlow<String?>(null)
    private val importJournal = ImportRecoveryJournal(importsDirectory, libraryStore.rootDirectory(), object : ImportRecoveryJournal.Participants {
        override suspend fun applyDatabase(payload: File) {
            val exported = requireNotNull(databaseAdapter.fromJson(File(payload, "database.json").readText()))
            database.withTransaction {
                clearDatabaseState(serverProfileDao.listAll().map { it.id })
                restoreDatabaseState(exported)
                if (exported.saveStates == null) rebuildManualSaveStateCatalog(exported.saveStateSyncJournal)
            }
            importCheckpoint("DATABASE_COMMIT")
        }
        override suspend fun applySecrets(importId: String) {
            val exported = requireNotNull(authSecretsAdapter.fromJson(requireNotNull(secretStore.importSecrets(importId))))
            val previous = requireNotNull(databaseAdapter.fromJson(File(importsDirectory, "$importId/previous-database.json").readText()))
            clearSecrets((previous.serverProfiles.map { it.id } + exported.profiles.map { it.profileId }).distinct())
            restoreSecrets(exported)
            // Imported browser authentication is never borrowed from this installation's old cookie jar.
            serverProfileDao.listAll().forEach { profile ->
                secretStore.storeCookieStoreName(profile.id, profile.baseUrl, "import_${importId}_${profile.id}")
            }
        }
        override suspend fun applyPreferences(payload: File) {
            controlsPreferencesStore.restore(requireNotNull(controlsAdapter.fromJson(File(payload, "controls.json").readText())).preferences)
        }
        override suspend fun releaseSecrets(importId: String) { secretStore.removeImportSecrets(importId) }
    }, progress = progressDirectory, checkpoint = { importCheckpoint(it.name) })

    suspend fun recoverInterruptedImport() = StorageAccess.exclusive {
        withContext(Dispatchers.IO) {
            try {
                if (importJournal.pending() != null) withProgressFiles {
                    if (importJournal.recover() != null) StorageAccess.didReplaceLibrary()
                }
                StorageAccess.requireRecovery(false)
                recoveryError.value = null
                libraryStore.ensureRootLayout()
            } catch (error: Throwable) {
                StorageAccess.requireRecovery(File(importsDirectory, "pending").exists())
                recoveryError.value = error.message ?: "Library import recovery failed. All recovery files are retained."
                throw error
            }
        }
    }

    suspend fun exportMigrationBundle(destinationUri: Uri): MigrationBundleManifest = StorageAccess.exclusive { withContext(Dispatchers.IO) { withProgressFiles {
        StorageAccess.checkRecovered()
        check(!File(importsDirectory, "pending").exists()) { "Finish import recovery before exporting the library." }
        val manifest = buildManifest()
        val databaseExport = buildDatabaseExport()
        val authSecrets = buildAuthSecretsExport(databaseExport.serverProfiles.map { it.id })
        val controls = MigrationControlsPreferencesExport(controlsPreferencesStore.snapshot())
        val output = context.contentResolver.openOutputStream(destinationUri)
            ?: error("Unable to open the selected export destination.")
        output.use { rawStream ->
            ZipOutputStream(BufferedOutputStream(rawStream)).use { zip ->
                writeJsonEntry(zip, MANIFEST_ENTRY, manifestAdapter.toJson(manifest))
                writeJsonEntry(zip, DATABASE_ENTRY, databaseAdapter.toJson(databaseExport))
                writeJsonEntry(zip, AUTH_SECRETS_ENTRY, authSecretsAdapter.toJson(authSecrets))
                writeJsonEntry(zip, CONTROLS_ENTRY, controlsAdapter.toJson(controls))
                writeDirectoryEntry(zip, LIBRARY_PREFIX)
                writeLibraryTree(zip, libraryStore.rootDirectory())
                writeDirectoryEntry(zip, PROGRESS_PREFIX)
                writeProgressTree(zip, requireNotNull(databaseExport.progress))
            }
        }
        manifest
    } } }

    suspend fun inspectMigrationBundle(sourceUri: Uri): MigrationBundleInspection = StorageAccess.normal { withContext(Dispatchers.IO) {
        val staged = stageBundle(sourceUri, extractLibrary = false)
        try {
            val downloaded = staged.databaseExport.downloadedRoms
            val progress = ProgressBundle.summary(requireNotNull(staged.databaseExport.progress))
            MigrationBundleInspection(
                manifest = staged.manifest,
                profileCount = staged.databaseExport.serverProfiles.size,
                installedGameCount = downloaded.map { Triple(InstallIdentity.namespace(it.ownerBaseUrl, it.ownerAccountId), it.romId, 0) }.distinct().size,
                installedFileCount = downloaded.size,
                downloadRecordCount = staged.databaseExport.downloadRecords.size,
                recoveryStateCount = staged.databaseExport.recoveryStates.size,
                manualSlotCount = staged.databaseExport.saveStateSyncJournal.count { it.slot >= 0 && !it.deleted },
                libraryBytes = staged.libraryBytes,
                requiresReplace = hasMeaningfulLocalData(),
                progressCheckpointCount = progress.checkpoints,
                progressBytes = progress.bytes,
                progressArtifactIssues = progress.artifactIssues,
            )
        } finally { staged.stagingRoot.deleteRecursively() }
    } }

    suspend fun importMigrationBundle(sourceUri: Uri, replaceExisting: Boolean): MigrationImportSummary = StorageAccess.exclusive { withContext(Dispatchers.IO) { withProgressFiles {
        StorageAccess.checkRecovered()
        check(importJournal.pending() == null) { "Finish the interrupted import before starting another." }
        if (hasMeaningfulLocalData() && !replaceExisting) error("Local data already exists. Confirm replacement before importing this bundle.")
        val staged = stageBundle(sourceUri, extractLibrary = true)
        val id = staged.stagingRoot.name
        try {
            AtomicFiles.writeBytes(File(staged.stagingRoot, "database.json"), databaseAdapter.toJson(staged.databaseExport).toByteArray())
            AtomicFiles.writeBytes(File(staged.stagingRoot, "controls.json"), controlsAdapter.toJson(staged.controls).toByteArray())
            AtomicFiles.writeBytes(File(staged.stagingRoot, "previous-database.json"), databaseAdapter.toJson(buildDatabaseExport()).toByteArray())
            AtomicFiles.writeBytes(File(staged.stagingRoot, "previous-controls.json"), controlsAdapter.toJson(MigrationControlsPreferencesExport(controlsPreferencesStore.snapshot())).toByteArray())
            // Both new and previous credentials remain encrypted by the platform secret store.
            secretStore.storeImportSecrets("previous_$id", authSecretsAdapter.toJson(buildAuthSecretsExport(serverProfileDao.listAll().map { it.id })))
            secretStore.storeImportSecrets(id, authSecretsAdapter.toJson(staged.authSecrets))
            importJournal.prepare(id, staged.stagingRoot)
            StorageAccess.requireRecovery(true)
            importJournal.recover()
            StorageAccess.didReplaceLibrary()
            libraryStore.ensureRootLayout()
            StorageAccess.requireRecovery(false)
            recoveryError.value = null
            // Derived thumbnails cannot turn a successful authoritative import into a rollback.
            runCatching { thumbnailCacheStore.clearAll() }
            buildSummary(staged.manifest, staged.databaseExport, staged.libraryBytes)
        } catch (error: Throwable) {
            if (File(importsDirectory, "pending").exists()) {
                StorageAccess.requireRecovery(true)
                recoveryError.value = "Import was interrupted. Retry recovery to finish installing the selected library. " + (error.message ?: "")
            } else {
                staged.stagingRoot.deleteRecursively()
                secretStore.removeImportSecrets(id)
                secretStore.removeImportSecrets("previous_$id")
            }
            throw error
        }
    } } }

    private suspend fun <T> withProgressFiles(block: suspend () -> T): T {
        closeProgressForReplacement()
        // Normal calls have drained and native sessions are excluded. Keep other processes out across both renames.
        return ProgressStorageLock.acquire(progressDirectory).use { block() }
    }

    private fun hasProgressData(): Boolean {
        val tables = listOf("namespaces", "contents", "operations", "heads", "outbox", "quarantine", "history", "history_index")
        val query = tables.joinToString(" UNION ALL ") { "SELECT 1 FROM progress_v2_$it" } + " LIMIT 1"
        database.openHelper.readableDatabase.query(query).use {
            if (it.moveToFirst()) return true
        }
        val root = progressDirectory
        if (java.nio.file.Files.isSymbolicLink(root.toPath())) return true
        if (!root.exists()) return false
        if (!root.isDirectory) return true
        val entries = root.listFiles() ?: return true
        return !entries.all {
            it.name == ".owner.lock" && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isFile && it.length() == 0L
        }
    }

    private suspend fun buildDatabaseExport(): MigrationDatabaseExport {
        return MigrationDatabaseExport(
            progress = progressBundle.snapshot(progressDirectory),
            saveStates = saveStateDao.listAll().map { MigrationSaveStateRecord(it.romId, it.slot, it.label, libraryRelativePath(it.localPath), it.screenshotPath?.let(::libraryRelativePath), it.updatedAtEpochMs, it.progressScope) },
            pendingRemoteActions = serverProfileDao.listAll().flatMap { pendingRemoteActionDao.listByProfile(it.id) },
            serverProfiles = serverProfileDao.listAll(),
            downloadedRoms = downloadedRomDao.listAll().map { it.toMigrationRecord(libraryRelativePath(it.localPath)) },
            downloadRecords = downloadRecordDao.listAll().map { it.toMigrationRecord(it.localPath?.let(::libraryRelativePath)) },
            touchLayoutProfiles = touchLayoutProfileDao.listAll(),
            hardwareBindingProfiles = hardwareBindingProfileDao.listAll(),
            gameSyncJournal = gameSyncJournalDao.listAll(),
            saveStateSyncJournal = saveStateSyncJournalDao.listAll().map { it.toMigrationRecord(it.localPath?.let(::libraryRelativePath)) },
            recoveryStates = recoveryStateDao.listAll().map { it.toMigrationRecord(libraryRelativePath(it.localPath)) },
        )
    }

    private fun buildAuthSecretsExport(profileIds: List<String>): MigrationAuthSecretsExport {
        val profiles = profileIds.map { profileId ->
            MigrationProfileSecrets(
                profileId = profileId,
                deviceId = secretStore.getDeviceId(profileId),
                tokenBundle = secretStore.getTokenBundle(profileId),
                basicCredentials = secretStore.getBasicCredentials(profileId),
                cloudflareCredentials = secretStore.getCloudflareCredentials(profileId),
            )
        }
        return MigrationAuthSecretsExport(profiles = profiles)
    }

    private fun buildManifest(): MigrationBundleManifest {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return MigrationBundleManifest(
            sourcePackageId = context.packageName,
            appVersionName = packageInfo.versionName.orEmpty(),
            appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
            exportedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private suspend fun hasMeaningfulLocalData(): Boolean {
        if (hasProgressData()) return true
        if (serverProfileDao.listAll().isNotEmpty()) return true
        if (downloadedRomDao.listAll().isNotEmpty()) return true
        if (downloadRecordDao.listAll().isNotEmpty()) return true
        if (saveStateDao.listAll().isNotEmpty()) return true
        if (touchLayoutProfileDao.listAll().isNotEmpty()) return true
        if (hardwareBindingProfileDao.listAll().isNotEmpty()) return true
        if (gameSyncJournalDao.listAll().isNotEmpty()) return true
        if (saveStateSyncJournalDao.listAll().isNotEmpty()) return true
        if (recoveryStateDao.listAll().isNotEmpty()) return true
        return libraryStore.rootDirectory()
            .walkTopDown()
            .any { file -> file.isFile }
    }

    private suspend fun clearDatabaseState(existingProfileIds: List<String>) {
        downloadedRomDao.deleteAll()
        downloadRecordDao.deleteAll()
        saveStateDao.deleteAll()
        touchLayoutProfileDao.deleteAll()
        hardwareBindingProfileDao.deleteAll()
        recoveryStateDao.deleteAll()
        gameSyncJournalDao.deleteAll()
        saveStateSyncJournalDao.deleteAll()
        mediaCacheEntryDao.deleteAll()
        existingProfileIds.forEach { profileId ->
            cachedHomeEntryDao.deleteByProfile(profileId)
            cachedCollectionRomDao.deleteByProfile(profileId)
            cachedCollectionDao.deleteByProfile(profileId)
            cachedRomDao.deleteByProfile(profileId)
            cachedPlatformDao.deleteByProfile(profileId)
            profileCacheStateDao.deleteByProfile(profileId)
            pendingRemoteActionDao.deleteByProfile(profileId)
        }
        serverProfileDao.deleteAll()
    }

    private suspend fun restoreDatabaseState(export: MigrationDatabaseExport) {
        // Old interrupted imports have no progress participant. New imports normalize legacy bundles to an empty snapshot.
        export.progress?.let { progressBundle.restore(it) }
        val emptySession = io.github.bandoracer.rommio.data.auth.AuthJsonCodec().encodeSessionState(io.github.bandoracer.rommio.model.SessionState())
        serverProfileDao.upsertAll(export.serverProfiles.map {
            it.copy(status = io.github.bandoracer.rommio.model.AuthStatus.REAUTH_REQUIRED_ORIGIN.name, sessionStateJson = emptySession, lastValidationAt = null)
        })
        export.pendingRemoteActions.forEach { pendingRemoteActionDao.upsert(it) }
        downloadedRomDao.upsertAll(export.downloadedRoms.map { it.toEntity(libraryStore.rootDirectory()) })
        downloadRecordDao.upsertAll(export.downloadRecords.map { it.toEntity(libraryStore.rootDirectory()) })
        touchLayoutProfileDao.upsertAll(export.touchLayoutProfiles)
        hardwareBindingProfileDao.upsertAll(export.hardwareBindingProfiles)
        gameSyncJournalDao.upsertAll(export.gameSyncJournal)
        saveStateSyncJournalDao.upsertAll(export.saveStateSyncJournal.map { it.toEntity(libraryStore.rootDirectory()) })
        recoveryStateDao.upsertAll(export.recoveryStates.map { it.toEntity(libraryStore.rootDirectory()) })
        export.saveStates?.let { states -> saveStateDao.upsertAll(states.map {
            SaveStateEntity(it.romId, it.slot, it.label, File(libraryStore.rootDirectory(), it.localPathRelative).absolutePath,
                it.screenshotPathRelative?.let { path -> File(libraryStore.rootDirectory(), path).absolutePath }, it.updatedAtEpochMs, it.progressScope)
        }) }
    }

    private suspend fun rebuildManualSaveStateCatalog(syncJournal: List<MigrationSaveStateSyncJournalRecord>) {
        val labels = syncJournal
            .filter { it.slot >= 0 && !it.deleted }
            .groupBy { it.romId to it.slot }
            .mapValues { (_, rows) ->
                rows.maxWithOrNull(compareBy<MigrationSaveStateSyncJournalRecord> { it.localUpdatedAtEpochMs ?: 0L }
                    .thenBy { it.lastSyncedAtEpochMs ?: 0L })?.label
            }
        val statesDirectory = File(libraryStore.rootDirectory(), "states")
        val manualStates = statesDirectory.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                val match = SLOT_FILE_REGEX.find(file.name) ?: return@mapNotNull null
                val romId = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val slot = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (slot < 0 || syncJournal.any { it.romId == romId && it.slot == slot && it.deleted }) return@mapNotNull null
                SaveStateEntity(
                    romId = romId,
                    slot = slot,
                    label = labels[romId to slot] ?: "Slot $slot",
                    localPath = file.absolutePath,
                    screenshotPath = null,
                    updatedAtEpochMs = file.lastModified(),
                )
            }
            .toList()
        saveStateDao.upsertAll(manualStates)
    }

    private fun clearSecrets(profileIds: List<String>) {
        profileIds.forEach(secretStore::clearAll)
    }

    private fun restoreSecrets(export: MigrationAuthSecretsExport) {
        export.profiles.forEach { profile ->
            profile.basicCredentials?.let { secretStore.storeBasicCredentials(profile.profileId, it) }
                ?: secretStore.clearBasicCredentials(profile.profileId)
            profile.tokenBundle?.let { secretStore.storeTokenBundle(profile.profileId, it) }
                ?: secretStore.clearTokenBundle(profile.profileId)
            profile.cloudflareCredentials?.let { secretStore.storeCloudflareCredentials(profile.profileId, it) }
                ?: secretStore.clearCloudflareCredentials(profile.profileId)
            profile.deviceId?.let { secretStore.storeDeviceId(profile.profileId, it) }
                ?: secretStore.clearDeviceId(profile.profileId)
        }
    }

    private fun buildSummary(
        manifest: MigrationBundleManifest,
        export: MigrationDatabaseExport,
        libraryBytes: Long,
    ): MigrationImportSummary {
        val installed = export.downloadedRoms
        val progress = ProgressBundle.summary(requireNotNull(export.progress))
        return MigrationImportSummary(
            sourcePackageId = manifest.sourcePackageId,
            profileCount = export.serverProfiles.size,
            installedGameCount = installed.map { Triple(InstallIdentity.namespace(it.ownerBaseUrl, it.ownerAccountId), it.romId, 0) }.distinct().size,
            installedFileCount = installed.size,
            downloadRecordCount = export.downloadRecords.size,
            recoveryStateCount = export.recoveryStates.size,
            manualSlotCount = export.saveStateSyncJournal.count { it.slot >= 0 && !it.deleted },
            libraryBytes = libraryBytes,
            progressCheckpointCount = progress.checkpoints,
            progressBytes = progress.bytes,
            progressArtifactIssues = progress.artifactIssues,
        )
    }

    private fun stageBundle(sourceUri: Uri, extractLibrary: Boolean): StagedBundle {
        val stagingRoot = File(importsDirectory, UUID.randomUUID().toString()).apply {
            check(mkdirs()) { "Unable to prepare durable import storage." }
        }
        val stagedLibrary = File(stagingRoot, "library").apply { mkdirs() }
        val stagedProgress = File(stagingRoot, "progress-v2").apply { check(mkdirs()) }
        val progressEntries = mutableListOf<MigrationProgressEntry>()
        var progressSeen = false
        var manifestJson: String? = null
        var databaseJson: String? = null
        var authJson: String? = null
        var controlsJson: String? = null
        var librarySeen = false
        var libraryBytes = 0L

        try {
        val entries = mutableSetOf<String>()
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: error("Unable to open the selected migration bundle.")
        input.use { rawStream ->
            ZipInputStream(BufferedInputStream(rawStream)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(entries.size < ProgressBundle.MAX_ENTRIES) { "Bundle has too many entries." }
                    require(entries.add(entry.name)) { "Bundle contains duplicate entries." }
                    require(entry.name != "$LIBRARY_PREFIX${ImportRecoveryJournal.GENERATION}") { "Bundle contains reserved import metadata." }
                    when {
                        entry.name == MANIFEST_ENTRY -> manifestJson = readMetadata(zip)
                        entry.name == DATABASE_ENTRY -> databaseJson = readMetadata(zip)
                        entry.name == AUTH_SECRETS_ENTRY -> authJson = readMetadata(zip)
                        entry.name == CONTROLS_ENTRY -> controlsJson = readMetadata(zip)
                        entry.name == PROGRESS_PREFIX -> { require(entry.isDirectory); progressSeen = true }
                        entry.name.startsWith(PROGRESS_PREFIX) -> {
                            progressSeen = true
                            val path = entry.name.removePrefix(PROGRESS_PREFIX).removeSuffix("/")
                            val target = safeLibraryChild(stagedProgress, path)
                            val record = if (entry.isDirectory) {
                                if (extractLibrary) check(target.isDirectory || target.mkdirs())
                                require(zip.read() == -1) { "Progress directory contains file data." }
                                MigrationProgressEntry(path, 0, null)
                            } else if (extractLibrary) {
                                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                                java.io.FileOutputStream(target).use { output ->
                                    ProgressBundle.describe(path, zip, output).also { output.flush(); output.fd.sync() }
                                }
                            } else ProgressBundle.describe(path, zip)
                            progressEntries.add(record)
                        }
                        entry.name == LIBRARY_PREFIX -> librarySeen = true
                        entry.name.startsWith(LIBRARY_PREFIX) -> {
                            librarySeen = true
                            val relativePath = entry.name.removePrefix(LIBRARY_PREFIX)
                            if (entry.isDirectory) {
                                if (extractLibrary) {
                                    safeLibraryChild(stagedLibrary, relativePath).mkdirs()
                                }
                            } else {
                                if (extractLibrary) {
                                    val target = safeLibraryChild(stagedLibrary, relativePath)
                                    target.parentFile?.mkdirs()
                                    libraryBytes += copyAndCount(zip, target)
                                } else {
                                    libraryBytes += discardAndCount(zip)
                                }
                            }
                        }

                        else -> {
                            discardAndCount(zip)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }

        val manifest = manifestAdapter.fromJson(requireNotNull(manifestJson) { "Bundle is missing manifest.json." })
            ?: error("manifest.json is invalid.")
        require(manifest.formatVersion in 1..MigrationBundleManifest.CURRENT_FORMAT_VERSION) {
            "Unsupported migration bundle version ${manifest.formatVersion}."
        }
        var databaseExport = databaseAdapter.fromJson(requireNotNull(databaseJson) { "Bundle is missing database.json." })
            ?: error("database.json is invalid.")
        val authSecrets = authSecretsAdapter.fromJson(requireNotNull(authJson) { "Bundle is missing auth_secrets.json." })
            ?: error("auth_secrets.json is invalid.")
        val controls = controlsAdapter.fromJson(requireNotNull(controlsJson) { "Bundle is missing controls_preferences.json." })
            ?: error("controls_preferences.json is invalid.")
        require(librarySeen) { "Bundle is missing the library/ payload." }
        if (manifest.formatVersion >= 3) {
            require(progressSeen) { "Bundle is missing the progress-v2/ payload." }
            val progress = requireNotNull(databaseExport.progress) { "Bundle is missing progress metadata." }
            ProgressBundle.validate(progress)
            ProgressBundle.verifyEntries(progress.entries, progressEntries)
        } else {
            require(!progressSeen && databaseExport.progress == null) { "Legacy bundle contains unsupported progress data." }
            databaseExport = databaseExport.copy(progress = MigrationProgressSnapshot())
        }
        validatePayload(databaseExport, authSecrets, stagedLibrary, extractLibrary)

        return StagedBundle(
            stagingRoot = stagingRoot,
            libraryDirectory = stagedLibrary,
            manifest = manifest,
            databaseExport = databaseExport,
            authSecrets = authSecrets,
            controls = controls,
            libraryBytes = libraryBytes,
        )
        } catch (error: Throwable) {
            stagingRoot.deleteRecursively()
            throw error
        }
    }

    private fun validatePayload(export: MigrationDatabaseExport, secrets: MigrationAuthSecretsExport, stagedLibrary: File, checkFiles: Boolean) {
        val profiles = export.serverProfiles.associateBy { it.id }
        require(profiles.size == export.serverProfiles.size && export.serverProfiles.count { it.isActive } <= 1) { "Bundle profile identities are ambiguous." }
        require(secrets.profiles.map { it.profileId }.distinct().size == secrets.profiles.size && secrets.profiles.all { it.profileId in profiles }) { "Bundle credentials have unknown or duplicate owners." }
        require(export.pendingRemoteActions.all { it.profileId in profiles }) { "Queued work has no imported profile." }
        fun path(relative: String?, required: Boolean = false) {
            if (relative == null) return
            val file = safeLibraryChild(stagedLibrary, relative)
            if (checkFiles && required) require(file.isFile) { "Bundle is missing a referenced installed file." }
        }
        fun owner(id: String?, base: String?, account: Int?) {
            if (id == null) { require(base == null && account == null) { "Partial download ownership." }; return }
            require(base != null && account != null && profiles[id]?.baseUrl == base) { "Imported download ownership does not match its profile." }
            secretStore.verifiedAccount(id)?.let { require(it.baseUrl == base && it.userId == account) { "Imported account conflicts with retained ownership. Use a separate profile." } }
        }
        export.serverProfiles.forEach { profile ->
            secretStore.verifiedAccount(profile.id)?.let { require(it.baseUrl == profile.baseUrl) { "Imported server conflicts with retained account ownership." } }
        }
        require(export.downloadedRoms.map { Triple(InstallIdentity.namespace(it.ownerBaseUrl, it.ownerAccountId), it.romId, it.fileId) }.distinct().size == export.downloadedRoms.size) { "Duplicate installed file identities." }
        require(export.downloadRecords.map { Triple(InstallIdentity.namespace(it.ownerBaseUrl, it.ownerAccountId), it.romId, it.fileId) }.distinct().size == export.downloadRecords.size) { "Duplicate download identities." }
        fun scope(value: String) { require(value == InstallIdentity.LEGACY || value.matches(Regex("[0-9a-f]{64}"))) { "Invalid progress ownership." } }
        export.downloadedRoms.forEach {
            path(it.localPathRelative, true); owner(it.ownerProfileId, it.ownerBaseUrl, it.ownerAccountId); scope(it.progressScope)
            if (it.progressScope != InstallIdentity.LEGACY) require(it.progressScope == InstallIdentity.progress(it.ownerBaseUrl, it.ownerAccountId, it.romId, it.fileId, it.contentSha256)) { "Installed content and progress identity disagree." }
            it.contentSha256?.let { hash ->
                require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid installed content digest." }
                if (checkFiles) {
                    val file = safeLibraryChild(stagedLibrary, it.localPathRelative)
                    require(file.length() == it.fileSizeBytes && io.github.bandoracer.rommio.domain.player.CoreBinary.sha256(file) == hash) {
                        "Imported ROM bytes do not match their recorded content identity."
                    }
                }
            }
        }
        export.downloadRecords.forEach { path(it.localPathRelative); path(it.destinationRelativePath); owner(it.ownerProfileId, it.ownerBaseUrl, it.ownerAccountId) }
        fun progressPath(relative: String?, value: String) {
            scope(value); path(relative)
            if (relative != null && value != InstallIdentity.LEGACY) require(relative.startsWith("progress-local/$value/")) { "Progress file belongs to another content generation." }
        }
        export.saveStates?.forEach { progressPath(it.localPathRelative, it.progressScope); progressPath(it.screenshotPathRelative, it.progressScope) }
        export.gameSyncJournal.forEach { scope(it.progressScope) }
        export.saveStateSyncJournal.forEach { progressPath(it.localPathRelative, it.progressScope) }
        export.recoveryStates.forEach { progressPath(it.localPathRelative, it.progressScope) }
    }

    private fun writeLibraryTree(zip: ZipOutputStream, libraryRoot: File) {
        if (!libraryRoot.exists()) {
            return
        }
        libraryRoot.walkTopDown().forEach { file ->
            require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "Migration export cannot follow symbolic links." }
            val relative = file.relativeTo(libraryRoot).invariantSeparatorsPath
            if (relative.isEmpty() || relative == ImportRecoveryJournal.GENERATION) {
                return@forEach
            }
            val entryName = "$LIBRARY_PREFIX$relative${if (file.isDirectory) "/" else ""}"
            if (file.isDirectory) {
                writeDirectoryEntry(zip, entryName)
            } else {
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun writeProgressTree(zip: ZipOutputStream, snapshot: MigrationProgressSnapshot) {
        for (entry in snapshot.entries) {
            val file = safeLibraryChild(progressDirectory, entry.path)
            require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
            if (entry.sha256 == null) {
                require(file.isDirectory); writeDirectoryEntry(zip, "$PROGRESS_PREFIX${entry.path}")
            } else {
                zip.putNextEntry(ZipEntry("$PROGRESS_PREFIX${entry.path}"))
                val actual = file.inputStream().use { ProgressBundle.describe(entry.path, it, zip) }
                require(actual == entry) { "Progress changed during export; export is incomplete." }
                zip.closeEntry()
            }
        }
    }

    private fun readMetadata(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            size += count
            require(size <= ProgressBundle.MAX_METADATA_BYTES) { "Bundle metadata exceeds the supported limit." }
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    private fun writeJsonEntry(zip: ZipOutputStream, name: String, content: String) {
        require(content.toByteArray(Charsets.UTF_8).size <= ProgressBundle.MAX_METADATA_BYTES) { "Bundle metadata exceeds the supported limit." }
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun writeDirectoryEntry(zip: ZipOutputStream, name: String) {
        val normalized = if (name.endsWith('/')) name else "$name/"
        zip.putNextEntry(ZipEntry(normalized))
        zip.closeEntry()
    }

    private fun copyAndCount(input: InputStream, target: File): Long {
        var bytes = 0L
        java.io.FileOutputStream(target).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                bytes += read
            }
            output.flush()
            output.fd.sync()
        }
        return bytes
    }

    private fun discardAndCount(input: InputStream): Long {
        var bytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            bytes += read
        }
        return bytes
    }

    private fun safeLibraryChild(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') && '\\' !in relativePath && relativePath.removeSuffix("/").split('/').none { it.isEmpty() || it == ".." || it == "." }) { "Invalid managed-library path." }
        val sanitized = relativePath
        val candidate = File(root, sanitized)
        val rootPath = root.canonicalPath
        val candidatePath = candidate.canonicalPath
        require(candidatePath == rootPath || candidatePath.startsWith("$rootPath${File.separator}")) {
            "Bundle contains an invalid library entry: $relativePath"
        }
        return candidate
    }

    private fun libraryRelativePath(absolutePath: String): String {
        val libraryRoot = libraryStore.rootDirectory().canonicalFile
        val candidate = File(absolutePath).canonicalFile
        require(candidate.path.startsWith(libraryRoot.path + File.separator)) {
            "Migration only supports files inside the managed library directory: $absolutePath"
        }
        return candidate.relativeTo(libraryRoot).invariantSeparatorsPath
    }

    companion object {
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val DATABASE_ENTRY = "database.json"
        private const val AUTH_SECRETS_ENTRY = "auth_secrets.json"
        private const val CONTROLS_ENTRY = "controls_preferences.json"
        private const val LIBRARY_PREFIX = "library/"
        private const val PROGRESS_PREFIX = "progress-v2/"
        private const val RESUME_SLOT = 0
        private val SLOT_FILE_REGEX = Regex("^(\\d+)_slot(\\d+)\\.state$")
        const val LEGACY_PACKAGE_ID: String = "io.github.bandoracer.rommio"
    }
}

private data class StagedBundle(
    val stagingRoot: File,
    val libraryDirectory: File,
    val manifest: MigrationBundleManifest,
    val databaseExport: MigrationDatabaseExport,
    val authSecrets: MigrationAuthSecretsExport,
    val controls: MigrationControlsPreferencesExport,
    val libraryBytes: Long,
)

private fun MigrationDownloadedRomRecord.toEntity(libraryRoot: File): DownloadedRomEntity {
    return DownloadedRomEntity(
        progressScope = progressScope,
        romId = romId,
        fileId = fileId,
        platformSlug = platformSlug,
        romName = romName,
        fileName = fileName,
        localPath = File(libraryRoot, localPathRelative).absolutePath,
        fileSizeBytes = fileSizeBytes,
        downloadedAtEpochMs = downloadedAtEpochMs,
        ownerProfileId = ownerProfileId,
        ownerBaseUrl = ownerBaseUrl,
        ownerAccountId = ownerAccountId,
        contentSha256 = contentSha256,
    )
}

private fun DownloadedRomEntity.toMigrationRecord(localPathRelative: String): MigrationDownloadedRomRecord {
    return MigrationDownloadedRomRecord(
        progressScope = progressScope,
        romId = romId,
        fileId = fileId,
        platformSlug = platformSlug,
        romName = romName,
        fileName = fileName,
        localPathRelative = localPathRelative,
        fileSizeBytes = fileSizeBytes,
        downloadedAtEpochMs = downloadedAtEpochMs,
        ownerProfileId = ownerProfileId,
        ownerBaseUrl = ownerBaseUrl,
        ownerAccountId = ownerAccountId,
        contentSha256 = contentSha256,
    )
}

private fun MigrationDownloadRecord.toEntity(libraryRoot: File): DownloadRecordEntity {
    return DownloadRecordEntity(
        romId = romId,
        fileId = fileId,
        romName = romName,
        platformSlug = platformSlug,
        fileName = fileName,
        fileSizeBytes = fileSizeBytes,
        workId = workId,
        status = status,
        progressPercent = progressPercent,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        localPath = localPathRelative?.let { File(libraryRoot, it).absolutePath },
        lastError = lastError,
        enqueuedAtEpochMs = enqueuedAtEpochMs,
        startedAtEpochMs = startedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        ownerProfileId = ownerProfileId,
        ownerBaseUrl = ownerBaseUrl,
        ownerAccountId = ownerAccountId,
        attemptId = attemptId,
        destinationRelativePath = destinationRelativePath,
        contentSha256 = contentSha256,
        retryAfterEpochMs = retryAfterEpochMs,
        parkedForAuthentication = parkedForAuthentication,
    )
}

private fun DownloadRecordEntity.toMigrationRecord(localPathRelative: String?): MigrationDownloadRecord {
    return MigrationDownloadRecord(
        romId = romId,
        fileId = fileId,
        romName = romName,
        platformSlug = platformSlug,
        fileName = fileName,
        fileSizeBytes = fileSizeBytes,
        workId = workId,
        status = status,
        progressPercent = progressPercent,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        localPathRelative = localPathRelative,
        lastError = lastError,
        enqueuedAtEpochMs = enqueuedAtEpochMs,
        startedAtEpochMs = startedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        ownerProfileId = ownerProfileId,
        ownerBaseUrl = ownerBaseUrl,
        ownerAccountId = ownerAccountId,
        attemptId = attemptId,
        destinationRelativePath = destinationRelativePath,
        contentSha256 = contentSha256,
        retryAfterEpochMs = retryAfterEpochMs,
        parkedForAuthentication = parkedForAuthentication,
    )
}

private fun MigrationSaveStateSyncJournalRecord.toEntity(libraryRoot: File): SaveStateSyncJournalEntity {
    return SaveStateSyncJournalEntity(
        progressScope = progressScope,
        profileId = profileId,
        romId = romId,
        fileId = fileId,
        slot = slot,
        label = label,
        localPath = localPathRelative?.let { File(libraryRoot, it).absolutePath },
        localHash = localHash,
        localUpdatedAtEpochMs = localUpdatedAtEpochMs,
        remoteHash = remoteHash,
        remoteUpdatedAtEpochMs = remoteUpdatedAtEpochMs,
        sourceDeviceName = sourceDeviceName,
        deleted = deleted,
        pendingUpload = pendingUpload,
        pendingDelete = pendingDelete,
        lastSyncedAtEpochMs = lastSyncedAtEpochMs,
    )
}

private fun MigrationRecoveryStateRecord.toEntity(libraryRoot: File): RecoveryStateEntity {
    return RecoveryStateEntity(
        progressScope = progressScope,
        romId = romId,
        fileId = fileId,
        entryId = entryId,
        label = label,
        origin = origin,
        localPath = File(libraryRoot, localPathRelative).absolutePath,
        remoteFileName = remoteFileName,
        localHash = localHash,
        remoteHash = remoteHash,
        ringIndex = ringIndex,
        preserved = preserved,
        sourceDeviceName = sourceDeviceName,
        capturedAtEpochMs = capturedAtEpochMs,
        lastSyncedAtEpochMs = lastSyncedAtEpochMs,
    )
}
