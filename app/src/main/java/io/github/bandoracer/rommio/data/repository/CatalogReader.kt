package io.github.bandoracer.rommio.data.repository

import io.github.bandoracer.rommio.data.network.RommService
import io.github.bandoracer.rommio.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class CatalogSnapshot(
    val platforms: List<PlatformDto>,
    val roms: List<RomDto>,
    val collections: List<RommCollectionDto>,
    val continuePlaying: List<RomDto>,
    val recent: List<RomDto>,
)

/** Network staging only. A failed/inconsistent traversal never returns a partial inventory. */
internal class CatalogReader(private val service: RommService) {
    suspend fun roms(platformId: Int? = null, collection: RommCollectionDto? = null): List<RomDto> {
        val regular = collection?.takeIf { it.kind == CollectionKind.REGULAR }?.let { requireNotNull(it.id.toIntOrNull()) }
        val smart = collection?.takeIf { it.kind == CollectionKind.SMART }?.let { requireNotNull(it.id.toIntOrNull()) }
        val virtual = collection?.takeIf { it.kind == CollectionKind.VIRTUAL }?.id
        val completeLibrary = platformId == null && collection == null
        // This endpoint is ungrouped. Checking before/after prevents deleting cache entries
        // from a traversal whose membership changed while pages were being fetched.
        val initialIds = if (completeLibrary) identifiers() else null
        val result = readRomPages { offset ->
            service.getRoms(platformIds = platformId, legacyPlatformId = platformId,
                collectionId = regular, smartCollectionId = smart, virtualCollectionId = virtual,
                limit = 100, offset = offset, groupByMetaId = 0, orderBy = "id", orderDir = "asc", withFiles = true)
        }
        if (initialIds != null) {
            check(result.map { it.id }.toSet() == initialIds && identifiers() == initialIds) {
                "The library changed during refresh. The previous offline catalog was retained; retry refresh."
            }
        }
        return result
    }

    private suspend fun identifiers(): Set<Int> {
        val ids = service.getRomIdentifiers()
        check(ids.size == ids.toSet().size && ids.all { it > 0 }) { "Invalid ROM inventory." }
        return ids.toSet()
    }

    suspend fun collections(): List<RommCollectionDto> {
        // RomM 5.2 returns complete lists here; virtual's optional limit must be omitted.
        val collections = service.getCollections().map { it.toDomain() } +
            service.getSmartCollections().map { it.toDomain() } +
            service.getVirtualCollections(type = "all").map { it.toDomain() }
        check(collections.distinctBy { it.kind to it.id }.size == collections.size) { "Duplicate collection identity." }
        check(collections.all { it.romCount == it.romIds.size }) { "Incomplete collection membership." }
        return collections.sortedWith(compareByDescending<RommCollectionDto> { it.isFavorite }
            .thenByDescending { it.romCount }.thenBy { it.name.lowercase() })
    }
}

internal suspend fun readRomPages(fetch: suspend (offset: Int) -> ItemsResponse<RomDto>): List<RomDto> {
    val result = mutableListOf<RomDto>()
    val seen = hashSetOf<Int>()
    var expectedTotal: Int? = null
    var expectedIndex: List<Int>? = null
    repeat(10_000) {
        currentCoroutineContext().ensureActive()
        val page = fetch(result.size)
        page.total?.let { total ->
            check(total in 0..1_000_000 && (expectedTotal == null || total == expectedTotal)) { "Catalog count changed during refresh." }
            expectedTotal = total
        }
        page.romIdIndex?.let { index ->
            check(index.size <= 1_000_000 && index.distinct().size == index.size) { "Invalid catalog index." }
            check(expectedIndex == null || expectedIndex == index) { "Catalog membership changed during refresh." }
            expectedIndex = index
            check(index.drop(result.size).take(page.items.size) == page.items.map { it.id }) { "Catalog page does not match its index." }
            check(expectedTotal == null || expectedTotal == index.size) { "Catalog index count mismatch." }
            expectedTotal = index.size
        }
        if (page.items.isEmpty()) {
            check(expectedTotal == null || result.size == expectedTotal) { "Catalog ended before all games were received." }
            return result
        }
        check(result.size + page.items.size <= 1_000_000) { "Catalog exceeds the supported inventory limit." }
        page.items.forEach { rom ->
            check(rom.id > 0 && seen.add(rom.id)) { "Repeated or invalid ROM in catalog pages." }
            check(result.lastOrNull()?.id?.let { it < rom.id } != false) { "Catalog pages are not ordered by ID." }
            result.add(rom)
        }
        expectedTotal?.let { total ->
            check(result.size <= total) { "Catalog returned more games than its total." }
            if (result.size == total) return result
        }
        // A short page can be a server-side page cap. Without a total, continue until empty.
    }
    error("Catalog pagination did not finish within the supported page limit.")
}
