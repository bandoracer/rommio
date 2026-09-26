import Foundation
import GRDB
import RommioContract

public struct HomeSnapshot: Codable, Hashable, Sendable {
    public var continuePlaying: [RomDTO]
    public var recentlyAdded: [RomDTO]
    public var highlightedCollections: [RommCollectionDTO]

    public init(
        continuePlaying: [RomDTO] = [],
        recentlyAdded: [RomDTO] = [],
        highlightedCollections: [RommCollectionDTO] = []
    ) {
        self.continuePlaying = continuePlaying
        self.recentlyAdded = recentlyAdded
        self.highlightedCollections = highlightedCollections
    }
}

public struct DownloadRecord: Codable, Hashable, Sendable, Identifiable {
    public var profileID: String
    public var romID: Int
    public var fileID: Int
    public var romName: String
    public var platformSlug: String
    public var fileName: String
    public var fileSizeBytes: Int64
    public var status: DownloadStatus
    public var progressPercent: Int
    public var bytesDownloaded: Int64
    public var totalBytes: Int64
    public var localPath: String?
    public var lastError: String?
    public var enqueuedAtEpochMS: Int64
    public var startedAtEpochMS: Int64?
    public var completedAtEpochMS: Int64?
    public var updatedAtEpochMS: Int64

    // Optional only for decoding legacy records. New work always freezes these fields.
    public var owner: VerifiedAccount?
    public var attemptID: String?
    public var destinationRelativePath: String?
    public var contentSHA256: String?
    public var retryAfterEpochMS: Int64?
    public var parkedForAuthentication: Bool?

    public var id: String {
        "\(profileID):\(romID):\(fileID)"
    }

    public init(
        profileID: String,
        romID: Int,
        fileID: Int,
        romName: String,
        platformSlug: String,
        fileName: String,
        fileSizeBytes: Int64,
        status: DownloadStatus = .queued,
        progressPercent: Int = 0,
        bytesDownloaded: Int64 = 0,
        totalBytes: Int64 = 0,
        localPath: String? = nil,
        lastError: String? = nil,
        enqueuedAtEpochMS: Int64,
        startedAtEpochMS: Int64? = nil,
        completedAtEpochMS: Int64? = nil,
        updatedAtEpochMS: Int64
    ) {
        self.profileID = profileID
        self.romID = romID
        self.fileID = fileID
        self.romName = romName
        self.platformSlug = platformSlug
        self.fileName = fileName
        self.fileSizeBytes = fileSizeBytes
        self.status = status
        self.progressPercent = progressPercent
        self.bytesDownloaded = bytesDownloaded
        self.totalBytes = totalBytes
        self.localPath = localPath
        self.lastError = lastError
        self.enqueuedAtEpochMS = enqueuedAtEpochMS
        self.startedAtEpochMS = startedAtEpochMS
        self.completedAtEpochMS = completedAtEpochMS
        self.updatedAtEpochMS = updatedAtEpochMS
    }
}

public final class AppDatabase: @unchecked Sendable {
    public let dbQueue: DatabaseQueue
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init(rootDirectory: URL) throws {
        try FileManager.default.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
        dbQueue = try DatabaseQueue(path: rootDirectory.appending(path: "rommio.sqlite").path)
        try Self.migrator.migrate(dbQueue)
    }

    public func read<T>(_ value: (Database) throws -> T) throws -> T {
        try dbQueue.read(value)
    }

    public func write<T>(_ value: (Database) throws -> T) throws -> T {
        try dbQueue.write(value)
    }

    public func encoded<T: Encodable>(_ value: T) throws -> Data {
        try encoder.encode(value)
    }

    public func decoded<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        try decoder.decode(type, from: data)
    }

    /// Every schema version in order. Tests migrate a real older schema with `migrate(_:upTo:)`.
    static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()

        migrator.registerMigration("v1") { db in
            try db.execute(sql: """
                CREATE TABLE server_profiles (
                    id TEXT PRIMARY KEY NOT NULL,
                    base_url TEXT NOT NULL,
                    payload BLOB NOT NULL,
                    is_active INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """)

            try db.execute(sql: """
                CREATE TABLE offline_states (
                    profile_id TEXT PRIMARY KEY NOT NULL,
                    payload BLOB NOT NULL
                )
                """)

            try db.execute(sql: """
                CREATE TABLE cached_platforms (
                    profile_id TEXT NOT NULL,
                    platform_id INTEGER NOT NULL,
                    payload BLOB NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    PRIMARY KEY (profile_id, platform_id)
                )
                """)

            try db.execute(sql: """
                CREATE TABLE cached_roms (
                    profile_id TEXT NOT NULL,
                    rom_id INTEGER NOT NULL,
                    platform_id INTEGER NOT NULL,
                    payload BLOB NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    PRIMARY KEY (profile_id, rom_id)
                )
                """)

            try db.execute(sql: """
                CREATE TABLE cached_collections (
                    profile_id TEXT NOT NULL,
                    collection_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    payload BLOB NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    PRIMARY KEY (profile_id, collection_id)
                )
                """)

            try db.execute(sql: """
                CREATE TABLE home_snapshots (
                    profile_id TEXT PRIMARY KEY NOT NULL,
                    payload BLOB NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL
                )
                """)

            try db.execute(sql: """
                CREATE TABLE download_records (
                    profile_id TEXT NOT NULL,
                    rom_id INTEGER NOT NULL,
                    file_id INTEGER NOT NULL,
                    payload BLOB NOT NULL,
                    PRIMARY KEY (profile_id, rom_id, file_id)
                )
                """)
        }

        migrator.registerMigration("v2_player_controls") { db in
            try db.execute(sql: """
                CREATE TABLE player_touch_layouts (
                    platform_family_id TEXT PRIMARY KEY NOT NULL,
                    preset_id TEXT NOT NULL,
                    element_states_blob BLOB NOT NULL,
                    opacity DOUBLE NOT NULL,
                    global_scale DOUBLE NOT NULL,
                    left_handed INTEGER NOT NULL DEFAULT 0,
                    updated_at_epoch_ms INTEGER NOT NULL
                )
                """)

            try db.execute(sql: """
                CREATE TABLE player_hardware_bindings (
                    platform_family_id TEXT PRIMARY KEY NOT NULL,
                    controller_type_id INTEGER,
                    deadzone DOUBLE NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL
                )
                """)

            try db.execute(sql: """
                CREATE TABLE player_control_preferences (
                    id INTEGER PRIMARY KEY NOT NULL,
                    touch_controls_enabled INTEGER NOT NULL,
                    auto_hide_touch_on_controller INTEGER NOT NULL,
                    rumble_to_device_enabled INTEGER NOT NULL,
                    oled_black_mode_enabled INTEGER NOT NULL,
                    console_colors_enabled INTEGER NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL
                )
                """)

            let now = Int64(Date().timeIntervalSince1970 * 1000)
            try db.execute(
                sql: """
                INSERT INTO player_control_preferences (
                    id,
                    touch_controls_enabled,
                    auto_hide_touch_on_controller,
                    rumble_to_device_enabled,
                    oled_black_mode_enabled,
                    console_colors_enabled,
                    updated_at_epoch_ms
                ) VALUES (1, 1, 1, 1, 0, 1, ?)
                """,
                arguments: [now]
            )
        }

        migrator.registerMigration("v3_game_state_sync") { db in
            try db.execute(sql: """
                CREATE TABLE game_sync_journal (
                    profile_id TEXT NOT NULL,
                    rom_id INTEGER NOT NULL,
                    file_id INTEGER NOT NULL,
                    payload BLOB NOT NULL,
                    PRIMARY KEY (profile_id, rom_id, file_id)
                )
                """)

            try db.execute(sql: """
                CREATE TABLE save_state_sync_journal (
                    profile_id TEXT NOT NULL,
                    rom_id INTEGER NOT NULL,
                    file_id INTEGER NOT NULL,
                    slot INTEGER NOT NULL,
                    payload BLOB NOT NULL,
                    PRIMARY KEY (profile_id, rom_id, file_id, slot)
                )
                """)

            try db.execute(sql: """
                CREATE TABLE recovery_states (
                    rom_id INTEGER NOT NULL,
                    file_id INTEGER NOT NULL,
                    entry_id TEXT NOT NULL,
                    captured_at_epoch_ms INTEGER NOT NULL,
                    payload BLOB NOT NULL,
                    PRIMARY KEY (rom_id, file_id, entry_id)
                )
                """)
        }

        migrator.registerMigration("v4_progress_store") { db in
            try db.execute(sql: "CREATE TABLE IF NOT EXISTS progress_v2_namespaces (namespaceId TEXT NOT NULL PRIMARY KEY, serverKey TEXT NOT NULL, accountId TEXT NOT NULL)")
            try db.execute(sql: "CREATE UNIQUE INDEX IF NOT EXISTS index_progress_v2_namespaces_serverKey_accountId ON progress_v2_namespaces(serverKey, accountId)")
            try db.execute(sql: "CREATE TABLE IF NOT EXISTS progress_v2_contents (contentId TEXT NOT NULL PRIMARY KEY, namespaceId TEXT NOT NULL, bindingKey TEXT NOT NULL)")
            try db.execute(sql: "CREATE UNIQUE INDEX IF NOT EXISTS index_progress_v2_contents_namespaceId_bindingKey ON progress_v2_contents(namespaceId, bindingKey)")
            try db.execute(sql: "CREATE TABLE IF NOT EXISTS progress_v2_operations (namespaceId TEXT NOT NULL, contentId TEXT NOT NULL, operationId TEXT NOT NULL, payload BLOB NOT NULL, payloadHash TEXT NOT NULL, PRIMARY KEY(namespaceId, contentId, operationId))")
            try db.execute(sql: "CREATE TABLE IF NOT EXISTS progress_v2_heads (namespaceId TEXT NOT NULL, contentId TEXT NOT NULL, reference TEXT NOT NULL, operationId TEXT NOT NULL, PRIMARY KEY(namespaceId, contentId, reference, operationId))")
            try db.execute(sql: "CREATE TABLE IF NOT EXISTS progress_v2_outbox (namespaceId TEXT NOT NULL, contentId TEXT NOT NULL, operationId TEXT NOT NULL, payloadHash TEXT NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL, leaseId TEXT, leaseUntil INTEGER, nextAttempt INTEGER NOT NULL, lastError TEXT, PRIMARY KEY(namespaceId, contentId, operationId))")
            try db.execute(sql: "CREATE TABLE IF NOT EXISTS progress_v2_quarantine (namespaceId TEXT NOT NULL, contentId TEXT NOT NULL, payloadHash TEXT NOT NULL, payload BLOB NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(namespaceId, contentId, payloadHash))")
        }

        migrator.registerMigration("v5_progress_recovery") { db in
            try db.execute(sql: "CREATE TABLE progress_pins (namespaceId TEXT NOT NULL, contentId TEXT NOT NULL, checkpointId TEXT NOT NULL, PRIMARY KEY(namespaceId, contentId, checkpointId))")
        }
        migrator.registerMigration("v6_progress_import") { db in
            try db.execute(sql: "CREATE TABLE progress_import_generation (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), generation TEXT NOT NULL)")
        }
        // Local play data (docs/play-data-local.md): play sessions and save-state names.
        // Local only; never part of a checkpoint, the outbox or a progress backup.
        migrator.registerMigration("v7_play_data") { db in
            try db.execute(sql: """
                CREATE TABLE play_sessions (
                    sessionId TEXT PRIMARY KEY NOT NULL,
                    namespaceId TEXT NOT NULL,
                    contentId TEXT NOT NULL,
                    serverKey TEXT NOT NULL,
                    accountId TEXT NOT NULL,
                    romId INTEGER NOT NULL,
                    fileId INTEGER,
                    platformSlug TEXT,
                    deviceId TEXT NOT NULL,
                    coreId TEXT,
                    startedAtEpochMs INTEGER NOT NULL,
                    endedAtEpochMs INTEGER,
                    lastHeartbeatEpochMs INTEGER NOT NULL,
                    activeDurationMs INTEGER NOT NULL,
                    endReason TEXT
                )
                """)
            try db.execute(sql: "CREATE INDEX index_play_sessions_serverKey_accountId_startedAtEpochMs ON play_sessions(serverKey, accountId, startedAtEpochMs)")
            try db.execute(sql: "CREATE INDEX index_play_sessions_endedAtEpochMs ON play_sessions(endedAtEpochMs)")
            try db.execute(sql: """
                CREATE TABLE save_state_labels (
                    namespaceId TEXT NOT NULL,
                    contentId TEXT NOT NULL,
                    checkpointId TEXT NOT NULL,
                    label TEXT NOT NULL,
                    updatedAtEpochMs INTEGER NOT NULL,
                    PRIMARY KEY (namespaceId, contentId, checkpointId)
                )
                """)
        }
        // Touch-control haptics (docs/player-controls.md): on by default, device-local.
        migrator.registerMigration("v8_player_haptics") { db in
            try db.execute(sql: "ALTER TABLE player_control_preferences ADD COLUMN haptics_enabled INTEGER NOT NULL DEFAULT 1")
        }
        // Per-game touch-control settings (docs/player-controls.md): PlayStation's Analog
        // controls (off by default) and the touch-stick tuning (the console profile by
        // default). Device-local; never synced.
        migrator.registerMigration("v9_player_game_controls") { db in
            try db.execute(sql: """
                CREATE TABLE player_game_controls (
                    rom_id INTEGER PRIMARY KEY NOT NULL,
                    analog_controls INTEGER NOT NULL DEFAULT 0,
                    stick_sensitivity TEXT NOT NULL DEFAULT 'normal',
                    stick_dead_zone TEXT NOT NULL DEFAULT 'normal',
                    stick_centre TEXT NOT NULL DEFAULT 'floating',
                    updated_at_epoch_ms INTEGER NOT NULL
                )
                """)
        }
        // The pause menu's sound toggle: sound on by default, device-local.
        migrator.registerMigration("v10_player_audio_muted") { db in
            try db.execute(sql: "ALTER TABLE player_control_preferences ADD COLUMN audio_muted INTEGER NOT NULL DEFAULT 0")
        }
        return migrator
    }
}
