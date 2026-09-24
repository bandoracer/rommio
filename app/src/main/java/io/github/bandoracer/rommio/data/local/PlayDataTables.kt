package io.github.bandoracer.rommio.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One launch of one game on this device (docs/play-data-local.md, "Play sessions"). Local only:
 * never synced, never part of a checkpoint or a migration bundle.
 */
@Entity(
    tableName = "play_sessions",
    indices = [
        Index(value = ["serverKey", "accountId", "startedAtEpochMs"]),
        Index(value = ["endedAtEpochMs"]),
    ],
)
data class PlaySessionEntity(
    /** The progress session id: the same id every checkpoint of this launch carries. */
    @PrimaryKey val sessionId: String,
    /** Progress namespace and content generation, as ProgressStore identifies the game. */
    val namespaceId: String,
    val contentId: String,
    /** Server/account binding of that namespace, for filtering by the signed-in account. */
    val serverKey: String,
    val accountId: String,
    val romId: Long,
    /** First file of the content binding (disc 0), or null when the binding has none. */
    val fileId: Long?,
    val platformSlug: String?,
    val deviceId: String,
    val coreId: String?,
    val startedAtEpochMs: Long,
    /** Null while the session is open. */
    val endedAtEpochMs: Long?,
    /** Last time the recorder saw this session alive; closes abandoned sessions after process death. */
    val lastHeartbeatEpochMs: Long,
    /** Unpaused, foreground play only. */
    val activeDurationMs: Long,
    /** resume | state | quit | background; null while open. */
    val endReason: String?,
)

/** Optional user name for one save state, keyed by the checkpoint that holds it. Local only. */
@Entity(tableName = "save_state_labels", primaryKeys = ["namespaceId", "contentId", "checkpointId"])
data class SaveStateLabelEntity(
    val namespaceId: String,
    val contentId: String,
    val checkpointId: String,
    val label: String,
    val updatedAtEpochMs: Long,
)

@Dao
interface PlayDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(entity: PlaySessionEntity)

    @Query("SELECT * FROM play_sessions WHERE sessionId = :sessionId")
    suspend fun session(sessionId: String): PlaySessionEntity?

    @Query("SELECT * FROM play_sessions WHERE serverKey = :serverKey AND accountId = :accountId ORDER BY startedAtEpochMs DESC")
    suspend fun sessionsFor(serverKey: String, accountId: String): List<PlaySessionEntity>

    @Query("SELECT * FROM play_sessions WHERE endedAtEpochMs IS NULL")
    suspend fun openSessions(): List<PlaySessionEntity>

    @Query("UPDATE play_sessions SET lastHeartbeatEpochMs = :heartbeat, activeDurationMs = :activeMs WHERE sessionId = :sessionId AND endedAtEpochMs IS NULL")
    suspend fun heartbeat(sessionId: String, heartbeat: Long, activeMs: Long)

    @Query("UPDATE play_sessions SET endedAtEpochMs = :endedAt, lastHeartbeatEpochMs = :endedAt, activeDurationMs = :activeMs, endReason = :reason WHERE sessionId = :sessionId AND endedAtEpochMs IS NULL")
    suspend fun finish(sessionId: String, endedAt: Long, activeMs: Long, reason: String): Int

    @Query("SELECT COUNT(*) FROM play_sessions")
    fun observeSessionCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLabel(entity: SaveStateLabelEntity)

    @Query("DELETE FROM save_state_labels WHERE namespaceId = :namespaceId AND contentId = :contentId AND checkpointId = :checkpointId")
    suspend fun deleteLabel(namespaceId: String, contentId: String, checkpointId: String)

    @Query("SELECT * FROM save_state_labels WHERE namespaceId = :namespaceId AND contentId = :contentId")
    suspend fun labelsFor(namespaceId: String, contentId: String): List<SaveStateLabelEntity>

    @Query("SELECT * FROM save_state_labels")
    fun observeLabels(): Flow<List<SaveStateLabelEntity>>
}
