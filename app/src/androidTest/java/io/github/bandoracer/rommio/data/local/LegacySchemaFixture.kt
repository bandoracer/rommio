package io.github.bandoracer.rommio.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** Rebuild a genuine pre-scope fixture, including its old primary keys, without DROP COLUMN support. */
fun restoreVersion10TableShapes(database: SupportSQLiteDatabase) {
    restoreVersion12ProgressTables(database)
    for ((table, keys) in mapOf(
        "download_records" to listOf("romId", "fileId"), "downloaded_roms" to listOf("romId", "fileId"),
        "save_states" to listOf("romId", "slot"), "game_sync_journal" to listOf("profileId", "romId", "fileId"),
        "save_state_sync_journal" to listOf("profileId", "romId", "fileId", "slot"), "recovery_states" to listOf("romId", "fileId", "entryId"),
    )) {
        val columns = mutableListOf<String>(); val definitions = mutableListOf<String>()
        database.query("PRAGMA table_info(`$table`)").use { rows -> while (rows.moveToNext()) {
            val name = rows.getString(1)
            if (name in listOf("namespaceKey", "progressScope")) continue
            columns += "`$name`"
            definitions += "`$name` ${rows.getString(2)}" + if (rows.getInt(3) == 1) " NOT NULL" else ""
        } }
        database.execSQL("CREATE TABLE `${table}_v10` (${definitions.joinToString()}, PRIMARY KEY (${keys.joinToString { "`$it`" }}))")
        database.execSQL("INSERT INTO `${table}_v10` (${columns.joinToString()}) SELECT ${columns.joinToString()} FROM `$table`")
        database.execSQL("DROP TABLE `$table`")
        database.execSQL("ALTER TABLE `${table}_v10` RENAME TO `$table`")
    }
}

/** Strip all schema-13 additions before testing real older-schema upgrades. */
fun restoreVersion12ProgressTables(database: SupportSQLiteDatabase) {
    database.execSQL("DROP TABLE IF EXISTS progress_v2_history")
    database.execSQL("DROP TABLE IF EXISTS progress_v2_history_index")
    val columns = mutableSetOf<String>()
    database.query("PRAGMA table_info(progress_v2_operations)").use { rows -> while (rows.moveToNext()) columns.add(rows.getString(1)) }
    if ("localSequence" !in columns) return
    database.execSQL("CREATE TABLE progress_v2_operations_v12 (namespaceId TEXT NOT NULL, contentId TEXT NOT NULL, operationId TEXT NOT NULL, payload BLOB NOT NULL, payloadHash TEXT NOT NULL, PRIMARY KEY(namespaceId, contentId, operationId))")
    database.execSQL("INSERT INTO progress_v2_operations_v12 SELECT namespaceId, contentId, operationId, payload, payloadHash FROM progress_v2_operations ORDER BY localSequence")
    database.execSQL("DROP TABLE progress_v2_operations")
    database.execSQL("ALTER TABLE progress_v2_operations_v12 RENAME TO progress_v2_operations")
}
