package io.github.bandoracer.rommio.domain.playdata

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A checkpoint as the progress store identifies it; thumbnails and names are keyed by this. */
data class SaveKey(val namespaceId: String, val contentId: String, val checkpointId: String)

/** What the player sees (save-sync-spec §11 terms). */
enum class SaveKind {
    /** A state the player made (manual save state or a slot). */
    SAVE_STATE,

    /** An automatic resume point (periodic, pause, background, exit, safety or restore capture with a state). */
    RESUME_POINT,

    /** Game-save data only (no emulator state). Shown in a game's history, never in Recent saves. */
    GAME_SAVE,
}

/** Remote state of the operation that committed a checkpoint (save-sync-spec §11 copy). */
enum class SaveSyncStatus(val label: String) {
    SYNCED("Synced"),
    SYNCING("Syncing"),
    WAITING("Waiting to sync"),
    LOCAL_ONLY("Local only"),
    SIGN_IN_NEEDED("Sign-in needed"),
    CONFLICT("Conflict"),
    UNKNOWN(""),
}

/** How this device describes itself in save rows. */
enum class DeviceFormFactor(val thisDeviceLabel: String) {
    PHONE("This phone"),
    TABLET("This tablet"),
    UNKNOWN("This device"),
}

/** One committed checkpoint and what the store says about it; the input to [SavedProgressRules]. */
data class StoredCheckpoint(
    val key: SaveKey,
    val romId: Long,
    val fileId: Long?,
    val operationId: String,
    val capturedAtEpochMs: Long,
    val captureReason: String,
    val deviceId: String,
    val hasState: Boolean,
    /** Made by the player (history marks manual captures and slot references). */
    val manual: Boolean,
    /** Artifacts are kept on this device (not evicted by retention). */
    val retained: Boolean,
    /** References whose current head selects this checkpoint (e.g. `state/resume/…`, `state/slot/…`). */
    val currentReferences: Set<String> = emptySet(),
    /** The subset of [currentReferences] that has more than one head (an unresolved conflict). */
    val conflictedReferences: Set<String> = emptySet(),
    /** Outbox status of [operationId]: PENDING, LEASED, PUBLISHED, LOCAL_ONLY, PARKED_AUTH. */
    val outboxStatus: String? = null,
    /** The platform whose player made the state (`android`, `ios`), when it has one. */
    val statePlatform: String? = null,
)

data class SaveEntry(
    val key: SaveKey,
    val romId: Long,
    val fileId: Long?,
    val operationId: String,
    val kind: SaveKind,
    val capturedAtEpochMs: Long,
    val captureReason: String,
    val deviceId: String,
    val fromThisDevice: Boolean,
    /** "This phone", "This tablet", "This device" or "Another device". */
    val deviceLabel: String,
    val sync: SaveSyncStatus,
    /** The player's name for this save state, if any. */
    val label: String?,
    /** Quick-save slot number (1–4) when this is the current revision of a numbered slot. */
    val slotNumber: Int?,
    /** Current head of an automatic resume reference. */
    val isCurrentResume: Boolean,
    /** Current head of a slot reference. */
    val isCurrentSlot: Boolean,
    /** Has a state and its artifacts are kept on this device. */
    val loadable: Boolean,
    val thumbnailPath: String?,
    /** Current head of the game's game-save reference (`game/current`, or v2 `save/current`). */
    val isCurrentGameSave: Boolean = false,
    /** Its state was made by a player on another platform (e.g. iOS): this device can't resume it. */
    val foreignState: Boolean = false,
) {
    /** Title for a save row: the player's name, then the slot, then the kind. */
    val title: String
        get() = label ?: when (kind) {
            SaveKind.SAVE_STATE -> slotNumber?.let { "Save state · Slot $it" } ?: "Save state"
            SaveKind.RESUME_POINT -> if (isCurrentResume) "Resume point" else "Earlier resume point"
            SaveKind.GAME_SAVE -> "Game-save data"
        }

    val kindLabel: String
        get() = when (kind) {
            SaveKind.SAVE_STATE -> "Save state"
            SaveKind.RESUME_POINT -> "Resume point"
            SaveKind.GAME_SAVE -> "Game-save data"
        }
}

data class SaveDay(val day: LocalDate, val entries: List<SaveEntry>)

/** Which reference has more than one head (docs/theme-layouts.md "Conflict"). */
enum class SaveConflictKind(
    /** What each choice is, for "The other … stays in this game's saved progress." */
    val noun: String,
    /** Plural for counts ("2 resume points"). */
    val plural: String,
) {
    RESUME("resume point", "resume points"),
    GAME_SAVE("game-save data", "game-save data"),
}

/**
 * A reference with more than one current head: a choice is needed before the game loads
 * automatically. [choices] are newest first; the Conflict sheet selects the first.
 */
data class SaveConflict(val kind: SaveConflictKind, val choices: List<SaveEntry>) {
    /** "EarthBound has two resume points. Choose which to keep playing." (iOS wording). */
    fun prompt(gameName: String): String = prompt(gameName, kind, choices.size)

    /** The chip on Play and the Game page: "Two resume points", "Three game saves", "5 resume points". */
    val chipTitle: String get() = chipTitle(kind, choices.size)

    /** Riso's kicker detail: "2 resume points", "2 game-save data". */
    val countLabel: String get() = "${choices.size} ${if (choices.size == 1) kind.noun else kind.plural}"

    companion object {
        fun prompt(gameName: String, kind: SaveConflictKind, count: Int): String {
            val number = when (count) { 2 -> "two"; 3 -> "three"; else -> count.toString() }
            return when (kind) {
                SaveConflictKind.RESUME -> "$gameName has $number resume points. Choose which to keep playing."
                SaveConflictKind.GAME_SAVE -> "$gameName has $number versions of its game-save data. Choose which to keep playing."
            }
        }

        fun chipTitle(kind: SaveConflictKind, count: Int): String {
            val number = when (count) { 2 -> "Two"; 3 -> "Three"; else -> count.toString() }
            return when (kind) {
                SaveConflictKind.RESUME -> "$number resume points"
                SaveConflictKind.GAME_SAVE -> "$number game saves"
            }
        }
    }
}

/** All saves for one game, newest first within each group. */
data class GameSaves(
    /** The current resume point (newest if several heads), or null. */
    val resume: SaveEntry? = null,
    /** More than one current resume head: a choice is needed before loading automatically. */
    val resumeConflict: Boolean = false,
    /** Every current resume head, newest first (two or more when [resumeConflict]); for the Conflict sheet. */
    val resumeHeads: List<SaveEntry> = emptyList(),
    val saveStates: List<SaveEntry> = emptyList(),
    /** Earlier resume points still in local history. */
    val history: List<SaveEntry> = emptyList(),
    /** Game-save data captures (no emulator state), newest first. */
    val gameSaveData: List<SaveEntry> = emptyList(),
    /**
     * A reference the launch would stop to ask about: two or more resume points, else two or more
     * versions of the game-save data. The Conflict sheet resolves it before the player opens.
     */
    val conflict: SaveConflict? = null,
) {
    val isEmpty: Boolean get() = resume == null && saveStates.isEmpty() && history.isEmpty() && gameSaveData.isEmpty()

    /**
     * The Launch choice (docs/theme-layouts.md "Launch choice"): a resume point this device can
     * load and game-save data to start from instead. Null when there is nothing to choose.
     */
    val launchOptions: LaunchOptions?
        get() {
            if (conflict != null) return null
            val resume = resume ?: return null
            val gameSave = gameSaveData.firstOrNull() ?: return null
            return LaunchOptions(resume = resume, gameSave = gameSave, resumeUnavailable = !resume.loadable || resume.foreignState)
        }
}

/** How a launch starts (the planner's request: Automatic resumes; the game save cold-boots). */
enum class LaunchStart(val routeValue: String) {
    RESUME("resume"),
    GAME_SAVE("gameSave"),
    ;

    companion object {
        fun fromRoute(value: String?): LaunchStart? = entries.firstOrNull { it.routeValue == value }
    }
}

/**
 * What the Launch choice sheet offers for one game. When the resume point can't load on this
 * device ([resumeUnavailable]: made by another platform's player, or its state isn't kept here) the
 * planner would stop in the player to offer the game save, so the choice is made before it opens.
 */
data class LaunchOptions(val resume: SaveEntry, val gameSave: SaveEntry, val resumeUnavailable: Boolean = false) {
    /** The default selection: Resume when it can load, else the game save. */
    val default: LaunchStart get() = if (resumeUnavailable) LaunchStart.GAME_SAVE else LaunchStart.RESUME
}

/**
 * Pure rules for the cross-game saves reader (docs/play-data-local.md, "Saves reader").
 * Platform code gathers [StoredCheckpoint]s read-only from the progress store; everything the
 * pages show is decided here.
 */
object SavedProgressRules {
    const val ANOTHER_DEVICE = "Another device"

    /** The stable quick-save slot ids the player uses (`UUID.nameUUIDFromBytes("rommio-slot-N")`). */
    private val quickSlots: Map<String, Int> = (1..4).associateBy { slot ->
        UUID.nameUUIDFromBytes("rommio-slot-$slot".toByteArray()).toString()
    }

    fun slotId(reference: String): String? = when {
        reference.startsWith("state/slot/") -> reference.removePrefix("state/slot/")
        reference.startsWith("slot/") -> reference.removePrefix("slot/")
        else -> null
    }

    fun isResumeReference(reference: String): Boolean = reference.startsWith("state/resume/") || reference.startsWith("resume/")

    fun kind(hasState: Boolean, manual: Boolean): SaveKind = when {
        !hasState -> SaveKind.GAME_SAVE
        manual -> SaveKind.SAVE_STATE
        else -> SaveKind.RESUME_POINT
    }

    fun syncStatus(outboxStatus: String?, conflicted: Boolean): SaveSyncStatus = when {
        conflicted -> SaveSyncStatus.CONFLICT
        outboxStatus == "PUBLISHED" -> SaveSyncStatus.SYNCED
        outboxStatus == "LEASED" -> SaveSyncStatus.SYNCING
        outboxStatus == "PENDING" -> SaveSyncStatus.WAITING
        outboxStatus == "LOCAL_ONLY" -> SaveSyncStatus.LOCAL_ONLY
        outboxStatus == "PARKED_AUTH" -> SaveSyncStatus.SIGN_IN_NEEDED
        else -> SaveSyncStatus.UNKNOWN
    }

    fun deviceLabel(checkpointDeviceId: String, localDeviceId: String?, formFactor: DeviceFormFactor): String =
        if (localDeviceId != null && checkpointDeviceId == localDeviceId) formFactor.thisDeviceLabel else ANOTHER_DEVICE

    /** Plain words for a checkpoint's capture reason. */
    fun reasonLabel(reason: String): String = when (reason) {
        "manual" -> "Manual"
        "periodic" -> "Automatic"
        "pause" -> "Paused"
        "background" -> "App in background"
        "exit" -> "Left game"
        "safety" -> "Safety copy"
        "restore" -> "Loaded state"
        "import" -> "Imported"
        else -> reason.replaceFirstChar { it.uppercase() }
    }

    fun entry(
        stored: StoredCheckpoint,
        labels: Map<SaveKey, String>,
        localDeviceId: String?,
        formFactor: DeviceFormFactor,
        thumbnail: (SaveKey) -> String?,
    ): SaveEntry {
        val kind = kind(stored.hasState, stored.manual)
        val slot = stored.currentReferences.firstNotNullOfOrNull(::slotId)
        return SaveEntry(
            key = stored.key,
            romId = stored.romId,
            fileId = stored.fileId,
            operationId = stored.operationId,
            kind = kind,
            capturedAtEpochMs = stored.capturedAtEpochMs,
            captureReason = stored.captureReason,
            deviceId = stored.deviceId,
            fromThisDevice = localDeviceId != null && stored.deviceId == localDeviceId,
            deviceLabel = deviceLabel(stored.deviceId, localDeviceId, formFactor),
            sync = syncStatus(stored.outboxStatus, stored.conflictedReferences.isNotEmpty()),
            label = labels[stored.key]?.takeIf { it.isNotBlank() && kind != SaveKind.GAME_SAVE },
            slotNumber = slot?.let(quickSlots::get),
            isCurrentResume = stored.currentReferences.any(::isResumeReference),
            isCurrentSlot = slot != null,
            loadable = stored.hasState && stored.retained,
            thumbnailPath = if (stored.hasState) thumbnail(stored.key) else null,
            isCurrentGameSave = stored.currentReferences.any(::isGameSaveReference),
            foreignState = stored.hasState && stored.statePlatform != null && stored.statePlatform != THIS_PLATFORM,
        )
    }

    /** The platform a state must come from to load here (`ProgressStateCompatibility.platform`). */
    const val THIS_PLATFORM = "android"

    fun isGameSaveReference(reference: String): Boolean = reference == "game/current" || reference == "save/current"

    private val newestFirst = compareByDescending<SaveEntry> { it.capturedAtEpochMs }
        .thenByDescending { it.isCurrentResume || it.isCurrentSlot }
        .thenBy { it.key.checkpointId }

    /**
     * Recent saves across games: every save state still on this device plus each game's current
     * resume point, newest first. Earlier automatic resume points and game-save data are left out.
     */
    fun recent(entries: List<SaveEntry>, limit: Int): List<SaveEntry> = entries
        .asSequence()
        .filter { (it.kind == SaveKind.SAVE_STATE && (it.loadable || it.isCurrentSlot)) || (it.kind == SaveKind.RESUME_POINT && it.isCurrentResume) }
        .distinctBy { it.key }
        .sortedWith(newestFirst)
        .take(limit)
        .toList()

    /** One game's saves, split into its current resume point, save states and the rest of its history. */
    fun forGame(entries: List<SaveEntry>): GameSaves {
        val unique = entries.distinctBy { it.key }.sortedWith(newestFirst)
        val resumeHeads = unique.filter { it.isCurrentResume }
        val resume = resumeHeads.firstOrNull()
        val states = unique.filter { it.kind == SaveKind.SAVE_STATE && it.key != resume?.key }
        val history = unique.filter { it.kind == SaveKind.RESUME_POINT && it.key != resume?.key }
        val gameSaveData = unique.filter { it.kind == SaveKind.GAME_SAVE }
        val gameSaveHeads = unique.filter { it.isCurrentGameSave }
        val conflict = when {
            resumeHeads.size > 1 -> SaveConflict(SaveConflictKind.RESUME, resumeHeads)
            gameSaveHeads.size > 1 -> SaveConflict(SaveConflictKind.GAME_SAVE, gameSaveHeads)
            else -> null
        }
        return GameSaves(resume = resume, resumeConflict = conflict != null,
            resumeHeads = resumeHeads, saveStates = states, history = history, gameSaveData = gameSaveData, conflict = conflict)
    }

    /** Groups newest-first entries by local calendar day, keeping order. */
    fun groupByDay(entries: List<SaveEntry>, zone: ZoneId): List<SaveDay> = entries
        .groupBy { Instant.ofEpochMilli(it.capturedAtEpochMs).atZone(zone).toLocalDate() }
        .map { (day, list) -> SaveDay(day, list) }
        .sortedByDescending { it.day }

    /** Normalises a name typed by the player; blank means "remove the name". */
    fun normalizeLabel(raw: String): String? = raw.trim().replace(Regex("\\s+"), " ").take(MAX_LABEL_LENGTH).takeIf { it.isNotEmpty() }

    const val MAX_LABEL_LENGTH = 60
}
