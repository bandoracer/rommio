package io.github.bandoracer.rommio.data.repository

import androidx.room.withTransaction
import io.github.bandoracer.rommio.data.local.*
import io.github.bandoracer.rommio.model.*

/** Only complete inventories may replace membership or reconcile removed catalog records. */
internal class CatalogCache(
    private val database: AppDatabase,
    private val resolveCachedUri: suspend (String, String) -> String? = { _, _ -> null },
    private val checkpoint: (String) -> Unit = {},
) {
    private val cacheCodec = OfflineCacheJsonCodec()
    private val cachedPlatformDao = database.cachedPlatformDao()
    private val cachedRomDao = database.cachedRomDao()
    private val cachedCollectionDao = database.cachedCollectionDao()
    private val cachedCollectionRomDao = database.cachedCollectionRomDao()
    private val cachedHomeEntryDao = database.cachedHomeEntryDao()

    suspend fun finishTargetedRefresh(profileId: String) = database.withTransaction {
        val state = database.profileCacheStateDao().getByProfile(profileId) ?: ProfileCacheStateEntity(profileId)
        database.profileCacheStateDao().upsert(state.copy(isRefreshing = false, lastError = null))
    }

    suspend fun commitFull(profile: ServerProfile, snapshot: CatalogSnapshot, now: Long) = database.withTransaction {
        val current = database.serverProfileDao().getById(profile.id)
        check(current?.baseUrl == profile.baseUrl) { "The catalog profile changed during refresh." }
        cachePlatforms(profile, snapshot.platforms)
        cacheRoms(profile, snapshot.roms, filesIncluded = true)
        checkpoint("ROMS_WRITTEN")
        val present = snapshot.roms.map { it.id }.toSet()
        check(snapshot.collections.all { present.containsAll(it.romIds) }) { "Collection membership changed during catalog refresh." }
        val installed = database.downloadedRomDao().listAll().filter { (it.ownerProfileId == profile.id || it.ownerProfileId == null) }.map { it.romId }.toSet()
        cachedRomDao.listAll(profile.id).filter { it.romId !in present && it.romId !in installed }.forEach {
            cachedRomDao.deleteById(profile.id, it.romId)
        }
        cacheCollections(profile, snapshot.collections)
        cacheRomFeed(profile.id, OfflineHomeFeedType.CONTINUE_PLAYING, snapshot.continuePlaying.filter { it.id in present })
        cacheRomFeed(profile.id, OfflineHomeFeedType.RECENTLY_ADDED, snapshot.recent.filter { it.id in present })
        cacheCollectionFeed(profile.id, snapshot.collections.take(8))
        checkpoint("MEMBERSHIP_WRITTEN")
        val state = database.profileCacheStateDao().getByProfile(profile.id) ?: ProfileCacheStateEntity(profile.id)
        database.profileCacheStateDao().upsert(state.copy(lastFullSyncAtEpochMs = now, catalogReady = true,
            mediaReady = false, isRefreshing = true, lastError = null))
    }

    suspend fun cachePlatforms(profile: ServerProfile, platforms: List<PlatformDto>) = database.withTransaction {
        val now = System.currentTimeMillis()
        cachedPlatformDao.deleteByProfile(profile.id)
        cachedPlatformDao.upsertAll(
            platforms.map { platform ->
                val absoluteLogo = platform.platformLogoSource(profile.baseUrl)
                CachedPlatformEntity(
                    profileId = profile.id,
                    platformId = platform.id,
                    slug = platform.slug,
                    name = platform.name,
                    fsSlug = platform.fsSlug,
                    urlLogo = platform.urlLogo,
                    romCount = platform.romCount,
                    logoCachedUri = absoluteLogo?.let { resolveCachedUri(profile.id, it) },
                    updatedAtEpochMs = now,
                )
            },
        )
    }

    suspend fun cacheRoms(profile: ServerProfile, roms: List<RomDto>, detailed: Boolean = false, filesIncluded: Boolean = false) = database.withTransaction {
        if (roms.isEmpty()) return@withTransaction
        val now = System.currentTimeMillis()
        cachedRomDao.upsertAll(
            roms.distinctBy { it.id }.map { rom ->
                val previous = cachedRomDao.getById(profile.id, rom.id)
                val absoluteCover = rom.coverSource(profile.baseUrl)
                CachedRomEntity(
                    profileId = profile.id,
                    romId = rom.id,
                    name = rom.name,
                    summary = rom.summary,
                    platformId = rom.platformId,
                    platformName = rom.platformName,
                    platformSlug = rom.platformSlug,
                    fsName = rom.fsName,
                    filesJson = when {
                        detailed -> cacheCodec.encodeFiles(rom.files)
                        !filesIncluded && rom.files.isEmpty() -> previous?.filesJson ?: "[]"
                        else -> cacheCodec.encodeFiles((rom.files + cacheCodec.decodeFiles(previous?.filesJson ?: "[]")
                            .filter { it.romId != rom.id && (rom.siblings == null || rom.siblings.any { sibling -> sibling.id == it.romId }) }).distinctBy { it.id })
                    },
                    siblingsJson = if (!detailed && rom.siblings == null) previous?.siblingsJson ?: "[]" else cacheCodec.encodeSiblings(rom.siblings.orEmpty()),
                    urlCover = rom.urlCover,
                    coverCachedUri = absoluteCover?.let { resolveCachedUri(profile.id, it) },
                    updatedAtEpochMs = now,
                )
            },
        )
    }

    suspend fun cacheCollections(profile: ServerProfile, collections: List<RommCollectionDto>) = database.withTransaction {
        val now = System.currentTimeMillis()
        cachedCollectionDao.deleteByProfile(profile.id)
        cachedCollectionRomDao.deleteByProfile(profile.id)
        cachedCollectionDao.upsertAll(
            collections.map { collection ->
                val absoluteCover = collection.collectionCoverSource(profile.baseUrl)
                CachedCollectionEntity(
                    profileId = profile.id,
                    kind = collection.kind.name,
                    collectionId = collection.id,
                    name = collection.name,
                    description = collection.description,
                    romCount = collection.romCount,
                    pathCoverSmall = collection.pathCoverSmall,
                    pathCoverLarge = collection.pathCoverLarge,
                    pathCoversSmallJson = cacheCodec.encodeStringList(collection.pathCoversSmall),
                    pathCoversLargeJson = cacheCodec.encodeStringList(collection.pathCoversLarge),
                    coverCachedUri = absoluteCover?.let { resolveCachedUri(profile.id, it) },
                    isPublic = collection.isPublic,
                    isFavorite = collection.isFavorite,
                    isVirtual = collection.isVirtual,
                    isSmart = collection.isSmart,
                    ownerUsername = collection.ownerUsername,
                    updatedAtEpochMs = now,
                )
            },
        )
        collections.forEach { collection ->
            replaceCollectionMembership(
                profileId = profile.id,
                collection = collection,
                romIds = collection.romIds.toList(),
            )
        }
    }

    suspend fun replaceCollectionMembership(
        profileId: String,
        collection: RommCollectionDto,
        romIds: List<Int>,
    ) = database.withTransaction {
        cachedCollectionRomDao.deleteByCollection(profileId, collection.kind.name, collection.id)
        if (romIds.isNotEmpty()) {
            cachedCollectionRomDao.upsertAll(
                romIds.distinct().mapIndexed { index, romId ->
                    CachedCollectionRomEntity(
                        profileId = profileId,
                        kind = collection.kind.name,
                        collectionId = collection.id,
                        romId = romId,
                        position = index,
                    )
                },
            )
        }
    }

    suspend fun cacheRomFeed(profileId: String, feedType: OfflineHomeFeedType, roms: List<RomDto>) = database.withTransaction {
        cachedHomeEntryDao.deleteByFeed(profileId, feedType.name)
        if (roms.isEmpty()) return@withTransaction
        cachedHomeEntryDao.upsertAll(
            roms.distinctBy { it.id }.mapIndexed { index, rom ->
                CachedHomeEntryEntity(
                    profileId = profileId,
                    feedType = feedType.name,
                    position = index,
                    romId = rom.id,
                )
            },
        )
    }

    suspend fun cacheCollectionFeed(profileId: String, collections: List<RommCollectionDto>) = database.withTransaction {
        cachedHomeEntryDao.deleteByFeed(profileId, OfflineHomeFeedType.FEATURED_COLLECTION.name)
        if (collections.isEmpty()) return@withTransaction
        cachedHomeEntryDao.upsertAll(
            collections.mapIndexed { index, collection ->
                CachedHomeEntryEntity(
                    profileId = profileId,
                    feedType = OfflineHomeFeedType.FEATURED_COLLECTION.name,
                    position = index,
                    collectionKind = collection.kind.name,
                    collectionId = collection.id,
                )
            },
        )
    }

}
