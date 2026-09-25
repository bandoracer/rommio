import CryptoKit
import Foundation

/// A checkpoint as the progress store identifies it; previews and names key on this.
public struct SaveKey: Hashable, Sendable {
    public let namespaceId: String
    public let contentId: String
    public let checkpointId: String

    public init(namespaceId: String, contentId: String, checkpointId: String) {
        self.namespaceId = namespaceId
        self.contentId = contentId
        self.checkpointId = checkpointId
    }
}

/// What the player sees (save-sync-spec §11 terms).
public enum SaveKind: Sendable, Hashable {
    /// A state the player made (a manual save state or a slot).
    case saveState
    /// An automatic resume point (periodic, pause, background, exit, safety or restore capture with a state).
    case resumePoint
    /// Game-save data only (no emulator state). In a game's history, never in Recent saves.
    case gameSave

    public var title: String {
        switch self {
        case .saveState: "Save state"
        case .resumePoint: "Resume point"
        case .gameSave: "Game-save data"
        }
    }
}

/// Remote state of the operation that committed a checkpoint (save-sync-spec §11 copy).
public enum SaveSyncStatus: String, Sendable, Hashable {
    case synced, syncing, waiting, localOnly, signInNeeded, conflict, unknown

    /// "" for unknown: nothing is shown.
    public var label: String {
        switch self {
        case .synced: "Synced"
        case .syncing: "Syncing"
        case .waiting: "Waiting to sync"
        case .localOnly: "Local only"
        case .signInNeeded: "Sign-in needed"
        case .conflict: "Conflict"
        case .unknown: ""
        }
    }
}

/// How this device describes itself in save rows. iOS says "This iPhone" / "This
/// iPad" where the shared spec says phone / tablet; iPhone Duo and Mac say "This device".
public enum DeviceFormFactor: Sendable, Hashable {
    case phone, tablet, unknown

    public var thisDeviceLabel: String {
        switch self {
        case .phone: "This iPhone"
        case .tablet: "This iPad"
        case .unknown: "This device"
        }
    }
}

/// One committed checkpoint and what the store says about it; the input to `SavedProgressRules`.
public struct StoredCheckpoint: Sendable, Hashable {
    public let key: SaveKey
    public let romId: Int64
    public let fileId: Int64?
    public let operationId: String
    public let capturedAtEpochMs: Int64
    public let captureReason: String
    public let deviceId: String
    public let hasState: Bool
    /// Made by the player (history marks manual captures and slot references).
    public let manual: Bool
    /// Artifacts are kept on this device.
    public let retained: Bool
    /// References whose current head selects this checkpoint (`state/resume/…`, `state/slot/…`).
    public let currentReferences: Set<String>
    /// The subset of `currentReferences` that has more than one head (an unresolved conflict).
    public let conflictedReferences: Set<String>
    /// Outbox status of `operationId`: PENDING, LEASED, PUBLISHED, LOCAL_ONLY, PARKED_AUTH.
    public let outboxStatus: String?

    public init(key: SaveKey, romId: Int64, fileId: Int64?, operationId: String, capturedAtEpochMs: Int64, captureReason: String, deviceId: String,
                hasState: Bool, manual: Bool, retained: Bool, currentReferences: Set<String> = [], conflictedReferences: Set<String> = [], outboxStatus: String? = nil) {
        self.key = key
        self.romId = romId
        self.fileId = fileId
        self.operationId = operationId
        self.capturedAtEpochMs = capturedAtEpochMs
        self.captureReason = captureReason
        self.deviceId = deviceId
        self.hasState = hasState
        self.manual = manual
        self.retained = retained
        self.currentReferences = currentReferences
        self.conflictedReferences = conflictedReferences
        self.outboxStatus = outboxStatus
    }
}

/// A save as the pages show it.
public struct SaveEntry: Sendable, Hashable, Identifiable {
    public var id: SaveKey { key }
    public let key: SaveKey
    public let romId: Int64
    public let fileId: Int64?
    public let operationId: String
    public let kind: SaveKind
    public let capturedAtEpochMs: Int64
    public let captureReason: String
    public let deviceId: String
    public let fromThisDevice: Bool
    /// "This iPhone", "This iPad", "This device" or "Another device".
    public let deviceLabel: String
    public let sync: SaveSyncStatus
    /// The player's name for this save state, if any.
    public var label: String?
    /// Quick-save slot number (1–4) when this is the current revision of a numbered slot.
    public let slotNumber: Int?
    /// Current head of an automatic resume reference.
    public let isCurrentResume: Bool
    /// Current head of a slot reference.
    public let isCurrentSlot: Bool
    /// Has a state and its artifacts are kept on this device.
    public let loadable: Bool
    /// Local file of the save's preview (the frame captured with it), if any.
    public let thumbnailURL: URL?

    public var capturedAt: Date { Date(timeIntervalSince1970: TimeInterval(capturedAtEpochMs) / 1000) }

    /// Title for a save row: the player's name, then the slot, then the kind.
    public var title: String {
        if let label { return label }
        switch kind {
        case .saveState: return slotNumber.map { "Save state · Slot \($0)" } ?? "Save state"
        case .resumePoint: return isCurrentResume ? "Resume point" : "Earlier resume point"
        case .gameSave: return "Game-save data"
        }
    }

    /// "Save state", "Save state · Slot 2", "Resume point", "Game-save data".
    public var kindTitle: String {
        if kind == .saveState, let slotNumber { return "Save state · Slot \(slotNumber)" }
        return kind.title
    }

    /// Why it was captured, in plain words ("Left game", "Paused", "Manual"…).
    public var reasonTitle: String { SavedProgressRules.reasonLabel(captureReason) }

    public init(key: SaveKey, romId: Int64, fileId: Int64?, operationId: String, kind: SaveKind, capturedAtEpochMs: Int64, captureReason: String,
                deviceId: String, fromThisDevice: Bool, deviceLabel: String, sync: SaveSyncStatus, label: String?, slotNumber: Int?,
                isCurrentResume: Bool, isCurrentSlot: Bool, loadable: Bool, thumbnailURL: URL?) {
        self.key = key
        self.romId = romId
        self.fileId = fileId
        self.operationId = operationId
        self.kind = kind
        self.capturedAtEpochMs = capturedAtEpochMs
        self.captureReason = captureReason
        self.deviceId = deviceId
        self.fromThisDevice = fromThisDevice
        self.deviceLabel = deviceLabel
        self.sync = sync
        self.label = label
        self.slotNumber = slotNumber
        self.isCurrentResume = isCurrentResume
        self.isCurrentSlot = isCurrentSlot
        self.loadable = loadable
        self.thumbnailURL = thumbnailURL
    }
}

/// Saves of one local calendar day, newest first.
public struct SaveDay: Sendable, Hashable {
    public let day: Date
    public let entries: [SaveEntry]
}

/// All saves for one game, newest first within each group.
public struct GameSaves: Sendable, Hashable {
    /// The current resume point (newest if several heads), or nil.
    public var resume: SaveEntry?
    /// More than one current resume head: a choice is needed before loading automatically.
    public var resumeConflict: Bool
    public var saveStates: [SaveEntry]
    /// Earlier resume points still in local history.
    public var history: [SaveEntry]
    /// Game-save data captures (no emulator state), newest first.
    public var gameSaveData: [SaveEntry]

    public init(resume: SaveEntry? = nil, resumeConflict: Bool = false, saveStates: [SaveEntry] = [], history: [SaveEntry] = [], gameSaveData: [SaveEntry] = []) {
        self.resume = resume
        self.resumeConflict = resumeConflict
        self.saveStates = saveStates
        self.history = history
        self.gameSaveData = gameSaveData
    }

    public var isEmpty: Bool { resume == nil && saveStates.isEmpty && history.isEmpty && gameSaveData.isEmpty }
    public static let empty = GameSaves()
}

/// Pure rules for the cross-game saves reader (docs/play-data-local.md §3–5).
/// Platform code gathers `StoredCheckpoint`s read-only from the progress store;
/// everything the pages show is decided here.
public enum SavedProgressRules {
    public static let anotherDevice = "Another device"
    public static let maxLabelLength = 60

    /// Java's `UUID.nameUUIDFromBytes` (MD5, version 3): the four quick-save slot ids
    /// Android uses are `nameUUIDFromBytes("rommio-slot-N")`.
    public static func nameUUID(_ bytes: Data) -> String {
        var digest = Array(Insecure.MD5.hash(data: bytes))
        digest[6] = (digest[6] & 0x0f) | 0x30
        digest[8] = (digest[8] & 0x3f) | 0x80
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        let parts = [hex.prefix(8), hex.dropFirst(8).prefix(4), hex.dropFirst(12).prefix(4), hex.dropFirst(16).prefix(4), hex.dropFirst(20)]
        return parts.joined(separator: "-")
    }

    /// The stable quick-save slot ids (1–4) and their numbers.
    public static let quickSlots: [String: Int] = Dictionary(uniqueKeysWithValues: (1...4).map { (nameUUID(Data("rommio-slot-\($0)".utf8)), $0) })

    public static func slotId(_ reference: String) -> String? {
        if reference.hasPrefix("state/slot/") { return String(reference.dropFirst("state/slot/".count)) }
        if reference.hasPrefix("slot/") { return String(reference.dropFirst("slot/".count)) }
        return nil
    }

    public static func isResumeReference(_ reference: String) -> Bool {
        reference.hasPrefix("state/resume/") || reference.hasPrefix("resume/")
    }

    public static func kind(hasState: Bool, manual: Bool) -> SaveKind {
        if !hasState { return .gameSave }
        return manual ? .saveState : .resumePoint
    }

    public static func syncStatus(outboxStatus: String?, conflicted: Bool) -> SaveSyncStatus {
        if conflicted { return .conflict }
        switch outboxStatus {
        case "PUBLISHED": return .synced
        case "LEASED": return .syncing
        case "PENDING": return .waiting
        case "LOCAL_ONLY": return .localOnly
        case "PARKED_AUTH": return .signInNeeded
        default: return .unknown
        }
    }

    public static func deviceLabel(checkpointDeviceId: String, localDeviceId: String?, formFactor: DeviceFormFactor) -> String {
        if let localDeviceId, checkpointDeviceId == localDeviceId { return formFactor.thisDeviceLabel }
        return anotherDevice
    }

    /// Plain words for a checkpoint's capture reason.
    public static func reasonLabel(_ reason: String) -> String {
        switch reason {
        case "manual": "Manual"
        case "periodic": "Automatic"
        case "pause": "Paused"
        case "background": "App in background"
        case "exit": "Left game"
        case "safety": "Safety copy"
        case "restore": "Loaded state"
        case "import": "Imported"
        default: reason.prefix(1).uppercased() + reason.dropFirst()
        }
    }

    public static func entry(_ stored: StoredCheckpoint, labels: [SaveKey: String], localDeviceId: String?, formFactor: DeviceFormFactor,
                             thumbnail: (SaveKey) -> URL?) -> SaveEntry {
        let kind = kind(hasState: stored.hasState, manual: stored.manual)
        let slot = stored.currentReferences.sorted().lazy.compactMap(slotId).first
        let label = labels[stored.key].flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || kind == .gameSave ? nil : $0 }
        return SaveEntry(
            key: stored.key,
            romId: stored.romId,
            fileId: stored.fileId,
            operationId: stored.operationId,
            kind: kind,
            capturedAtEpochMs: stored.capturedAtEpochMs,
            captureReason: stored.captureReason,
            deviceId: stored.deviceId,
            fromThisDevice: localDeviceId != nil && stored.deviceId == localDeviceId,
            deviceLabel: deviceLabel(checkpointDeviceId: stored.deviceId, localDeviceId: localDeviceId, formFactor: formFactor),
            sync: syncStatus(outboxStatus: stored.outboxStatus, conflicted: !stored.conflictedReferences.isEmpty),
            label: label,
            slotNumber: slot.flatMap { quickSlots[$0] },
            isCurrentResume: stored.currentReferences.contains(where: isResumeReference),
            isCurrentSlot: slot != nil,
            loadable: stored.hasState && stored.retained,
            thumbnailURL: stored.hasState ? thumbnail(stored.key) : nil
        )
    }

    /// Newest first by capture time, then current heads first, then by checkpoint id.
    static func newestFirst(_ a: SaveEntry, _ b: SaveEntry) -> Bool {
        if a.capturedAtEpochMs != b.capturedAtEpochMs { return a.capturedAtEpochMs > b.capturedAtEpochMs }
        let aHead = a.isCurrentResume || a.isCurrentSlot, bHead = b.isCurrentResume || b.isCurrentSlot
        if aHead != bHead { return aHead }
        return a.key.checkpointId < b.key.checkpointId
    }

    private static func unique(_ entries: [SaveEntry]) -> [SaveEntry] {
        var seen = Set<SaveKey>()
        return entries.filter { seen.insert($0.key).inserted }
    }

    /// Recent saves across games: every save state still on this device (or current
    /// slot) plus each game's current resume point, newest first. Earlier resume
    /// points and game-save data are left out.
    public static func recent(_ entries: [SaveEntry], limit: Int) -> [SaveEntry] {
        let kept = unique(entries.filter { entry in
            (entry.kind == .saveState && (entry.loadable || entry.isCurrentSlot)) || (entry.kind == .resumePoint && entry.isCurrentResume)
        })
        return Array(kept.sorted(by: newestFirst).prefix(limit))
    }

    /// Each game's latest place to continue from (Play's screens): its newest current
    /// resume point, else its newest save state still on this device (or current slot).
    public static func latestByGame(_ entries: [SaveEntry]) -> [Int64: SaveEntry] {
        var result: [Int64: SaveEntry] = [:]
        for entry in unique(entries).sorted(by: newestFirst) {
            let resume = entry.kind == .resumePoint && entry.isCurrentResume
            let state = entry.kind == .saveState && (entry.loadable || entry.isCurrentSlot)
            guard resume || state else { continue }
            if let chosen = result[entry.romId] {
                // A resume point beats a save state; otherwise the newest (already chosen) stays.
                if resume && chosen.kind != .resumePoint { result[entry.romId] = entry }
            } else {
                result[entry.romId] = entry
            }
        }
        return result
    }

    /// One game's saves: its current resume point, save states and the rest of its history.
    public static func forGame(_ entries: [SaveEntry]) -> GameSaves {
        let sorted = unique(entries).sorted(by: newestFirst)
        let resumeHeads = sorted.filter(\.isCurrentResume)
        let resume = resumeHeads.first
        return GameSaves(
            resume: resume,
            // A conflicted resume reference (several heads). iOS keys resume references by
            // core compatibility, so two current resume points of different cores are not a conflict.
            resumeConflict: resumeHeads.contains { $0.sync == .conflict },
            saveStates: sorted.filter { $0.kind == .saveState && $0.key != resume?.key },
            history: sorted.filter { $0.kind == .resumePoint && $0.key != resume?.key },
            gameSaveData: sorted.filter { $0.kind == .gameSave }
        )
    }

    /// Groups newest-first entries by local calendar day, keeping order.
    public static func groupByDay(_ entries: [SaveEntry], calendar: Calendar = .current) -> [SaveDay] {
        var order: [Date] = []
        var groups: [Date: [SaveEntry]] = [:]
        for entry in entries {
            let day = calendar.startOfDay(for: entry.capturedAt)
            if groups[day] == nil { order.append(day) }
            groups[day, default: []].append(entry)
        }
        return order.sorted(by: >).map { SaveDay(day: $0, entries: groups[$0]!) }
    }

    /// Normalises a name typed by the player; nil means "remove the name".
    public static func normalizeLabel(_ raw: String) -> String? {
        // As Android: trim, collapse whitespace runs to one space, then at most 60 characters.
        let collapsed = raw.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        let limited = String(collapsed.prefix(maxLabelLength))
        return limited.isEmpty ? nil : limited
    }
}

public extension DeviceFormFactor {
    /// Form factor only when unambiguous: an iPhone is "This iPhone" and an iPad
    /// "This iPad"; iPhone Duo (a phone whose screen is at least 460 pt on its short
    /// side, as the player stage recognises it), a Mac and anything else say "This device".
    static func resolve(isPhone: Bool, isPad: Bool, screenShortSide: Double, runsOnMac: Bool) -> DeviceFormFactor {
        if runsOnMac { return .unknown }
        if isPhone { return screenShortSide >= 460 ? .unknown : .phone }
        if isPad { return .tablet }
        return .unknown
    }
}

#if canImport(UIKit)
import UIKit

public extension DeviceFormFactor {
    @MainActor
    static func current() -> DeviceFormFactor {
        let bounds = UIScreen.main.bounds
        return resolve(isPhone: UIDevice.current.userInterfaceIdiom == .phone, isPad: UIDevice.current.userInterfaceIdiom == .pad,
                       screenShortSide: Double(min(bounds.width, bounds.height)),
                       runsOnMac: ProcessInfo.processInfo.isiOSAppOnMac || ProcessInfo.processInfo.isMacCatalystApp)
    }
}
#else
public extension DeviceFormFactor {
    @MainActor
    static func current() -> DeviceFormFactor { .unknown }
}
#endif
