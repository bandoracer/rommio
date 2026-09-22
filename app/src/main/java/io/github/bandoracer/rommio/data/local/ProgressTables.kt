package io.github.bandoracer.rommio.data.local

import androidx.room.*

@Entity(tableName = "progress_v2_namespaces", indices = [Index(value = ["serverKey", "accountId"], unique = true)])
data class ProgressNamespaceRow(@PrimaryKey val namespaceId: String, val serverKey: String, val accountId: String)

@Entity(tableName = "progress_v2_contents", indices = [Index(value = ["namespaceId", "bindingKey"], unique = true)])
data class ProgressContentRow(@PrimaryKey val contentId: String, val namespaceId: String, val bindingKey: String)

@Entity(tableName = "progress_v2_operations", primaryKeys = ["namespaceId", "contentId", "operationId"], indices = [Index(value = ["namespaceId", "contentId", "localSequence"], unique = true)])
data class ProgressOperationRow(val namespaceId: String, val contentId: String, val operationId: String, val payload: ByteArray, val payloadHash: String, @ColumnInfo(defaultValue = "0") val localSequence: Long = 0)

@Entity(tableName = "progress_v2_heads", primaryKeys = ["namespaceId", "contentId", "reference", "operationId"])
data class ProgressHeadRow(val namespaceId: String, val contentId: String, val reference: String, val operationId: String)

@Entity(tableName = "progress_v2_outbox", primaryKeys = ["namespaceId", "contentId", "operationId"])
data class ProgressOutboxRow(
    val namespaceId: String, val contentId: String, val operationId: String, val payloadHash: String,
    val status: String, val attempts: Int = 0, val leaseId: String? = null, val leaseUntil: Long? = null,
    val nextAttempt: Long = 0, val lastError: String? = null,
)

@Entity(tableName = "progress_v2_quarantine", primaryKeys = ["namespaceId", "contentId", "payloadHash"])
data class ProgressQuarantineRow(val namespaceId: String, val contentId: String, val payloadHash: String, val payload: ByteArray, val reason: String)

/** Derived local recovery index; immutable operation envelopes remain authoritative. */
@Entity(tableName = "progress_v2_history", primaryKeys = ["namespaceId", "contentId", "checkpointId"], indices = [
    Index(value = ["namespaceId", "contentId", "checkpointId"], unique = true),
    Index(value = ["namespaceId", "contentId", "sequence"]),
])
data class ProgressHistoryRow(
    val sequence: Long,
    val namespaceId: String, val contentId: String, val checkpointId: String, val operationId: String,
    val recordedAt: Long, val manual: Boolean, val pinned: Boolean = false, val evicted: Boolean = false,
    val pinOverride: Boolean? = null,
)

@Entity(tableName = "progress_v2_history_index", primaryKeys = ["namespaceId", "contentId"])
data class ProgressHistoryIndexRow(val namespaceId: String, val contentId: String, val throughRowId: Long)

data class ProgressSequencedOperation(val localRowId: Long, @Embedded val operation: ProgressOperationRow)

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress_v2_namespaces ORDER BY namespaceId") suspend fun exportNamespaces(): List<ProgressNamespaceRow>
    @Query("SELECT * FROM progress_v2_contents ORDER BY namespaceId, contentId") suspend fun exportContents(): List<ProgressContentRow>
    @Query("SELECT * FROM progress_v2_operations ORDER BY namespaceId, contentId, localSequence") suspend fun exportOperations(): List<ProgressOperationRow>
    @Query("SELECT * FROM progress_v2_heads ORDER BY namespaceId, contentId, reference, operationId") suspend fun exportHeads(): List<ProgressHeadRow>
    @Query("SELECT * FROM progress_v2_outbox ORDER BY namespaceId, contentId, operationId") suspend fun exportOutbox(): List<ProgressOutboxRow>
    @Query("SELECT * FROM progress_v2_quarantine ORDER BY namespaceId, contentId, payloadHash") suspend fun exportQuarantine(): List<ProgressQuarantineRow>
    @Query("SELECT * FROM progress_v2_history ORDER BY namespaceId, contentId, sequence") suspend fun exportHistory(): List<ProgressHistoryRow>
    @Query("SELECT * FROM progress_v2_history_index ORDER BY namespaceId, contentId") suspend fun exportHistoryIndex(): List<ProgressHistoryIndexRow>
    @Query("DELETE FROM progress_v2_namespaces") suspend fun clearNamespaces()
    @Query("DELETE FROM progress_v2_contents") suspend fun clearContents()
    @Query("DELETE FROM progress_v2_operations") suspend fun clearOperations()
    @Query("DELETE FROM progress_v2_heads") suspend fun clearHeads()
    @Query("DELETE FROM progress_v2_outbox") suspend fun clearOutbox()
    @Query("DELETE FROM progress_v2_quarantine") suspend fun clearQuarantine()
    @Query("DELETE FROM progress_v2_history") suspend fun clearHistory()
    @Query("DELETE FROM progress_v2_history_index") suspend fun clearHistoryIndex()
    @Query("SELECT * FROM progress_v2_namespaces WHERE serverKey = :server AND accountId = :account LIMIT 1")
    suspend fun namespace(server: String, account: String): ProgressNamespaceRow?
    @Query("SELECT * FROM progress_v2_contents WHERE namespaceId = :namespaceId AND bindingKey = :bindingKey LIMIT 1")
    suspend fun content(namespaceId: String, bindingKey: String): ProgressContentRow?
    @Insert suspend fun insertNamespace(row: ProgressNamespaceRow)
    @Insert suspend fun insertContent(row: ProgressContentRow)
    @Query("SELECT * FROM progress_v2_operations WHERE namespaceId = :namespaceId AND contentId = :contentId")
    suspend fun operations(namespaceId: String, contentId: String): List<ProgressOperationRow>
    @Query("SELECT * FROM progress_v2_operations WHERE namespaceId = :namespaceId AND contentId = :contentId AND operationId = :operationId")
    suspend fun operation(namespaceId: String, contentId: String, operationId: String): ProgressOperationRow?
    @Query("SELECT COALESCE(MAX(localSequence), 0) FROM progress_v2_operations WHERE namespaceId = :namespaceId AND contentId = :contentId")
    suspend fun lastOperationSequence(namespaceId: String, contentId: String): Long
    @Insert suspend fun insertOperation(row: ProgressOperationRow)
    @Query("UPDATE progress_v2_operations SET payload = :payload WHERE namespaceId = :namespaceId AND contentId = :contentId AND operationId = :operationId")
    suspend fun replaceOperationPayload(namespaceId: String, contentId: String, operationId: String, payload: ByteArray)
    @Query("SELECT * FROM progress_v2_heads WHERE namespaceId = :namespaceId AND contentId = :contentId AND reference = :reference ORDER BY operationId")
    suspend fun heads(namespaceId: String, contentId: String, reference: String): List<ProgressHeadRow>
    @Query("DELETE FROM progress_v2_heads WHERE namespaceId = :namespaceId AND contentId = :contentId AND reference = :reference AND operationId IN (:parents)")
    suspend fun removeParents(namespaceId: String, contentId: String, reference: String, parents: List<String>)
    @Insert suspend fun insertHead(row: ProgressHeadRow)
    @Insert suspend fun insertOutbox(row: ProgressOutboxRow)
    @Update suspend fun updateOutbox(row: ProgressOutboxRow)
    @Query("SELECT * FROM progress_v2_outbox WHERE namespaceId = :namespaceId AND contentId = :contentId ORDER BY operationId")
    suspend fun outbox(namespaceId: String, contentId: String): List<ProgressOutboxRow>
    @Query("UPDATE progress_v2_outbox SET status = 'PENDING', leaseId = NULL, leaseUntil = NULL WHERE status = 'LEASED'")
    suspend fun recoverLeases()
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun quarantine(row: ProgressQuarantineRow)

    @Query("SELECT localSequence AS localRowId, * FROM progress_v2_operations WHERE namespaceId = :namespaceId AND contentId = :contentId AND localSequence > :after ORDER BY localSequence LIMIT :limit")
    suspend fun operationPage(namespaceId: String, contentId: String, after: Long, limit: Int): List<ProgressSequencedOperation>
    @Query("SELECT * FROM progress_v2_history_index WHERE namespaceId = :namespaceId AND contentId = :contentId")
    suspend fun historyIndex(namespaceId: String, contentId: String): ProgressHistoryIndexRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun updateHistoryIndex(row: ProgressHistoryIndexRow)
    @Query("SELECT * FROM progress_v2_history WHERE namespaceId = :namespaceId AND contentId = :contentId AND checkpointId = :checkpointId")
    suspend fun historyCheckpoint(namespaceId: String, contentId: String, checkpointId: String): ProgressHistoryRow?
    @Insert suspend fun insertHistory(row: ProgressHistoryRow)
    @Update suspend fun updateHistory(row: ProgressHistoryRow)
    @Query("SELECT * FROM progress_v2_history WHERE namespaceId = :namespaceId AND contentId = :contentId AND sequence < :before ORDER BY sequence DESC LIMIT :limit")
    suspend fun historyPage(namespaceId: String, contentId: String, before: Long, limit: Int): List<ProgressHistoryRow>
    @Query("SELECT * FROM progress_v2_heads WHERE namespaceId = :namespaceId AND contentId = :contentId")
    suspend fun allHeads(namespaceId: String, contentId: String): List<ProgressHeadRow>
    @Query("SELECT * FROM progress_v2_outbox WHERE namespaceId = :namespaceId AND contentId = :contentId AND operationId = :operationId")
    suspend fun outboxOperation(namespaceId: String, contentId: String, operationId: String): ProgressOutboxRow?
}
