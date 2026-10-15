import Foundation
import RommioContract

public let legacySyncPausedMessage = "Cloud sync is paused while save safety is upgraded. Local progress is retained."

public struct LegacySyncUnavailableError: Error, LocalizedError, Sendable {
    public var errorDescription: String? { legacySyncPausedMessage }
}

private struct LocalContinuitySnapshot {
    let sram: CloudSaveRevision?
    let resume: CloudStateRevision?
}

public struct RommSyncBridge: Sendable {
    public let libraryStore: LibraryStore
    public let database: AppDatabase
    public let deviceName: String

    public init(
        libraryStore: LibraryStore,
        database: AppDatabase,
        deviceName: String
    ) {
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
        try retainLocalIntent(profileID: profileID, installation: installation, deviceID: deviceID)
        return try cachedPlayerLaunch(profileID: profileID, installation: installation, connectivity: connectivity)
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

        let saveSlots = manualJournals
            .filter { !$0.deleted && !$0.pendingDelete }
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
        try retainLocalIntent(profileID: profileID, installation: installation, deviceID: deviceID)
        _ = try captureAutoSnapshotIfNeeded(installation: installation, deviceID: deviceID)
        throw LegacySyncUnavailableError()
    }

    private func retainLocalIntent(profileID: String, installation: InstalledROMReference, deviceID: String?) throws {
        try importLocalManualStates(profileID: profileID, installation: installation)
        let journal = try database.gameSyncJournal(profileID: profileID, romID: installation.romID, fileID: installation.fileID)
            ?? GameSyncJournal(profileID: profileID, romID: installation.romID, fileID: installation.fileID)
        let local = try loadLocalContinuity(installation: installation, deviceID: deviceID)
        var updated = updatedOfflineJournal(journal: journal, local: local)
        updated.lastSyncNote = legacySyncPausedMessage
        try database.upsertGameSyncJournal(updated)
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
            if FileManager.default.fileExists(atPath: manualURL.path) { try FileManager.default.removeItem(at: manualURL) }
        }
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
        try Data(contentsOf: resumeURL).write(to: target, options: .atomic)
        let handle = try FileHandle(forWritingTo: target)
        try handle.synchronize()
        try handle.close()
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
            let existing = try database.saveStateSyncJournal(
                profileID: profileID, romID: installation.romID, fileID: installation.fileID, slot: slot
            )
            if existing?.deleted == true || existing?.pendingDelete == true { continue }
            let canonicalURL = libraryStore.saveStateURL(for: installation, slot: slot)
            if fileURL.standardizedFileURL.path != canonicalURL.standardizedFileURL.path &&
                !FileManager.default.fileExists(atPath: canonicalURL.path) {
                // An import preserves the source, including differing aliases for later recovery.
                try Data(contentsOf: fileURL).write(to: canonicalURL, options: .atomic)
            }
            guard FileManager.default.fileExists(atPath: canonicalURL.path) else { continue }
            let hash = try canonicalURL.sha256()
            let journal = SaveStateSyncJournal(
                profileID: profileID,
                romID: installation.romID,
                fileID: installation.fileID,
                slot: slot,
                label: "Save slot \(slot)",
                localPath: canonicalURL.path,
                localHash: hash,
                localUpdatedAtEpochMS: try canonicalURL.modifiedAtEpochMS(),
                remoteHash: existing?.remoteHash,
                remoteUpdatedAtEpochMS: existing?.remoteUpdatedAtEpochMS,
                sourceDeviceID: existing?.sourceDeviceID,
                sourceDeviceName: existing?.sourceDeviceName ?? deviceName,
                deleted: false,
                pendingUpload: existing?.pendingUpload == true || existing == nil || existing?.localHash != hash,
                pendingDelete: false,
                lastSyncedAtEpochMS: existing?.lastSyncedAtEpochMS
            )
            try database.upsertSaveStateSyncJournal(journal)
        }
    }

    private func buildResumeSummary(
        journal: GameSyncJournal?, resumeURL: URL,
        connectivity: ConnectivityState, hasActiveProfile: Bool
    ) -> ResumeStateSummary {
        let available = FileManager.default.fileExists(atPath: resumeURL.path)
        return ResumeStateSummary(
            available: available, localPath: available ? resumeURL.path : nil,
            updatedAtEpochMS: available ? (try? resumeURL.modifiedAtEpochMS()) : nil,
            statusKind: available ? .localOnly : .unavailable,
            lastSuccessfulSyncAtEpochMS: journal?.lastSuccessfulSyncAtEpochMS,
            primaryStatusMessage: available ? legacySyncPausedMessage : "No resume state yet."
        )
    }
}

private func autoSnapshotFileName(for installation: InstalledROMReference, ringIndex: Int) -> String {
    "\(installation.romID)_recovery_auto_\(ringIndex).state"
}

private func autoRecoveryEntryID(ringIndex: Int) -> String {
    "auto:\(ringIndex)"
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

private func firstMatchGroup(_ value: String, pattern: String) -> String? {
    guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
    let range = NSRange(value.startIndex..<value.endIndex, in: value)
    guard let match = regex.firstMatch(in: value, range: range), match.numberOfRanges > 1,
          let groupRange = Range(match.range(at: 1), in: value) else {
        return nil
    }
    return String(value[groupRange])
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
        try ProgressFileHash.sha256(self)
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
        GameSyncPresentation(kind: .localOnly, message: legacySyncPausedMessage,
                             lastSuccessfulSyncAtEpochMS: self?.lastSuccessfulSyncAtEpochMS)
    }
}
