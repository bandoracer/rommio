import Foundation
import GRDB

private enum GameStateTable {
    static let gameSyncJournal = "game_sync_journal"
    static let saveStateSyncJournal = "save_state_sync_journal"
    static let recoveryStates = "recovery_states"
}

public extension AppDatabase {
    func gameSyncJournal(profileID: String, romID: Int, fileID: Int) throws -> GameSyncJournal? {
        try read { db in
            guard let data = try Data.fetchOne(
                db,
                sql: "SELECT payload FROM \(GameStateTable.gameSyncJournal) WHERE profile_id = ? AND rom_id = ? AND file_id = ? LIMIT 1",
                arguments: [profileID, romID, fileID]
            ) else {
                return nil
            }
            return try decoded(GameSyncJournal.self, from: data)
        }
    }

    func upsertGameSyncJournal(_ journal: GameSyncJournal) throws {
        try write { db in
            try db.execute(
                sql: """
                    INSERT INTO \(GameStateTable.gameSyncJournal) (profile_id, rom_id, file_id, payload)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(profile_id, rom_id, file_id) DO UPDATE SET
                        payload = excluded.payload
                    """,
                arguments: [journal.profileID, journal.romID, journal.fileID, try encoded(journal)]
            )
        }
    }

    func deleteGameSyncJournal(profileID: String) throws {
        try write { db in
            try db.execute(
                sql: "DELETE FROM \(GameStateTable.gameSyncJournal) WHERE profile_id = ?",
                arguments: [profileID]
            )
        }
    }

    func saveStateSyncJournals(profileID: String, romID: Int, fileID: Int) throws -> [SaveStateSyncJournal] {
        try read { db in
            let rows = try Row.fetchAll(
                db,
                sql: """
                    SELECT payload
                    FROM \(GameStateTable.saveStateSyncJournal)
                    WHERE profile_id = ? AND rom_id = ? AND file_id = ?
                    ORDER BY slot ASC
                    """,
                arguments: [profileID, romID, fileID]
            )
            return try rows.map { row in
                try decoded(SaveStateSyncJournal.self, from: row["payload"])
            }
        }
    }

    func saveStateSyncJournal(profileID: String, romID: Int, fileID: Int, slot: Int) throws -> SaveStateSyncJournal? {
        try read { db in
            guard let data = try Data.fetchOne(
                db,
                sql: """
                    SELECT payload
                    FROM \(GameStateTable.saveStateSyncJournal)
                    WHERE profile_id = ? AND rom_id = ? AND file_id = ? AND slot = ?
                    LIMIT 1
                    """,
                arguments: [profileID, romID, fileID, slot]
            ) else {
                return nil
            }
            return try decoded(SaveStateSyncJournal.self, from: data)
        }
    }

    func upsertSaveStateSyncJournal(_ journal: SaveStateSyncJournal) throws {
        try write { db in
            try db.execute(
                sql: """
                    INSERT INTO \(GameStateTable.saveStateSyncJournal) (profile_id, rom_id, file_id, slot, payload)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(profile_id, rom_id, file_id, slot) DO UPDATE SET
                        payload = excluded.payload
                    """,
                arguments: [journal.profileID, journal.romID, journal.fileID, journal.slot, try encoded(journal)]
            )
        }
    }

    func deleteSaveStateSyncJournals(profileID: String) throws {
        try write { db in
            try db.execute(
                sql: "DELETE FROM \(GameStateTable.saveStateSyncJournal) WHERE profile_id = ?",
                arguments: [profileID]
            )
        }
    }

    func recoveryStates(romID: Int, fileID: Int) throws -> [RecoveryStateRecord] {
        try read { db in
            let rows = try Row.fetchAll(
                db,
                sql: """
                    SELECT payload
                    FROM \(GameStateTable.recoveryStates)
                    WHERE rom_id = ? AND file_id = ?
                    ORDER BY captured_at_epoch_ms DESC, entry_id ASC
                    """,
                arguments: [romID, fileID]
            )
            return try rows.map { row in
                try decoded(RecoveryStateRecord.self, from: row["payload"])
            }
        }
    }

    func recoveryState(romID: Int, fileID: Int, entryID: String) throws -> RecoveryStateRecord? {
        try read { db in
            guard let data = try Data.fetchOne(
                db,
                sql: """
                    SELECT payload
                    FROM \(GameStateTable.recoveryStates)
                    WHERE rom_id = ? AND file_id = ? AND entry_id = ?
                    LIMIT 1
                    """,
                arguments: [romID, fileID, entryID]
            ) else {
                return nil
            }
            return try decoded(RecoveryStateRecord.self, from: data)
        }
    }

    func upsertRecoveryState(_ record: RecoveryStateRecord) throws {
        try write { db in
            try db.execute(
                sql: """
                    INSERT INTO \(GameStateTable.recoveryStates) (rom_id, file_id, entry_id, captured_at_epoch_ms, payload)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(rom_id, file_id, entry_id) DO UPDATE SET
                        captured_at_epoch_ms = excluded.captured_at_epoch_ms,
                        payload = excluded.payload
                    """,
                arguments: [record.romID, record.fileID, record.entryID, record.capturedAtEpochMS, try encoded(record)]
            )
        }
    }

    func deleteRecoveryState(romID: Int, fileID: Int, entryID: String) throws {
        try write { db in
            try db.execute(
                sql: """
                    DELETE FROM \(GameStateTable.recoveryStates)
                    WHERE rom_id = ? AND file_id = ? AND entry_id = ?
                    """,
                arguments: [romID, fileID, entryID]
            )
        }
    }

    func replaceRecoveryStates(romID: Int, fileID: Int, records: [RecoveryStateRecord]) throws {
        try write { db in
            try db.execute(
                sql: "DELETE FROM \(GameStateTable.recoveryStates) WHERE rom_id = ? AND file_id = ?",
                arguments: [romID, fileID]
            )
            for record in records {
                try db.execute(
                    sql: """
                        INSERT INTO \(GameStateTable.recoveryStates) (rom_id, file_id, entry_id, captured_at_epoch_ms, payload)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                    arguments: [record.romID, record.fileID, record.entryID, record.capturedAtEpochMS, try encoded(record)]
                )
            }
        }
    }
}
