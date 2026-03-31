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

    @Query("SELECT * FROM downloaded_roms WHERE romId = :romId AND fileId = :fileId LIMIT 1")
    suspend fun getByIds(romId: Int, fileId: Int): DownloadedRomEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadedRomEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DownloadedRomEntity>)

    @Query("DELETE FROM downloaded_roms WHERE romId = :romId AND fileId = :fileId")
    suspend fun delete(romId: Int, fileId: Int)

    @Query("DELETE FROM downloaded_roms")
    suspend fun deleteAll()
}

@Dao
interface DownloadRecordDao {
    @Query("SELECT * FROM download_records ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<DownloadRecordEntity>>

    @Query("SELECT * FROM download_records")
    suspend fun listAll(): List<DownloadRecordEntity>

    @Query("SELECT * FROM download_records WHERE romId = :romId AND fileId = :fileId LIMIT 1")
    fun observeByIds(romId: Int, fileId: Int): Flow<DownloadRecordEntity?>

    @Query("SELECT * FROM download_records WHERE romId = :romId AND fileId = :fileId LIMIT 1")
    suspend fun getByIds(romId: Int, fileId: Int): DownloadRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DownloadRecordEntity>)

    @Query("DELETE FROM download_records WHERE romId = :romId AND fileId = :fileId")
    suspend fun delete(romId: Int, fileId: Int)

    @Query("DELETE FROM download_records")
    suspend fun deleteAll()
}

@Dao
interface SaveStateDao {
    @Query("SELECT * FROM save_states WHERE romId = :romId ORDER BY slot ASC")
    fun observeByRomId(romId: Int): Flow<List<SaveStateEntity>>

    @Query("SELECT * FROM save_states ORDER BY romId ASC, slot ASC")
    suspend fun listAll(): List<SaveStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SaveStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SaveStateEntity>)

    @Query("DELETE FROM save_states WHERE romId = :romId AND slot = :slot")
    suspend fun delete(romId: Int, slot: Int)

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
    ],
    version = 8,
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

    companion object {
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
