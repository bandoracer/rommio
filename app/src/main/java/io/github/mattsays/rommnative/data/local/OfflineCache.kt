package io.github.mattsays.rommnative.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.mattsays.rommnative.model.MediaCacheCategory
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "cached_platforms",
    primaryKeys = ["profileId", "platformId"],
)
data class CachedPlatformEntity(
    val profileId: String,
    val platformId: Int,
    val slug: String,
    val name: String,
    val fsSlug: String,
    val urlLogo: String? = null,
    val romCount: Int = 0,
    val logoCachedUri: String? = null,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "cached_roms",
    primaryKeys = ["profileId", "romId"],
)
data class CachedRomEntity(
    val profileId: String,
    val romId: Int,
    val name: String? = null,
    val summary: String? = null,
    val platformId: Int = 0,
    val platformName: String = "",
    val platformSlug: String = "",
    val fsName: String = "",
    val filesJson: String = "[]",
    val siblingsJson: String = "[]",
    val urlCover: String? = null,
    val coverCachedUri: String? = null,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "cached_collections",
    primaryKeys = ["profileId", "kind", "collectionId"],
)
data class CachedCollectionEntity(
    val profileId: String,
    val kind: String,
    val collectionId: String,
    val name: String,
    val description: String = "",
    val romCount: Int = 0,
    val pathCoverSmall: String? = null,
    val pathCoverLarge: String? = null,
    val pathCoversSmallJson: String = "[]",
    val pathCoversLargeJson: String = "[]",
    val coverCachedUri: String? = null,
    val isPublic: Boolean = false,
    val isFavorite: Boolean = false,
    val isVirtual: Boolean = false,
    val isSmart: Boolean = false,
    val ownerUsername: String? = null,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "cached_collection_roms",
    primaryKeys = ["profileId", "kind", "collectionId", "romId"],
)
data class CachedCollectionRomEntity(
    val profileId: String,
    val kind: String,
    val collectionId: String,
    val romId: Int,
    val position: Int,
)

@Entity(
    tableName = "cached_home_entries",
    primaryKeys = ["profileId", "feedType", "position"],
)
data class CachedHomeEntryEntity(
    val profileId: String,
    val feedType: String,
    val position: Int,
    val romId: Int? = null,
    val collectionKind: String? = null,
    val collectionId: String? = null,
)

@Entity(tableName = "profile_cache_state")
data class ProfileCacheStateEntity(
    @PrimaryKey val profileId: String,
    val lastFullSyncAtEpochMs: Long? = null,
    val lastMediaSyncAtEpochMs: Long? = null,
    val catalogReady: Boolean = false,
    val mediaReady: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastError: String? = null,
)

@Entity(
    tableName = "pending_remote_actions",
    primaryKeys = ["profileId", "actionType", "dedupeKey"],
)
data class PendingRemoteActionEntity(
    val profileId: String,
    val actionType: String,
    val dedupeKey: String,
    val payloadJson: String,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastError: String? = null,
)

@Entity(
    tableName = "media_cache_entries",
    primaryKeys = ["profileId", "sourceUrl"],
)
data class MediaCacheEntryEntity(
    val profileId: String,
    val sourceUrl: String,
    val localPath: String,
    val category: String,
    val pinned: Boolean,
    val sizeBytes: Long,
    val lastAccessEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Dao
interface CachedPlatformDao {
    @Query("SELECT * FROM cached_platforms WHERE profileId = :profileId ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(profileId: String): Flow<List<CachedPlatformEntity>>

    @Query("SELECT * FROM cached_platforms WHERE profileId = :profileId ORDER BY name COLLATE NOCASE ASC")
    suspend fun listAll(profileId: String): List<CachedPlatformEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedPlatformEntity>)

    @Query("DELETE FROM cached_platforms WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: String)
}

@Dao
interface CachedRomDao {
    @Query("SELECT * FROM cached_roms WHERE profileId = :profileId ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(profileId: String): Flow<List<CachedRomEntity>>

    @Query("SELECT * FROM cached_roms WHERE profileId = :profileId ORDER BY name COLLATE NOCASE ASC")
    suspend fun listAll(profileId: String): List<CachedRomEntity>

    @Query("SELECT * FROM cached_roms WHERE profileId = :profileId AND platformId = :platformId ORDER BY name COLLATE NOCASE ASC")
    fun observeByPlatform(profileId: String, platformId: Int): Flow<List<CachedRomEntity>>

    @Query("SELECT * FROM cached_roms WHERE profileId = :profileId AND platformId = :platformId ORDER BY name COLLATE NOCASE ASC")
    suspend fun listByPlatform(profileId: String, platformId: Int): List<CachedRomEntity>

    @Query("SELECT * FROM cached_roms WHERE profileId = :profileId AND romId = :romId LIMIT 1")
    suspend fun getById(profileId: String, romId: Int): CachedRomEntity?

    @Query("SELECT * FROM cached_roms WHERE profileId = :profileId AND romId = :romId LIMIT 1")
    fun observeById(profileId: String, romId: Int): Flow<CachedRomEntity?>

    @Query("SELECT * FROM cached_roms WHERE profileId = :profileId AND romId IN (:romIds)")
    suspend fun listByIds(profileId: String, romIds: List<Int>): List<CachedRomEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedRomEntity>)

    @Query("DELETE FROM cached_roms WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: String)
}

@Dao
interface CachedCollectionDao {
    @Query("SELECT * FROM cached_collections WHERE profileId = :profileId ORDER BY isFavorite DESC, romCount DESC, name COLLATE NOCASE ASC")
    fun observeAll(profileId: String): Flow<List<CachedCollectionEntity>>

    @Query("SELECT * FROM cached_collections WHERE profileId = :profileId AND kind = :kind AND collectionId = :collectionId LIMIT 1")
    fun observeById(profileId: String, kind: String, collectionId: String): Flow<CachedCollectionEntity?>

    @Query("SELECT * FROM cached_collections WHERE profileId = :profileId ORDER BY isFavorite DESC, romCount DESC, name COLLATE NOCASE ASC")
    suspend fun listAll(profileId: String): List<CachedCollectionEntity>

    @Query("SELECT * FROM cached_collections WHERE profileId = :profileId AND kind = :kind AND collectionId = :collectionId LIMIT 1")
    suspend fun getById(profileId: String, kind: String, collectionId: String): CachedCollectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedCollectionEntity>)

    @Query("DELETE FROM cached_collections WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: String)
}

@Dao
interface CachedCollectionRomDao {
    @Query("SELECT * FROM cached_collection_roms WHERE profileId = :profileId AND kind = :kind AND collectionId = :collectionId ORDER BY position ASC")
    fun observeByCollection(profileId: String, kind: String, collectionId: String): Flow<List<CachedCollectionRomEntity>>

    @Query("SELECT * FROM cached_collection_roms WHERE profileId = :profileId AND kind = :kind AND collectionId = :collectionId ORDER BY position ASC")
    suspend fun listByCollection(profileId: String, kind: String, collectionId: String): List<CachedCollectionRomEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedCollectionRomEntity>)

    @Query("DELETE FROM cached_collection_roms WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: String)

    @Query("DELETE FROM cached_collection_roms WHERE profileId = :profileId AND kind = :kind AND collectionId = :collectionId")
    suspend fun deleteByCollection(profileId: String, kind: String, collectionId: String)
}

@Dao
interface CachedHomeEntryDao {
    @Query("SELECT * FROM cached_home_entries WHERE profileId = :profileId AND feedType = :feedType ORDER BY position ASC")
    fun observeByFeed(profileId: String, feedType: String): Flow<List<CachedHomeEntryEntity>>

    @Query("SELECT * FROM cached_home_entries WHERE profileId = :profileId AND feedType = :feedType ORDER BY position ASC")
    suspend fun listByFeed(profileId: String, feedType: String): List<CachedHomeEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedHomeEntryEntity>)

    @Query("DELETE FROM cached_home_entries WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: String)

    @Query("DELETE FROM cached_home_entries WHERE profileId = :profileId AND feedType = :feedType")
    suspend fun deleteByFeed(profileId: String, feedType: String)
}

@Dao
interface ProfileCacheStateDao {
    @Query("SELECT * FROM profile_cache_state WHERE profileId = :profileId LIMIT 1")
    fun observeByProfile(profileId: String): Flow<ProfileCacheStateEntity?>

    @Query("SELECT * FROM profile_cache_state WHERE profileId = :profileId LIMIT 1")
    suspend fun getByProfile(profileId: String): ProfileCacheStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProfileCacheStateEntity)

    @Query("DELETE FROM profile_cache_state WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: String)
}

@Dao
interface PendingRemoteActionDao {
    @Query("SELECT * FROM pending_remote_actions WHERE profileId = :profileId ORDER BY createdAtEpochMs ASC")
    suspend fun listByProfile(profileId: String): List<PendingRemoteActionEntity>

    @Query("SELECT * FROM pending_remote_actions WHERE profileId = :profileId AND status IN ('PENDING', 'FAILED') ORDER BY createdAtEpochMs ASC")
    suspend fun listPendingByProfile(profileId: String): List<PendingRemoteActionEntity>

    @Query("SELECT * FROM pending_remote_actions WHERE profileId = :profileId AND actionType = :actionType AND dedupeKey = :dedupeKey LIMIT 1")
    suspend fun getByKey(profileId: String, actionType: String, dedupeKey: String): PendingRemoteActionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingRemoteActionEntity)

    @Query("DELETE FROM pending_remote_actions WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: String)

    @Query("DELETE FROM pending_remote_actions WHERE profileId = :profileId AND actionType = :actionType AND dedupeKey = :dedupeKey")
    suspend fun deleteByKey(profileId: String, actionType: String, dedupeKey: String)
}

@Dao
interface MediaCacheEntryDao {
    @Query("SELECT * FROM media_cache_entries WHERE profileId = :profileId AND sourceUrl = :sourceUrl LIMIT 1")
    suspend fun getBySource(profileId: String, sourceUrl: String): MediaCacheEntryEntity?

    @Query("SELECT * FROM media_cache_entries ORDER BY pinned ASC, lastAccessEpochMs ASC")
    suspend fun listForEviction(): List<MediaCacheEntryEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM media_cache_entries")
    suspend fun totalBytes(): Long

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM media_cache_entries WHERE profileId = :profileId")
    suspend fun totalBytesByProfile(profileId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaCacheEntryEntity)

    @Query("DELETE FROM media_cache_entries WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: String)

    @Query("DELETE FROM media_cache_entries WHERE profileId = :profileId AND sourceUrl = :sourceUrl")
    suspend fun deleteBySource(profileId: String, sourceUrl: String)
}
