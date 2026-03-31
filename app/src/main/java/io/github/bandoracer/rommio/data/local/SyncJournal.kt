package io.github.bandoracer.rommio.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "game_sync_journal",
    primaryKeys = ["profileId", "romId", "fileId"],
)
data class GameSyncJournalEntity(
    val profileId: String,
    val romId: Int,
    val fileId: Int,
    val lastSyncedSramHash: String? = null,
    val lastSyncedResumeHash: String? = null,
    val remoteSramHash: String? = null,
    val remoteResumeHash: String? = null,
    val remoteDeviceId: String? = null,
    val remoteDeviceName: String? = null,
    val remoteSessionActive: Boolean = false,
    val remoteSessionHeartbeatEpochMs: Long? = null,
    val remoteContinuityUpdatedAtEpochMs: Long? = null,
    val remoteContinuityAvailable: Boolean = false,
    val pendingContinuityUpload: Boolean = false,
    val lastSuccessfulSyncAtEpochMs: Long? = null,
    val lastSyncAttemptAtEpochMs: Long? = null,
    val lastSyncNote: String? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "save_state_sync_journal",
    primaryKeys = ["profileId", "romId", "fileId", "slot"],
)
data class SaveStateSyncJournalEntity(
    val profileId: String,
    val romId: Int,
    val fileId: Int,
    val slot: Int,
    val label: String,
    val localPath: String? = null,
    val localHash: String? = null,
    val localUpdatedAtEpochMs: Long? = null,
    val remoteHash: String? = null,
    val remoteUpdatedAtEpochMs: Long? = null,
    val sourceDeviceName: String? = null,
    val deleted: Boolean = false,
    val pendingUpload: Boolean = false,
    val pendingDelete: Boolean = false,
    val lastSyncedAtEpochMs: Long? = null,
)

@Dao
interface GameSyncJournalDao {
    @Query("SELECT * FROM game_sync_journal WHERE profileId = :profileId AND romId = :romId AND fileId = :fileId LIMIT 1")
    fun observeByKey(profileId: String, romId: Int, fileId: Int): Flow<GameSyncJournalEntity?>

    @Query("SELECT * FROM game_sync_journal WHERE profileId = :profileId AND romId = :romId AND fileId = :fileId LIMIT 1")
    suspend fun getByKey(profileId: String, romId: Int, fileId: Int): GameSyncJournalEntity?

    @Query("SELECT * FROM game_sync_journal ORDER BY profileId ASC, romId ASC, fileId ASC")
    suspend fun listAll(): List<GameSyncJournalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GameSyncJournalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<GameSyncJournalEntity>)

    @Query("DELETE FROM game_sync_journal WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: String)

    @Query("DELETE FROM game_sync_journal")
    suspend fun deleteAll()
}

@Dao
interface SaveStateSyncJournalDao {
    @Query("SELECT * FROM save_state_sync_journal WHERE profileId = :profileId AND romId = :romId AND fileId = :fileId ORDER BY slot ASC")
    fun observeByGame(profileId: String, romId: Int, fileId: Int): Flow<List<SaveStateSyncJournalEntity>>

    @Query("SELECT * FROM save_state_sync_journal WHERE profileId = :profileId AND romId = :romId AND fileId = :fileId ORDER BY slot ASC")
    suspend fun listByGame(profileId: String, romId: Int, fileId: Int): List<SaveStateSyncJournalEntity>

    @Query("SELECT * FROM save_state_sync_journal ORDER BY profileId ASC, romId ASC, fileId ASC, slot ASC")
    suspend fun listAll(): List<SaveStateSyncJournalEntity>

    @Query("SELECT * FROM save_state_sync_journal WHERE profileId = :profileId AND romId = :romId AND fileId = :fileId AND slot = :slot LIMIT 1")
    suspend fun getByKey(profileId: String, romId: Int, fileId: Int, slot: Int): SaveStateSyncJournalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SaveStateSyncJournalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SaveStateSyncJournalEntity>)

    @Query("DELETE FROM save_state_sync_journal WHERE profileId = :profileId AND romId = :romId AND fileId = :fileId AND slot = :slot")
    suspend fun deleteByKey(profileId: String, romId: Int, fileId: Int, slot: Int)

    @Query("DELETE FROM save_state_sync_journal WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: String)

    @Query("DELETE FROM save_state_sync_journal")
    suspend fun deleteAll()
}
