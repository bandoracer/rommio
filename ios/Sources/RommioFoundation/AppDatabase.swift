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
        try migrator.migrate(dbQueue)
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

    private var migrator: DatabaseMigrator {
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
                ) VALUES (1, 1, 1, 1, 0, 0, ?)
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

        return migrator
    }
}
