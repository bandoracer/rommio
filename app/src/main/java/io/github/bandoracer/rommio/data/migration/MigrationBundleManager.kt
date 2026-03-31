package io.github.bandoracer.rommio.data.migration

import android.content.Context
import android.net.Uri
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
) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val manifestAdapter = moshi.adapter(MigrationBundleManifest::class.java)
    private val databaseAdapter = moshi.adapter(MigrationDatabaseExport::class.java)
    private val authSecretsAdapter = moshi.adapter(MigrationAuthSecretsExport::class.java)
    private val controlsAdapter = moshi.adapter(MigrationControlsPreferencesExport::class.java)

    suspend fun exportMigrationBundle(destinationUri: Uri): MigrationBundleManifest = withContext(Dispatchers.IO) {
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
            }
        }
        manifest
    }

    suspend fun inspectMigrationBundle(sourceUri: Uri): MigrationBundleInspection = withContext(Dispatchers.IO) {
        val staged = stageBundle(sourceUri, extractLibrary = false)
        val downloaded = staged.databaseExport.downloadedRoms
        MigrationBundleInspection(
            manifest = staged.manifest,
            profileCount = staged.databaseExport.serverProfiles.size,
            installedGameCount = downloaded.map { it.romId }.distinct().size,
            installedFileCount = downloaded.size,
            downloadRecordCount = staged.databaseExport.downloadRecords.size,
            recoveryStateCount = staged.databaseExport.recoveryStates.size,
            manualSlotCount = staged.databaseExport.saveStateSyncJournal.count { it.slot >= 0 && !it.deleted },
            libraryBytes = staged.libraryBytes,
            requiresReplace = hasMeaningfulLocalData(),
        )
    }

    suspend fun importMigrationBundle(sourceUri: Uri, replaceExisting: Boolean): MigrationImportSummary = withContext(Dispatchers.IO) {
        val staged = stageBundle(sourceUri, extractLibrary = true)
        val existingProfileIds = serverProfileDao.listAll().map { it.id }
        val hasExistingData = hasMeaningfulLocalData()
        if (hasExistingData && !replaceExisting) {
            error("Local data already exists. Confirm replacement before importing this bundle.")
        }

        val backupDirectory = swapLibraryRoot(staged.libraryDirectory)
        var importCompleted = false
        try {
            database.withTransaction {
                clearDatabaseState(existingProfileIds)
                restoreDatabaseState(staged.databaseExport)
                rebuildManualSaveStateCatalog(staged.databaseExport.saveStateSyncJournal)
            }
            clearSecrets(existingProfileIds)
            restoreSecrets(staged.authSecrets)
            controlsPreferencesStore.restore(staged.controls.preferences)
            thumbnailCacheStore.clearAll()
            importCompleted = true
            buildSummary(staged.manifest, staged.databaseExport, staged.libraryBytes)
        } catch (error: Throwable) {
            restoreLibraryBackup(backupDirectory)
            throw error
        } finally {
            if (importCompleted) {
                runCatching { backupDirectory?.deleteRecursively() }
            }
            runCatching { staged.stagingRoot.deleteRecursively() }
        }
    }

    private suspend fun buildDatabaseExport(): MigrationDatabaseExport {
        return MigrationDatabaseExport(
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
            appVersionCode = packageInfo.longVersionCode,
            exportedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private suspend fun hasMeaningfulLocalData(): Boolean {
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
        serverProfileDao.upsertAll(export.serverProfiles)
        downloadedRomDao.upsertAll(export.downloadedRoms.map { it.toEntity(libraryStore.rootDirectory()) })
        downloadRecordDao.upsertAll(export.downloadRecords.map { it.toEntity(libraryStore.rootDirectory()) })
        touchLayoutProfileDao.upsertAll(export.touchLayoutProfiles)
        hardwareBindingProfileDao.upsertAll(export.hardwareBindingProfiles)
        gameSyncJournalDao.upsertAll(export.gameSyncJournal)
        saveStateSyncJournalDao.upsertAll(export.saveStateSyncJournal.map { it.toEntity(libraryStore.rootDirectory()) })
        recoveryStateDao.upsertAll(export.recoveryStates.map { it.toEntity(libraryStore.rootDirectory()) })
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
                if (slot < 0) return@mapNotNull null
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
        return MigrationImportSummary(
            sourcePackageId = manifest.sourcePackageId,
            profileCount = export.serverProfiles.size,
            installedGameCount = installed.map { it.romId }.distinct().size,
            installedFileCount = installed.size,
            downloadRecordCount = export.downloadRecords.size,
            recoveryStateCount = export.recoveryStates.size,
            manualSlotCount = export.saveStateSyncJournal.count { it.slot >= 0 && !it.deleted },
            libraryBytes = libraryBytes,
        )
    }

    private fun stageBundle(sourceUri: Uri, extractLibrary: Boolean): StagedBundle {
        val stagingRoot = File(context.cacheDir, "migration_bundle_${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        val stagedLibrary = File(stagingRoot, "library").apply { mkdirs() }
        var manifestJson: String? = null
        var databaseJson: String? = null
        var authJson: String? = null
        var controlsJson: String? = null
        var librarySeen = false
        var libraryBytes = 0L

        val input = context.contentResolver.openInputStream(sourceUri)
            ?: error("Unable to open the selected migration bundle.")
        input.use { rawStream ->
            ZipInputStream(BufferedInputStream(rawStream)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == MANIFEST_ENTRY -> manifestJson = zip.readBytes().decodeToString()
                        entry.name == DATABASE_ENTRY -> databaseJson = zip.readBytes().decodeToString()
                        entry.name == AUTH_SECRETS_ENTRY -> authJson = zip.readBytes().decodeToString()
                        entry.name == CONTROLS_ENTRY -> controlsJson = zip.readBytes().decodeToString()
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
        require(manifest.formatVersion == MigrationBundleManifest.CURRENT_FORMAT_VERSION) {
            "Unsupported migration bundle version ${manifest.formatVersion}."
        }
        val databaseExport = databaseAdapter.fromJson(requireNotNull(databaseJson) { "Bundle is missing database.json." })
            ?: error("database.json is invalid.")
        val authSecrets = authSecretsAdapter.fromJson(requireNotNull(authJson) { "Bundle is missing auth_secrets.json." })
            ?: error("auth_secrets.json is invalid.")
        val controls = controlsAdapter.fromJson(requireNotNull(controlsJson) { "Bundle is missing controls_preferences.json." })
            ?: error("controls_preferences.json is invalid.")
        require(librarySeen) { "Bundle is missing the library/ payload." }

        return StagedBundle(
            stagingRoot = stagingRoot,
            libraryDirectory = stagedLibrary,
            manifest = manifest,
            databaseExport = databaseExport,
            authSecrets = authSecrets,
            controls = controls,
            libraryBytes = libraryBytes,
        )
    }

    private fun swapLibraryRoot(stagedLibraryDirectory: File): File? {
        val target = libraryStore.rootDirectory()
        val parent = target.parentFile ?: error("Library root is not writable.")
        val backup = File(parent, "${target.name}_backup_preimport")
        runCatching { backup.deleteRecursively() }
        if (target.exists() && !target.renameTo(backup)) {
            error("Unable to prepare the current library for import.")
        }
        if (!moveDirectory(stagedLibraryDirectory, target)) {
            restoreLibraryBackup(backup)
            error("Unable to install the imported library payload.")
        }
        libraryStore.ensureRootLayout()
        return backup.takeIf { it.exists() }
    }

    private fun restoreLibraryBackup(backupDirectory: File?) {
        val target = libraryStore.rootDirectory()
        if (backupDirectory == null || !backupDirectory.exists()) {
            return
        }
        runCatching { target.deleteRecursively() }
        check(backupDirectory.renameTo(target)) {
            "Import failed and the previous library backup could not be restored automatically."
        }
        libraryStore.ensureRootLayout()
    }

    private fun moveDirectory(source: File, target: File): Boolean {
        if (source.renameTo(target)) {
            return true
        }
        if (!copyDirectory(source, target)) {
            return false
        }
        runCatching { source.deleteRecursively() }
        return true
    }

    private fun copyDirectory(source: File, target: File): Boolean {
        if (!source.exists()) return false
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source).invariantSeparatorsPath
            val destination = if (relative.isBlank()) target else File(target, relative)
            if (file.isDirectory) {
                destination.mkdirs()
            } else {
                destination.parentFile?.mkdirs()
                file.inputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return true
    }

    private fun writeLibraryTree(zip: ZipOutputStream, libraryRoot: File) {
        if (!libraryRoot.exists()) {
            return
        }
        libraryRoot.walkTopDown().forEach { file ->
            val relative = file.relativeTo(libraryRoot).invariantSeparatorsPath
            if (relative.isEmpty()) {
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

    private fun writeJsonEntry(zip: ZipOutputStream, name: String, content: String) {
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
        target.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                bytes += read
            }
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
        val sanitized = relativePath.removePrefix("/").replace('\\', '/')
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
        require(candidate.path.startsWith(libraryRoot.path)) {
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
        romId = romId,
        fileId = fileId,
        platformSlug = platformSlug,
        romName = romName,
        fileName = fileName,
        localPath = File(libraryRoot, localPathRelative).absolutePath,
        fileSizeBytes = fileSizeBytes,
        downloadedAtEpochMs = downloadedAtEpochMs,
    )
}

private fun DownloadedRomEntity.toMigrationRecord(localPathRelative: String): MigrationDownloadedRomRecord {
    return MigrationDownloadedRomRecord(
        romId = romId,
        fileId = fileId,
        platformSlug = platformSlug,
        romName = romName,
        fileName = fileName,
        localPathRelative = localPathRelative,
        fileSizeBytes = fileSizeBytes,
        downloadedAtEpochMs = downloadedAtEpochMs,
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
    )
}

private fun MigrationSaveStateSyncJournalRecord.toEntity(libraryRoot: File): SaveStateSyncJournalEntity {
    return SaveStateSyncJournalEntity(
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
