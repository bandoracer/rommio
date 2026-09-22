package io.github.bandoracer.rommio.data.repository

import io.github.bandoracer.rommio.data.local.RecoveryStateEntity
import io.github.bandoracer.rommio.model.ConnectivityState
import io.github.bandoracer.rommio.model.BrowsableGameStateKind
import io.github.bandoracer.rommio.model.RecoveryStateOrigin
import io.github.bandoracer.rommio.model.SaveStateEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class GameStateRecoveryTest {
    @Test
    fun deletedSlotJournalHidesResidualFileRowsAfterInterruptedDeletion() {
        val recovery = buildGameStateRecovery(
            resumeFile = null,
            manualStates = listOf(SaveStateEntity(42, 1, "Slot 1", "/tmp/residual.state", updatedAtEpochMs = 1)),
            manualStateJournals = listOf(io.github.bandoracer.rommio.data.local.SaveStateSyncJournalEntity(
                "profile", 42, 7, 1, "Slot 1", deleted = true, pendingDelete = true,
            )),
            recoveryStates = emptyList(), journal = null, connectivity = ConnectivityState.ONLINE,
        )
        assertEquals(emptyList<Any>(), recovery.saveSlots)
    }

    @Test
    fun recoveryKeepsAutomaticHistorySeparateFromOrderedSlotsAndPlayableImports() {
        val manualStates = listOf(
            SaveStateEntity(romId = 25, slot = 3, label = "Slot 3", localPath = "/tmp/slot3.state", updatedAtEpochMs = 300L),
            SaveStateEntity(romId = 25, slot = 1, label = "Slot 1", localPath = "/tmp/slot1.state", updatedAtEpochMs = 900L),
        )
        val recoveryStates = listOf(
            RecoveryStateEntity(
                romId = 25,
                fileId = 9,
                entryId = "auto:0",
                label = "Auto snapshot",
                origin = RecoveryStateOrigin.AUTO_HISTORY.name,
                localPath = "/tmp/auto0.state",
                remoteFileName = "25_recovery_auto_0.state",
                ringIndex = 0,
                capturedAtEpochMs = 1_000L,
            ),
            RecoveryStateEntity(
                romId = 25,
                fileId = 9,
                entryId = "legacy:cloud.state",
                label = "Imported cloud",
                origin = RecoveryStateOrigin.LEGACY_IMPORT.name,
                localPath = "/tmp/cloud.state",
                remoteFileName = "cloud.state",
                preserved = true,
                capturedAtEpochMs = 2_000L,
            ),
        )

        val recovery = buildGameStateRecovery(
            resumeFile = null,
            manualStates = manualStates,
            manualStateJournals = emptyList(),
            recoveryStates = recoveryStates,
            journal = null,
            connectivity = ConnectivityState.OFFLINE,
        )

        assertEquals(listOf("auto:0"), recovery.snapshots.map { it.id })
        assertEquals(
            listOf(BrowsableGameStateKind.RECOVERY_HISTORY),
            recovery.snapshots.map { it.kind },
        )
        assertEquals(listOf("manual:1", "manual:3", "legacy:cloud.state"), recovery.saveSlots.map { it.id })
        assertEquals(listOf(1, 3), recovery.saveSlots.mapNotNull { it.slot })
    }
}
