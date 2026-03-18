package io.github.mattsays.rommnative.domain.sync

import io.github.mattsays.rommnative.model.DownloadedRomEntity
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.SyncSummary
import io.github.mattsays.rommnative.domain.player.RuntimeProfile

interface SyncBridge {
    suspend fun syncGame(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
    ): SyncSummary
}
