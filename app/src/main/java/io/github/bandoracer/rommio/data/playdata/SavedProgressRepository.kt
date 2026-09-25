package io.github.bandoracer.rommio.data.playdata

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import io.github.bandoracer.rommio.data.auth.VerifiedAccount
import io.github.bandoracer.rommio.data.local.PlayDataDao
import io.github.bandoracer.rommio.data.local.SaveStateLabelEntity
import io.github.bandoracer.rommio.domain.playdata.DeviceFormFactor
import io.github.bandoracer.rommio.domain.playdata.GameSaves
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveKey
import io.github.bandoracer.rommio.domain.playdata.SavedProgressRules
import io.github.bandoracer.rommio.domain.playdata.StoredCheckpoint
import io.github.bandoracer.rommio.domain.progress.ProgressHistoryEntry
import io.github.bandoracer.rommio.domain.progress.ProgressIdentity
import io.github.bandoracer.rommio.domain.progress.ProgressLocalRetention
import io.github.bandoracer.rommio.domain.progress.ProgressProtocol
import io.github.bandoracer.rommio.domain.progress.ProgressStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * "This phone" / "This tablet" only when the form factor is unambiguous: foldables, desktops,
 * TVs and cars say "This device".
 */
object DeviceFormFactors {
    fun resolve(context: Context): DeviceFormFactor {
        val packages = context.packageManager
        val ambiguous = listOf(PackageManager.FEATURE_LEANBACK, PackageManager.FEATURE_AUTOMOTIVE, PackageManager.FEATURE_PC)
            .any(packages::hasSystemFeature) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && packages.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE))
        if (ambiguous) return DeviceFormFactor.UNKNOWN
        val smallestWidth = context.resources.configuration.smallestScreenWidthDp
        return when {
            smallestWidth == Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED -> DeviceFormFactor.UNKNOWN
            smallestWidth >= 600 -> DeviceFormFactor.TABLET
            else -> DeviceFormFactor.PHONE
        }
    }
}

/**
 * Read-only view of saved progress across games for the signed-in account (docs/play-data-local.md,
 * "Saves reader"). Uses only the progress store's read APIs (`identities`, `referenceSnapshot`,
 * `history`, `publicationStatus`); names and thumbnails come from local play data. It never
 * creates identities, commits, pins or changes references.
 */
class SavedProgressRepository(
    private val storeProvider: suspend () -> ProgressStore,
    private val accountProvider: suspend () -> VerifiedAccount?,
    private val localDeviceId: suspend () -> String?,
    private val dao: PlayDataDao,
    private val thumbnails: SaveThumbnailStore,
    private val formFactor: () -> DeviceFormFactor,
) {
    private val mutex = Mutex()
    private val recentState = MutableStateFlow<List<SaveEntry>>(emptyList())
    private val latestState = MutableStateFlow<Map<Long, SaveEntry>>(emptyMap())
    private val labelRevision = MutableStateFlow(0L)

    /** Newest first; empty until the first [refreshRecent]. */
    val recent: StateFlow<List<SaveEntry>> = recentState.asStateFlow()

    /**
     * Each game's latest place to continue from (its current resume point, else a save state),
     * by ROM id: the screens on Aurora Play. Read with [recent], from every save (not only the
     * newest shown).
     */
    val latest: StateFlow<Map<Long, SaveEntry>> = latestState.asStateFlow()

    /** Bumps after a rename so open pages can re-read. */
    val labelChanges: StateFlow<Long> = labelRevision.asStateFlow()

    /** Recomputes Recent saves. Failures (storage busy during an import) keep the last value. */
    suspend fun refreshRecent(limit: Int = RECENT_LIMIT): List<SaveEntry> = mutex.withLock {
        val (next, latest) = try {
            withContext(Dispatchers.IO) {
                val context = readContext() ?: return@withContext emptyList<SaveEntry>() to emptyMap()
                val entries = context.identities.flatMap { identity -> entriesFor(context, identity, prune = true) }
                SavedProgressRules.recent(entries, limit) to SavedProgressRules.latestByGame(entries)
            }
        } catch (error: CancellationException) { throw error } catch (_: Exception) { return@withLock recentState.value }
        latestState.value = latest
        recentState.value = next
        next
    }

    /**
     * One game's saves. [progressScope] (the install's binding key) picks the exact content
     * generation; without it every generation of [romId] for this account is included.
     */
    suspend fun gameSaves(romId: Long, progressScope: String?): GameSaves = withContext(Dispatchers.IO) {
        try {
            val context = readContext() ?: return@withContext GameSaves()
            val identities = context.identities.filter { identity ->
                identity.binding.romId == romId && (progressScope == null || ProgressProtocol.bindingKey(identity.binding) == progressScope)
            }
            SavedProgressRules.forGame(identities.flatMap { entriesFor(context, it, prune = true) })
        } catch (error: CancellationException) { throw error } catch (_: Exception) { GameSaves() }
    }

    /** Describes entries a live player session already read (its own history page). */
    suspend fun describe(identity: ProgressIdentity, entries: List<ProgressHistoryEntry>): List<SaveEntry> = withContext(Dispatchers.IO) {
        try {
            val store = storeProvider()
            val stored = storedCheckpoints(store, identity, entries)
            val labels = labelsFor(identity)
            val device = localDeviceId()
            stored.map { SavedProgressRules.entry(it, labels, device, formFactor(), thumbnails::pathIfPresent) }
        } catch (error: CancellationException) { throw error } catch (_: Exception) { emptyList() }
    }

    /** Names a save state; blank removes the name. Local only: the checkpoint itself is unchanged. */
    suspend fun rename(key: SaveKey, raw: String) = withContext(Dispatchers.IO) {
        val label = SavedProgressRules.normalizeLabel(raw)
        if (label == null) dao.deleteLabel(key.namespaceId, key.contentId, key.checkpointId)
        else dao.upsertLabel(SaveStateLabelEntity(key.namespaceId, key.contentId, key.checkpointId, label, System.currentTimeMillis()))
        recentState.update { list -> list.map { if (it.key == key) it.copy(label = label) else it } }
        latestState.update { map -> map.mapValues { (_, entry) -> if (entry.key == key) entry.copy(label = label) else entry } }
        labelRevision.update { it + 1 }
    }

    private class ReadContext(val store: ProgressStore, val identities: List<ProgressIdentity>, val device: String?)

    private suspend fun readContext(): ReadContext? {
        val account = accountProvider() ?: return null
        val store = storeProvider()
        val accountId = account.userId.toString()
        val identities = store.identities().filter { it.binding.serverKey == account.baseUrl && it.binding.accountId == accountId }
        return ReadContext(store, identities, localDeviceId())
    }

    private suspend fun entriesFor(context: ReadContext, identity: ProgressIdentity, prune: Boolean): List<SaveEntry> {
        val history = mutableListOf<ProgressHistoryEntry>()
        var before = Long.MAX_VALUE
        var complete = false
        while (history.size < MAX_HISTORY) {
            val page = context.store.history(identity, before, 100)
            history += page.entries
            val next = page.beforeSequence
            if (next == null) { complete = true; break }
            before = next
        }
        val stored = storedCheckpoints(context.store, identity, history)
        if (prune && complete) {
            runCatching {
                thumbnails.prune(identity.namespaceId, identity.contentId,
                    stored.filter { it.retained && it.hasState }.map { it.key.checkpointId }.toSet())
            }
        }
        val labels = labelsFor(identity)
        return stored.map { SavedProgressRules.entry(it, labels, context.device, formFactor(), thumbnails::pathIfPresent) }
    }

    private suspend fun labelsFor(identity: ProgressIdentity): Map<SaveKey, String> =
        dao.labelsFor(identity.namespaceId, identity.contentId).associate { SaveKey(it.namespaceId, it.contentId, it.checkpointId) to it.label }

    private suspend fun storedCheckpoints(store: ProgressStore, identity: ProgressIdentity, history: List<ProgressHistoryEntry>): List<StoredCheckpoint> {
        val current = mutableMapOf<String, MutableSet<String>>()
        val conflicted = mutableMapOf<String, MutableSet<String>>()
        val headOperation = mutableMapOf<String, String>()
        val headCheckpoints = mutableMapOf<String, io.github.bandoracer.rommio.domain.progress.ProgressCheckpoint>()
        store.referenceSnapshot(identity).forEach { (reference, heads) ->
            heads.forEach { head ->
                val checkpoint = head.checkpoint ?: return@forEach
                current.getOrPut(checkpoint.checkpointId) { mutableSetOf() } += reference
                if (heads.size > 1) conflicted.getOrPut(checkpoint.checkpointId) { mutableSetOf() } += reference
                headOperation.putIfAbsent(checkpoint.checkpointId, head.operationId)
                headCheckpoints.putIfAbsent(checkpoint.checkpointId, checkpoint)
            }
        }
        val outbox = store.publicationStatus(identity).associate { it.operationId to it.status }
        val fileId = identity.binding.files.minByOrNull { it.discIndex }?.fileId
        fun stored(checkpoint: io.github.bandoracer.rommio.domain.progress.ProgressCheckpoint, operationId: String, manual: Boolean, retained: Boolean) = StoredCheckpoint(
            key = SaveKey(identity.namespaceId, identity.contentId, checkpoint.checkpointId),
            romId = identity.binding.romId,
            fileId = fileId,
            operationId = operationId,
            capturedAtEpochMs = checkpoint.createdAtEpochMs,
            captureReason = checkpoint.captureReason,
            deviceId = checkpoint.deviceId,
            hasState = checkpoint.stateCompatibility != null,
            manual = manual,
            retained = retained,
            currentReferences = current[checkpoint.checkpointId].orEmpty(),
            conflictedReferences = conflicted[checkpoint.checkpointId].orEmpty(),
            outboxStatus = outbox[operationId],
            statePlatform = checkpoint.stateCompatibility?.platform,
        )
        val fromHistory = history.map { entry ->
            stored(entry.checkpoint, entry.operationId, entry.manual, entry.retention == ProgressLocalRetention.RETAINED)
        }
        // Heads older than the pages read are still current; include them from the snapshot.
        val seen = fromHistory.map { it.key.checkpointId }.toSet()
        val olderHeads = headCheckpoints.filterKeys { it !in seen }.map { (id, checkpoint) ->
            val manual = checkpoint.captureReason == "manual" || current[id].orEmpty().any { SavedProgressRules.slotId(it) != null }
            stored(checkpoint, headOperation.getValue(id), manual, retained = true)
        }
        return fromHistory + olderHeads
    }

    companion object {
        const val RECENT_LIMIT = 24
        private const val MAX_HISTORY = 200
    }
}
