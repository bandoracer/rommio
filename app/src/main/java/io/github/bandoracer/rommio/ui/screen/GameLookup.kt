package io.github.bandoracer.rommio.ui.screen

import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.RomDto

/** A game known only from its local install (removed from RomM or not yet cached). */
internal fun DownloadedRomEntity.fallbackRom(): RomDto = RomDto(
    id = romId,
    name = romName,
    platformName = platformSlug.replace('-', ' ').replaceFirstChar { it.uppercase() },
    platformSlug = platformSlug,
    fsName = fileName.substringBeforeLast('.'),
    urlCover = null,
)

/** Cached catalogue first, then local installs, for looking games up by id. */
internal class GameIndex(cached: List<RomDto>, installed: List<DownloadedRomEntity>) {
    private val byId: Map<Int, RomDto> = buildMap {
        installed.forEach { putIfAbsent(it.romId, it.fallbackRom()) }
        cached.forEach { put(it.id, it) }
    }
    private val installedFileByRom: Map<Int, Int> = installed
        .sortedByDescending { it.downloadedAtEpochMs }
        .groupBy { it.romId }
        .mapValues { (_, rows) -> rows.first().fileId }

    operator fun get(romId: Int): RomDto? = byId[romId]
    fun installedFileId(romId: Int): Int? = installedFileByRom[romId]
    val installedRomIds: Set<Int> get() = installedFileByRom.keys
}
