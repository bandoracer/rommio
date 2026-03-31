package io.github.bandoracer.rommio.domain.sync

import io.github.bandoracer.rommio.domain.player.RuntimeProfile
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.PlayerLaunchPreparation
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.SyncSummary
import java.io.File

interface SyncBridge {
    suspend fun preparePlayerEntry(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
    ): PlayerLaunchPreparation

    suspend fun flushContinuity(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
        sessionActive: Boolean,
    ): SyncSummary

    suspend fun adoptRemoteContinuity(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
    ): PlayerLaunchPreparation

    suspend fun refreshStateRecovery(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
    )

    suspend fun syncGame(
        installation: DownloadedRomEntity,
        rom: RomDto,
        runtimeProfile: RuntimeProfile,
    ): SyncSummary

    suspend fun recordManualState(
        installation: DownloadedRomEntity,
        slot: Int,
        file: File,
    )

    suspend fun markManualStateDeleted(
        installation: DownloadedRomEntity,
        slot: Int,
    )
}
