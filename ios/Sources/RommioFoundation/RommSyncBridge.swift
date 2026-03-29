import CryptoKit
import Foundation
import RommioContract

private struct RemoteSnapshot {
    let remoteSave: SaveDTO?
    let statesByName: [String: StateDTO]
    let manifest: GameSyncManifest?

    func remoteResumeRevision(for installation: InstalledROMReference) -> CloudStateRevision? {
        if let manifestResume = manifest?.resume {
            return manifestResume
        }
        guard let state = statesByName[resumeStateFileName(for: installation)] else {
            return nil
        }
        return cloudStateRevision(
            fileName: state.fileName,
            kind: .resume,
            updatedAtEpochMS: state.updatedAt.toEpochMS(),
            sourceDeviceID: manifest?.deviceID,
            sourceDeviceName: manifest?.deviceName
        )
    }
}

private struct LocalContinuitySnapshot {
    let sram: CloudSaveRevision?
    let resume: CloudStateRevision?
}

public struct RommSyncBridge: Sendable {
    public let client: RommServicing
    public let libraryStore: LibraryStore
    public let database: AppDatabase
    public let deviceName: String

    public init(
        client: RommServicing,
        libraryStore: LibraryStore,
        database: AppDatabase,
        deviceName: String
    ) {
        self.client = client
        self.libraryStore = libraryStore
        self.database = database
        self.deviceName = deviceName
    }

    public func preparePlayerEntry(
        profileID: String,
        installation: InstalledROMReference,
        rom: RomDTO,
        runtimeID: String,
        remoteBaseURL: URL,
        deviceID: String?,
        connectivity: ConnectivityState
    ) async throws -> PlayerLaunchPreparation {
        try importLocalManualStates(profileID: profileID, installation: installation)

        let journal = try database.gameSyncJournal(
            profileID: profileID,
            romID: installation.romID,
            fileID: installation.fileID
        ) ?? GameSyncJournal(profileID: profileID, romID: installation.romID, fileID: installation.fileID)
        let local = try loadLocalContinuity(installation: installation, deviceID: deviceID)
        var launchTarget = localResumeLaunchTarget(installation: installation)

        guard connectivity == .online else {
            let updatedJournal = updatedOfflineJournal(journal: journal, local: local)
            try database.upsertGameSyncJournal(updatedJournal)
            return PlayerLaunchPreparation(
                launchTarget: launchTarget,
                syncPresentation: updatedJournal.toPresentation(connectivity: connectivity)
            )
        }

        let remote = try await loadRemoteSnapshot(
            romID: rom.id,
            installation: installation,
            remoteBaseURL: remoteBaseURL,
            deviceID: deviceID
        )
        _ = try await refreshManualSlotsFromRemote(
            profileID: profileID,
            installation: installation,
            remote: remote,
            remoteBaseURL: remoteBaseURL
        )
        _ = try await refreshRecoveryStatesFromRemote(
            installation: installation,
            remote: remote,
            remoteBaseURL: remoteBaseURL
        )

        let remoteSRAMHash = remote.remoteSave.map(remoteAssetHash)
        let remoteResumeHash = remote.remoteResumeRevision(for: installation)?.hash
        let remoteUpdatedAt = max(
            remote.remoteSave?.updatedAt.toEpochMS() ?? 0,
            remote.remoteResumeRevision(for: installation)?.updatedAtEpochMS ?? 0
        )
        let localUpdatedAt = max(local.sram?.updatedAtEpochMS ?? 0, local.resume?.updatedAtEpochMS ?? 0)
        let localDirty =
            local.sram?.hash != nil && local.sram?.hash != journal.lastSyncedSRAMHash ||
            local.resume?.hash != nil && local.resume?.hash != journal.lastSyncedResumeHash
        let remoteChanged =
            remoteSRAMHash != nil && remoteSRAMHash != journal.lastSyncedSRAMHash && remoteSRAMHash != local.sram?.hash ||
            remoteResumeHash != nil && remoteResumeHash != journal.lastSyncedResumeHash && remoteResumeHash != local.resume?.hash
        let sameDeviceRemote = remote.manifest?.deviceID != nil && remote.manifest?.deviceID == deviceID
        let now = nowEpochMS()

        if remoteChanged && !localDirty {
            try await applyRemoteContinuity(
                installation: installation,
                remote: remote,
                remoteBaseURL: remoteBaseURL
            )
            let applied = try loadLocalContinuity(installation: installation, deviceID: deviceID)
            launchTarget = localResumeLaunchTarget(installation: installation)
            let updatedJournal = GameSyncJournal(
                profileID: profileID,
                romID: installation.romID,
                fileID: installation.fileID,
                lastSyncedSRAMHash: applied.sram?.hash,
                lastSyncedResumeHash: applied.resume?.hash,
                remoteSRAMHash: remoteSRAMHash,
                remoteResumeHash: remoteResumeHash,
                remoteDeviceID: remote.manifest?.deviceID,
                remoteDeviceName: remote.manifest?.deviceName,
                remoteSessionActive: remote.manifest?.sessionActive == true,
                remoteSessionHeartbeatEpochMS: remote.manifest?.lastHeartbeatEpochMS,
                remoteContinuityUpdatedAtEpochMS: remoteUpdatedAt > 0 ? remoteUpdatedAt : nil,
                remoteContinuityAvailable: false,
                pendingContinuityUpload: false,
                lastSuccessfulSyncAtEpochMS: now,
                lastSyncAttemptAtEpochMS: now,
                lastSyncNote: remote.manifest?.deviceName != nil
                    ? "Cloud progress ready from \(remote.manifest!.deviceName)."
                    : "Cloud progress ready.",
                lastError: nil
            )
            try database.upsertGameSyncJournal(updatedJournal)
            return PlayerLaunchPreparation(
                launchTarget: launchTarget,
                syncPresentation: updatedJournal.toPresentation(connectivity: connectivity)
            )
        }

        let updatedJournal: GameSyncJournal
        let resumeConflict: ResumeConflict?
        if remoteChanged && localDirty && !sameDeviceRemote {
            updatedJournal = GameSyncJournal(
                profileID: profileID,
                romID: installation.romID,
                fileID: installation.fileID,
                lastSyncedSRAMHash: journal.lastSyncedSRAMHash,
                lastSyncedResumeHash: journal.lastSyncedResumeHash,
                remoteSRAMHash: remoteSRAMHash,
                remoteResumeHash: remoteResumeHash,
                remoteDeviceID: remote.manifest?.deviceID,
                remoteDeviceName: remote.manifest?.deviceName,
                remoteSessionActive: remote.manifest?.sessionActive == true,
                remoteSessionHeartbeatEpochMS: remote.manifest?.lastHeartbeatEpochMS,
                remoteContinuityUpdatedAtEpochMS: remoteUpdatedAt > 0 ? remoteUpdatedAt : nil,
                remoteContinuityAvailable: true,
                pendingContinuityUpload: true,
                lastSuccessfulSyncAtEpochMS: journal.lastSuccessfulSyncAtEpochMS,
                lastSyncAttemptAtEpochMS: now,
                lastSyncNote: nil,
                lastError: nil
            )
            resumeConflict = ResumeConflict(
                remoteDeviceName: remote.manifest?.deviceName,
                remoteUpdatedAtEpochMS: remoteUpdatedAt,
                localUpdatedAtEpochMS: localUpdatedAt
            )
        } else {
            updatedJournal = GameSyncJournal(
                profileID: profileID,
                romID: installation.romID,
                fileID: installation.fileID,
                lastSyncedSRAMHash: journal.lastSyncedSRAMHash,
                lastSyncedResumeHash: journal.lastSyncedResumeHash,
                remoteSRAMHash: remoteSRAMHash,
                remoteResumeHash: remoteResumeHash,
                remoteDeviceID: remote.manifest?.deviceID,
                remoteDeviceName: remote.manifest?.deviceName,
                remoteSessionActive: remote.manifest?.sessionActive == true,
                remoteSessionHeartbeatEpochMS: remote.manifest?.lastHeartbeatEpochMS,
                remoteContinuityUpdatedAtEpochMS: remoteUpdatedAt > 0 ? remoteUpdatedAt : nil,
                remoteContinuityAvailable: remoteChanged,
                pendingContinuityUpload: localDirty,
                lastSuccessfulSyncAtEpochMS: journal.lastSuccessfulSyncAtEpochMS,
                lastSyncAttemptAtEpochMS: now,
                lastSyncNote: remoteChanged
                    ? (remote.manifest?.deviceName != nil
                        ? "Cloud progress available from \(remote.manifest!.deviceName)."
                        : "Cloud progress available.")
                    : (localDirty ? "Local progress pending upload." : journal.lastSyncNote),
                lastError: nil
            )
            resumeConflict = nil
        }
        try database.upsertGameSyncJournal(updatedJournal)
        return PlayerLaunchPreparation(
            launchTarget: launchTarget,
            resumeConflict: resumeConflict,
            syncPresentation: updatedJournal.toPresentation(connectivity: connectivity)
        )
    }

    public func cachedPlayerLaunch(
        profileID: String?,
        installation: InstalledROMReference,
        connectivity: ConnectivityState
    ) throws -> PlayerLaunchPreparation {
        if let profileID {
            try importLocalManualStates(profileID: profileID, installation: installation)
        }
        let journal = try profileID.flatMap {
            try database.gameSyncJournal(profileID: $0, romID: installation.romID, fileID: installation.fileID)
        }
        let launchTarget = localResumeLaunchTarget(installation: installation)
        return PlayerLaunchPreparation(
            launchTarget: launchTarget,
            syncPresentation: journal.toPresentation(connectivity: connectivity)
        )
    }

    public func buildStateBrowser(
        profileID: String?,
        installation: InstalledROMReference,
        connectivity: ConnectivityState
    ) throws -> GameStateBrowser {
        if let profileID {
            try importLocalManualStates(profileID: profileID, installation: installation)
        }
        let journal = try profileID.flatMap {
            try database.gameSyncJournal(profileID: $0, romID: installation.romID, fileID: installation.fileID)
        }
        let manualJournals = try profileID.map {
            try database.saveStateSyncJournals(profileID: $0, romID: installation.romID, fileID: installation.fileID)
        } ?? []
        let recovery = try database.recoveryStates(romID: installation.romID, fileID: installation.fileID)
        let resumeURL = libraryStore.continuityResumeStateURL(for: installation)
        let local = try loadLocalContinuity(installation: installation, deviceID: profileID)

        let saveSlots = manualJournals
            .filter { !$0.deleted }
            .compactMap { entry -> BrowsableGameState? in
                guard let localPath = entry.localPath, FileManager.default.fileExists(atPath: localPath) else {
                    return nil
                }
                return BrowsableGameState(
                    id: "manual:\(entry.slot)",
                    kind: .manualSlot,
                    label: "Save slot \(entry.slot)",
                    localPath: localPath,
                    updatedAtEpochMS: entry.localUpdatedAtEpochMS ?? entry.remoteUpdatedAtEpochMS ?? 0,
                    slot: entry.slot,
                    preserved: false,
                    sourceDeviceID: entry.sourceDeviceID,
                    sourceDeviceName: entry.sourceDeviceName,
                    originKind: .manualSlot,
                    deletePolicy: .localAndRemoteWhenSupported
                )
            }
            .sorted { left, right in
                if let leftSlot = left.slot, let rightSlot = right.slot {
                    return leftSlot < rightSlot
                }
                return left.updatedAtEpochMS > right.updatedAtEpochMS
            }

        let snapshots = recovery
            .filter { $0.origin == .autoHistory && FileManager.default.fileExists(atPath: $0.localPath) }
            .map(\.asBrowsableState)
            .sorted { $0.updatedAtEpochMS > $1.updatedAtEpochMS }

        let importedPlayables = recovery
            .filter { $0.origin == .legacyImport && FileManager.default.fileExists(atPath: $0.localPath) }
            .map(\.asBrowsableState)
            .sorted { $0.updatedAtEpochMS > $1.updatedAtEpochMS }

        return GameStateBrowser(
            resume: buildResumeSummary(
                journal: journal,
                local: local,
                resumeURL: resumeURL,
                connectivity: connectivity,
                hasActiveProfile: profileID != nil
            ),
            saveSlots: saveSlots + importedPlayables,
            snapshots: snapshots
        )
    }

    public func refreshStateBrowser(
        profileID: String,
        installation: InstalledROMReference,
        rom: RomDTO,
        runtimeID: String,
        remoteBaseURL: URL,
        deviceID: String?,
        connectivity: ConnectivityState
    ) async throws -> GameStateBrowser {
        try importLocalManualStates(profileID: profileID, installation: installation)
        if connectivity == .online {
            let remote = try await loadRemoteSnapshot(
                romID: rom.id,
                installation: installation,
                remoteBaseURL: remoteBaseURL,
                deviceID: deviceID
            )
            _ = try await refreshManualSlotsFromRemote(
                profileID: profileID,
                installation: installation,
                remote: remote,
                remoteBaseURL: remoteBaseURL
            )
            _ = try await refreshRecoveryStatesFromRemote(
                installation: installation,
                remote: remote,
                remoteBaseURL: remoteBaseURL
            )
        }
        return try buildStateBrowser(profileID: profileID, installation: installation, connectivity: connectivity)
    }

    public func syncGame(
        profileID: String,
        installation: InstalledROMReference,
        rom: RomDTO,
        runtimeID: String,
        remoteBaseURL: URL,
        deviceID: String?,
        includeManualSlots: Bool = true,
        sessionActive: Bool = false,
        sessionStartedAtEpochMS: Int64? = nil
    ) async throws -> SyncSummary {
        try importLocalManualStates(profileID: profileID, installation: installation)

        let now = nowEpochMS()
        var uploaded = 0
        var downloaded = 0
        var notes: [String] = []

        let remote = try await loadRemoteSnapshot(
            romID: rom.id,
            installation: installation,
            remoteBaseURL: remoteBaseURL,
            deviceID: deviceID
        )
        downloaded += try await refreshManualSlotsFromRemote(
            profileID: profileID,
            installation: installation,
            remote: remote,
            remoteBaseURL: remoteBaseURL
        )
        downloaded += try await refreshRecoveryStatesFromRemote(
            installation: installation,
            remote: remote,
            remoteBaseURL: remoteBaseURL
        )

        var journal = try database.gameSyncJournal(
            profileID: profileID,
            romID: installation.romID,
            fileID: installation.fileID
        ) ?? GameSyncJournal(profileID: profileID, romID: installation.romID, fileID: installation.fileID)
        let local = try loadLocalContinuity(installation: installation, deviceID: deviceID)

        if let sram = local.sram, sram.hash != journal.lastSyncedSRAMHash {
            _ = try await client.uploadSave(
                romID: rom.id,
                emulator: runtimeID,
                slot: nil,
                deviceID: nil,
                overwrite: true,
                fileURL: libraryStore.saveRAMURL(for: installation)
            )
            uploaded += 1
            journal.lastSyncedSRAMHash = sram.hash
        }

        if let resume = local.resume, resume.hash != journal.lastSyncedResumeHash {
            _ = try await client.uploadState(
                romID: rom.id,
                emulator: runtimeID,
                fileURL: libraryStore.continuityResumeStateURL(for: installation)
            )
            uploaded += 1
            journal.lastSyncedResumeHash = resume.hash
        }

        if let snapshotUpload = try captureAutoSnapshotIfNeeded(
            installation: installation,
            deviceID: deviceID
        ) {
            _ = try await client.uploadState(
                romID: rom.id,
                emulator: runtimeID,
                fileURL: URL(fileURLWithPath: snapshotUpload.localPath)
            )
            uploaded += 1
            notes.append("Captured snapshot.")
        }

        if includeManualSlots {
            let manualUploads = try await syncManualSlots(
                profileID: profileID,
                installation: installation,
                rom: rom,
                runtimeID: runtimeID,
                now: now
            )
            uploaded += manualUploads.uploaded
        }

        let manualRevisions = try database.saveStateSyncJournals(profileID: profileID, romID: installation.romID, fileID: installation.fileID)
            .map { entry in
                CloudStateRevision(
                    fileName: libraryStore.saveStateURL(for: installation, slot: entry.slot).lastPathComponent,
                    kind: .manualSlot,
                    slot: entry.slot,
                    hash: entry.deleted ? nil : (entry.localHash ?? entry.remoteHash),
                    updatedAtEpochMS: entry.localUpdatedAtEpochMS ?? entry.remoteUpdatedAtEpochMS ?? now,
                    sourceDeviceID: entry.sourceDeviceID ?? deviceID,
                    sourceDeviceName: entry.sourceDeviceName ?? deviceName,
                    preserved: false,
                    deleted: entry.deleted
                )
            }

        let recoveryRevisions = try database.recoveryStates(romID: installation.romID, fileID: installation.fileID)
            .filter { $0.origin == .autoHistory }
            .map { record in
                CloudStateRevision(
                    fileName: record.remoteFileName,
                    kind: .recoveryHistory,
                    ringIndex: record.ringIndex,
                    hash: record.localHash ?? record.remoteHash,
                    updatedAtEpochMS: record.capturedAtEpochMS,
                    sourceDeviceID: record.sourceDeviceID ?? deviceID,
                    sourceDeviceName: record.sourceDeviceName ?? deviceName,
                    preserved: false,
                    deleted: false
                )
            }
            .sorted { $0.updatedAtEpochMS > $1.updatedAtEpochMS }

        let manifest = GameSyncManifest(
            romID: installation.romID,
            fileID: installation.fileID,
            deviceID: deviceID ?? "unknown-device",
            deviceName: deviceName,
            sessionActive: sessionActive,
            sessionStartedAtEpochMS: sessionActive ? (sessionStartedAtEpochMS ?? remote.manifest?.sessionStartedAtEpochMS ?? now) : nil,
            lastHeartbeatEpochMS: now,
            sram: local.sram,
            resume: local.resume,
            manualSlots: manualRevisions,
            recoveryHistory: recoveryRevisions
        )
        try await uploadManifest(
            manifest,
            romID: rom.id,
            runtimeID: runtimeID
        )
        uploaded += 1

        journal.remoteSRAMHash = local.sram?.hash
        journal.remoteResumeHash = local.resume?.hash
        journal.remoteDeviceID = deviceID
        journal.remoteDeviceName = deviceName
        journal.remoteSessionActive = sessionActive
        journal.remoteSessionHeartbeatEpochMS = now
        journal.remoteContinuityUpdatedAtEpochMS = max(local.sram?.updatedAtEpochMS ?? 0, local.resume?.updatedAtEpochMS ?? 0)
        journal.remoteContinuityAvailable = false
        journal.pendingContinuityUpload = false
        journal.lastSuccessfulSyncAtEpochMS = now
        journal.lastSyncAttemptAtEpochMS = now
        journal.lastSyncNote = "Synced just now."
        journal.lastError = nil
        try database.upsertGameSyncJournal(journal)

        return SyncSummary(uploaded: uploaded, downloaded: downloaded, notes: notes)
    }

    public func recordManualState(
        profileID: String,
        installation: InstalledROMReference,
        slot: Int,
        fileURL: URL,
        deviceID: String?
    ) throws {
        try importLocalManualStates(profileID: profileID, installation: installation)
        let journal = try database.saveStateSyncJournal(
            profileID: profileID,
            romID: installation.romID,
            fileID: installation.fileID,
            slot: slot
        )
        let updated = SaveStateSyncJournal(
            profileID: profileID,
            romID: installation.romID,
            fileID: installation.fileID,
            slot: slot,
            label: "Save slot \(slot)",
            localPath: fileURL.path,
            localHash: try fileURL.sha256(),
            localUpdatedAtEpochMS: try fileURL.modifiedAtEpochMS(),
            remoteHash: journal?.remoteHash,
            remoteUpdatedAtEpochMS: journal?.remoteUpdatedAtEpochMS,
            sourceDeviceID: deviceID,
            sourceDeviceName: deviceName,
            deleted: false,
            pendingUpload: true,
            pendingDelete: false,
            lastSyncedAtEpochMS: journal?.lastSyncedAtEpochMS
        )
        try database.upsertSaveStateSyncJournal(updated)
    }

    public func deleteBrowsableState(
        profileID: String,
        installation: InstalledROMReference,
        state: BrowsableGameState
    ) throws {
        switch state.deletePolicy {
        case .none:
            return
        case .localOnly:
            try? FileManager.default.removeItem(atPath: state.localPath)
            try database.deleteRecoveryState(romID: installation.romID, fileID: installation.fileID, entryID: state.id)
        case .localAndRemoteWhenSupported:
            guard let slot = state.slot else { return }
            let existing = try database.saveStateSyncJournal(
                profileID: profileID,
                romID: installation.romID,
                fileID: installation.fileID,
                slot: slot
            )
            let manualURL = libraryStore.saveStateURL(for: installation, slot: slot)
            try? FileManager.default.removeItem(at: manualURL)
            let tombstone = SaveStateSyncJournal(
                profileID: profileID,
                romID: installation.romID,
                fileID: installation.fileID,
                slot: slot,
                label: "Save slot \(slot)",
                localPath: nil,
                localHash: nil,
                localUpdatedAtEpochMS: nowEpochMS(),
                remoteHash: existing?.remoteHash,
                remoteUpdatedAtEpochMS: existing?.remoteUpdatedAtEpochMS,
                sourceDeviceID: existing?.sourceDeviceID,
                sourceDeviceName: existing?.sourceDeviceName,
                deleted: true,
                pendingUpload: false,
                pendingDelete: true,
                lastSyncedAtEpochMS: existing?.lastSyncedAtEpochMS
            )
            try database.upsertSaveStateSyncJournal(tombstone)
        }
    }

    private func syncManualSlots(
        profileID: String,
        installation: InstalledROMReference,
        rom: RomDTO,
        runtimeID: String,
        now: Int64
    ) async throws -> (uploaded: Int, notes: [String]) {
        var uploaded = 0
        let journals = try database.saveStateSyncJournals(profileID: profileID, romID: installation.romID, fileID: installation.fileID)
        for entry in journals {
            if entry.deleted || entry.pendingDelete {
                let updated = SaveStateSyncJournal(
                    profileID: entry.profileID,
                    romID: entry.romID,
                    fileID: entry.fileID,
                    slot: entry.slot,
                    label: entry.label,
                    localPath: nil,
                    localHash: nil,
                    localUpdatedAtEpochMS: now,
                    remoteHash: entry.remoteHash,
                    remoteUpdatedAtEpochMS: now,
                    sourceDeviceID: entry.sourceDeviceID,
                    sourceDeviceName: entry.sourceDeviceName,
                    deleted: true,
                    pendingUpload: false,
                    pendingDelete: false,
                    lastSyncedAtEpochMS: now
                )
                try database.upsertSaveStateSyncJournal(updated)
                continue
            }

            guard
                let localPath = entry.localPath,
                FileManager.default.fileExists(atPath: localPath)
            else {
                continue
            }
            let localURL = URL(fileURLWithPath: localPath)
            let localHash = try localURL.sha256()
            let localUpdatedAt = try localURL.modifiedAtEpochMS()
            let remoteUpdatedAt = entry.remoteUpdatedAtEpochMS ?? 0
            let shouldUpload = entry.pendingUpload || entry.remoteUpdatedAtEpochMS == nil || localUpdatedAt > remoteUpdatedAt
            if shouldUpload {
                _ = try await client.uploadState(
                    romID: rom.id,
                    emulator: runtimeID,
                    fileURL: localURL
                )
                uploaded += 1
            }
            let updated = SaveStateSyncJournal(
                profileID: entry.profileID,
                romID: entry.romID,
                fileID: entry.fileID,
                slot: entry.slot,
                label: entry.label,
                localPath: localPath,
                localHash: localHash,
                localUpdatedAtEpochMS: localUpdatedAt,
                remoteHash: shouldUpload ? localHash : entry.remoteHash,
                remoteUpdatedAtEpochMS: shouldUpload ? localUpdatedAt : entry.remoteUpdatedAtEpochMS,
                sourceDeviceID: entry.sourceDeviceID,
                sourceDeviceName: entry.sourceDeviceName ?? deviceName,
                deleted: false,
                pendingUpload: false,
                pendingDelete: false,
                lastSyncedAtEpochMS: shouldUpload ? now : entry.lastSyncedAtEpochMS
            )
            try database.upsertSaveStateSyncJournal(updated)
        }
        return (uploaded, [])
    }

    private func refreshManualSlotsFromRemote(
        profileID: String,
        installation: InstalledROMReference,
        remote: RemoteSnapshot,
        remoteBaseURL: URL
    ) async throws -> Int {
        let existing = try database.saveStateSyncJournals(profileID: profileID, romID: installation.romID, fileID: installation.fileID)
        let existingBySlot = Dictionary(uniqueKeysWithValues: existing.map { ($0.slot, $0) })
        let manifestRevisions = Dictionary(uniqueKeysWithValues: (remote.manifest?.manualSlots ?? []).compactMap { revision -> (Int, CloudStateRevision)? in
            guard let slot = revision.slot else { return nil }
            return (slot, revision)
        })
        var downloaded = 0

        var merged: [Int: CloudStateRevision] = manifestRevisions
        for state in remote.statesByName.values {
            guard let slot = parseSlot(from: state.fileName, installation: installation) else { continue }
            if merged[slot] != nil { continue }
            merged[slot] = cloudStateRevision(
                fileName: state.fileName,
                kind: .manualSlot,
                slot: slot,
                updatedAtEpochMS: state.updatedAt.toEpochMS(),
                sourceDeviceID: remote.manifest?.deviceID,
                sourceDeviceName: remote.manifest?.deviceName
            )
        }

        for (slot, revision) in merged.sorted(by: { $0.key < $1.key }) {
            let target = libraryStore.saveStateURL(for: installation, slot: slot)
            let prior = existingBySlot[slot]

            if revision.deleted {
                try? FileManager.default.removeItem(at: target)
                let tombstone = SaveStateSyncJournal(
                    profileID: profileID,
                    romID: installation.romID,
                    fileID: installation.fileID,
                    slot: slot,
                    label: "Save slot \(slot)",
                    localPath: nil,
                    localHash: nil,
                    localUpdatedAtEpochMS: revision.updatedAtEpochMS,
                    remoteHash: revision.hash,
                    remoteUpdatedAtEpochMS: revision.updatedAtEpochMS,
                    sourceDeviceID: revision.sourceDeviceID,
                    sourceDeviceName: revision.sourceDeviceName,
                    deleted: true,
                    pendingUpload: false,
                    pendingDelete: false,
                    lastSyncedAtEpochMS: nowEpochMS()
                )
                try database.upsertSaveStateSyncJournal(tombstone)
                continue
            }

            let shouldDownload: Bool
            if FileManager.default.fileExists(atPath: target.path) {
                if prior == nil {
                    // On first discovery, preserve an existing local slot instead of clobbering it.
                    shouldDownload = false
                } else {
                    shouldDownload = revision.updatedAtEpochMS > ((try? target.modifiedAtEpochMS()) ?? 0)
                }
            } else {
                shouldDownload = true
            }

            if shouldDownload, let state = remote.statesByName[revision.fileName] {
                try await downloadRemoteState(
                    state,
                    remoteBaseURL: remoteBaseURL,
                    target: target
                )
                downloaded += 1
            }

            guard FileManager.default.fileExists(atPath: target.path) else { continue }
            let localHash = try target.sha256()
            let localUpdatedAtEpochMS = try target.modifiedAtEpochMS()
            let pendingUpload = (prior == nil && !shouldDownload) || localUpdatedAtEpochMS > revision.updatedAtEpochMS
            let updated = SaveStateSyncJournal(
                profileID: profileID,
                romID: installation.romID,
                fileID: installation.fileID,
                slot: slot,
                label: "Save slot \(slot)",
                localPath: target.path,
                localHash: localHash,
                localUpdatedAtEpochMS: localUpdatedAtEpochMS,
                remoteHash: revision.hash,
                remoteUpdatedAtEpochMS: revision.updatedAtEpochMS,
                sourceDeviceID: pendingUpload ? (prior?.sourceDeviceID) : (revision.sourceDeviceID ?? prior?.sourceDeviceID),
                sourceDeviceName: pendingUpload
                    ? (prior?.sourceDeviceName ?? deviceName)
                    : (revision.sourceDeviceName ?? prior?.sourceDeviceName),
                deleted: false,
                pendingUpload: pendingUpload,
                pendingDelete: false,
                lastSyncedAtEpochMS: nowEpochMS()
            )
            try database.upsertSaveStateSyncJournal(updated)
        }
        return downloaded
    }

    private func refreshRecoveryStatesFromRemote(
        installation: InstalledROMReference,
        remote: RemoteSnapshot,
        remoteBaseURL: URL
    ) async throws -> Int {
        let existing = try database.recoveryStates(romID: installation.romID, fileID: installation.fileID)
        let existingByID = Dictionary(uniqueKeysWithValues: existing.map { ($0.entryID, $0) })
        var downloaded = 0

        let manualFileNames = Set((remote.manifest?.manualSlots ?? []).map(\.fileName))
        let recoveryFileNames = Set((remote.manifest?.recoveryHistory ?? []).map(\.fileName))
        let autoRevisions: [CloudStateRevision]
        if let manifestHistory = remote.manifest?.recoveryHistory, !manifestHistory.isEmpty {
            autoRevisions = manifestHistory
        } else {
            autoRevisions = remote.statesByName.values.compactMap { state in
                guard let ringIndex = parseAutoRingIndex(from: state.fileName, installation: installation) else {
                    return nil
                }
                return cloudStateRevision(
                    fileName: state.fileName,
                    kind: .recoveryHistory,
                    ringIndex: ringIndex,
                    updatedAtEpochMS: state.updatedAt.toEpochMS(),
                    sourceDeviceID: remote.manifest?.deviceID,
                    sourceDeviceName: remote.manifest?.deviceName
                )
            }
        }

        var observedAutoIDs = Set<String>()
        for revision in autoRevisions {
            guard let ringIndex = revision.ringIndex else { continue }
            let entryID = autoRecoveryEntryID(ringIndex: ringIndex)
            observedAutoIDs.insert(entryID)
            let target = libraryStore.autoSnapshotURL(for: installation, ringIndex: ringIndex)
            let shouldDownload: Bool
            if FileManager.default.fileExists(atPath: target.path) {
                shouldDownload = revision.updatedAtEpochMS > ((try? target.modifiedAtEpochMS()) ?? 0)
            } else {
                shouldDownload = true
            }
            if shouldDownload, let state = remote.statesByName[revision.fileName] {
                try await downloadRemoteState(state, remoteBaseURL: remoteBaseURL, target: target)
                downloaded += 1
            }
            guard FileManager.default.fileExists(atPath: target.path) else { continue }
            let record = RecoveryStateRecord(
                romID: installation.romID,
                fileID: installation.fileID,
                entryID: entryID,
                label: "Snapshot",
                origin: .autoHistory,
                localPath: target.path,
                remoteFileName: revision.fileName,
                localHash: try target.sha256(),
                remoteHash: revision.hash,
                ringIndex: ringIndex,
                preserved: false,
                sourceDeviceID: revision.sourceDeviceID,
                sourceDeviceName: revision.sourceDeviceName,
                capturedAtEpochMS: revision.updatedAtEpochMS,
                lastSyncedAtEpochMS: nowEpochMS()
            )
            try database.upsertRecoveryState(record)
        }

        for record in existing where record.origin == .autoHistory && !observedAutoIDs.contains(record.entryID) {
            try? FileManager.default.removeItem(atPath: record.localPath)
            try database.deleteRecoveryState(romID: record.romID, fileID: record.fileID, entryID: record.entryID)
        }

        let claimedNames = manualFileNames
            .union(recoveryFileNames)
            .union([resumeStateFileName(for: installation), manifestFileName(for: installation)])

        for state in remote.statesByName.values {
            guard state.fileName.hasSuffix(".state") else { continue }
            if claimedNames.contains(state.fileName) { continue }
            if parseSlot(from: state.fileName, installation: installation) != nil { continue }
            if parseAutoRingIndex(from: state.fileName, installation: installation) != nil { continue }

            let entryID = "legacy:\(state.fileName)"
            let target = libraryStore.saveStatesDirectory(for: installation).appending(path: state.fileName)
            let shouldDownload: Bool
            if FileManager.default.fileExists(atPath: target.path) {
                shouldDownload = state.updatedAt.toEpochMS() > ((try? target.modifiedAtEpochMS()) ?? 0)
            } else {
                shouldDownload = true
            }
            if shouldDownload {
                try await downloadRemoteState(state, remoteBaseURL: remoteBaseURL, target: target)
                downloaded += 1
            }
            guard FileManager.default.fileExists(atPath: target.path) else { continue }
            let label = state.fileName.replacingOccurrences(of: ".state", with: "")
            let prior = existingByID[entryID]
            let record = RecoveryStateRecord(
                romID: installation.romID,
                fileID: installation.fileID,
                entryID: entryID,
                label: label,
                origin: .legacyImport,
                localPath: target.path,
                remoteFileName: state.fileName,
                localHash: try target.sha256(),
                remoteHash: remoteAssetHash(state),
                ringIndex: nil,
                preserved: true,
                sourceDeviceID: prior?.sourceDeviceID,
                sourceDeviceName: prior?.sourceDeviceName,
                capturedAtEpochMS: state.updatedAt.toEpochMS(),
                lastSyncedAtEpochMS: nowEpochMS()
            )
            try database.upsertRecoveryState(record)
        }
        return downloaded
    }

    private func loadRemoteSnapshot(
        romID: Int,
        installation: InstalledROMReference,
        remoteBaseURL: URL,
        deviceID: String?
    ) async throws -> RemoteSnapshot {
        let remoteSave = try await client.listSaves(romID: romID, deviceID: deviceID)
            .first { $0.fileName == libraryStore.saveRAMURL(for: installation).lastPathComponent }
        let states = try await client.listStates(romID: romID)
        let statesByName = Dictionary(uniqueKeysWithValues: states.map { ($0.fileName, $0) })
        let manifest: GameSyncManifest?
        if let manifestState = statesByName[manifestFileName(for: installation)] {
            let tempURL = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathComponent(manifestState.fileName)
            try await downloadRemoteState(manifestState, remoteBaseURL: remoteBaseURL, target: tempURL)
            defer { try? FileManager.default.removeItem(at: tempURL) }
            manifest = try JSONDecoder().decode(GameSyncManifest.self, from: Data(contentsOf: tempURL))
        } else {
            manifest = nil
        }
        return RemoteSnapshot(remoteSave: remoteSave, statesByName: statesByName, manifest: manifest)
    }

    private func applyRemoteContinuity(
        installation: InstalledROMReference,
        remote: RemoteSnapshot,
        remoteBaseURL: URL
    ) async throws {
        if let save = remote.remoteSave {
            try await downloadRemoteSave(save, remoteBaseURL: remoteBaseURL, target: libraryStore.saveRAMURL(for: installation))
        }
        if let resume = remote.remoteResumeRevision(for: installation),
           let state = remote.statesByName[resume.fileName] {
            try await downloadRemoteState(
                state,
                remoteBaseURL: remoteBaseURL,
                target: libraryStore.continuityResumeStateURL(for: installation)
            )
        }
    }

    private func uploadManifest(
        _ manifest: GameSyncManifest,
        romID: Int,
        runtimeID: String
    ) async throws {
        let tempDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)
        let fileURL = tempDirectory.appendingPathComponent(manifestFileName(romID: manifest.romID, fileID: manifest.fileID))
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(manifest).write(to: fileURL, options: .atomic)
        defer { try? FileManager.default.removeItem(at: tempDirectory) }
        _ = try await client.uploadState(
            romID: romID,
            emulator: runtimeID,
            fileURL: fileURL
        )
    }

    private func captureAutoSnapshotIfNeeded(
        installation: InstalledROMReference,
        deviceID: String?
    ) throws -> RecoveryStateRecord? {
        let resumeURL = libraryStore.continuityResumeStateURL(for: installation)
        guard FileManager.default.fileExists(atPath: resumeURL.path) else {
            return nil
        }
        let existing = try database.recoveryStates(romID: installation.romID, fileID: installation.fileID)
            .filter { $0.origin == .autoHistory }
        let now = nowEpochMS()
        if let lastSnapshot = existing.max(by: { $0.capturedAtEpochMS < $1.capturedAtEpochMS }),
           now - lastSnapshot.capturedAtEpochMS < autoHistoryIntervalMS {
            return nil
        }
        let ringIndex = recoveryRingIndex(now: now)
        let target = libraryStore.autoSnapshotURL(for: installation, ringIndex: ringIndex)
        try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        if FileManager.default.fileExists(atPath: target.path) {
            try? FileManager.default.removeItem(at: target)
        }
        try FileManager.default.copyItem(at: resumeURL, to: target)
        let record = RecoveryStateRecord(
            romID: installation.romID,
            fileID: installation.fileID,
            entryID: autoRecoveryEntryID(ringIndex: ringIndex),
            label: "Snapshot",
            origin: .autoHistory,
            localPath: target.path,
            remoteFileName: autoSnapshotFileName(for: installation, ringIndex: ringIndex),
            localHash: try target.sha256(),
            ringIndex: ringIndex,
            preserved: false,
            sourceDeviceID: deviceID,
            sourceDeviceName: deviceName,
            capturedAtEpochMS: now,
            lastSyncedAtEpochMS: nil
        )
        try database.upsertRecoveryState(record)
        return record
    }

    private func loadLocalContinuity(
        installation: InstalledROMReference,
        deviceID: String?
    ) throws -> LocalContinuitySnapshot {
        let sramURL = libraryStore.saveRAMURL(for: installation)
        let resumeURL = libraryStore.continuityResumeStateURL(for: installation)
        return LocalContinuitySnapshot(
            sram: FileManager.default.fileExists(atPath: sramURL.path)
                ? CloudSaveRevision(
                    fileName: sramURL.lastPathComponent,
                    hash: try sramURL.sha256(),
                    updatedAtEpochMS: try sramURL.modifiedAtEpochMS(),
                    sourceDeviceID: deviceID,
                    sourceDeviceName: deviceName
                )
                : nil,
            resume: FileManager.default.fileExists(atPath: resumeURL.path)
                ? CloudStateRevision(
                    fileName: resumeURL.lastPathComponent,
                    kind: .resume,
                    hash: try resumeURL.sha256(),
                    updatedAtEpochMS: try resumeURL.modifiedAtEpochMS(),
                    sourceDeviceID: deviceID,
                    sourceDeviceName: deviceName
                )
                : nil
        )
    }

    private func localResumeLaunchTarget(installation: InstalledROMReference) -> PlayerLaunchTarget? {
        let resumeURL = libraryStore.continuityResumeStateURL(for: installation)
        guard FileManager.default.fileExists(atPath: resumeURL.path) else {
            return nil
        }
        return PlayerLaunchTarget(
            kind: .continuity,
            localStatePath: resumeURL.path,
            stateID: "resume",
            label: "Resume"
        )
    }

    private func updatedOfflineJournal(
        journal: GameSyncJournal,
        local: LocalContinuitySnapshot
    ) -> GameSyncJournal {
        var updated = journal
        let localDirty =
            local.sram?.hash != nil && local.sram?.hash != journal.lastSyncedSRAMHash ||
            local.resume?.hash != nil && local.resume?.hash != journal.lastSyncedResumeHash
        updated.pendingContinuityUpload = localDirty || journal.pendingContinuityUpload
        updated.lastSyncAttemptAtEpochMS = nowEpochMS()
        if localDirty {
            updated.lastSyncNote = "Waiting to sync."
        }
        return updated
    }

    private func importLocalManualStates(
        profileID: String,
        installation: InstalledROMReference
    ) throws {
        let stateDirectory = libraryStore.saveStatesDirectory(for: installation)
        try FileManager.default.createDirectory(at: stateDirectory, withIntermediateDirectories: true)
        let urls = try FileManager.default.contentsOfDirectory(at: stateDirectory, includingPropertiesForKeys: [.contentModificationDateKey])
        for fileURL in urls where !fileURL.hasDirectoryPath {
            guard let slot = parseSlot(from: fileURL.lastPathComponent, installation: installation) else { continue }
            let canonicalURL = libraryStore.saveStateURL(for: installation, slot: slot)
            if fileURL.standardizedFileURL.path != canonicalURL.standardizedFileURL.path {
                if !FileManager.default.fileExists(atPath: canonicalURL.path) {
                    try? FileManager.default.moveItem(at: fileURL, to: canonicalURL)
                } else {
                    try? FileManager.default.removeItem(at: fileURL)
                }
            }
            guard FileManager.default.fileExists(atPath: canonicalURL.path) else { continue }
            let existing = try database.saveStateSyncJournal(
                profileID: profileID,
                romID: installation.romID,
                fileID: installation.fileID,
                slot: slot
            )
            let journal = SaveStateSyncJournal(
                profileID: profileID,
                romID: installation.romID,
                fileID: installation.fileID,
                slot: slot,
                label: "Save slot \(slot)",
                localPath: canonicalURL.path,
                localHash: try canonicalURL.sha256(),
                localUpdatedAtEpochMS: try canonicalURL.modifiedAtEpochMS(),
                remoteHash: existing?.remoteHash,
                remoteUpdatedAtEpochMS: existing?.remoteUpdatedAtEpochMS,
                sourceDeviceID: existing?.sourceDeviceID,
                sourceDeviceName: existing?.sourceDeviceName ?? deviceName,
                deleted: false,
                pendingUpload: existing == nil,
                pendingDelete: false,
                lastSyncedAtEpochMS: existing?.lastSyncedAtEpochMS
            )
            try database.upsertSaveStateSyncJournal(journal)
        }
    }

    private func buildResumeSummary(
        journal: GameSyncJournal?,
        local: LocalContinuitySnapshot,
        resumeURL: URL,
        connectivity: ConnectivityState,
        hasActiveProfile: Bool
    ) -> ResumeStateSummary {
        let available = FileManager.default.fileExists(atPath: resumeURL.path)
        guard hasActiveProfile else {
            return ResumeStateSummary(
                available: available,
                localPath: available ? resumeURL.path : nil,
                updatedAtEpochMS: available ? (try? resumeURL.modifiedAtEpochMS()) : nil,
                statusKind: available ? .localOnly : .unavailable,
                primaryStatusMessage: available ? "Local-only install." : "No resume state yet."
            )
        }
        guard let journal else {
            return ResumeStateSummary(
                available: available,
                localPath: available ? resumeURL.path : nil,
                updatedAtEpochMS: available ? (try? resumeURL.modifiedAtEpochMS()) : nil,
                statusKind: available ? .pendingUpload : .unavailable,
                primaryStatusMessage: available ? "Changes queued for sync." : "No resume state yet."
            )
        }

        let localDirty =
            local.sram?.hash != nil && local.sram?.hash != journal.lastSyncedSRAMHash ||
            local.resume?.hash != nil && local.resume?.hash != journal.lastSyncedResumeHash
        let statusKind: ResumeStateStatusKind
        if journal.lastError != nil {
            statusKind = .error
        } else if journal.remoteContinuityAvailable && (journal.pendingContinuityUpload || localDirty) {
            statusKind = .conflict
        } else if journal.remoteContinuityAvailable {
            statusKind = .cloudAvailable
        } else if localDirty || journal.pendingContinuityUpload {
            statusKind = .pendingUpload
        } else if journal.lastSuccessfulSyncAtEpochMS != nil && journal.remoteDeviceName != nil &&
            (journal.lastSyncNote?.hasPrefix("Resumed cloud session from") == true ||
                journal.lastSyncNote?.hasPrefix("Cloud progress ready from") == true) {
            statusKind = .syncedRemoteSource
        } else if journal.lastSuccessfulSyncAtEpochMS != nil {
            statusKind = .synced
        } else if available {
            statusKind = .pendingUpload
        } else {
            statusKind = .unavailable
        }

        let message: String
        switch statusKind {
        case .synced, .syncedRemoteSource:
            message = "Synced to server."
        case .pendingUpload:
            message = connectivity == .offline ? "Waiting to sync." : "Changes queued for sync."
        case .cloudAvailable:
            message = journal.remoteDeviceName.map { "Newer resume available from \($0)." } ?? "Newer cloud resume available."
        case .conflict:
            message = "Resume conflict needs a choice."
        case .error:
            message = journal.lastError ?? "Resume sync failed."
        case .localOnly:
            message = "Local-only install."
        case .unavailable:
            message = "No resume state yet."
        }

        return ResumeStateSummary(
            available: available,
            localPath: available ? resumeURL.path : nil,
            updatedAtEpochMS: available ? (try? resumeURL.modifiedAtEpochMS()) : journal.remoteContinuityUpdatedAtEpochMS,
            statusKind: statusKind,
            lastSuccessfulSyncAtEpochMS: journal.lastSuccessfulSyncAtEpochMS,
            sourceDeviceID: journal.remoteDeviceID,
            sourceDeviceName: journal.remoteDeviceName,
            sourceOrigin: statusKind == .syncedRemoteSource ? .remoteDevice : .thisDevice,
            primaryStatusMessage: message
        )
    }

    private func downloadRemoteSave(_ save: SaveDTO, remoteBaseURL: URL, target: URL) async throws {
        try await client.download(
            from: absoluteAssetURL(baseURL: remoteBaseURL, path: save.downloadPath),
            to: target
        )
        try setModifiedAtEpochMS(save.updatedAt.toEpochMS(), for: target)
    }

    private func downloadRemoteState(_ state: StateDTO, remoteBaseURL: URL, target: URL) async throws {
        try await client.download(
            from: absoluteAssetURL(baseURL: remoteBaseURL, path: state.downloadPath),
            to: target
        )
        try setModifiedAtEpochMS(state.updatedAt.toEpochMS(), for: target)
    }

    private func setModifiedAtEpochMS(_ epochMS: Int64, for url: URL) throws {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMS) / 1000)
        try FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: url.path)
    }

    private func absoluteAssetURL(baseURL: URL, path: String) -> URL {
        if let absolute = URL(string: path), absolute.scheme != nil {
            return absolute
        }
        return baseURL.appending(path: path.trimmingCharacters(in: CharacterSet(charactersIn: "/")))
    }
}

private func manifestFileName(for installation: InstalledROMReference) -> String {
    manifestFileName(romID: installation.romID, fileID: installation.fileID)
}

private func manifestFileName(romID: Int, fileID: Int) -> String {
    "__rommio_sync_\(romID)_\(fileID).json"
}

private func resumeStateFileName(for installation: InstalledROMReference) -> String {
    "__rommio_resume_\(installation.romID)_\(installation.fileID).state"
}

private func autoSnapshotFileName(for installation: InstalledROMReference, ringIndex: Int) -> String {
    "\(installation.romID)_recovery_auto_\(ringIndex).state"
}

private func autoRecoveryEntryID(ringIndex: Int) -> String {
    "auto:\(ringIndex)"
}

private func cloudStateRevision(
    fileName: String,
    kind: CloudStateKind,
    slot: Int? = nil,
    ringIndex: Int? = nil,
    updatedAtEpochMS: Int64,
    sourceDeviceID: String? = nil,
    sourceDeviceName: String? = nil,
    preserved: Bool = false,
    deleted: Bool = false
) -> CloudStateRevision {
    CloudStateRevision(
        fileName: fileName,
        kind: kind,
        slot: slot,
        ringIndex: ringIndex,
        hash: remoteAssetHash(fileName: fileName, updatedAtEpochMS: updatedAtEpochMS),
        updatedAtEpochMS: updatedAtEpochMS,
        sourceDeviceID: sourceDeviceID,
        sourceDeviceName: sourceDeviceName,
        preserved: preserved,
        deleted: deleted
    )
}

private func parseSlot(from fileName: String, installation: InstalledROMReference) -> Int? {
    let currentPattern = "^\(installation.romID)_slot(\\d+)\\.state$"
    let legacyPattern = "^\(installation.fileID)_slot(\\d+)\\.state$"
    if let slot = firstMatchGroup(fileName, pattern: currentPattern) {
        return Int(slot)
    }
    if let slot = firstMatchGroup(fileName, pattern: legacyPattern) {
        return Int(slot)
    }
    return nil
}

private func parseAutoRingIndex(from fileName: String, installation: InstalledROMReference) -> Int? {
    firstMatchGroup(fileName, pattern: "^\(installation.romID)_recovery_auto_(\\d+)\\.state$").flatMap(Int.init)
}

private func firstMatchGroup(_ value: String, pattern: String) -> String? {
    guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
    let range = NSRange(value.startIndex..<value.endIndex, in: value)
    guard let match = regex.firstMatch(in: value, range: range), match.numberOfRanges > 1,
          let groupRange = Range(match.range(at: 1), in: value) else {
        return nil
    }
    return String(value[groupRange])
}

private func remoteAssetHash(_ save: SaveDTO) -> String {
    remoteAssetHash(fileName: save.fileName, updatedAtEpochMS: save.updatedAt.toEpochMS(), size: save.fileSizeBytes)
}

private func remoteAssetHash(_ state: StateDTO) -> String {
    remoteAssetHash(fileName: state.fileName, updatedAtEpochMS: state.updatedAt.toEpochMS(), size: state.fileSizeBytes)
}

private func remoteAssetHash(fileName: String, updatedAtEpochMS: Int64, size: Int64 = 0) -> String {
    "\(fileName)|\(updatedAtEpochMS)|\(size)"
}

private let autoHistoryIntervalMS: Int64 = 30 * 60 * 1000
private let autoHistorySlots = 10

private func recoveryRingIndex(now: Int64) -> Int {
    Int((now / autoHistoryIntervalMS) % Int64(autoHistorySlots))
}

private func nowEpochMS() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
}

private extension String {
    func toEpochMS() -> Int64 {
        guard let date = ISO8601DateFormatter().date(from: self) else { return 0 }
        return Int64(date.timeIntervalSince1970 * 1000)
    }
}

private extension URL {
    func modifiedAtEpochMS() throws -> Int64 {
        let values = try resourceValues(forKeys: [.contentModificationDateKey])
        let date = values.contentModificationDate ?? .distantPast
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    func sha256() throws -> String {
        let data = try Data(contentsOf: self)
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}

private extension RecoveryStateRecord {
    var asBrowsableState: BrowsableGameState {
        BrowsableGameState(
            id: entryID,
            kind: origin == .autoHistory ? .recoveryHistory : .importedCloud,
            label: label,
            localPath: localPath,
            updatedAtEpochMS: capturedAtEpochMS,
            ringIndex: ringIndex,
            preserved: preserved,
            sourceDeviceID: sourceDeviceID,
            sourceDeviceName: sourceDeviceName,
            originKind: origin == .autoHistory ? .autoSnapshot : .importedPlayable,
            deletePolicy: origin == .autoHistory ? .none : .localOnly
        )
    }
}

private extension Optional where Wrapped == GameSyncJournal {
    func toPresentation(connectivity: ConnectivityState) -> GameSyncPresentation {
        guard let journal = self else {
            return GameSyncPresentation()
        }
        return journal.toPresentation(connectivity: connectivity)
    }
}

private extension GameSyncJournal {
    func toPresentation(connectivity: ConnectivityState) -> GameSyncPresentation {
        let kind: GameSyncStatusKind
        if lastError != nil {
            kind = .error
        } else if remoteContinuityAvailable && pendingContinuityUpload {
            kind = .conflict
        } else if remoteContinuityAvailable {
            kind = .cloudProgressAvailable
        } else if pendingContinuityUpload {
            kind = .offlinePending
        } else if lastSuccessfulSyncAtEpochMS != nil {
            kind = .synced
        } else {
            kind = .idle
        }

        let message: String
        switch kind {
        case .error:
            message = lastError ?? "Sync failed."
        case .cloudProgressAvailable:
            message = remoteDeviceName.map { "Cloud progress available from \($0)." } ?? "Cloud progress available."
        case .conflict:
            message = "Choose which resume to keep."
        case .offlinePending:
            message = connectivity == .offline ? "Offline changes pending." : "Changes queued for upload."
        case .synced:
            message = "Synced to server."
        case .localOnly:
            message = "Local-only install."
        case .idle:
            message = "Not synced yet."
        }

        return GameSyncPresentation(
            kind: kind,
            message: message,
            lastSuccessfulSyncAtEpochMS: lastSuccessfulSyncAtEpochMS,
            remoteDeviceName: remoteDeviceName
        )
    }
}
