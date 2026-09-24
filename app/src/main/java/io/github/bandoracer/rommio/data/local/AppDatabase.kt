package io.github.bandoracer.rommio.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.bandoracer.rommio.model.DownloadRecordEntity
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.SaveStateEntity
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "server_profiles")
data class ServerProfileEntity(
    @PrimaryKey val id: String,
    val label: String,
    val baseUrl: String,
    val edgeAuthMode: String,
    val originAuthMode: String,
    val capabilitiesJson: String,
    val serverAccessJson: String,
    val sessionStateJson: String,
    val isActive: Boolean,
    val status: String,
    val lastValidationAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

@Dao
interface DownloadedRomDao {
    @Query("SELECT * FROM downloaded_roms ORDER BY downloadedAtEpochMs DESC")
    fun observeAll(): Flow<List<DownloadedRomEntity>>

    @Query("SELECT * FROM downloaded_roms ORDER BY downloadedAtEpochMs DESC")
    suspend fun listAll(): List<DownloadedRomEntity>

    @Query("SELECT * FROM downloaded_roms WHERE romId = :romId ORDER BY downloadedAtEpochMs DESC")
    fun observeByRomId(romId: Int): Flow<List<DownloadedRomEntity>>

    @Query("SELECT * FROM downloaded_roms WHERE namespaceKey = :namespaceKey AND romId = :romId AND fileId = :fileId LIMIT 1")
    suspend fun getByIds(romId: Int, fileId: Int, namespaceKey: String = "legacy"): DownloadedRomEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadedRomEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DownloadedRomEntity>)

    @Query("DELETE FROM downloaded_roms WHERE namespaceKey = :namespaceKey AND romId = :romId AND fileId = :fileId")
    suspend fun delete(romId: Int, fileId: Int, namespaceKey: String = "legacy")

    @Query("DELETE FROM downloaded_roms")
    suspend fun deleteAll()
}

@Dao
interface DownloadRecordDao {
    @Query("SELECT * FROM download_records ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<DownloadRecordEntity>>

    @Query("SELECT * FROM download_records")
    suspend fun listAll(): List<DownloadRecordEntity>

    @Query("SELECT * FROM download_records WHERE namespaceKey = :namespaceKey AND romId = :romId AND fileId = :fileId LIMIT 1")
    fun observeByIds(romId: Int, fileId: Int, namespaceKey: String = "legacy"): Flow<DownloadRecordEntity?>

    @Query("SELECT * FROM download_records WHERE namespaceKey = :namespaceKey AND romId = :romId AND fileId = :fileId LIMIT 1")
    suspend fun getByIds(romId: Int, fileId: Int, namespaceKey: String = "legacy"): DownloadRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DownloadRecordEntity>)

    @Query("DELETE FROM download_records WHERE namespaceKey = :namespaceKey AND romId = :romId AND fileId = :fileId")
    suspend fun delete(romId: Int, fileId: Int, namespaceKey: String = "legacy")

    @Query("DELETE FROM download_records")
    suspend fun deleteAll()
}

@Dao
interface SaveStateDao {
    @Query("SELECT * FROM save_states WHERE progressScope = :progressScope AND romId = :romId ORDER BY slot ASC")
    fun observeByRomId(romId: Int, progressScope: String = "legacy"): Flow<List<SaveStateEntity>>

    @Query("SELECT * FROM save_states ORDER BY romId ASC, slot ASC")
    suspend fun listAll(): List<SaveStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SaveStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SaveStateEntity>)

    @Query("DELETE FROM save_states WHERE progressScope = :progressScope AND romId = :romId AND slot = :slot")
    suspend fun delete(romId: Int, slot: Int, progressScope: String = "legacy")

    @Query("DELETE FROM save_states")
    suspend fun deleteAll()
}

@Dao
interface ServerProfileDao {
    @Query("SELECT * FROM server_profiles WHERE isActive = 1 ORDER BY updatedAt DESC LIMIT 1")
    fun observeActive(): Flow<ServerProfileEntity?>

    @Query("SELECT * FROM server_profiles WHERE isActive = 1 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActive(): ServerProfileEntity?

    @Query("SELECT * FROM server_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServerProfileEntity?

    @Query("SELECT * FROM server_profiles ORDER BY updatedAt DESC")
    suspend fun listAll(): List<ServerProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ServerProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ServerProfileEntity>)

    @Query("UPDATE server_profiles SET isActive = CASE WHEN id = :id THEN 1 ELSE 0 END, updatedAt = :updatedAt")
    suspend fun setActiveOnly(id: String, updatedAt: String)

    @Query("DELETE FROM server_profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM server_profiles")
    suspend fun deleteAll()
}

@Database(
    entities = [
        DownloadedRomEntity::class,
        DownloadRecordEntity::class,
        SaveStateEntity::class,
        ServerProfileEntity::class,
        TouchLayoutProfileEntity::class,
        HardwareBindingProfileEntity::class,
        CachedPlatformEntity::class,
        CachedRomEntity::class,
        CachedCollectionEntity::class,
        CachedCollectionRomEntity::class,
        CachedHomeEntryEntity::class,
        ProfileCacheStateEntity::class,
        PendingRemoteActionEntity::class,
        MediaCacheEntryEntity::class,
        GameSyncJournalEntity::class,
        SaveStateSyncJournalEntity::class,
        RecoveryStateEntity::class,
        ProgressNamespaceRow::class, ProgressContentRow::class, ProgressOperationRow::class,
        ProgressHeadRow::class, ProgressOutboxRow::class, ProgressQuarantineRow::class,
        ProgressHistoryRow::class, ProgressHistoryIndexRow::class,
        PlaySessionEntity::class, SaveStateLabelEntity::class,
    ],
    version = 14,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadedRomDao(): DownloadedRomDao
    abstract fun downloadRecordDao(): DownloadRecordDao
    abstract fun saveStateDao(): SaveStateDao
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun touchLayoutProfileDao(): TouchLayoutProfileDao
    abstract fun hardwareBindingProfileDao(): HardwareBindingProfileDao
    abstract fun cachedPlatformDao(): CachedPlatformDao
    abstract fun cachedRomDao(): CachedRomDao
    abstract fun cachedCollectionDao(): CachedCollectionDao
    abstract fun cachedCollectionRomDao(): CachedCollectionRomDao
    abstract fun cachedHomeEntryDao(): CachedHomeEntryDao
    abstract fun profileCacheStateDao(): ProfileCacheStateDao
    abstract fun pendingRemoteActionDao(): PendingRemoteActionDao
    abstract fun mediaCacheEntryDao(): MediaCacheEntryDao
    abstract fun gameSyncJournalDao(): GameSyncJournalDao
    abstract fun saveStateSyncJournalDao(): SaveStateSyncJournalDao
    abstract fun recoveryStateDao(): RecoveryStateDao
    abstract fun progressDao(): ProgressDao
    abstract fun playDataDao(): PlayDataDao

    companion object {
        /** Local play data (docs/play-data-local.md): play sessions, save-state names, RomM metadata cache. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `play_sessions` (`sessionId` TEXT NOT NULL, `namespaceId` TEXT NOT NULL, `contentId` TEXT NOT NULL, `serverKey` TEXT NOT NULL, `accountId` TEXT NOT NULL, `romId` INTEGER NOT NULL, `fileId` INTEGER, `platformSlug` TEXT, `deviceId` TEXT NOT NULL, `coreId` TEXT, `startedAtEpochMs` INTEGER NOT NULL, `endedAtEpochMs` INTEGER, `lastHeartbeatEpochMs` INTEGER NOT NULL, `activeDurationMs` INTEGER NOT NULL, `endReason` TEXT, PRIMARY KEY(`sessionId`))")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_play_sessions_serverKey_accountId_startedAtEpochMs` ON `play_sessions` (`serverKey`, `accountId`, `startedAtEpochMs`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_play_sessions_endedAtEpochMs` ON `play_sessions` (`endedAtEpochMs`)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `save_state_labels` (`namespaceId` TEXT NOT NULL, `contentId` TEXT NOT NULL, `checkpointId` TEXT NOT NULL, `label` TEXT NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`namespaceId`, `contentId`, `checkpointId`))")
                // Existing rows keep their catalogue data; metadata fills in on the next refresh.
                database.execSQL("ALTER TABLE `cached_roms` ADD COLUMN `metadataJson` TEXT NOT NULL DEFAULT '{}'")
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE progress_v2_operations ADD COLUMN localSequence INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE progress_v2_operations SET localSequence = rowid")
                database.execSQL("CREATE UNIQUE INDEX index_progress_v2_operations_namespaceId_contentId_localSequence ON progress_v2_operations(namespaceId, contentId, localSequence)")
                database.execSQL("CREATE TABLE IF NOT EXISTS progress_v2_history (sequence INTEGER NOT NULL, namespaceId TEXT NOT NULL, contentId TEXT NOT NULL, checkpointId TEXT NOT NULL, operationId TEXT NOT NULL, recordedAt INTEGER NOT NULL, manual INTEGER NOT NULL, pinned INTEGER NOT NULL, evicted INTEGER NOT NULL, pinOverride INTEGER, PRIMARY KEY(namespaceId, contentId, checkpointId))")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_progress_v2_history_namespaceId_contentId_checkpointId ON progress_v2_history(namespaceId, contentId, checkpointId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_progress_v2_history_namespaceId_contentId_sequence ON progress_v2_history(namespaceId, contentId, sequence)")
                database.execSQL("CREATE TABLE IF NOT EXISTS progress_v2_history_index (namespaceId TEXT NOT NULL, contentId TEXT NOT NULL, throughRowId INTEGER NOT NULL, PRIMARY KEY(namespaceId, contentId))")
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Older versions set these flags after partial screen refreshes. Keep all
                // cached content, but require one complete inventory to certify coverage.
                database.execSQL("UPDATE profile_cache_state SET catalogReady = 0, mediaReady = 0, isRefreshing = 0, lastFullSyncAtEpochMs = NULL, lastMediaSyncAtEpochMs = NULL")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                for (table in listOf("download_records", "downloaded_roms")) {
                    database.execSQL("ALTER TABLE $table ADD COLUMN namespaceKey TEXT NOT NULL DEFAULT 'legacy'")
                    database.query("SELECT romId, fileId, ownerBaseUrl, ownerAccountId FROM $table").use { rows ->
                        while (rows.moveToNext()) {
                            if (!rows.isNull(2) && !rows.isNull(3)) {
                                val key = io.github.bandoracer.rommio.model.InstallIdentity.namespace(rows.getString(2), rows.getInt(3))
                                database.execSQL("UPDATE $table SET namespaceKey = ? WHERE romId = ? AND fileId = ?", arrayOf(key, rows.getInt(0), rows.getInt(1)))
                            }
                        }
                    }
                    if (table == "downloaded_roms") database.execSQL("ALTER TABLE $table ADD COLUMN progressScope TEXT NOT NULL DEFAULT 'legacy'")
                    rebuildScopedKey(database, table, listOf("namespaceKey", "romId", "fileId"))
                }
                // Existing save ownership/content cannot be inferred from an installed ROM alone.
                for ((table, keys) in mapOf(
                    "save_states" to listOf("progressScope", "romId", "slot"),
                    "game_sync_journal" to listOf("progressScope", "profileId", "romId", "fileId"),
                    "save_state_sync_journal" to listOf("progressScope", "profileId", "romId", "fileId", "slot"),
                    "recovery_states" to listOf("progressScope", "romId", "fileId", "entryId"),
                )) {
                    database.execSQL("ALTER TABLE $table ADD COLUMN progressScope TEXT NOT NULL DEFAULT 'legacy'")
                    rebuildScopedKey(database, table, keys)
                }
            }
        }

        private fun rebuildScopedKey(database: SupportSQLiteDatabase, table: String, keys: List<String>) {
            val columns = mutableListOf<String>()
            val definitions = mutableListOf<String>()
            database.query("PRAGMA table_info(`$table`)").use { rows ->
                while (rows.moveToNext()) {
                    val name = rows.getString(1); columns += "`$name`"
                    // New tables match Room's schema; migration-only defaults are intentionally dropped.
                    definitions += "`$name` ${rows.getString(2)}" + if (rows.getInt(3) == 1) " NOT NULL" else ""
                }
            }
            database.execSQL("CREATE TABLE `${table}_scoped` (${definitions.joinToString()}, PRIMARY KEY (${keys.joinToString { "`$it`" }}))")
            database.execSQL("INSERT INTO `${table}_scoped` (${columns.joinToString()}) SELECT ${columns.joinToString()} FROM `$table`")
            database.execSQL("DROP TABLE `$table`")
            database.execSQL("ALTER TABLE `${table}_scoped` RENAME TO `$table`")
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Preserve unowned rows exactly. Account binding cannot be inferred during schema migration.
                for (table in listOf("download_records", "downloaded_roms")) {
                    database.execSQL("ALTER TABLE $table ADD COLUMN ownerProfileId TEXT")
                    database.execSQL("ALTER TABLE $table ADD COLUMN ownerBaseUrl TEXT")
                    database.execSQL("ALTER TABLE $table ADD COLUMN ownerAccountId INTEGER")
                    database.execSQL("ALTER TABLE $table ADD COLUMN contentSha256 TEXT")
                }
                database.execSQL("ALTER TABLE download_records ADD COLUMN attemptId TEXT")
                database.execSQL("ALTER TABLE download_records ADD COLUMN destinationRelativePath TEXT")
                database.execSQL("ALTER TABLE download_records ADD COLUMN retryAfterEpochMs INTEGER")
                database.execSQL("ALTER TABLE download_records ADD COLUMN parkedForAuthentication INTEGER")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS progress_v2_namespaces (namespaceId TEXT NOT NULL PRIMARY KEY, serverKey TEXT NOT NULL, accountId TEXT NOT NULL)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_progress_v2_namespaces_serverKey_accountId ON progress_v2_namespaces(serverKey, accountId)")
                database.execSQL("CREATE TABLE IF NOT EXISTS progress_v2_contents (contentId TEXT NOT NULL PRIMARY KEY, namespaceId TEXT NOT NULL, bindingKey TEXT NOT NULL)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_progress_v2_contents_namespaceId_bindingKey ON progress_v2_contents(namespaceId, bindingKey)")
                database.execSQL("CREATE TABLE IF NOT EXISTS progress_v2_operations (namespaceId TEXT NOT NULL, contentId TEXT NOT NULL, operationId TEXT NOT NULL, payload BLOB NOT NULL, payloadHash TEXT NOT NULL, PRIMARY KEY(namespaceId, contentId, operationId))")
                database.execSQL("CREATE TABLE IF NOT EXISTS progress_v2_heads (namespaceId TEXT NOT NULL, contentId TEXT NOT NULL, reference TEXT NOT NULL, operationId TEXT NOT NULL, PRIMARY KEY(namespaceId, contentId, reference, operationId))")
                database.execSQL("CREATE TABLE IF NOT EXISTS progress_v2_outbox (namespaceId TEXT NOT NULL, contentId TEXT NOT NULL, operationId TEXT NOT NULL, payloadHash TEXT NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL, leaseId TEXT, leaseUntil INTEGER, nextAttempt INTEGER NOT NULL, lastError TEXT, PRIMARY KEY(namespaceId, contentId, operationId))")
                database.execSQL("CREATE TABLE IF NOT EXISTS progress_v2_quarantine (namespaceId TEXT NOT NULL, contentId TEXT NOT NULL, payloadHash TEXT NOT NULL, payload BLOB NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(namespaceId, contentId, payloadHash))")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS server_profiles (
                      id TEXT NOT NULL PRIMARY KEY,
                      label TEXT NOT NULL,
                      baseUrl TEXT NOT NULL,
                      edgeAuthMode TEXT NOT NULL,
                      originAuthMode TEXT NOT NULL,
                      capabilitiesJson TEXT NOT NULL,
                      serverAccessJson TEXT NOT NULL,
                      sessionStateJson TEXT NOT NULL,
                      isActive INTEGER NOT NULL,
                      status TEXT NOT NULL,
                      lastValidationAt TEXT,
                      createdAt TEXT NOT NULL,
                      updatedAt TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_server_profiles_isActive_updatedAt ON server_profiles(isActive, updatedAt)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS touch_layout_profiles (
                      platformFamilyId TEXT NOT NULL PRIMARY KEY,
                      presetId TEXT NOT NULL,
                      layoutJson TEXT NOT NULL,
                      opacity REAL NOT NULL,
                      globalScale REAL NOT NULL,
                      leftHanded INTEGER NOT NULL,
                      updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS hardware_binding_profiles (
                      platformFamilyId TEXT NOT NULL PRIMARY KEY,
                      controllerTypeId INTEGER,
                      deadzone REAL NOT NULL,
                      bindingsJson TEXT NOT NULL,
                      updatedAtEpochMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS download_records (
                      romId INTEGER NOT NULL,
                      fileId INTEGER NOT NULL,
                      romName TEXT NOT NULL,
                      platformSlug TEXT NOT NULL,
                      fileName TEXT NOT NULL,
                      fileSizeBytes INTEGER NOT NULL,
                      workId TEXT,
                      status TEXT NOT NULL,
                      progressPercent INTEGER NOT NULL,
                      bytesDownloaded INTEGER NOT NULL,
                      totalBytes INTEGER NOT NULL,
                      localPath TEXT,
                      lastError TEXT,
                      enqueuedAtEpochMs INTEGER NOT NULL,
                      startedAtEpochMs INTEGER,
                      completedAtEpochMs INTEGER,
                      updatedAtEpochMs INTEGER NOT NULL,
                      PRIMARY KEY(romId, fileId)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_platforms (
                      profileId TEXT NOT NULL,
                      platformId INTEGER NOT NULL,
                      slug TEXT NOT NULL,
                      name TEXT NOT NULL,
                      fsSlug TEXT NOT NULL,
                      urlLogo TEXT,
                      romCount INTEGER NOT NULL,
                      logoCachedUri TEXT,
                      updatedAtEpochMs INTEGER NOT NULL,
                      PRIMARY KEY(profileId, platformId)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_roms (
                      profileId TEXT NOT NULL,
                      romId INTEGER NOT NULL,
                      name TEXT,
                      summary TEXT,
                      platformId INTEGER NOT NULL,
                      platformName TEXT NOT NULL,
                      platformSlug TEXT NOT NULL,
                      fsName TEXT NOT NULL,
                      filesJson TEXT NOT NULL,
                      siblingsJson TEXT NOT NULL,
                      urlCover TEXT,
                      coverCachedUri TEXT,
                      updatedAtEpochMs INTEGER NOT NULL,
                      PRIMARY KEY(profileId, romId)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_collections (
                      profileId TEXT NOT NULL,
                      kind TEXT NOT NULL,
                      collectionId TEXT NOT NULL,
                      name TEXT NOT NULL,
                      description TEXT NOT NULL,
                      romCount INTEGER NOT NULL,
                      pathCoverSmall TEXT,
                      pathCoverLarge TEXT,
                      pathCoversSmallJson TEXT NOT NULL,
                      pathCoversLargeJson TEXT NOT NULL,
                      coverCachedUri TEXT,
                      isPublic INTEGER NOT NULL,
                      isFavorite INTEGER NOT NULL,
                      isVirtual INTEGER NOT NULL,
                      isSmart INTEGER NOT NULL,
                      ownerUsername TEXT,
                      updatedAtEpochMs INTEGER NOT NULL,
                      PRIMARY KEY(profileId, kind, collectionId)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_collection_roms (
                      profileId TEXT NOT NULL,
                      kind TEXT NOT NULL,
                      collectionId TEXT NOT NULL,
                      romId INTEGER NOT NULL,
                      position INTEGER NOT NULL,
                      PRIMARY KEY(profileId, kind, collectionId, romId)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_home_entries (
                      profileId TEXT NOT NULL,
                      feedType TEXT NOT NULL,
                      position INTEGER NOT NULL,
                      romId INTEGER,
                      collectionKind TEXT,
                      collectionId TEXT,
                      PRIMARY KEY(profileId, feedType, position)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS profile_cache_state (
                      profileId TEXT NOT NULL PRIMARY KEY,
                      lastFullSyncAtEpochMs INTEGER,
                      lastMediaSyncAtEpochMs INTEGER,
                      catalogReady INTEGER NOT NULL,
                      mediaReady INTEGER NOT NULL,
                      isRefreshing INTEGER NOT NULL,
                      lastError TEXT
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_remote_actions (
                      profileId TEXT NOT NULL,
                      actionType TEXT NOT NULL,
                      dedupeKey TEXT NOT NULL,
                      payloadJson TEXT NOT NULL,
                      status TEXT NOT NULL,
                      createdAtEpochMs INTEGER NOT NULL,
                      updatedAtEpochMs INTEGER NOT NULL,
                      lastError TEXT,
                      PRIMARY KEY(profileId, actionType, dedupeKey)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS media_cache_entries (
                      profileId TEXT NOT NULL,
                      sourceUrl TEXT NOT NULL,
                      localPath TEXT NOT NULL,
                      category TEXT NOT NULL,
                      pinned INTEGER NOT NULL,
                      sizeBytes INTEGER NOT NULL,
                      lastAccessEpochMs INTEGER NOT NULL,
                      updatedAtEpochMs INTEGER NOT NULL,
                      PRIMARY KEY(profileId, sourceUrl)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS game_sync_journal (
                      profileId TEXT NOT NULL,
                      romId INTEGER NOT NULL,
                      fileId INTEGER NOT NULL,
                      lastSyncedSramHash TEXT,
                      lastSyncedResumeHash TEXT,
                      remoteSramHash TEXT,
                      remoteResumeHash TEXT,
                      remoteDeviceId TEXT,
                      remoteDeviceName TEXT,
                      remoteSessionActive INTEGER NOT NULL,
                      remoteSessionHeartbeatEpochMs INTEGER,
                      remoteContinuityUpdatedAtEpochMs INTEGER,
                      remoteContinuityAvailable INTEGER NOT NULL,
                      pendingContinuityUpload INTEGER NOT NULL,
                      lastSuccessfulSyncAtEpochMs INTEGER,
                      lastSyncAttemptAtEpochMs INTEGER,
                      lastSyncNote TEXT,
                      lastError TEXT,
                      PRIMARY KEY(profileId, romId, fileId)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS save_state_sync_journal (
                      profileId TEXT NOT NULL,
                      romId INTEGER NOT NULL,
                      fileId INTEGER NOT NULL,
                      slot INTEGER NOT NULL,
                      label TEXT NOT NULL,
                      localPath TEXT,
                      localHash TEXT,
                      localUpdatedAtEpochMs INTEGER,
                      remoteHash TEXT,
                      remoteUpdatedAtEpochMs INTEGER,
                      deleted INTEGER NOT NULL,
                      pendingUpload INTEGER NOT NULL,
                      pendingDelete INTEGER NOT NULL,
                      lastSyncedAtEpochMs INTEGER,
                      PRIMARY KEY(profileId, romId, fileId, slot)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recovery_states (
                      romId INTEGER NOT NULL,
                      fileId INTEGER NOT NULL,
                      entryId TEXT NOT NULL,
                      label TEXT NOT NULL,
                      origin TEXT NOT NULL,
                      localPath TEXT NOT NULL,
                      remoteFileName TEXT NOT NULL,
                      localHash TEXT,
                      remoteHash TEXT,
                      ringIndex INTEGER,
                      preserved INTEGER NOT NULL,
                      sourceDeviceName TEXT,
                      capturedAtEpochMs INTEGER NOT NULL,
                      lastSyncedAtEpochMs INTEGER,
                      PRIMARY KEY(romId, fileId, entryId)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE save_state_sync_journal ADD COLUMN sourceDeviceName TEXT",
                )
            }
        }
    }
}
