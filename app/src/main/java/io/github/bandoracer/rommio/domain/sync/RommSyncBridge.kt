package io.github.bandoracer.rommio.domain.sync

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.data.auth.AuthManager
import io.github.bandoracer.rommio.data.local.GameSyncJournalDao
import io.github.bandoracer.rommio.data.local.GameSyncJournalEntity
import io.github.bandoracer.rommio.data.local.RecoveryStateDao
import io.github.bandoracer.rommio.data.local.RecoveryStateEntity
import io.github.bandoracer.rommio.data.local.SaveStateDao
import io.github.bandoracer.rommio.data.local.SaveStateSyncJournalDao
import io.github.bandoracer.rommio.data.local.SaveStateSyncJournalEntity
import io.github.bandoracer.rommio.data.network.DownloadClient
import io.github.bandoracer.rommio.data.network.RommService
import io.github.bandoracer.rommio.data.network.RommServiceFactory
import io.github.bandoracer.rommio.domain.player.RuntimeProfile
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import io.github.bandoracer.rommio.model.AuthenticatedServerContext
import io.github.bandoracer.rommio.model.CloudSaveRevision
import io.github.bandoracer.rommio.model.CloudStateKind
import io.github.bandoracer.rommio.model.CloudStateRevision
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.GameSyncManifest
import io.github.bandoracer.rommio.model.GameSyncPresentation
import io.github.bandoracer.rommio.model.GameSyncStatusKind
import io.github.bandoracer.rommio.model.PlayerLaunchPreparation
import io.github.bandoracer.rommio.model.PlayerLaunchTarget
import io.github.bandoracer.rommio.model.PlayerLaunchTargetKind
import io.github.bandoracer.rommio.model.RecoveryStateOrigin
import io.github.bandoracer.rommio.model.ResumeConflict
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.SaveDto
import io.github.bandoracer.rommio.model.SaveStateEntity
import io.github.bandoracer.rommio.model.ServerProfile
import io.github.bandoracer.rommio.model.StateDto
import io.github.bandoracer.rommio.model.SyncSummary
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

private data class RemoteSnapshot(
    val remoteSave: SaveDto?,
    val statesByName: Map<String, StateDto>,
    val manifest: GameSyncManifest?,
)

private data class LocalContinuitySnapshot(
    val sram: CloudSaveRevision?,
    val resume: CloudStateRevision?,
)

class RommSyncBridge(
    private val authManager: AuthManager,
    private val serviceFactory: RommServiceFactory,
    private val downloadClient: DownloadClient,
    private val libraryStore: LibraryStore,
    private val saveStateDao: SaveStateDao,
    private val gameSyncJournalDao: GameSyncJournalDao,
    private val saveStateSyncJournalDao: SaveStateSyncJournalDao,
    private val recoveryStateDao: RecoveryStateDao,
    private val deviceName: String,
) : SyncBridge {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val manifestAdapter = moshi.adapter(GameSyncManifest::class.java)

    override suspend fun preparePlayerEntry(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
    ): PlayerLaunchPreparation = withContext(Dispatchers.IO) {
        val authContext = ensureAuthenticatedContext()
        val deviceId = authContext.deviceId ?: deviceName
        val service = serviceFactory.create(authContext.profile)
        importLegacyManualStates(authContext.profile.id, installation)

        val journal = gameSyncJournalDao.getByKey(authContext.profile.id, installation.romId, installation.fileId)
            ?: GameSyncJournalEntity(profileId = authContext.profile.id, romId = installation.romId, fileId = installation.fileId)
        val remote = loadRemoteSnapshot(service, authContext.profile, rom.id, installation)
        val local = loadLocalContinuity(installation, deviceId)

        refreshManualSlotsFromRemote(authContext.profile, installation, remote)
        refreshRecoveryStatesFromRemote(authContext.profile, installation, remote)

        val remoteSramHash = remote.remoteSave?.remoteHash()
        val remoteResume = remote.remoteResumeRevision(installation)
        val remoteResumeHash = remoteResume?.hash
        val localDirty = (local.sram?.hash != null && local.sram.hash != journal.lastSyncedSramHash) ||
            (local.resume?.hash != null && local.resume.hash != journal.lastSyncedResumeHash)
        val remoteChanged = (remoteSramHash != null && remoteSramHash != journal.lastSyncedSramHash && remoteSramHash != local.sram?.hash) ||
            (remoteResumeHash != null && remoteResumeHash != journal.lastSyncedResumeHash && remoteResumeHash != local.resume?.hash)
        val sameDeviceRemote = remote.manifest?.deviceId == deviceId
        val remoteUpdatedAt = maxOf(
            remote.remoteSave?.updatedAt.toEpochMillis(),
            remoteResume?.updatedAtEpochMs ?: 0L,
        )
        val localUpdatedAt = maxOf(local.sram?.updatedAtEpochMs ?: 0L, local.resume?.updatedAtEpochMs ?: 0L)
        val now = System.currentTimeMillis()

        when {
            remoteChanged && !localDirty -> {
                val launchTarget = applyRemoteContinuity(authContext.profile, installation, remote)
                val appliedLocal = loadLocalContinuity(installation, deviceId)
                val updatedJournal = journal.copy(
                    lastSyncedSramHash = appliedLocal.sram?.hash,
                    lastSyncedResumeHash = appliedLocal.resume?.hash,
                    remoteSramHash = remoteSramHash,
                    remoteResumeHash = remoteResumeHash,
                    remoteDeviceId = remote.manifest?.deviceId,
                    remoteDeviceName = remote.manifest?.deviceName,
                    remoteSessionActive = remote.manifest?.sessionActive == true,
                    remoteSessionHeartbeatEpochMs = remote.manifest?.lastHeartbeatEpochMs,
                    remoteContinuityUpdatedAtEpochMs = remoteUpdatedAt.takeIf { it > 0L },
                    remoteContinuityAvailable = false,
                    pendingContinuityUpload = false,
                    lastSuccessfulSyncAtEpochMs = now,
                    lastSyncAttemptAtEpochMs = now,
                    lastSyncNote = remote.manifest?.deviceName?.let { "Resumed cloud session from $it." } ?: "Resumed cloud session.",
                    lastError = null,
                )
                gameSyncJournalDao.upsert(updatedJournal)
                PlayerLaunchPreparation(
                    launchTarget = launchTarget,
                    syncPresentation = updatedJournal.toPresentation(),
                )
            }

            remoteChanged && localDirty && !sameDeviceRemote -> {
                val updatedJournal = journal.copy(
                    remoteSramHash = remoteSramHash,
                    remoteResumeHash = remoteResumeHash,
                    remoteDeviceId = remote.manifest?.deviceId,
                    remoteDeviceName = remote.manifest?.deviceName,
                    remoteSessionActive = remote.manifest?.sessionActive == true,
                    remoteSessionHeartbeatEpochMs = remote.manifest?.lastHeartbeatEpochMs,
                    remoteContinuityUpdatedAtEpochMs = remoteUpdatedAt.takeIf { it > 0L },
                    remoteContinuityAvailable = true,
                    pendingContinuityUpload = true,
                    lastSyncAttemptAtEpochMs = now,
                    lastSyncNote = "Choose which resume to keep.",
                    lastError = null,
                )
                gameSyncJournalDao.upsert(updatedJournal)
                PlayerLaunchPreparation(
                    resumeConflict = ResumeConflict(
                        remoteDeviceName = remote.manifest?.deviceName,
                        remoteUpdatedAtEpochMs = remoteUpdatedAt,
                        localUpdatedAtEpochMs = localUpdatedAt,
                    ),
                    syncPresentation = updatedJournal.toPresentation(),
                )
            }

            else -> {
                val updatedJournal = journal.copy(
                    remoteSramHash = remoteSramHash,
                    remoteResumeHash = remoteResumeHash,
                    remoteDeviceId = remote.manifest?.deviceId,
                    remoteDeviceName = remote.manifest?.deviceName,
                    remoteSessionActive = remote.manifest?.sessionActive == true,
                    remoteSessionHeartbeatEpochMs = remote.manifest?.lastHeartbeatEpochMs,
                    remoteContinuityUpdatedAtEpochMs = remoteUpdatedAt.takeIf { it > 0L },
                    remoteContinuityAvailable = remoteChanged && !sameDeviceRemote,
                    pendingContinuityUpload = localDirty,
                    lastSyncAttemptAtEpochMs = now,
                    lastSyncNote = when {
                        remoteChanged && !sameDeviceRemote -> remote.manifest?.deviceName?.let { "Cloud progress available from $it." }
                            ?: "Cloud progress available."
                        localDirty -> "Local progress pending upload."
                        else -> journal.lastSyncNote
                    },
                    lastError = null,
                )
                gameSyncJournalDao.upsert(updatedJournal)
                PlayerLaunchPreparation(syncPresentation = updatedJournal.toPresentation())
            }
        }
    }

    override suspend fun flushContinuity(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
        sessionActive: Boolean,
    ): SyncSummary = withContext(Dispatchers.IO) {
        syncInternal(
            installation = installation,
            rom = rom,
            runtimeProfile = runtimeProfile,
            sessionActive = sessionActive,
            includeManualSlots = false,
        )
    }

    override suspend fun adoptRemoteContinuity(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
    ): PlayerLaunchPreparation = withContext(Dispatchers.IO) {
        val authContext = ensureAuthenticatedContext()
        val deviceId = authContext.deviceId ?: deviceName
        val service = serviceFactory.create(authContext.profile)
        val remote = loadRemoteSnapshot(service, authContext.profile, rom.id, installation)
        importLegacyManualStates(authContext.profile.id, installation)
        refreshManualSlotsFromRemote(authContext.profile, installation, remote)
        refreshRecoveryStatesFromRemote(authContext.profile, installation, remote)

        val launchTarget = applyRemoteContinuity(authContext.profile, installation, remote)
        val appliedLocal = loadLocalContinuity(installation, deviceId)
        val now = System.currentTimeMillis()
        val journal = gameSyncJournalDao.getByKey(authContext.profile.id, installation.romId, installation.fileId)
            ?: GameSyncJournalEntity(profileId = authContext.profile.id, romId = installation.romId, fileId = installation.fileId)
        val updatedJournal = journal.copy(
            lastSyncedSramHash = appliedLocal.sram?.hash,
            lastSyncedResumeHash = appliedLocal.resume?.hash,
            remoteSramHash = remote.remoteSave?.remoteHash(),
            remoteResumeHash = remote.remoteResumeRevision(installation)?.hash,
            remoteDeviceId = remote.manifest?.deviceId,
            remoteDeviceName = remote.manifest?.deviceName,
            remoteSessionActive = remote.manifest?.sessionActive == true,
            remoteSessionHeartbeatEpochMs = remote.manifest?.lastHeartbeatEpochMs,
            remoteContinuityUpdatedAtEpochMs = maxOf(
                remote.remoteSave?.updatedAt.toEpochMillis(),
                remote.remoteResumeRevision(installation)?.updatedAtEpochMs ?: 0L,
            ).takeIf { it > 0L },
            remoteContinuityAvailable = false,
            pendingContinuityUpload = false,
            lastSuccessfulSyncAtEpochMs = now,
            lastSyncAttemptAtEpochMs = now,
            lastSyncNote = remote.manifest?.deviceName?.let { "Resumed cloud session from $it." } ?: "Resumed cloud session.",
            lastError = null,
        )
        gameSyncJournalDao.upsert(updatedJournal)
        PlayerLaunchPreparation(
            launchTarget = launchTarget,
            syncPresentation = updatedJournal.toPresentation(),
        )
    }

    override suspend fun refreshStateRecovery(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
    ) = withContext(Dispatchers.IO) {
        val authContext = ensureAuthenticatedContext()
        val service = serviceFactory.create(authContext.profile)
        importLegacyManualStates(authContext.profile.id, installation)
        val remote = loadRemoteSnapshot(service, authContext.profile, rom.id, installation)
        refreshManualSlotsFromRemote(authContext.profile, installation, remote)
        refreshRecoveryStatesFromRemote(authContext.profile, installation, remote)
    }

    override suspend fun syncGame(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
    ): SyncSummary = withContext(Dispatchers.IO) {
        syncInternal(
            installation = installation,
            rom = rom,
            runtimeProfile = runtimeProfile,
            sessionActive = false,
            includeManualSlots = true,
        )
    }

    override suspend fun recordManualState(
        installation: DownloadedRomEntity,
        slot: Int,
        file: File,
    ) = withContext(Dispatchers.IO) {
        saveStateDao.upsert(
            SaveStateEntity(
                romId = installation.romId,
                slot = slot,
                label = "Slot $slot",
                localPath = file.absolutePath,
                updatedAtEpochMs = file.lastModified(),
            ),
        )
        val profile = authManager.getActiveProfile() ?: return@withContext
        saveStateSyncJournalDao.upsert(
            SaveStateSyncJournalEntity(
                profileId = profile.id,
                romId = installation.romId,
                fileId = installation.fileId,
                slot = slot,
                label = "Slot $slot",
                localPath = file.absolutePath,
                localHash = file.sha256(),
                localUpdatedAtEpochMs = file.lastModified(),
                sourceDeviceName = deviceName,
                deleted = false,
                pendingUpload = true,
                pendingDelete = false,
            ),
        )
    }

    override suspend fun markManualStateDeleted(
        installation: DownloadedRomEntity,
        slot: Int,
    ) = withContext(Dispatchers.IO) {
        saveStateDao.delete(installation.romId, slot)
        runCatching { libraryStore.saveStateFile(installation, slot).delete() }
        val profile = authManager.getActiveProfile() ?: return@withContext
        val existing = saveStateSyncJournalDao.getByKey(profile.id, installation.romId, installation.fileId, slot)
        saveStateSyncJournalDao.upsert(
            (existing ?: SaveStateSyncJournalEntity(
                profileId = profile.id,
                romId = installation.romId,
                fileId = installation.fileId,
                slot = slot,
                label = "Slot $slot",
            )).copy(
                localPath = null,
                localHash = null,
                localUpdatedAtEpochMs = null,
                sourceDeviceName = existing?.sourceDeviceName ?: deviceName,
                deleted = true,
                pendingUpload = false,
                pendingDelete = true,
            ),
        )
    }

    private suspend fun syncInternal(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
        sessionActive: Boolean,
        includeManualSlots: Boolean,
    ): SyncSummary {
        val authContext = ensureAuthenticatedContext()
        val deviceId = authContext.deviceId ?: deviceName
        val profile = authContext.profile
        val service = serviceFactory.create(profile)
        importLegacyManualStates(profile.id, installation)

        val notes = mutableListOf<String>()
        var uploaded = 0
        var downloaded = 0
        val journal = gameSyncJournalDao.getByKey(profile.id, installation.romId, installation.fileId)
            ?: GameSyncJournalEntity(profileId = profile.id, romId = installation.romId, fileId = installation.fileId)
        val remote = loadRemoteSnapshot(service, profile, rom.id, installation)
        val local = loadLocalContinuity(installation, deviceId)
        val now = System.currentTimeMillis()

        if (local.sram != null && local.sram.hash != journal.lastSyncedSramHash) {
            service.uploadSave(
                romId = rom.id,
                emulator = runtimeProfile.runtimeId,
                slot = null,
                deviceId = null,
                overwrite = true,
                saveFile = libraryStore.saveRamFile(installation).toMultipart("saveFile"),
            )
            uploaded += 1
        }

        if (local.resume != null && local.resume.hash != journal.lastSyncedResumeHash) {
            service.uploadState(
                romId = rom.id,
                emulator = runtimeProfile.runtimeId,
                stateFile = libraryStore.continuityResumeStateFile(installation).toMultipart("stateFile"),
            )
            uploaded += 1
        }

        if (captureAutoRecoveryState(installation)) {
            notes += "Captured recovery snapshot."
        }

        if (includeManualSlots) {
            val manualResult = syncManualSlots(
                profile = profile,
                service = service,
                installation = installation,
                rom = rom,
                runtimeProfile = runtimeProfile,
                remote = remote,
            )
            uploaded += manualResult.uploaded
            downloaded += manualResult.downloaded
            notes += manualResult.notes
        } else {
            refreshManualSlotsFromRemote(profile, installation, remote)
        }

        val recoveryResult = syncRecoveryStates(
            profile = profile,
            service = service,
            installation = installation,
            rom = rom,
            runtimeProfile = runtimeProfile,
            remote = remote,
        )
        uploaded += recoveryResult.uploaded
        downloaded += recoveryResult.downloaded
        notes += recoveryResult.notes

        val refreshedLocal = loadLocalContinuity(installation, deviceId)
        val manualSlots = buildManualSlotManifest(profile.id, installation, remote)
        val recoveryHistory = buildRecoveryHistoryManifest(installation, remote)
        uploadManifest(
            service = service,
            profile = profile,
            installation = installation,
            runtimeProfile = runtimeProfile,
            deviceId = authContext.deviceId ?: deviceName,
            sessionActive = sessionActive,
            sram = refreshedLocal.sram,
            resume = refreshedLocal.resume,
            manualSlots = manualSlots,
            recoveryHistory = recoveryHistory,
        )
        uploaded += 1

        gameSyncJournalDao.upsert(
            journal.copy(
                lastSyncedSramHash = refreshedLocal.sram?.hash,
                lastSyncedResumeHash = refreshedLocal.resume?.hash,
                remoteSramHash = refreshedLocal.sram?.hash,
                remoteResumeHash = refreshedLocal.resume?.hash,
                remoteDeviceId = authContext.deviceId,
                remoteDeviceName = deviceName,
                remoteSessionActive = sessionActive,
                remoteSessionHeartbeatEpochMs = now,
                remoteContinuityUpdatedAtEpochMs = maxOf(
                    refreshedLocal.sram?.updatedAtEpochMs ?: 0L,
                    refreshedLocal.resume?.updatedAtEpochMs ?: 0L,
                ).takeIf { it > 0L },
                remoteContinuityAvailable = false,
                pendingContinuityUpload = false,
                lastSuccessfulSyncAtEpochMs = now,
                lastSyncAttemptAtEpochMs = now,
                lastSyncNote = "Synced just now.",
                lastError = null,
            ),
        )

        return SyncSummary(uploaded = uploaded, downloaded = downloaded, notes = notes)
    }

    private suspend fun syncManualSlots(
        profile: ServerProfile,
        service: RommService,
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
        remote: RemoteSnapshot,
    ): SyncSummary {
        val now = System.currentTimeMillis()
        val remoteSlots = remote.remoteManualSlots(installation).associateBy { it.slot }
        val localStates = saveStateDao.listAll().filter { it.romId == installation.romId }.associateBy { it.slot }
        val journals = saveStateSyncJournalDao.listByGame(profile.id, installation.romId, installation.fileId)
            .associateBy { it.slot }
            .toMutableMap()
        localStates.values.forEach { state ->
            if (state.slot !in journals) {
                val file = File(state.localPath)
                journals[state.slot] = SaveStateSyncJournalEntity(
                    profileId = profile.id,
                    romId = installation.romId,
                    fileId = installation.fileId,
                    slot = state.slot,
                    label = state.label,
                    localPath = state.localPath,
                    localHash = file.takeIf { it.exists() }?.sha256(),
                    localUpdatedAtEpochMs = state.updatedAtEpochMs,
                    sourceDeviceName = deviceName,
                    pendingUpload = file.exists(),
                )
            }
        }

        val notes = mutableListOf<String>()
        var uploaded = 0
        var downloaded = 0
        val allSlots = (journals.keys + localStates.keys + remoteSlots.keys.mapNotNull { it }).toSortedSet()

        allSlots.forEach { slot ->
            val existingRow = journals[slot]
            val remoteRevision = remoteSlots[slot]
            if (existingRow?.deleted == true || existingRow?.pendingDelete == true) {
                saveStateDao.delete(installation.romId, slot)
                runCatching { libraryStore.saveStateFile(installation, slot).delete() }
                saveStateSyncJournalDao.upsert(
                    (existingRow ?: SaveStateSyncJournalEntity(
                        profileId = profile.id,
                        romId = installation.romId,
                        fileId = installation.fileId,
                        slot = slot,
                        label = "Slot $slot",
                    )).copy(
                        localPath = null,
                        localHash = null,
                        localUpdatedAtEpochMs = null,
                        remoteHash = remoteRevision?.hash ?: existingRow?.remoteHash,
                        remoteUpdatedAtEpochMs = remoteRevision?.updatedAtEpochMs ?: existingRow?.remoteUpdatedAtEpochMs,
                        sourceDeviceName = existingRow?.sourceDeviceName ?: remoteRevision?.sourceDeviceName ?: deviceName,
                        deleted = true,
                        pendingUpload = false,
                        pendingDelete = false,
                        lastSyncedAtEpochMs = now,
                    ),
                )
                return@forEach
            }

            val localState = localStates[slot]
            val localFile = when {
                existingRow?.localPath != null -> File(existingRow.localPath)
                localState != null -> File(localState.localPath)
                else -> libraryStore.saveStateFile(installation, slot)
            }
            val localExists = localFile.exists()
            val localUpdatedAt = maxOf(
                existingRow?.localUpdatedAtEpochMs ?: 0L,
                localState?.updatedAtEpochMs ?: 0L,
                localFile.takeIf { it.exists() }?.lastModified() ?: 0L,
            )
            val localHash = localFile.takeIf { it.exists() }?.sha256() ?: existingRow?.localHash

            if (localExists && (existingRow?.pendingUpload == true || remoteRevision == null || localUpdatedAt >= remoteRevision.updatedAtEpochMs)) {
                service.uploadState(
                    romId = rom.id,
                    emulator = runtimeProfile.runtimeId,
                    stateFile = localFile.toMultipart("stateFile"),
                )
                saveStateDao.upsert(
                    SaveStateEntity(
                        romId = installation.romId,
                        slot = slot,
                        label = "Slot $slot",
                        localPath = localFile.absolutePath,
                        updatedAtEpochMs = localFile.lastModified(),
                    ),
                )
                saveStateSyncJournalDao.upsert(
                    (existingRow ?: SaveStateSyncJournalEntity(
                        profileId = profile.id,
                        romId = installation.romId,
                        fileId = installation.fileId,
                        slot = slot,
                        label = "Slot $slot",
                    )).copy(
                        label = "Slot $slot",
                        localPath = localFile.absolutePath,
                        localHash = localHash,
                        localUpdatedAtEpochMs = localFile.lastModified(),
                        remoteHash = localHash,
                        remoteUpdatedAtEpochMs = localFile.lastModified(),
                        sourceDeviceName = deviceName,
                        deleted = false,
                        pendingUpload = false,
                        pendingDelete = false,
                        lastSyncedAtEpochMs = now,
                    ),
                )
                uploaded += 1
                return@forEach
            }

            if (remoteRevision != null && (!localExists || remoteRevision.updatedAtEpochMs > localUpdatedAt)) {
                val state = remote.stateFor(remoteRevision.fileName) ?: return@forEach
                val target = libraryStore.saveStateFile(installation, slot)
                downloadRemoteState(profile, state, target)
                saveStateDao.upsert(
                    SaveStateEntity(
                        romId = installation.romId,
                        slot = slot,
                        label = "Slot $slot",
                        localPath = target.absolutePath,
                        updatedAtEpochMs = target.lastModified(),
                    ),
                )
                saveStateSyncJournalDao.upsert(
                    (existingRow ?: SaveStateSyncJournalEntity(
                        profileId = profile.id,
                        romId = installation.romId,
                        fileId = installation.fileId,
                        slot = slot,
                        label = "Slot $slot",
                    )).copy(
                        label = "Slot $slot",
                        localPath = target.absolutePath,
                        localHash = target.sha256(),
                        localUpdatedAtEpochMs = target.lastModified(),
                        remoteHash = remoteRevision.hash,
                        remoteUpdatedAtEpochMs = remoteRevision.updatedAtEpochMs,
                        sourceDeviceName = remoteRevision.sourceDeviceName,
                        deleted = false,
                        pendingUpload = false,
                        pendingDelete = false,
                        lastSyncedAtEpochMs = now,
                    ),
                )
                downloaded += 1
                return@forEach
            }

            if (localExists) {
                saveStateDao.upsert(
                    SaveStateEntity(
                        romId = installation.romId,
                        slot = slot,
                        label = "Slot $slot",
                        localPath = localFile.absolutePath,
                        updatedAtEpochMs = localFile.lastModified(),
                    ),
                )
                saveStateSyncJournalDao.upsert(
                    (existingRow ?: SaveStateSyncJournalEntity(
                        profileId = profile.id,
                        romId = installation.romId,
                        fileId = installation.fileId,
                        slot = slot,
                        label = "Slot $slot",
                    )).copy(
                        label = "Slot $slot",
                        localPath = localFile.absolutePath,
                        localHash = localHash,
                        localUpdatedAtEpochMs = localUpdatedAt,
                        remoteHash = remoteRevision?.hash ?: existingRow?.remoteHash,
                        remoteUpdatedAtEpochMs = remoteRevision?.updatedAtEpochMs ?: existingRow?.remoteUpdatedAtEpochMs,
                        sourceDeviceName = existingRow?.sourceDeviceName ?: remoteRevision?.sourceDeviceName,
                        deleted = false,
                        pendingUpload = false,
                        pendingDelete = false,
                        lastSyncedAtEpochMs = existingRow?.lastSyncedAtEpochMs,
                    ),
                )
            }
        }

        return SyncSummary(uploaded = uploaded, downloaded = downloaded, notes = notes)
    }

    private suspend fun buildManualSlotManifest(
        profileId: String,
        installation: DownloadedRomEntity,
        remote: RemoteSnapshot,
    ): List<CloudStateRevision> {
        val remoteBySlot = remote.remoteManualSlots(installation).associateBy { it.slot }
        return saveStateSyncJournalDao.listByGame(profileId, installation.romId, installation.fileId)
            .map { row ->
                CloudStateRevision(
                    fileName = stateFileName(installation, row.slot),
                    kind = CloudStateKind.MANUAL_SLOT,
                    slot = row.slot,
                    hash = row.remoteHash ?: row.localHash ?: remoteBySlot[row.slot]?.hash,
                    updatedAtEpochMs = row.remoteUpdatedAtEpochMs ?: row.localUpdatedAtEpochMs ?: System.currentTimeMillis(),
                    sourceDeviceId = null,
                    sourceDeviceName = row.sourceDeviceName ?: if (row.deleted) deviceName else null,
                    deleted = row.deleted,
                )
            }
    }

    private suspend fun buildRecoveryHistoryManifest(
        installation: DownloadedRomEntity,
        remote: RemoteSnapshot,
    ): List<CloudStateRevision> {
        val remoteByEntryId = remote.remoteRecoveryHistory(installation).associateBy { recoveryEntryId(it) }
        return recoveryStateDao.listByGame(installation.romId, installation.fileId)
            .filter { it.origin == RecoveryStateOrigin.AUTO_HISTORY.name }
            .map { row ->
                CloudStateRevision(
                    fileName = row.remoteFileName.ifBlank {
                        row.ringIndex?.let { autoHistoryFileName(installation, it) } ?: row.entryId
                    },
                    kind = CloudStateKind.RECOVERY_HISTORY,
                    ringIndex = row.ringIndex,
                    hash = row.remoteHash ?: row.localHash ?: remoteByEntryId[row.entryId]?.hash,
                    updatedAtEpochMs = row.capturedAtEpochMs,
                    sourceDeviceId = null,
                    sourceDeviceName = row.sourceDeviceName,
                    preserved = row.preserved,
                )
            }
            .sortedByDescending { it.updatedAtEpochMs }
    }

    private suspend fun refreshManualSlotsFromRemote(
        profile: ServerProfile,
        installation: DownloadedRomEntity,
        remote: RemoteSnapshot,
    ) {
        val now = System.currentTimeMillis()
        val journals = saveStateSyncJournalDao.listByGame(profile.id, installation.romId, installation.fileId)
            .associateBy { it.slot }
        remote.remoteManualSlots(installation).forEach { revision ->
            val slot = revision.slot ?: return@forEach
            val existing = journals[slot]
            if (revision.deleted) {
                saveStateDao.delete(installation.romId, slot)
                runCatching { libraryStore.saveStateFile(installation, slot).delete() }
                if (existing != null) {
                    saveStateSyncJournalDao.upsert(
                        existing.copy(
                            localPath = null,
                            localHash = null,
                            localUpdatedAtEpochMs = null,
                            remoteHash = revision.hash,
                            remoteUpdatedAtEpochMs = revision.updatedAtEpochMs,
                            sourceDeviceName = revision.sourceDeviceName,
                            deleted = true,
                            pendingUpload = false,
                            pendingDelete = false,
                            lastSyncedAtEpochMs = now,
                        ),
                    )
                }
                return@forEach
            }

            val target = existing?.localPath?.let(::File) ?: libraryStore.saveStateFile(installation, slot)
            val localUpdatedAt = maxOf(existing?.localUpdatedAtEpochMs ?: 0L, target.takeIf { it.exists() }?.lastModified() ?: 0L)
            val state = remote.stateFor(revision.fileName) ?: return@forEach
            if (!target.exists() || revision.updatedAtEpochMs > localUpdatedAt) {
                downloadRemoteState(profile, state, target)
            }
            saveStateDao.upsert(
                SaveStateEntity(
                    romId = installation.romId,
                    slot = slot,
                    label = "Slot $slot",
                    localPath = target.absolutePath,
                    updatedAtEpochMs = target.lastModified(),
                ),
            )
            saveStateSyncJournalDao.upsert(
                (existing ?: SaveStateSyncJournalEntity(
                    profileId = profile.id,
                    romId = installation.romId,
                    fileId = installation.fileId,
                    slot = slot,
                    label = "Slot $slot",
                )).copy(
                    label = "Slot $slot",
                    localPath = target.absolutePath,
                    localHash = target.sha256(),
                    localUpdatedAtEpochMs = target.lastModified(),
                    remoteHash = revision.hash,
                    remoteUpdatedAtEpochMs = revision.updatedAtEpochMs,
                    sourceDeviceName = revision.sourceDeviceName,
                    deleted = false,
                    pendingUpload = false,
                    pendingDelete = false,
                    lastSyncedAtEpochMs = now,
                ),
            )
        }
    }

    private suspend fun refreshRecoveryStatesFromRemote(
        profile: ServerProfile,
        installation: DownloadedRomEntity,
        remote: RemoteSnapshot,
    ) {
        val now = System.currentTimeMillis()
        remote.remoteRecoveryHistory(installation).forEach { remoteRevision ->
            val entryId = recoveryEntryId(remoteRevision)
            val local = recoveryStateDao.getByKey(installation.romId, installation.fileId, entryId)
            val target = local?.localPath?.let(::File) ?: recoveryStateFile(installation, remoteRevision)
            val localUpdatedAt = local?.capturedAtEpochMs ?: target.lastModified()
            val state = remote.stateFor(remoteRevision.fileName) ?: return@forEach
            if (!target.exists() || remoteRevision.updatedAtEpochMs > localUpdatedAt) {
                downloadRemoteState(profile, state, target)
            }
            recoveryStateDao.upsert(
                RecoveryStateEntity(
                    romId = installation.romId,
                    fileId = installation.fileId,
                    entryId = entryId,
                    label = recoveryLabel(remoteRevision),
                    origin = recoveryOrigin(remoteRevision).name,
                    localPath = target.absolutePath,
                    remoteFileName = remoteRevision.fileName,
                    localHash = target.sha256(),
                    remoteHash = remoteRevision.hash,
                    ringIndex = remoteRevision.ringIndex,
                    preserved = remoteRevision.preserved,
                    sourceDeviceName = remoteRevision.sourceDeviceName,
                    capturedAtEpochMs = remoteRevision.updatedAtEpochMs,
                    lastSyncedAtEpochMs = now,
                ),
            )
        }
    }

    private suspend fun captureAutoRecoveryState(
        installation: DownloadedRomEntity,
    ): Boolean {
        val resumeFile = libraryStore.continuityResumeStateFile(installation)
        if (!resumeFile.exists()) return false
        val now = System.currentTimeMillis()
        val existingAuto = recoveryStateDao.listByGame(installation.romId, installation.fileId)
            .filter { it.origin == RecoveryStateOrigin.AUTO_HISTORY.name }
        if (!shouldCaptureAutoRecovery(existingAuto.map { it.capturedAtEpochMs }, now)) return false
        val ringIndex = recoveryRingIndex(now)
        val entryId = autoRecoveryEntryId(ringIndex)
        val target = File(libraryStore.saveStatesDirectory(installation), autoHistoryFileName(installation, ringIndex))
        target.parentFile?.mkdirs()
        resumeFile.copyTo(target, overwrite = true)
        recoveryStateDao.upsert(
            RecoveryStateEntity(
                romId = installation.romId,
                fileId = installation.fileId,
                entryId = entryId,
                label = "Auto snapshot",
                origin = RecoveryStateOrigin.AUTO_HISTORY.name,
                localPath = target.absolutePath,
                remoteFileName = autoHistoryFileName(installation, ringIndex),
                localHash = target.sha256(),
                remoteHash = null,
                ringIndex = ringIndex,
                preserved = false,
                sourceDeviceName = deviceName,
                capturedAtEpochMs = now,
                lastSyncedAtEpochMs = null,
            ),
        )
        return true
    }

    private suspend fun syncRecoveryStates(
        profile: ServerProfile,
        service: RommService,
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
        remote: RemoteSnapshot,
    ): SyncSummary {
        val now = System.currentTimeMillis()
        val remoteStates = remote.remoteRecoveryHistory(installation).associateBy { recoveryEntryId(it) }
        val existing = recoveryStateDao.listByGame(installation.romId, installation.fileId).associateBy { it.entryId }
        val notes = mutableListOf<String>()
        var uploaded = 0
        var downloaded = 0

        existing.values.sortedByDescending { it.capturedAtEpochMs }.forEach { row ->
            val localFile = File(row.localPath)
            val localExists = localFile.exists()
            val localUpdatedAt = maxOf(row.capturedAtEpochMs, localFile.takeIf { it.exists() }?.lastModified() ?: 0L)
            val remoteRevision = remoteStates[row.entryId]
            val origin = runCatching { RecoveryStateOrigin.valueOf(row.origin) }.getOrDefault(RecoveryStateOrigin.LEGACY_IMPORT)

            if (origin == RecoveryStateOrigin.AUTO_HISTORY && localExists && (remoteRevision == null || localUpdatedAt >= remoteRevision.updatedAtEpochMs)) {
                service.uploadState(
                    romId = rom.id,
                    emulator = runtimeProfile.runtimeId,
                    stateFile = localFile.toMultipart("stateFile"),
                )
                recoveryStateDao.upsert(
                    row.copy(
                        localHash = localFile.sha256(),
                        remoteHash = localFile.sha256(),
                        sourceDeviceName = deviceName,
                        capturedAtEpochMs = localUpdatedAt,
                        lastSyncedAtEpochMs = now,
                    ),
                )
                uploaded += 1
                return@forEach
            }

            if (remoteRevision != null && (!localExists || remoteRevision.updatedAtEpochMs > localUpdatedAt)) {
                val state = remote.stateFor(remoteRevision.fileName) ?: return@forEach
                val target = recoveryStateFile(installation, remoteRevision)
                downloadRemoteState(profile, state, target)
                recoveryStateDao.upsert(
                    row.copy(
                        label = recoveryLabel(remoteRevision),
                        origin = recoveryOrigin(remoteRevision).name,
                        localPath = target.absolutePath,
                        remoteFileName = remoteRevision.fileName,
                        localHash = target.sha256(),
                        remoteHash = remoteRevision.hash,
                        ringIndex = remoteRevision.ringIndex,
                        preserved = remoteRevision.preserved,
                        sourceDeviceName = remoteRevision.sourceDeviceName,
                        capturedAtEpochMs = remoteRevision.updatedAtEpochMs,
                        lastSyncedAtEpochMs = now,
                    ),
                )
                downloaded += 1
            }
        }

        remoteStates.values
            .filter { recoveryEntryId(it) !in existing.keys }
            .forEach { remoteRevision ->
                val state = remote.stateFor(remoteRevision.fileName) ?: return@forEach
                val target = recoveryStateFile(installation, remoteRevision)
                downloadRemoteState(profile, state, target)
                recoveryStateDao.upsert(
                    RecoveryStateEntity(
                        romId = installation.romId,
                        fileId = installation.fileId,
                        entryId = recoveryEntryId(remoteRevision),
                        label = recoveryLabel(remoteRevision),
                        origin = recoveryOrigin(remoteRevision).name,
                        localPath = target.absolutePath,
                        remoteFileName = remoteRevision.fileName,
                        localHash = target.sha256(),
                        remoteHash = remoteRevision.hash,
                        ringIndex = remoteRevision.ringIndex,
                        preserved = remoteRevision.preserved,
                        sourceDeviceName = remoteRevision.sourceDeviceName,
                        capturedAtEpochMs = remoteRevision.updatedAtEpochMs,
                        lastSyncedAtEpochMs = now,
                    ),
                )
                downloaded += 1
            }

        return SyncSummary(uploaded = uploaded, downloaded = downloaded, notes = notes)
    }

    private suspend fun applyRemoteContinuity(
        profile: ServerProfile,
        installation: DownloadedRomEntity,
        remote: RemoteSnapshot,
    ): PlayerLaunchTarget? {
        remote.remoteSave?.let { save ->
            downloadRemoteSave(profile, save, libraryStore.saveRamFile(installation))
        }
        val remoteResume = remote.remoteResumeRevision(installation) ?: return null
        val target = libraryStore.continuityResumeStateFile(installation)
        val state = remote.stateFor(remoteResume.fileName) ?: return null
        downloadRemoteState(profile, state, target)
        return PlayerLaunchTarget(
            kind = PlayerLaunchTargetKind.CONTINUITY,
            localStatePath = target.absolutePath,
            label = "Resume",
        )
    }

    private suspend fun uploadManifest(
        service: RommService,
        profile: ServerProfile,
        installation: DownloadedRomEntity,
        runtimeProfile: RuntimeProfile,
        deviceId: String,
        sessionActive: Boolean,
        sram: CloudSaveRevision?,
        resume: CloudStateRevision?,
        manualSlots: List<CloudStateRevision>,
        recoveryHistory: List<CloudStateRevision>,
    ) {
        val manifest = GameSyncManifest(
            romId = installation.romId,
            fileId = installation.fileId,
            deviceId = deviceId,
            deviceName = deviceName,
            sessionActive = sessionActive,
            sessionStartedAtEpochMs = null,
            lastHeartbeatEpochMs = System.currentTimeMillis(),
            sram = sram,
            resume = resume,
            manualSlots = manualSlots,
            recoveryHistory = recoveryHistory,
        )
        val tempDir = createTempDir(prefix = "rommio-manifest")
        val target = File(tempDir, manifestFileName(installation))
        target.writeText(manifestAdapter.toJson(manifest))
        try {
            service.uploadState(
                romId = installation.romId,
                emulator = runtimeProfile.runtimeId,
                stateFile = target.toMultipart("stateFile"),
            )
        } finally {
            runCatching { target.delete() }
            runCatching { tempDir.delete() }
        }
    }

    private suspend fun loadRemoteSnapshot(
        service: RommService,
        profile: ServerProfile,
        romId: Int,
        installation: DownloadedRomEntity,
    ): RemoteSnapshot {
        val remoteSave = service.listSaves(romId, null)
            .filter { it.fileName == libraryStore.saveRamFile(installation).name }
            .maxByOrNull { it.updatedAt.toEpochMillis() }
        val statesByName = service.listStates(romId)
            .groupBy { it.fileName }
            .mapValues { (_, entries) -> entries.maxByOrNull { it.updatedAt.toEpochMillis() }!! }
        val manifest = statesByName[manifestFileName(installation)]?.let { downloadManifest(profile, it) }
        return RemoteSnapshot(
            remoteSave = remoteSave,
            statesByName = statesByName,
            manifest = manifest,
        )
    }

    private suspend fun downloadManifest(profile: ServerProfile, state: StateDto): GameSyncManifest? {
        val temp = File.createTempFile("rommio-manifest-download", ".json")
        return try {
            downloadClient.downloadToFile(
                profileId = profile.id,
                absoluteUrl = buildStateContentUrl(profile.baseUrl, state.downloadPath),
                target = temp,
            )
            manifestAdapter.fromJson(temp.readText())
        } finally {
            temp.delete()
        }
    }

    private suspend fun downloadRemoteSave(
        profile: ServerProfile,
        save: SaveDto,
        target: File,
    ) {
        downloadClient.downloadToFile(
            profileId = profile.id,
            absoluteUrl = buildSaveContentUrl(profile.baseUrl, save.id),
            target = target,
        )
    }

    private suspend fun downloadRemoteState(
        profile: ServerProfile,
        state: StateDto,
        target: File,
    ) {
        downloadClient.downloadToFile(
            profileId = profile.id,
            absoluteUrl = buildStateContentUrl(profile.baseUrl, state.downloadPath),
            target = target,
        )
    }

    private suspend fun loadLocalContinuity(
        installation: DownloadedRomEntity,
        deviceId: String,
    ): LocalContinuitySnapshot {
        val sramFile = libraryStore.saveRamFile(installation)
        val resumeFile = libraryStore.continuityResumeStateFile(installation)
        return LocalContinuitySnapshot(
            sram = sramFile.takeIf { it.exists() }?.toSaveRevision(deviceId),
            resume = resumeFile.takeIf { it.exists() }?.toStateRevision(
                kind = CloudStateKind.RESUME,
                slot = null,
                deviceId = deviceId,
            ),
        )
    }

    private suspend fun importLegacyManualStates(profileId: String, installation: DownloadedRomEntity) {
        migrateLegacyResumeStateIfNeeded(profileId, installation)
        libraryStore.saveStatesDirectory(installation).listFiles().orEmpty()
            .filter { it.isFile }
            .mapNotNull { file ->
                SLOT_REGEX.find(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { slot -> slot to file }
            }
            .filter { (slot, _) -> slot >= 0 }
            .forEach { (slot, file) ->
                saveStateDao.upsert(
                    SaveStateEntity(
                        romId = installation.romId,
                        slot = slot,
                        label = "Slot $slot",
                        localPath = file.absolutePath,
                        updatedAtEpochMs = file.lastModified(),
                    ),
                )
                val existing = saveStateSyncJournalDao.getByKey(profileId, installation.romId, installation.fileId, slot)
                saveStateSyncJournalDao.upsert(
                    (existing ?: SaveStateSyncJournalEntity(
                        profileId = profileId,
                        romId = installation.romId,
                        fileId = installation.fileId,
                        slot = slot,
                        label = "Slot $slot",
                    )).copy(
                        label = "Slot $slot",
                        localPath = file.absolutePath,
                        localHash = file.sha256(),
                        localUpdatedAtEpochMs = file.lastModified(),
                        sourceDeviceName = existing?.sourceDeviceName ?: deviceName,
                        deleted = false,
                        pendingUpload = existing?.pendingUpload ?: true,
                        pendingDelete = false,
                    ),
                )
            }
    }

    private suspend fun ensureAuthenticatedContext(): AuthenticatedServerContext {
        val profile = authManager.getActiveProfile()
            ?: error("Configure server access before syncing.")
        val deviceId = authManager.ensureDeviceId(profile.id)
        val refreshedProfile = authManager.getProfile(profile.id) ?: profile
        return AuthenticatedServerContext(
            profile = refreshedProfile,
            deviceId = deviceId,
        )
    }

    private fun SaveDto.remoteHash(): String = syntheticRemoteHash(fileName, updatedAt.toEpochMillis())

    private fun RemoteSnapshot.remoteResumeRevision(installation: DownloadedRomEntity): CloudStateRevision? {
        return manifest?.resume?.let { manifestResume ->
            manifestResume.copy(
                hash = manifestResume.hash ?: syntheticRemoteHash(manifestResume.fileName, manifestResume.updatedAtEpochMs),
            )
        } ?: stateFor(resumeStateFileName(installation))?.let { state ->
            CloudStateRevision(
                fileName = state.fileName,
                kind = CloudStateKind.RESUME,
                slot = null,
                ringIndex = null,
                hash = syntheticRemoteHash(state.fileName, state.updatedAt.toEpochMillis()),
                updatedAtEpochMs = state.updatedAt.toEpochMillis(),
                sourceDeviceId = manifest?.deviceId,
                sourceDeviceName = manifest?.deviceName,
            )
        } ?: stateFor(legacyResumeStateFileName(installation))?.let { state ->
            CloudStateRevision(
                fileName = state.fileName,
                kind = CloudStateKind.RESUME,
                slot = null,
                ringIndex = null,
                hash = syntheticRemoteHash(state.fileName, state.updatedAt.toEpochMillis()),
                updatedAtEpochMs = state.updatedAt.toEpochMillis(),
                sourceDeviceId = manifest?.deviceId,
                sourceDeviceName = manifest?.deviceName,
            )
        }
    }

    private fun RemoteSnapshot.remoteManualSlots(installation: DownloadedRomEntity): List<CloudStateRevision> {
        val fromManifest = manifest?.manualSlots.orEmpty()
            .filter { it.kind == CloudStateKind.MANUAL_SLOT }
            .map { revision ->
                revision.copy(
                    hash = revision.hash ?: syntheticRemoteHash(revision.fileName, revision.updatedAtEpochMs),
                )
            }
        val fallback = statesByName.values.mapNotNull { state ->
            val slot = SLOT_REGEX.find(state.fileName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            if (state.fileName == manifestFileName(installation) || state.fileName == resumeStateFileName(installation)) {
                return@mapNotNull null
            }
            CloudStateRevision(
                fileName = state.fileName,
                kind = CloudStateKind.MANUAL_SLOT,
                slot = slot,
                hash = syntheticRemoteHash(state.fileName, state.updatedAt.toEpochMillis()),
                updatedAtEpochMs = state.updatedAt.toEpochMillis(),
                sourceDeviceId = manifest?.deviceId,
                sourceDeviceName = manifest?.deviceName,
            )
        }
        if (fromManifest.isEmpty()) return fallback.sortedBy { it.slot }

        val mergedBySlot = linkedMapOf<Int, CloudStateRevision>()
        fromManifest.forEach { revision ->
            revision.slot?.let { slot -> mergedBySlot[slot] = revision }
        }
        fallback.forEach { revision ->
            revision.slot?.let { slot ->
                if (slot !in mergedBySlot) {
                    mergedBySlot[slot] = revision
                }
            }
        }
        return mergedBySlot.values.sortedBy { it.slot }
    }

    private fun RemoteSnapshot.remoteRecoveryHistory(installation: DownloadedRomEntity): List<CloudStateRevision> {
        val fromManifest = manifest?.recoveryHistory.orEmpty()
            .filter { it.kind == CloudStateKind.RECOVERY_HISTORY }
            .map { revision ->
                revision.copy(
                    hash = revision.hash ?: syntheticRemoteHash(revision.fileName, revision.updatedAtEpochMs),
                )
            }
        val fallback = statesByName.values.mapNotNull { state ->
            val fileName = state.fileName
            if (!fileName.endsWith(".state")) return@mapNotNull null
            if (fileName == manifestFileName(installation) || fileName == resumeStateFileName(installation)) {
                return@mapNotNull null
            }
            val slot = SLOT_REGEX.find(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (slot != null) {
                return@mapNotNull null
            }
            val ringIndex = AUTO_HISTORY_REGEX.find(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            CloudStateRevision(
                fileName = fileName,
                kind = CloudStateKind.RECOVERY_HISTORY,
                ringIndex = ringIndex,
                hash = syntheticRemoteHash(fileName, state.updatedAt.toEpochMillis()),
                updatedAtEpochMs = state.updatedAt.toEpochMillis(),
                sourceDeviceId = manifest?.deviceId,
                sourceDeviceName = manifest?.deviceName,
                preserved = ringIndex == null,
            )
        }
        if (fromManifest.isEmpty()) return fallback.sortedByDescending { it.updatedAtEpochMs }

        val mergedByEntryId = linkedMapOf<String, CloudStateRevision>()
        fromManifest.forEach { revision ->
            mergedByEntryId[recoveryEntryId(revision)] = revision
        }
        fallback.forEach { revision ->
            val entryId = recoveryEntryId(revision)
            if (entryId !in mergedByEntryId) {
                mergedByEntryId[entryId] = revision
            }
        }
        return mergedByEntryId.values.sortedByDescending { it.updatedAtEpochMs }
    }

    private fun RemoteSnapshot.stateFor(fileName: String): StateDto? = statesByName[fileName]

    private fun File.toSaveRevision(deviceId: String): CloudSaveRevision {
        return CloudSaveRevision(
            fileName = name,
            hash = sha256(),
            updatedAtEpochMs = lastModified(),
            sourceDeviceId = deviceId,
            sourceDeviceName = deviceName,
        )
    }

    private fun File.toStateRevision(
        kind: CloudStateKind,
        slot: Int?,
        deviceId: String,
        ringIndex: Int? = null,
        preserved: Boolean = false,
    ): CloudStateRevision {
        return CloudStateRevision(
            fileName = name,
            kind = kind,
            slot = slot,
            ringIndex = ringIndex,
            hash = sha256(),
            updatedAtEpochMs = lastModified(),
            sourceDeviceId = deviceId,
            sourceDeviceName = deviceName,
            preserved = preserved,
        )
    }

    private fun File.toMultipart(partName: String): MultipartBody.Part {
        return MultipartBody.Part.createFormData(
            partName,
            name,
            asRequestBody("application/octet-stream".toMediaType()),
        )
    }

    private fun GameSyncJournalEntity.toPresentation(): GameSyncPresentation {
        val kind = when {
            lastError != null -> GameSyncStatusKind.ERROR
            remoteContinuityAvailable && pendingContinuityUpload -> GameSyncStatusKind.CONFLICT
            remoteContinuityAvailable -> GameSyncStatusKind.CLOUD_PROGRESS_AVAILABLE
            pendingContinuityUpload -> GameSyncStatusKind.OFFLINE_PENDING
            lastSuccessfulSyncAtEpochMs != null -> GameSyncStatusKind.SYNCED
            else -> GameSyncStatusKind.IDLE
        }
        val message = when (kind) {
            GameSyncStatusKind.ERROR -> lastError ?: "Sync failed."
            GameSyncStatusKind.CLOUD_PROGRESS_AVAILABLE -> remoteDeviceName?.let { "Cloud progress available from $it." }
                ?: "Cloud progress available."
            GameSyncStatusKind.CONFLICT -> "Choose which resume to keep."
            GameSyncStatusKind.OFFLINE_PENDING -> "Offline changes pending."
            GameSyncStatusKind.SYNCED -> "Synced just now."
            GameSyncStatusKind.LOCAL_ONLY -> "Local-only install."
            GameSyncStatusKind.IDLE -> lastSyncNote ?: "Ready to sync."
        }
        return GameSyncPresentation(
            kind = kind,
            message = message,
            lastSuccessfulSyncAtEpochMs = lastSuccessfulSyncAtEpochMs,
            remoteDeviceName = remoteDeviceName,
        )
    }

    private fun String?.toEpochMillis(): Long = runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(0L)

    private fun syntheticRemoteHash(fileName: String, updatedAtEpochMs: Long): String {
        return "remote:$fileName:$updatedAtEpochMs"
    }

    private fun buildSaveContentUrl(baseUrl: String, saveId: Int): String {
        return "${baseUrl.removeSuffix("/")}/api/saves/$saveId/content?optimistic=false"
    }

    private fun buildStateContentUrl(baseUrl: String, downloadPath: String): String {
        return baseUrl.removeSuffix("/") + downloadPath
    }

    private fun manifestFileName(installation: DownloadedRomEntity): String {
        return "__rommio_sync_${installation.romId}_${installation.fileId}.json"
    }

    private fun resumeStateFileName(installation: DownloadedRomEntity): String {
        return "__rommio_resume_${installation.romId}_${installation.fileId}.state"
    }

    private fun legacyResumeStateFileName(installation: DownloadedRomEntity): String {
        return stateFileName(installation, LEGACY_RESUME_SLOT)
    }

    private fun stateFileName(installation: DownloadedRomEntity, slot: Int): String {
        return "${installation.romId}_slot$slot.state"
    }

    private fun autoHistoryFileName(installation: DownloadedRomEntity, ringIndex: Int): String {
        return "${installation.romId}_recovery_auto_$ringIndex.state"
    }

    private fun autoRecoveryEntryId(ringIndex: Int): String = "auto:$ringIndex"

    private fun recoveryEntryId(revision: CloudStateRevision): String {
        return revision.ringIndex?.let(::autoRecoveryEntryId) ?: "legacy:${revision.fileName}"
    }

    private fun recoveryOrigin(revision: CloudStateRevision): RecoveryStateOrigin {
        return if (revision.preserved) RecoveryStateOrigin.LEGACY_IMPORT else RecoveryStateOrigin.AUTO_HISTORY
    }

    private fun recoveryLabel(revision: CloudStateRevision): String {
        return when (recoveryOrigin(revision)) {
            RecoveryStateOrigin.AUTO_HISTORY -> "Auto snapshot"
            RecoveryStateOrigin.LEGACY_IMPORT -> revision.fileName.substringBeforeLast(".").ifBlank { "Imported cloud" }
        }
    }

    private fun recoveryStateFile(
        installation: DownloadedRomEntity,
        revision: CloudStateRevision,
    ): File {
        val fileName = revision.ringIndex?.let { autoHistoryFileName(installation, it) }
            ?: legacyRecoveryLocalFileName(installation, revision.fileName)
        return File(libraryStore.saveStatesDirectory(installation), fileName).apply {
            parentFile?.mkdirs()
        }
    }

    private fun legacyRecoveryLocalFileName(
        installation: DownloadedRomEntity,
        remoteFileName: String,
    ): String {
        val sanitized = remoteFileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${installation.romId}_recovery_import_${sanitized}"
    }

    private suspend fun migrateLegacyResumeStateIfNeeded(profileId: String, installation: DownloadedRomEntity) {
        val hiddenResume = libraryStore.continuityResumeStateFile(installation)
        if (hiddenResume.exists()) return
        val legacyResume = libraryStore.saveStateFile(installation, LEGACY_RESUME_SLOT)
        if (!legacyResume.exists()) return
        val slotJournal = saveStateSyncJournalDao.getByKey(profileId, installation.romId, installation.fileId, LEGACY_RESUME_SLOT)
        if (slotJournal != null) return
        legacyResume.copyTo(hiddenResume, overwrite = true)
        legacyResume.delete()
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val LEGACY_RESUME_SLOT = 0
        private const val AUTO_HISTORY_LIMIT = 10
        private const val AUTO_HISTORY_INTERVAL_MS = 30L * 60L * 1000L
        private val SLOT_REGEX = Regex("_slot(\\d+)\\.state$")
        private val AUTO_HISTORY_REGEX = Regex("_recovery_auto_(\\d+)\\.state$")
    }
}

internal fun shouldCaptureAutoRecovery(
    existingAutoCapturedAt: List<Long>,
    now: Long,
    intervalMs: Long = 30L * 60L * 1000L,
): Boolean {
    val currentBucket = now / intervalMs
    val latestBucket = existingAutoCapturedAt.maxOfOrNull { it / intervalMs }
    return latestBucket != currentBucket
}

internal fun recoveryRingIndex(
    now: Long,
    intervalMs: Long = 30L * 60L * 1000L,
    ringSize: Int = 10,
): Int {
    return ((now / intervalMs) % ringSize).toInt()
}
