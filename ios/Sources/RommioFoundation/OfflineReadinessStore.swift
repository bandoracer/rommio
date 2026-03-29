import Foundation
import GRDB
import RommioContract

public protocol OfflineReadinessStore: Sendable {
    func load(profileID: String) async throws -> OfflineState
    func save(_ state: OfflineState, profileID: String) async throws
    func delete(profileID: String) async throws
}

public actor GRDBOfflineReadinessStore: OfflineReadinessStore {
    private let database: AppDatabase

    public init(database: AppDatabase) {
        self.database = database
    }

    public func load(profileID: String) async throws -> OfflineState {
        try database.read { db in
            guard let row = try Row.fetchOne(db, sql: "SELECT payload FROM offline_states WHERE profile_id = ?", arguments: [profileID]) else {
                return OfflineState(connectivity: .online, activeProfileID: profileID)
            }
            return try database.decoded(OfflineState.self, from: row["payload"])
        }
    }

    public func save(_ state: OfflineState, profileID: String) async throws {
        try database.write { db in
            try db.execute(
                sql: """
                    INSERT INTO offline_states (profile_id, payload)
                    VALUES (?, ?)
                    ON CONFLICT(profile_id) DO UPDATE SET payload = excluded.payload
                    """,
                arguments: [profileID, try database.encoded(state)]
            )
        }
    }

    public func delete(profileID: String) async throws {
        try database.write { db in
            try db.execute(sql: "DELETE FROM offline_states WHERE profile_id = ?", arguments: [profileID])
        }
    }
}
