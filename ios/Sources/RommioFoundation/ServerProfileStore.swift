import Foundation
import GRDB
import RommioContract

public protocol ServerProfileStore: Sendable {
    func listProfiles() async throws -> [ServerProfile]
    func activeProfile() async throws -> ServerProfile?
    func profile(id: String) async throws -> ServerProfile?
    func save(_ profile: ServerProfile, makeActive: Bool) async throws
    func setActiveProfile(id: String) async throws
    func deleteProfile(id: String) async throws
}

public actor GRDBServerProfileStore: ServerProfileStore {
    private let database: AppDatabase

    public init(database: AppDatabase) {
        self.database = database
    }

    public func listProfiles() async throws -> [ServerProfile] {
        try database.read { db in
            let rows = try Row.fetchAll(db, sql: "SELECT payload, is_active FROM server_profiles ORDER BY updated_at DESC")
            return try rows.map { row in
                try hydratedProfile(from: row)
            }
        }
    }

    public func activeProfile() async throws -> ServerProfile? {
        try database.read { db in
            guard let row = try Row.fetchOne(db, sql: "SELECT payload, is_active FROM server_profiles WHERE is_active = 1 LIMIT 1") else {
                return nil
            }
            return try hydratedProfile(from: row)
        }
    }

    public func profile(id: String) async throws -> ServerProfile? {
        try database.read { db in
            guard let row = try Row.fetchOne(db, sql: "SELECT payload, is_active FROM server_profiles WHERE id = ?", arguments: [id]) else {
                return nil
            }
            return try hydratedProfile(from: row)
        }
    }

    public func save(_ profile: ServerProfile, makeActive: Bool) async throws {
        try database.write { db in
            if makeActive {
                try db.execute(sql: "UPDATE server_profiles SET is_active = 0")
            }
            var persistedProfile = profile
            persistedProfile.isActive = makeActive || profile.isActive
            try db.execute(
                sql: """
                    INSERT INTO server_profiles (id, base_url, payload, is_active, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        base_url = excluded.base_url,
                        payload = excluded.payload,
                        is_active = excluded.is_active,
                        updated_at = excluded.updated_at
                    """,
                arguments: [
                    persistedProfile.id,
                    persistedProfile.baseURL.absoluteString,
                    try database.encoded(persistedProfile),
                    persistedProfile.isActive ? 1 : 0,
                    persistedProfile.createdAt,
                    persistedProfile.updatedAt,
                ]
            )
        }
    }

    public func setActiveProfile(id: String) async throws {
        try database.write { db in
            try db.execute(sql: "UPDATE server_profiles SET is_active = 0")
            try db.execute(sql: "UPDATE server_profiles SET is_active = 1 WHERE id = ?", arguments: [id])
        }
    }

    public func deleteProfile(id: String) async throws {
        try database.write { db in
            try db.execute(sql: "DELETE FROM server_profiles WHERE id = ?", arguments: [id])
            try db.execute(sql: "DELETE FROM offline_states WHERE profile_id = ?", arguments: [id])
            try db.execute(sql: "DELETE FROM cached_platforms WHERE profile_id = ?", arguments: [id])
            try db.execute(sql: "DELETE FROM cached_roms WHERE profile_id = ?", arguments: [id])
            try db.execute(sql: "DELETE FROM cached_collections WHERE profile_id = ?", arguments: [id])
            try db.execute(sql: "DELETE FROM home_snapshots WHERE profile_id = ?", arguments: [id])
            try db.execute(sql: "DELETE FROM download_records WHERE profile_id = ?", arguments: [id])
        }
    }

    private func hydratedProfile(from row: Row) throws -> ServerProfile {
        var profile = try database.decoded(ServerProfile.self, from: row["payload"])
        profile.isActive = (row["is_active"] as Int64? ?? 0) == 1
        return profile
    }
}
