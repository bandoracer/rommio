package io.github.bandoracer.rommio.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.bandoracer.rommio.model.BrowsableGameState
import io.github.bandoracer.rommio.model.BrowsableGameStateOrigin
import io.github.bandoracer.rommio.model.BrowsableGameStateKind
import io.github.bandoracer.rommio.model.GameStateDeletePolicy
import io.github.bandoracer.rommio.model.RecoveryStateOrigin
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "recovery_states",
    primaryKeys = ["romId", "fileId", "entryId"],
)
data class RecoveryStateEntity(
    val romId: Int,
    val fileId: Int,
    val entryId: String,
    val label: String,
    val origin: String,
    val localPath: String,
    val remoteFileName: String,
    val localHash: String? = null,
    val remoteHash: String? = null,
    val ringIndex: Int? = null,
    val preserved: Boolean = false,
    val sourceDeviceName: String? = null,
    val capturedAtEpochMs: Long,
    val lastSyncedAtEpochMs: Long? = null,
)

@Dao
interface RecoveryStateDao {
    @Query("SELECT * FROM recovery_states WHERE romId = :romId AND fileId = :fileId ORDER BY capturedAtEpochMs DESC, entryId ASC")
    fun observeByGame(romId: Int, fileId: Int): Flow<List<RecoveryStateEntity>>

    @Query("SELECT * FROM recovery_states WHERE romId = :romId AND fileId = :fileId ORDER BY capturedAtEpochMs DESC, entryId ASC")
    suspend fun listByGame(romId: Int, fileId: Int): List<RecoveryStateEntity>

    @Query("SELECT * FROM recovery_states ORDER BY romId ASC, fileId ASC, capturedAtEpochMs DESC, entryId ASC")
    suspend fun listAll(): List<RecoveryStateEntity>

    @Query("SELECT * FROM recovery_states WHERE romId = :romId AND fileId = :fileId AND entryId = :entryId LIMIT 1")
    suspend fun getByKey(romId: Int, fileId: Int, entryId: String): RecoveryStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecoveryStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<RecoveryStateEntity>)

    @Query("DELETE FROM recovery_states WHERE romId = :romId AND fileId = :fileId")
    suspend fun deleteByGame(romId: Int, fileId: Int)

    @Query("DELETE FROM recovery_states WHERE romId = :romId AND fileId = :fileId AND entryId = :entryId")
    suspend fun deleteByKey(romId: Int, fileId: Int, entryId: String)

    @Query("DELETE FROM recovery_states")
    suspend fun deleteAll()
}

internal fun RecoveryStateEntity.toBrowsableGameState(): BrowsableGameState {
    val kind = when (runCatching { RecoveryStateOrigin.valueOf(origin) }.getOrDefault(RecoveryStateOrigin.LEGACY_IMPORT)) {
        RecoveryStateOrigin.AUTO_HISTORY -> BrowsableGameStateKind.RECOVERY_HISTORY
        RecoveryStateOrigin.LEGACY_IMPORT -> BrowsableGameStateKind.IMPORTED_CLOUD
    }
    return BrowsableGameState(
        id = entryId,
        kind = kind,
        label = label,
        localPath = localPath,
        updatedAtEpochMs = capturedAtEpochMs,
        ringIndex = ringIndex,
        preserved = preserved,
        sourceDeviceName = sourceDeviceName,
        originType = when (kind) {
            BrowsableGameStateKind.RECOVERY_HISTORY -> BrowsableGameStateOrigin.AUTO_SNAPSHOT
            BrowsableGameStateKind.IMPORTED_CLOUD -> BrowsableGameStateOrigin.IMPORTED_PLAYABLE
            BrowsableGameStateKind.MANUAL_SLOT -> BrowsableGameStateOrigin.MANUAL_SLOT
        },
        deletePolicy = when (kind) {
            BrowsableGameStateKind.RECOVERY_HISTORY -> GameStateDeletePolicy.NONE
            BrowsableGameStateKind.IMPORTED_CLOUD -> GameStateDeletePolicy.LOCAL_ONLY
            BrowsableGameStateKind.MANUAL_SLOT -> GameStateDeletePolicy.LOCAL_AND_REMOTE
        },
    )
}
