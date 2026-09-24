package io.github.bandoracer.rommio.domain.playdata

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedProgressRulesTest {
    private val ns = "00000000-0000-4000-8000-000000000001"
    private val content = "00000000-0000-4000-8000-000000000002"
    private val here = "11111111-1111-4111-8111-111111111111"
    private val there = "22222222-2222-4222-8222-222222222222"
    private var counter = 0

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun stored(
        time: Long,
        hasState: Boolean = true,
        manual: Boolean = false,
        reason: String = if (manual) "manual" else "periodic",
        refs: Set<String> = emptySet(),
        conflicted: Set<String> = emptySet(),
        outbox: String? = "PUBLISHED",
        device: String = here,
        retained: Boolean = true,
        romId: Long = 7,
        platform: String? = if (hasState) "android" else null,
    ) = StoredCheckpoint(
        key = SaveKey(ns, content, UUID.nameUUIDFromBytes("cp-${counter++}".toByteArray()).toString()),
        romId = romId, fileId = 70, operationId = "op-$counter", capturedAtEpochMs = time, captureReason = reason,
        deviceId = device, hasState = hasState, manual = manual, retained = retained,
        currentReferences = refs, conflictedReferences = conflicted, outboxStatus = outbox,
        statePlatform = platform,
    )

    private fun entry(stored: StoredCheckpoint, labels: Map<SaveKey, String> = emptyMap(), thumbnail: String? = null) =
        SavedProgressRules.entry(stored, labels, here, DeviceFormFactor.PHONE) { thumbnail }

    @Test
    fun kindsFollowStateAndWhoMadeIt() {
        assertEquals(SaveKind.GAME_SAVE, SavedProgressRules.kind(hasState = false, manual = true))
        assertEquals(SaveKind.SAVE_STATE, SavedProgressRules.kind(hasState = true, manual = true))
        assertEquals(SaveKind.RESUME_POINT, SavedProgressRules.kind(hasState = true, manual = false))
    }

    @Test
    fun syncStatusUsesTheSpecCopyAndConflictWins() {
        assertEquals("Synced", SavedProgressRules.syncStatus("PUBLISHED", false).label)
        assertEquals("Syncing", SavedProgressRules.syncStatus("LEASED", false).label)
        assertEquals("Waiting to sync", SavedProgressRules.syncStatus("PENDING", false).label)
        assertEquals("Local only", SavedProgressRules.syncStatus("LOCAL_ONLY", false).label)
        assertEquals("Sign-in needed", SavedProgressRules.syncStatus("PARKED_AUTH", false).label)
        assertEquals(SaveSyncStatus.CONFLICT, SavedProgressRules.syncStatus("PUBLISHED", true))
        assertEquals(SaveSyncStatus.UNKNOWN, SavedProgressRules.syncStatus(null, false))
    }

    @Test
    fun deviceLabelSaysThisDeviceOnlyForTheLocalId() {
        assertEquals("This phone", SavedProgressRules.deviceLabel(here, here, DeviceFormFactor.PHONE))
        assertEquals("This tablet", SavedProgressRules.deviceLabel(here, here, DeviceFormFactor.TABLET))
        assertEquals("This device", SavedProgressRules.deviceLabel(here, here, DeviceFormFactor.UNKNOWN))
        assertEquals("Another device", SavedProgressRules.deviceLabel(there, here, DeviceFormFactor.PHONE))
        assertEquals("Another device", SavedProgressRules.deviceLabel(here, null, DeviceFormFactor.PHONE))
    }

    @Test
    fun entriesCarryNamesSlotsThumbnailsAndCurrentReferences() {
        val slot2 = UUID.nameUUIDFromBytes("rommio-slot-2".toByteArray()).toString()
        val state = stored(at(20, 9), manual = true, refs = setOf("state/resume/abc", "state/slot/$slot2"), outbox = "PENDING")
        val named = entry(state, labels = mapOf(state.key to "Before Kraid"), thumbnail = "/thumbs/a.webp")
        assertEquals(SaveKind.SAVE_STATE, named.kind)
        assertEquals("Before Kraid", named.title)
        assertEquals(2, named.slotNumber)
        assertTrue(named.isCurrentResume && named.isCurrentSlot && named.loadable && named.fromThisDevice)
        assertEquals("/thumbs/a.webp", named.thumbnailPath)
        assertEquals(SaveSyncStatus.WAITING, named.sync)
        assertEquals("Save state · Slot 2", entry(state).title)

        val gameSave = stored(at(20, 9), hasState = false, manual = true, refs = setOf("game/current"))
        val game = entry(gameSave, labels = mapOf(gameSave.key to "ignored"), thumbnail = "/thumbs/b.webp")
        assertEquals("Game-save data", game.title)
        assertNull("Game-save data is never named", game.label)
        assertNull("Game-save data has no frame", game.thumbnailPath)
        assertFalse(game.loadable)

        val older = entry(stored(at(19, 9), device = there, retained = false))
        assertEquals("Earlier resume point", older.title)
        assertEquals("Another device", older.deviceLabel)
        assertFalse(older.loadable)
    }

    @Test
    fun legacyV2ReferencesAreRecognised() {
        val v2 = entry(stored(at(20, 9), manual = true, refs = setOf("save/current", "resume/xyz", "slot/${UUID.nameUUIDFromBytes("rommio-slot-4".toByteArray())}")))
        assertTrue(v2.isCurrentResume)
        assertEquals(4, v2.slotNumber)
        assertNull(entry(stored(at(20, 9), manual = true, refs = setOf("state/slot/${UUID.randomUUID()}"))).slotNumber)
    }

    @Test
    fun recentSavesKeepSaveStatesAndCurrentResumePointsNewestFirst() {
        val currentResume = entry(stored(at(20, 12), refs = setOf("state/resume/a")))
        val oldResume = entry(stored(at(20, 11)))
        val state = entry(stored(at(20, 10), manual = true, refs = setOf("state/slot/s")))
        val olderState = entry(stored(at(18, 10), manual = true))
        val evictedState = entry(stored(at(19, 10), manual = true, retained = false))
        val gameSave = entry(stored(at(20, 13), hasState = false, refs = setOf("game/current")))
        val otherGame = entry(stored(at(20, 11, 30), refs = setOf("state/resume/b"), romId = 9))
        val recent = SavedProgressRules.recent(listOf(oldResume, state, gameSave, olderState, currentResume, evictedState, otherGame, state), limit = 10)
        assertEquals(listOf(currentResume, otherGame, state, olderState), recent)
        assertEquals(listOf(currentResume, otherGame), SavedProgressRules.recent(recent, limit = 2))
    }

    @Test
    fun aGamesSavesSplitIntoResumeStatesAndHistory() {
        val resume = entry(stored(at(20, 12), refs = setOf("state/resume/a")))
        val manualResume = entry(stored(at(20, 13), manual = true, refs = setOf("state/slot/s")))
        val older = entry(stored(at(20, 11)))
        val gameSave = entry(stored(at(20, 12), hasState = false))
        val saves = SavedProgressRules.forGame(listOf(older, gameSave, manualResume, resume))
        assertEquals(resume, saves.resume)
        assertFalse(saves.resumeConflict)
        assertEquals(listOf(manualResume), saves.saveStates)
        assertEquals(listOf(older), saves.history)
        assertEquals(listOf(gameSave), saves.gameSaveData)
        assertTrue(SavedProgressRules.forGame(emptyList()).isEmpty)
    }

    @Test
    fun aManualCaptureThatIsAlsoTheResumePointIsShownAsTheResumePoint() {
        val both = entry(stored(at(20, 12), manual = true, refs = setOf("state/resume/a", "state/slot/s")))
        val saves = SavedProgressRules.forGame(listOf(both))
        assertEquals(both, saves.resume)
        assertTrue(saves.saveStates.isEmpty())
    }

    @Test
    fun twoResumeHeadsAreAConflict() {
        val a = entry(stored(at(20, 12), refs = setOf("state/resume/a"), conflicted = setOf("state/resume/a")))
        val b = entry(stored(at(20, 11), refs = setOf("state/resume/a"), conflicted = setOf("state/resume/a"), device = there))
        val saves = SavedProgressRules.forGame(listOf(b, a))
        assertEquals(a, saves.resume)
        assertTrue(saves.resumeConflict)
        assertEquals(SaveSyncStatus.CONFLICT, saves.resume?.sync)
    }

    @Test
    fun resumeConflictsCountTheirHeadsInTheCopy() {
        val heads = (0 until 3).map { i ->
            entry(stored(at(20, 12 - i), refs = setOf("state/resume/a"), conflicted = setOf("state/resume/a")))
        }
        val saves = SavedProgressRules.forGame(heads.reversed())
        val conflict = saves.conflict!!
        assertEquals(SaveConflictKind.RESUME, conflict.kind)
        assertEquals(heads, conflict.choices)
        assertEquals("EarthBound has three resume points. Choose which to keep playing.", conflict.prompt("EarthBound"))
        assertEquals("Three resume points", conflict.chipTitle)
        assertEquals("3 resume points", conflict.countLabel)
        val two = SaveConflict(SaveConflictKind.RESUME, heads.take(2))
        assertEquals("EarthBound has two resume points. Choose which to keep playing.", two.prompt("EarthBound"))
        assertEquals("Two resume points", two.chipTitle)
        val five = SaveConflict(SaveConflictKind.RESUME, heads + heads.take(2))
        assertEquals("EarthBound has 5 resume points. Choose which to keep playing.", five.prompt("EarthBound"))
        assertEquals("5 resume points", five.chipTitle)
        assertNull(saves.launchOptions)
    }

    @Test
    fun twoGameSaveHeadsAreAGameSaveConflict() {
        val a = entry(stored(at(20, 12), hasState = false, refs = setOf("game/current"), conflicted = setOf("game/current")))
        val b = entry(stored(at(20, 10), hasState = false, refs = setOf("game/current"), conflicted = setOf("game/current"), device = there))
        val resume = entry(stored(at(20, 11), refs = setOf("state/resume/a")))
        val saves = SavedProgressRules.forGame(listOf(b, resume, a))
        val conflict = saves.conflict!!
        assertEquals(SaveConflictKind.GAME_SAVE, conflict.kind)
        assertEquals(listOf(a, b), conflict.choices)
        assertTrue(saves.resumeConflict)
        assertEquals("EarthBound has two versions of its game-save data. Choose which to keep playing.", conflict.prompt("EarthBound"))
        assertEquals("Two game saves", conflict.chipTitle)
        // A resume conflict wins over a game-save one.
        val r1 = entry(stored(at(20, 9), refs = setOf("state/resume/a"), conflicted = setOf("state/resume/a")))
        val r2 = entry(stored(at(20, 8), refs = setOf("state/resume/a"), conflicted = setOf("state/resume/a")))
        assertEquals(SaveConflictKind.RESUME, SavedProgressRules.forGame(listOf(a, b, r1, r2)).conflict?.kind)
    }

    @Test
    fun launchOptionsNeedAResumePointAndGameSaveData() {
        val resume = entry(stored(at(20, 12), refs = setOf("state/resume/a")))
        val gameSave = entry(stored(at(20, 12, 1), hasState = false, refs = setOf("game/current")))
        assertNull(SavedProgressRules.forGame(listOf(resume)).launchOptions)
        assertNull(SavedProgressRules.forGame(listOf(gameSave)).launchOptions)
        val options = SavedProgressRules.forGame(listOf(resume, gameSave)).launchOptions!!
        assertEquals(resume, options.resume)
        assertEquals(gameSave, options.gameSave)
        assertFalse(options.resumeUnavailable)
        assertEquals(LaunchStart.RESUME, options.default)
        assertTrue(gameSave.isCurrentGameSave)
        assertFalse(resume.isCurrentGameSave)
    }

    @Test
    fun aResumePointFromAnotherPlatformOffersOnlyTheGameSave() {
        val ios = entry(stored(at(20, 12), refs = setOf("state/resume/ios"), device = there, platform = "ios"))
        val gameSave = entry(stored(at(20, 12, 1), hasState = false, refs = setOf("game/current")))
        assertTrue(ios.foreignState)
        val options = SavedProgressRules.forGame(listOf(ios, gameSave)).launchOptions!!
        assertEquals(ios, options.resume)
        assertTrue(options.resumeUnavailable)
        assertEquals(LaunchStart.GAME_SAVE, options.default)
        assertEquals(LaunchStart.GAME_SAVE, LaunchStart.fromRoute("gameSave"))
        assertEquals(LaunchStart.RESUME, LaunchStart.fromRoute("resume"))
        assertNull(LaunchStart.fromRoute(null))
    }

    @Test
    fun groupsByLocalDayNewestFirst() {
        val late = entry(stored(at(20, 23, 30)))
        val morning = entry(stored(at(20, 8)))
        val yesterday = entry(stored(at(19, 22)))
        val utc = SavedProgressRules.groupByDay(listOf(late, morning, yesterday), ZoneOffset.UTC)
        assertEquals(listOf(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 19)), utc.map { it.day })
        assertEquals(listOf(late, morning), utc.first().entries)
        // 23:30 UTC is the next day at UTC+2.
        val plusTwo = SavedProgressRules.groupByDay(listOf(late, morning, yesterday), ZoneOffset.ofHours(2))
        assertEquals(listOf(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 20)), plusTwo.map { it.day })
    }

    @Test
    fun labelsAreTrimmedCollapsedAndBounded() {
        assertEquals("Before Kraid", SavedProgressRules.normalizeLabel("  Before   Kraid "))
        assertNull(SavedProgressRules.normalizeLabel("   "))
        assertEquals(SavedProgressRules.MAX_LABEL_LENGTH, SavedProgressRules.normalizeLabel("x".repeat(200))!!.length)
    }

    @Test
    fun reasonLabelsArePlainWords() {
        assertEquals("Manual", SavedProgressRules.reasonLabel("manual"))
        assertEquals("Left game", SavedProgressRules.reasonLabel("exit"))
        assertEquals("Automatic", SavedProgressRules.reasonLabel("periodic"))
        assertEquals("Safety copy", SavedProgressRules.reasonLabel("safety"))
    }
}
