package io.github.bandoracer.rommio.domain.progress

import io.github.bandoracer.rommio.domain.player.CoreMemorySnapshot
import io.github.bandoracer.rommio.domain.player.PlayerEngine
import io.github.bandoracer.rommio.domain.player.PlayerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

sealed interface ProgressLaunchRequest {
    data object Automatic : ProgressLaunchRequest
    data object ColdBootCurrent : ProgressLaunchRequest
    data class ResumeBranch(val operationId: String) : ProgressLaunchRequest
    data class ContinueBranch(val operationId: String, val useState: Boolean) : ProgressLaunchRequest
}

sealed interface ProgressLaunchDecision {
    data class ContinueLocal(val reason: String) : ProgressLaunchDecision
    data class UnsupportedFormat(val reason: String) : ProgressLaunchDecision
    data class Ready internal constructor(
        val identity: ProgressIdentity,
        val qualification: MemoryOnlyPersistence,
        val checkpointId: String?,
        val useState: Boolean,
        val baselineParents: Map<String, List<String>>,
        val observedHeads: Map<String, List<String>>,
        val message: String,
    ) : ProgressLaunchDecision
    data class ChooseBranch(val current: List<ProgressReferenceHead>, val resume: List<ProgressReferenceHead> = emptyList()) : ProgressLaunchDecision
    data class OfferColdBoot(val checkpointId: String, val reason: String) : ProgressLaunchDecision
    data class RecoveryRequired(val reason: String) : ProgressLaunchDecision
}

/** Local-only launch selection. It does not reconcile cloud heads or infer legacy ownership. */
class ProgressLaunchPlanner(private val store: ProgressStore) {
    suspend fun prepare(workspace: ProgressWorkspace, engine: PlayerEngine, registry: FceummPersistenceRegistry,
        request: ProgressLaunchRequest = ProgressLaunchRequest.Automatic, legacyProgressPresent: Boolean = false): ProgressLaunchDecision {
        if (store.recoverWorkingSessions(workspace.identity).isNotEmpty()) {
            return ProgressLaunchDecision.RecoveryRequired("An interrupted session has retained files. Recover or explicitly discard them before starting another game.")
        }
        return prepare(workspace.session, engine, registry, request, legacyProgressPresent)
    }

    /** Production entry point: derive the contract from verified bytes and the effective loaded layout. */
    suspend fun prepare(
        session: PlayerSession,
        engine: PlayerEngine,
        registry: FceummPersistenceRegistry,
        request: ProgressLaunchRequest = ProgressLaunchRequest.Automatic,
        legacyProgressPresent: Boolean = false,
    ): ProgressLaunchDecision {
        require(session.initialSaveRam == null) { "Prepare an isolated cold session before selecting saved progress." }
        engine.awaitReady()
        val actual = engine.prepareForProgressSelection()
        return when (val support = registry.qualify(session, actual)) {
            is CorePersistenceSupport.Unsupported -> ProgressLaunchDecision.UnsupportedFormat(support.reason)
            is CorePersistenceSupport.Qualified -> plan(store.resolveIdentity(requireNotNull(session.progressBinding)),
                support.contract, actual, request, legacyProgressPresent)
        }
    }

    suspend fun plan(
        identity: ProgressIdentity,
        qualification: MemoryOnlyPersistence,
        actual: CoreMemorySnapshot,
        request: ProgressLaunchRequest = ProgressLaunchRequest.Automatic,
        legacyProgressPresent: Boolean = false,
    ): ProgressLaunchDecision {
        require(actual.binding == identity.binding && actual.memory.keys == qualification.inventory)
        val core = requireNotNull(actual.coreIdentity)
        require(core.runtimeId == qualification.coreId && core.sha256 == qualification.coreSha256 && core.abi == qualification.architecture)
        require(qualification.restoreOrder in setOf(MemoryRestoreOrder.STATE_THEN_MEMORY, MemoryRestoreOrder.FILES_THEN_STATE))
        qualification.validateMemory(actual)
        val references = store.referenceSnapshot(identity)
        if (references.containsKey("game/current")) return planModern(identity, qualification, actual, request, references)
        val heads = references["save/current"].orEmpty()
        val observed = references.mapValues { (_, values) -> values.map { it.operationId } }
        val selected = when (request) {
            is ProgressLaunchRequest.ContinueBranch -> heads.singleOrNull { it.operationId == request.operationId }
                ?: return ProgressLaunchDecision.RecoveryRequired("The selected branch changed. Refresh the available progress before continuing.")
            else -> {
                if (heads.size > 1) return ProgressLaunchDecision.ChooseBranch(heads)
                heads.singleOrNull()
            }
        }
        val parents = if (request is ProgressLaunchRequest.ContinueBranch) {
            // Continuing one branch does not resolve or acknowledge other observed branches.
            selected!!.changedReferences.associateWith { listOf(selected.operationId) }
        } else references.filterValues { it.size == 1 }.mapValues { (_, values) -> listOf(values.single().operationId) }
        fun ready(checkpointId: String?, state: Boolean, message: String): ProgressLaunchDecision.Ready {
            val guarded = setOfNotNull("save/current", if (state) "resume/${ProgressProtocol.stateCompatibilityKey(requireNotNull(selected?.checkpoint))}" else null)
            return ProgressLaunchDecision.Ready(identity, qualification, checkpointId, state, parents,
                guarded.associateWith { observed[it].orEmpty() }, "$message Cloud progress not checked.")
        }
        if (selected == null) {
            if (legacyProgressPresent) return ProgressLaunchDecision.RecoveryRequired("Existing saves need an explicit recovery choice before starting the new save format.")
            if (references.isNotEmpty()) return ProgressLaunchDecision.RecoveryRequired("Saved history exists without current progress. Choose a recovery checkpoint before starting fresh.")
            return ready(null, false, "Starting fresh on this device.")
        }
        val checkpoint = selected.checkpoint ?: return ready(null, false, "Current progress was explicitly deleted.")
        val loader = MemoryCheckpointLoader(store, identity, qualification)
        // Prove the complete in-game-save path first, independently of state compatibility/health.
        try { loader.load(checkpoint.checkpointId, actual, includeState = false) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { return ProgressLaunchDecision.RecoveryRequired(error.message ?: "In-game save data needs recovery.") }

        if (request == ProgressLaunchRequest.ColdBootCurrent ||
            request is ProgressLaunchRequest.ContinueBranch && !request.useState || checkpoint.stateCompatibility == null) {
            return ready(checkpoint.checkpointId, false, "Starting with this checkpoint's in-game save data.")
        }
        val state = checkpoint.stateCompatibility
        val compatible = state.coreId == core.runtimeId && state.coreSha256 == core.sha256 &&
            state.architecture == core.abi && state.platform == "android" && state.stateFormat == qualification.stateFormat &&
            state.options == actual.effectiveOptions && state.systemFiles == actual.firmwareSha256
        if (!compatible) return ProgressLaunchDecision.OfferColdBoot(checkpoint.checkpointId,
            "Resume is incompatible with this core, options, or firmware. The matching in-game save data is available.")
        val resumeReference = "resume/${ProgressProtocol.stateCompatibilityKey(checkpoint)}"
        val resumeHeads = references[resumeReference].orEmpty()
        if (request == ProgressLaunchRequest.Automatic) {
            if (resumeHeads.size > 1) return ProgressLaunchDecision.ChooseBranch(heads, resumeHeads)
            if (resumeHeads.singleOrNull()?.operationId != selected.operationId) {
                return ready(checkpoint.checkpointId, false, "Automatic resume is not current. Using the latest in-game save data.")
            }
        }
        try { loader.load(checkpoint.checkpointId, actual, includeState = true) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { return ProgressLaunchDecision.OfferColdBoot(checkpoint.checkpointId,
            "Resume could not be verified. Its matching in-game save data is available. ${error.message.orEmpty()}") }
        return ready(checkpoint.checkpointId, true, "Resuming this device's selected checkpoint.")
    }

    private suspend fun planModern(identity: ProgressIdentity, qualification: MemoryOnlyPersistence, actual: CoreMemorySnapshot,
        request: ProgressLaunchRequest, references: Map<String, List<ProgressReferenceHead>>): ProgressLaunchDecision {
        val games = references["game/current"].orEmpty()
        val game = if (request is ProgressLaunchRequest.ContinueBranch) games.singleOrNull { it.operationId == request.operationId }
            else games.singleOrNull()
        if (game == null) return ProgressLaunchDecision.ChooseBranch(games)
        val parents = references.filterValues { it.size == 1 }.mapValues { it.value.map { h -> h.operationId } }.toMutableMap()
        if (request is ProgressLaunchRequest.ContinueBranch) parents["game/current"] = listOf(game.operationId)
        val observed = references.mapValues { it.value.map { h -> h.operationId } }
        fun ready(cp: ProgressCheckpoint?, state: Boolean, message: String) = ProgressLaunchDecision.Ready(identity, qualification,
            cp?.checkpointId, state, parents.toMap(), observed.filterKeys { it == "game/current" || (state && it.startsWith("state/resume/")) }, message)
        val saved = game.checkpoint ?: return ready(null, false, "Game-save data was explicitly deleted.")
        val loader = MemoryCheckpointLoader(store, identity, qualification)
        try { loader.load(saved.checkpointId, actual, false) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { return ProgressLaunchDecision.RecoveryRequired(error.message ?: "Game-save data is unavailable.") }
        if (request == ProgressLaunchRequest.ColdBootCurrent || request is ProgressLaunchRequest.ContinueBranch && !request.useState)
            return ready(saved, false, "Starting with game-save data.")
        val states = references.filterKeys { it.startsWith("state/resume/") }.values.flatten().filter { head ->
            val cp = head.checkpoint
            cp != null && cp.saveCompatibility == saved.saveCompatibility && ProgressProtocol.gameArtifacts(cp) == ProgressProtocol.gameArtifacts(saved) &&
                store.isGameAncestor(identity, requireNotNull(cp.gameSaveOperationId), game.operationId)
        }
        val core = requireNotNull(actual.coreIdentity)
        val compatible = states.filter { head -> head.checkpoint?.stateCompatibility?.let { state ->
            state.coreId == core.runtimeId && state.coreSha256 == core.sha256 && state.architecture == core.abi &&
                state.platform == "android" && state.stateFormat == qualification.stateFormat && state.options == actual.effectiveOptions && state.systemFiles == actual.firmwareSha256
        } == true }
        if (compatible.isEmpty()) return if (states.isEmpty()) ready(saved, false, "Game-save data is available; no matching resume snapshot.")
            else ProgressLaunchDecision.OfferColdBoot(saved.checkpointId, "Resume snapshots are incompatible with this core. Your game-save data is available.")
        val stateHead = if (request is ProgressLaunchRequest.ResumeBranch) compatible.singleOrNull { it.operationId == request.operationId }
            else compatible.singleOrNull()
        if (stateHead == null) return ProgressLaunchDecision.ChooseBranch(games, compatible)
        stateHead.changedReferences.filter { it.startsWith("state/resume/") }.forEach { parents[it] = listOf(stateHead.operationId) }
        val state = requireNotNull(stateHead.checkpoint)
        try { loader.load(state.checkpointId, actual, true) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { return ProgressLaunchDecision.OfferColdBoot(saved.checkpointId, "Resume snapshot is unavailable. Your game-save data is verified and can be loaded.") }
        return ready(state, true, "Resuming the saved snapshot and matching game-save data.")
    }

    /** Starts paused with the frozen causal base. Callers must keep the view attached until exit completes. */
    suspend fun start(
        prepared: ProgressLaunchDecision.Ready,
        engine: PlayerEngine,
        deviceId: String,
        stagingRoot: File,
        publish: Boolean,
        workspace: ProgressWorkspace? = null,
        version: Int = 2,
    ): ProgressSessionCoordinator {
        val lease = if (workspace == null) store.acquireSession(prepared.identity, prepared.checkpointId, prepared.observedHeads)
            else store.selectWorkspace(workspace, prepared)
        var coordinator: ProgressSessionCoordinator? = null
        try {
            currentCoroutineContext().ensureActive()
            val writer = SessionCheckpointWriter(store, prepared.identity, prepared.qualification, deviceId,
                workspace?.sessionId ?: UUID.randomUUID().toString(), prepared.baselineParents,
                workspace?.stagingDirectory ?: stagingRoot, engine::captureMemorySnapshot, publish, version)
            val owner = ProgressSessionCoordinator(engine, writer, MemoryCheckpointLoader(store, prepared.identity, prepared.qualification),
                onNativeClosed = { store.releaseSession(lease, writer.committedFileBundle) })
            coordinator = owner
            owner.start(prepared.checkpointId, prepared.useState).await()
            return owner
        } catch (error: Exception) {
            // The accepted start outlives its waiter; retain ownership until it has stopped.
            withContext(NonCancellable) {
                val owner = coordinator
                if (owner == null) {
                    engine.close()
                    store.releaseSession(lease)
                } else if (owner.status.value.phase !in setOf(ProgressSessionPhase.CLOSED, ProgressSessionPhase.STOPPED)) {
                    owner.exitWithoutLatestProgress().await()
                }
            }
            throw error
        }
    }
}
