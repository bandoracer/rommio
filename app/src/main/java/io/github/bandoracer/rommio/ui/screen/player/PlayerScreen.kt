package io.github.bandoracer.rommio.ui.screen.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import io.github.bandoracer.rommio.ui.component.themedSurface
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.domain.input.ActiveInputMode
import io.github.bandoracer.rommio.domain.input.HotkeyAction
import io.github.bandoracer.rommio.domain.input.PlayerOrientationPolicy
import io.github.bandoracer.rommio.domain.input.PlayerStickTuning
import io.github.bandoracer.rommio.domain.input.PlayerControlsState
import io.github.bandoracer.rommio.domain.input.TouchLayoutProfile
import io.github.bandoracer.rommio.domain.player.PlayerControllerDescriptor
import io.github.bandoracer.rommio.domain.progress.*
import io.github.bandoracer.rommio.domain.player.PlayerEngine
import io.github.bandoracer.rommio.domain.player.PlayerSession
import io.github.bandoracer.rommio.domain.player.startPreparedPlayer
import io.github.bandoracer.rommio.model.PlayerLaunchTarget
import io.github.bandoracer.rommio.domain.player.PlayerInputConfiguration
import io.github.bandoracer.rommio.model.GameSyncStatusKind
import io.github.bandoracer.rommio.model.BrowsableGameState
import io.github.bandoracer.rommio.model.BrowsableGameStateKind
import io.github.bandoracer.rommio.ui.component.StateBrowserContent
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    container: AppContainer,
    romId: Int,
    fileId: Int,
    /** A resume point's checkpoint id chosen on the Conflict sheet: resolves that branch choice. */
    keepResume: String? = null,
    /** How the Launch choice sheet starts the game (resume, or the game save); null resumes as usual. */
    start: io.github.bandoracer.rommio.domain.playdata.LaunchStart? = null,
    onBack: () -> Unit,
) {
    val viewModel: PlayerViewModel = viewModel(
        key = "player-$romId-$fileId",
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(
                    repository = container.repository,
                    controlsRepository = container.playerControlsRepository,
                    progressSessions = container.progressSessions,
                    romId = romId,
                    fileId = fileId,
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val appTheme = io.github.bandoracer.rommio.ui.theme.RommioTheme.current
    val visualTheme = remember(state.controls, appTheme) { resolvePlayerVisualTheme(state.controls, appTheme = appTheme) }
    val sheetTheme = remember(appTheme) { resolvePlayerVisualTheme(null, appTheme = appTheme) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val rootView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycleCompat()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var slot by rememberSaveable { mutableIntStateOf(1) }
    var showPauseSheet by rememberSaveable { mutableStateOf(false) }
    var showStateBrowserSheet by rememberSaveable { mutableStateOf(false) }
    var showControlsSheet by rememberSaveable { mutableStateOf(false) }
    var playerAttached by remember { mutableStateOf(false) }
    var nativeFailure by remember { mutableStateOf<String?>(null) }
    var runtimeControllers by remember { mutableStateOf<List<PlayerControllerDescriptor>>(emptyList()) }
    val legacyEngine = remember { container.createPlayerEngine() }
    val cloudMessages by container.progressSync.messages.collectAsStateWithLifecycleCompat()
    val profileBaseUrl = remember(container) { container.repository.activeProfileFlow() }.collectAsState(initial = null).value?.baseUrl
    val owner = state.progressSession
    val engine = owner?.engine ?: legacyEngine
    val ownerState by (owner?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(ApplicationProgressState()) })
        .collectAsStateWithLifecycleCompat()
    LaunchedEffect(owner, ownerState.phase) {
        if (owner != null) playerAttached = ownerState.phase == ApplicationProgressPhase.ACTIVE
    }
    val latestUiState = rememberUpdatedState(state)
    val latestPlayerAttached = rememberUpdatedState(playerAttached)

    ImmersivePlayerMode(enabled = true)
    PlayerOrientationMode(policy = state.controls?.platformProfile?.playerOrientationPolicy)

    DisposableEffect(legacyEngine) {
        onDispose { legacyEngine.detach() }
    }
    LaunchedEffect(engine) {
        engine.failureSignals().collect { message ->
            playerAttached = false
            nativeFailure = message
            if (owner == null) engine.detach()
        }
    }

    suspend fun captureContinuity(sessionActive: Boolean, onFinished: ((String) -> Unit)? = null): Boolean {
        val currentState = latestUiState.value
        if (!latestPlayerAttached.value || currentState.isLoading || currentState.resumeConflict != null) {
            return true
        }
        if (currentState.installation == null || currentState.rom == null) {
            return true
        }
        try {
            if (owner != null) {
                val result = if (sessionActive) owner.checkpoint("manual").await() else owner.finishExit().await()
                if (result.stateRejected) snackbarHostState.showSnackbar(result.stateError ?: "Game-save data saved. Resume snapshot failed; the previous snapshot is preserved.")
                return true
            }
            engine.persistSaveRam()
            engine.saveResumeState(container.libraryStore.continuityResumeStateFile(currentState.installation))
            viewModel.flushContinuity(sessionActive = sessionActive, onFinished = onFinished)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            snackbarHostState.showSnackbar("Game-save capture failed: ${error.message ?: "retry before leaving"}")
            return false
        }
        return true
    }

    suspend fun playerAction(action: suspend () -> Unit) {
        try { action() }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { snackbarHostState.showSnackbar(error.message ?: "Player action failed.") }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(playerAttached, state.controls?.hardwareBinding?.deadzone) {
        if (playerAttached) {
            engine.updateInputConfiguration(
                PlayerInputConfiguration(
                    deadzone = state.controls?.hardwareBinding?.deadzone ?: 0.2f,
                ),
            )
        }
    }

    LaunchedEffect(playerAttached, state.controls?.hardwareBinding?.controllerTypeId, showControlsSheet) {
        if (playerAttached) {
            playerAction {
                runtimeControllers = engine.availableControllerTypes()
                (state.controls?.hardwareBinding?.controllerTypeId ?: when (state.controls?.platformProfile?.familyId) {
                    "sega16" -> 513
                    "psx" -> 517
                    else -> null
                })?.let { controllerTypeId ->
                    engine.setControllerType(port = 0, controllerTypeId = controllerTypeId)
                }
            }
        }
    }

    LaunchedEffect(playerAttached) {
        if (playerAttached) {
            engine.hotkeySignals().collect { hotkey ->
                if (hotkey == HotkeyAction.PAUSE_MENU) {
                    showPauseSheet = true
                }
            }
        }
    }

    LaunchedEffect(playerAttached, state.controls?.preferences?.rumbleToDeviceEnabled) {
        if (playerAttached) {
            engine.rumbleSignals().collect { rumble ->
                if (state.controls?.preferences?.rumbleToDeviceEnabled == true) {
                    triggerDeviceRumble(context, rumble.weakStrength, rumble.strongStrength)
                }
            }
        }
    }

    LaunchedEffect(playerAttached, showPauseSheet, showStateBrowserSheet, showControlsSheet, ownerState.progress?.phase, lifecycleState) {
        if (playerAttached) {
            playerAction {
                val paused = showPauseSheet || showStateBrowserSheet || showControlsSheet
                if (owner == null) engine.setPaused(paused)
                else if (paused && owner.state.value.progress?.phase == ProgressSessionPhase.RUNNING) owner.checkpoint("pause").await()
                else if (!paused && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && owner.state.value.progress?.phase == ProgressSessionPhase.PAUSED) owner.resume().await()
            }
        }
    }

    LaunchedEffect(showPauseSheet, showStateBrowserSheet, showControlsSheet, state.isLoading) {
        viewModel.setOverlayFadeSuspended(
            suspended = showPauseSheet || showStateBrowserSheet || showControlsSheet || state.isLoading,
        )
    }

    LaunchedEffect(playerAttached, state.resumeConflict, state.isLoading) {
        if (owner != null || !playerAttached || state.resumeConflict != null || state.isLoading) return@LaunchedEffect
        while (true) {
            delay(120_000)
            captureContinuity(sessionActive = true)
        }
    }

    DisposableEffect(lifecycleOwner, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (owner == null && event == Lifecycle.Event.ON_STOP) {
                scope.launch {
                    captureContinuity(sessionActive = false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(enabled = !state.isLoading) {
        when {
            nativeFailure != null || state.errorMessage != null -> scope.launch { playerAction {
                if (owner != null) owner.exitWithoutLatestProgress().await() else onBack()
            } }
            showControlsSheet -> showControlsSheet = false
            showStateBrowserSheet -> {
                showStateBrowserSheet = false
                showPauseSheet = true
            }
            showPauseSheet -> showPauseSheet = false
            else -> showPauseSheet = true
        }
    }

    val stageCover = io.github.bandoracer.rommio.util.resolveRemoteAssetUrl(profileBaseUrl, state.rom?.urlCover)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(visualTheme.canvasColor),
    ) {
        PlayerStageBackdrop(visual = visualTheme, coverUrl = stageCover, modifier = Modifier.fillMaxSize())
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.errorMessage != null || nativeFailure != null -> {
                    Column(modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                        Text(text = nativeFailure ?: state.errorMessage ?: "Unable to prepare player.",
                            color = MaterialTheme.colorScheme.error)
                        Button(shape = io.github.bandoracer.rommio.ui.theme.RommioTheme.radius.buttonShape, onClick = { scope.launch { playerAction {
                            if (owner != null) owner.exitWithoutLatestProgress().await() else onBack()
                        } } }) { Text("Back to library") }
                    }
                }

                else -> {
                    val controls = state.controls
                    val isLandscape = availableWidth > availableHeight
                    val systemBars = WindowInsets.systemBars.only(WindowInsetsSides.Vertical).asPaddingValues()
                    // Keep Select · Menu · Start out of the system's home-gesture area.
                    val gestures = WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom).asPaddingValues()
                    val cutout = ViewCompat.getRootWindowInsets(rootView)?.displayCutout
                    val stageInsets = with(density) {
                        // Side cutouts apply to both sides so the game stays centred.
                        val side = maxOf(cutout?.safeInsetLeft ?: 0, cutout?.safeInsetRight ?: 0).toDp().value
                        // How far the camera cutout reaches in (docs/player-controls.md, Geometry): the
                        // game sits 8 dp under it and the landscape rails 6 dp beside it, rather than
                        // at the safe inset, which is the status bar's height.
                        val rects = cutout?.boundingRects.orEmpty()
                        val width = rootView.width
                        val cameraTop = rects.filter { it.top < (cutout?.safeInsetTop ?: 0) }.maxOfOrNull { it.bottom }?.toDp()?.value
                        val cameraSide = rects.mapNotNull { rect ->
                            when {
                                rect.left < (cutout?.safeInsetLeft ?: 0) -> rect.right
                                rect.right > width - (cutout?.safeInsetRight ?: 0) -> width - rect.left
                                else -> null
                            }
                        }.maxOrNull()?.toDp()?.value
                        PlayerStageInsets(
                            top = if (isLandscape) 0f else maxOf(systemBars.calculateTopPadding(), (cutout?.safeInsetTop ?: 0).toDp()).value,
                            bottom = maxOf(systemBars.calculateBottomPadding(), gestures.calculateBottomPadding()).value,
                            left = side,
                            right = side,
                            cameraTop = if (isLandscape) null else cameraTop,
                            cameraSide = if (isLandscape) cameraSide else null,
                        )
                    }
                    val familyId = controls?.platformProfile?.familyId
                    // PlayStation's per-game Analog controls show both sticks and L3/R3; the game's
                    // stick tuning sets its sticks' travel, dead zone and centre.
                    val analogControls = state.analogControls
                    val stickTuning = state.stickTuning
                    val plan = remember(familyId, analogControls, stickTuning) { familyId?.let { PlayerControlPlan.of(it, analogControls, stickTuning) } }
                    val touchLayout = controls?.touchLayout
                    // Touch controls v2 (docs/player-controls.md): the stage places the game and every
                    // control; without touch controls the game takes the room and only Menu remains.
                    val reserveTouchSpace = plan != null && controls?.showTouchControls == true
                    val aspect = controls?.platformProfile?.preferredViewportAspectRatio
                    val prefersPortrait = when (controls?.platformProfile?.playerOrientationPolicy) {
                        PlayerOrientationPolicy.PORTRAIT_ONLY -> true
                        PlayerOrientationPolicy.LANDSCAPE_ONLY -> false
                        else -> null
                    }
                    val controlScale = touchLayout?.globalScale ?: 1f
                    val mirrored = touchLayout?.leftHanded == true
                    val stage = remember(availableWidth, availableHeight, stageInsets, aspect, plan, controlScale, mirrored, reserveTouchSpace, prefersPortrait) {
                        resolvePlayerStage(availableWidth.value, availableHeight.value, stageInsets, aspect, plan, controlScale, mirrored, reserveTouchSpace, prefersPortrait)
                    }
                    val viewport = stage.viewport
                    // The game view appears once the platform's controls (its aspect ratio and
                    // touch layout) are known, so its first size is its real one. A SurfaceView
                    // resized in the frames right after it is created kept its first frame on
                    // screen: a full-screen-shaped placeholder cropped the picture, with a black
                    // strip beside it. The controls normally arrive within a frame or two; after
                    // 1.5 s the view appears anyway.
                    val controlsWaitOver by produceState(initialValue = false) {
                        delay(1_500)
                        value = true
                    }

                    if (state.resumeConflict == null && (controls != null || controlsWaitOver)) {
                        PlayerViewport(
                            owner = owner,
                            session = requireNotNull(state.preparedSession),
                            initialTarget = state.pendingLaunchTarget,
                            start = start,
                            engine = engine,
                            modifier = Modifier
                                .offset(x = viewport.left.dp, y = viewport.top.dp)
                                .size(width = viewport.width.dp, height = viewport.height.dp),
                            onAttached = {
                                viewModel.consumePendingLaunchTarget()
                                playerAttached = true
                            },
                            onError = { message ->
                                nativeFailure = message
                                if (owner == null) engine.detach()
                            },
                        )
                    } else {
                        ResumeConflictGate(
                            conflict = state.resumeConflict,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 24.dp),
                            onResumeCloud = {
                                viewModel.resumeCloudSession { message ->
                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                }
                            },
                            onKeepLocal = viewModel::keepLocalProgress,
                        )
                    }

                    if (playerAttached && controls != null) {
                        val paused = showPauseSheet || showControlsSheet || showStateBrowserSheet
                        PlayerControlsOverlay(
                            layout = stage.controls,
                            visual = visualTheme,
                            opacity = touchLayout?.opacity ?: PlayerStyle.DEFAULT_OPACITY,
                            showControls = reserveTouchSpace && !paused,
                            haptics = controls.preferences.hapticsEnabled,
                            // Presses and releases go to the core at once, in touch order.
                            onInput = { output ->
                                when (output) {
                                    is PlayerTouchOutput.Digital -> engine.sendDigital(output.key, output.pressed)
                                    is PlayerTouchOutput.Motion -> engine.sendMotion(
                                        if (output.stick == 0) io.github.bandoracer.rommio.domain.player.PlayerMotionSource.ANALOG_LEFT
                                        else io.github.bandoracer.rommio.domain.player.PlayerMotionSource.ANALOG_RIGHT,
                                        output.x,
                                        output.y,
                                    )
                                    else -> Unit
                                }
                            },
                            onMenu = { showPauseSheet = true },
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
            ) { data ->
                val message = data.visuals.message
                PlayerToast(
                    message = message,
                    visual = visualTheme,
                    isError = message.contains("failed", ignoreCase = true) || message.contains("error", ignoreCase = true),
                )
            }
        }
    }

    if (owner != null) {
        ProgressPlayerGate(owner, ownerState, onBack, onMessage = { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }, keepResume = keepResume)
    }

    if (showPauseSheet && (owner == null || ownerState.phase == ApplicationProgressPhase.ACTIVE) && ownerState.progress?.phase != ProgressSessionPhase.SAVE_FAILED) {
        ModalBottomSheet(
            onDismissRequest = { showPauseSheet = false },
            containerColor = sheetTheme.panelColor,
            scrimColor = io.github.bandoracer.rommio.ui.component.OverArt.shade(0.72f),
        ) {
            PauseSheet(
                visualTheme = sheetTheme,
                title = state.rom?.displayName ?: "Player",
                platformSlug = state.rom?.platformSlug,
                coreName = state.preparedSession?.runtimeProfile?.displayName,
                currentSlot = slot,
                syncStatus = if (owner != null) (ownerState.progress?.lastCommitted?.stateError?.let { "$it " }.orEmpty() +
                    (cloudMessages[io.github.bandoracer.rommio.domain.progress.ProgressProtocol.bindingKey(owner.workspace.identity.binding)] ?: "Game saves and resume snapshots: sync pending.")) else state.syncPresentation.takeUnless {
                    it.kind == GameSyncStatusKind.IDLE && it.lastSuccessfulSyncAtEpochMs == null
                }?.message,
                hasLoadableStates = owner != null || viewModel.hasBrowsableStates(),
                onResume = { showPauseSheet = false },
                onOpenControls = {
                    showPauseSheet = false
                    showControlsSheet = true
                },
                onOpenStateBrowser = {
                    showPauseSheet = false
                    showStateBrowserSheet = true
                },
                onQuickSave = {
                    scope.launch { playerAction {
                        if (owner != null) {
                            val result = owner.checkpoint("manual", slotId = java.util.UUID.nameUUIDFromBytes("rommio-slot-$slot".toByteArray()).toString()).await()
                            snackbarHostState.showSnackbar(if (result.stateRejected) result.stateError ?: "Game-save data saved. Snapshot failed; the previous slot is preserved." else "Game-save data saved. Snapshot saved to slot $slot.")
                        } else {
                            val file = engine.saveState(slot)
                            viewModel.recordState(slot, file)
                            snackbarHostState.showSnackbar("Snapshot saved to slot $slot.")
                        }
                        slot = if (slot >= 4) 1 else slot + 1
                    } }
                },
                onReset = {
                    scope.launch { playerAction {
                        if (owner != null) owner.reset().await() else engine.reset()
                        snackbarHostState.showSnackbar("Core reset.")
                    } }
                },
                onSync = {
                    scope.launch {
                        if (!captureContinuity(sessionActive = true)) return@launch
                        if (owner != null) {
                            container.progressSync.sync(owner.workspace.identity.binding)
                            snackbarHostState.showSnackbar(container.progressSync.messages.value[io.github.bandoracer.rommio.domain.progress.ProgressProtocol.bindingKey(owner.workspace.identity.binding)] ?: "Sync pending.")
                        }
                        else viewModel.syncNow { message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        }
                    }
                },
                onLeaveGame = {
                    scope.launch {
                        if (!captureContinuity(sessionActive = false)) return@launch
                        showPauseSheet = false
                        if (owner == null) onBack()
                    }
                },
            )
        }
    }

    if (showStateBrowserSheet) {
        ModalBottomSheet(
            onDismissRequest = { showStateBrowserSheet = false },
            containerColor = sheetTheme.panelColor,
            scrimColor = io.github.bandoracer.rommio.ui.component.OverArt.shade(0.72f),
        ) {
            if (owner != null) ProgressHistoryContent(owner, container.savedProgress,
                io.github.bandoracer.rommio.util.resolveRemoteAssetUrl(profileBaseUrl, state.rom?.urlCover), onLoaded = {
                showStateBrowserSheet = false
                showPauseSheet = true
            }, onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } })
            else StateBrowserContent(
                resume = state.stateRecovery.resume,
                saveSlots = state.stateRecovery.saveSlots,
                snapshots = state.stateRecovery.snapshots,
                onUseResume = state.stateRecovery.resume?.localPath?.let { resumePath ->
                    {
                        scope.launch { playerAction {
                            val ok = engine.loadState(java.io.File(resumePath))
                            snackbarHostState.showSnackbar(
                                if (ok) "Loaded resume." else "Unable to load resume.",
                            )
                        } }
                        showStateBrowserSheet = false
                    }
                },
                onLoadState = { entry ->
                    scope.launch { playerAction {
                        val ok = engine.loadState(java.io.File(entry.localPath))
                        snackbarHostState.showSnackbar(
                            if (ok) "Loaded ${entry.label}." else "Unable to load ${entry.label}.",
                        )
                    } }
                    showStateBrowserSheet = false
                },
                onDeleteState = state.installation?.let {
                    { entry ->
                        viewModel.deleteState(entry) { message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        }
                    }
                },
                onClose = {
                    showStateBrowserSheet = false
                    showPauseSheet = true
                },
                closeLabel = "Back",
            )
        }
    }

    if (showControlsSheet) {
        val controls = state.controls
        ModalBottomSheet(
            onDismissRequest = { showControlsSheet = false },
            containerColor = sheetTheme.panelColor,
            scrimColor = io.github.bandoracer.rommio.ui.component.OverArt.shade(0.72f),
        ) {
            if (controls == null) {
                Text(
                    text = "Loading controls…",
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                ControlsSheet(
                    visualTheme = sheetTheme,
                    controls = controls,
                    runtimeControllers = runtimeControllers,
                    currentLayout = controls.touchLayout,
                    onDismiss = { showControlsSheet = false },
                    onTouchControlsEnabled = viewModel::setTouchControlsEnabled,
                    onAutoHideTouchControls = viewModel::setAutoHideTouchOnController,
                    onRumbleToDeviceEnabled = viewModel::setRumbleToDeviceEnabled,
                    onOledBlackModeEnabled = viewModel::setOledBlackModeEnabled,
                    onConsoleColorsEnabled = viewModel::setConsoleColorsEnabled,
                    onHapticsEnabled = viewModel::setHapticsEnabled,
                    analogControls = state.analogControls,
                    onAnalogControls = viewModel::setAnalogControls,
                    // The Stick group, for consoles with sticks (PlayStation: with Analog controls on).
                    stickTuning = state.stickTuning.takeIf {
                        PlayerControlPlan.of(controls.platformProfile.familyId, state.analogControls)?.hasSticks == true
                    },
                    onStickTuning = viewModel::setStickTuning,
                    onLayoutUpdated = { updated ->
                        viewModel.saveTouchLayout(updated)
                    },
                    onResetLayout = {
                        viewModel.resetTouchLayout()
                    },
                    onDeadzoneChanged = { deadzone ->
                        viewModel.saveHardwareBinding(
                            controls.hardwareBinding.copy(
                                deadzone = deadzone,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            ),
                        )
                    },
                    onControllerTypeSelected = { controllerTypeId ->
                        viewModel.saveHardwareBinding(
                            controls.hardwareBinding.copy(
                                controllerTypeId = controllerTypeId,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            ),
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsSheet(
    visualTheme: PlayerVisualTheme,
    controls: PlayerControlsState,
    runtimeControllers: List<PlayerControllerDescriptor>,
    currentLayout: TouchLayoutProfile?,
    onDismiss: () -> Unit,
    onTouchControlsEnabled: (Boolean) -> Unit,
    onAutoHideTouchControls: (Boolean) -> Unit,
    onRumbleToDeviceEnabled: (Boolean) -> Unit,
    onOledBlackModeEnabled: (Boolean) -> Unit,
    onConsoleColorsEnabled: (Boolean) -> Unit,
    onHapticsEnabled: (Boolean) -> Unit,
    analogControls: Boolean,
    onAnalogControls: (Boolean) -> Unit,
    stickTuning: PlayerStickTuning?,
    onStickTuning: (PlayerStickTuning) -> Unit,
    onLayoutUpdated: (TouchLayoutProfile) -> Unit,
    onResetLayout: () -> Unit,
    onDeadzoneChanged: (Float) -> Unit,
    onControllerTypeSelected: (Int) -> Unit,
) {
    val theme = io.github.bandoracer.rommio.ui.theme.RommioTheme.current
    // The Controls sheet (iOS wording, docs/theme-layouts.md §4): Touch controls, Controller and
    // Display groups; sliders show percentages.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = theme.spacing.gutter, vertical = 12.dp)
            .testTag("player.controls.form"),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.stack),
    ) {
        io.github.bandoracer.rommio.ui.component.RoleText(
            text = "Controls",
            role = theme.type.headline,
            color = visualTheme.textColor,
        )
        io.github.bandoracer.rommio.ui.component.RoleText(
            text = "${controls.platformProfile.displayName} · ${controls.inputMode.label()}",
            role = theme.type.secondary,
            color = theme.colors.inkSecondary,
        )
        io.github.bandoracer.rommio.ui.component.ListGroup(title = "Touch controls") {
            ControlToggleRow(
                label = "Show touch controls",
                checked = controls.preferences.touchControlsEnabled,
                onCheckedChange = onTouchControlsEnabled,
            )
            io.github.bandoracer.rommio.ui.component.ListDivider()
            ControlToggleRow(
                label = "Auto-hide on controller",
                checked = controls.preferences.autoHideTouchOnController,
                onCheckedChange = onAutoHideTouchControls,
            )
            if (currentLayout != null) {
                io.github.bandoracer.rommio.ui.component.ListDivider()
                ControlToggleRow(
                    label = "Left-handed swap",
                    checked = currentLayout.leftHanded,
                    onCheckedChange = { value -> onLayoutUpdated(currentLayout.copy(leftHanded = value, updatedAtEpochMs = System.currentTimeMillis())) },
                )
            }
            io.github.bandoracer.rommio.ui.component.ListDivider()
            ControlToggleRow(
                label = "Haptics",
                checked = controls.preferences.hapticsEnabled,
                onCheckedChange = onHapticsEnabled,
                modifier = Modifier.testTag("player.controls.haptics"),
            )
            if (currentLayout != null) {
                io.github.bandoracer.rommio.ui.component.ListDivider()
                LabeledSlider("Opacity", currentLayout.opacity, 0.25f..1f) { opacity ->
                    onLayoutUpdated(currentLayout.copy(opacity = opacity, updatedAtEpochMs = System.currentTimeMillis()))
                }
                io.github.bandoracer.rommio.ui.component.ListDivider()
                // The control size (docs/player-controls.md: 0.8–1.3).
                LabeledSlider("Global size", currentLayout.globalScale, PlayerControlMetrics.MIN_SCALE..PlayerControlMetrics.MAX_SCALE) { scale ->
                    onLayoutUpdated(currentLayout.copy(globalScale = scale, updatedAtEpochMs = System.currentTimeMillis()))
                }
            }
        }

        // PlayStation: sticks only in analog mode, per game (docs/player-controls.md, per console).
        if (controls.platformProfile.familyId == "psx") {
            io.github.bandoracer.rommio.ui.component.ListGroup(
                title = "This game",
                footer = "Shows both sticks, L3 and R3 for this game. The Analog button stays either way.",
                modifier = Modifier.padding(top = 8.dp),
            ) {
                ControlToggleRow(
                    label = "Analog controls",
                    checked = analogControls,
                    onCheckedChange = onAnalogControls,
                    modifier = Modifier.testTag("player.controls.analogControls"),
                )
            }
        }

        // Touch sticks, per game (docs/player-controls.md, "Per-game tuning").
        if (stickTuning != null) {
            io.github.bandoracer.rommio.ui.component.ListGroup(
                title = "Stick",
                footer = "For this game. Low sensitivity needs more thumb travel for full tilt. Floating sets the centre where your thumb lands.",
                modifier = Modifier.padding(top = 8.dp).testTag("player.controls.stick"),
            ) {
                StickChoiceRow("Sensitivity", PlayerStickTuning.Sensitivity.entries, stickTuning.sensitivity, { it.label }, "player.controls.stick.sensitivity") {
                    onStickTuning(stickTuning.copy(sensitivity = it))
                }
                io.github.bandoracer.rommio.ui.component.ListDivider()
                StickChoiceRow("Dead zone", PlayerStickTuning.DeadZone.entries, stickTuning.deadZone, { it.label }, "player.controls.stick.deadZone") {
                    onStickTuning(stickTuning.copy(deadZone = it))
                }
                io.github.bandoracer.rommio.ui.component.ListDivider()
                StickChoiceRow("Centre", PlayerStickTuning.Centre.entries, stickTuning.centre, { it.label }, "player.controls.stick.centre") {
                    onStickTuning(stickTuning.copy(centre = it))
                }
            }
            if (!stickTuning.isDefault) {
                io.github.bandoracer.rommio.ui.component.SecondaryButton(
                    text = "Reset stick",
                    onClick = { onStickTuning(PlayerStickTuning()) },
                    modifier = Modifier.fillMaxWidth().testTag("player.controls.stick.reset"),
                )
            }
        }

        io.github.bandoracer.rommio.ui.component.ListGroup(title = "Controller", modifier = Modifier.padding(top = 8.dp)) {
            LabeledSlider("Deadzone", controls.hardwareBinding.deadzone, 0.05f..0.5f, onDeadzoneChanged)
            io.github.bandoracer.rommio.ui.component.ListDivider()
            ControlToggleRow(
                label = "Rumble to device",
                checked = controls.preferences.rumbleToDeviceEnabled,
                onCheckedChange = onRumbleToDeviceEnabled,
            )
        }
        if (runtimeControllers.isNotEmpty()) {
            io.github.bandoracer.rommio.ui.component.SectionHeader(title = "Controller type")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                runtimeControllers.forEach { controller ->
                    io.github.bandoracer.rommio.ui.component.FilterPill(
                        text = controller.description,
                        selected = controls.hardwareBinding.controllerTypeId == controller.id,
                        onClick = { onControllerTypeSelected(controller.id) },
                    )
                }
            }
        }

        io.github.bandoracer.rommio.ui.component.ListGroup(title = "Display", modifier = Modifier.padding(top = 8.dp)) {
            ControlToggleRow(
                label = "OLED black mode",
                checked = controls.preferences.oledBlackModeEnabled,
                onCheckedChange = onOledBlackModeEnabled,
            )
            io.github.bandoracer.rommio.ui.component.ListDivider()
            ControlToggleRow(
                label = "Console colors",
                checked = controls.preferences.consoleColorsEnabled,
                onCheckedChange = onConsoleColorsEnabled,
                modifier = Modifier.testTag("player.controls.consoleColors"),
            )
        }

        if (currentLayout != null) {
            io.github.bandoracer.rommio.ui.component.SecondaryButton(
                text = "Reset control tuning",
                onClick = onResetLayout,
                destructive = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        io.github.bandoracer.rommio.ui.component.PrimaryButton(
            text = "Done",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A slider row: its title and the value as a percentage ("72%"), then the slider in the theme's tint. */
@Composable
private fun LabeledSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    val theme = io.github.bandoracer.rommio.ui.theme.RommioTheme.current
    val c = theme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = io.github.bandoracer.rommio.ui.component.LocalRowInset.current, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            io.github.bandoracer.rommio.ui.component.RoleText(title, theme.type.body, color = c.ink, modifier = Modifier.weight(1f))
            io.github.bandoracer.rommio.ui.component.RoleText(
                java.text.NumberFormat.getPercentInstance().format(value.toDouble()),
                theme.type.numeral,
                size = 13f,
                color = c.inkSecondary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = if (theme.isRiso) c.ink else io.github.bandoracer.rommio.ui.theme.RommioTheme.light.tintText,
                activeTrackColor = if (theme.isRiso) c.ink else io.github.bandoracer.rommio.ui.theme.RommioTheme.light.tintText,
                inactiveTrackColor = c.hairline,
            ),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

/** A Stick setting: its name and one pill per choice. */
@Composable
private fun <T> StickChoiceRow(
    label: String,
    choices: List<T>,
    selected: T,
    name: (T) -> String,
    tag: String,
    onSelect: (T) -> Unit,
) {
    val theme = io.github.bandoracer.rommio.ui.theme.RommioTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        io.github.bandoracer.rommio.ui.component.RoleText(
            text = label,
            role = theme.type.bodyEmphasis,
            size = 16f,
            color = theme.colors.ink,
            modifier = Modifier.weight(1f),
        )
        choices.forEach { choice ->
            io.github.bandoracer.rommio.ui.component.FilterPill(
                text = name(choice),
                selected = choice == selected,
                onClick = { onSelect(choice) },
                modifier = Modifier.testTag("$tag.${name(choice)}"),
            )
        }
    }
}

@Composable
private fun ControlToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    io.github.bandoracer.rommio.ui.component.ToggleRow(
        title = label,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
    )
}

@Composable
private fun PlayerOrientationMode(
    policy: PlayerOrientationPolicy?,
) {
    val context = LocalContext.current
    DisposableEffect(context, policy) {
        val activity = context.findActivity()
        if (activity == null || policy == null) {
            onDispose {}
        } else {
            val originalOrientation = activity.requestedOrientation
            activity.requestedOrientation = when (policy) {
                PlayerOrientationPolicy.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                PlayerOrientationPolicy.PORTRAIT_ONLY -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                PlayerOrientationPolicy.LANDSCAPE_ONLY -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            onDispose {
                activity.requestedOrientation = originalOrientation
            }
        }
    }
}

@Composable
private fun ImmersivePlayerMode(enabled: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current

    DisposableEffect(context, view, enabled) {
        val activity = context.findActivity()
        if (activity == null) {
            onDispose {}
        } else {
            val controller = WindowCompat.getInsetsController(activity.window, view)
            val originalBehavior = controller.systemBarsBehavior
            if (enabled) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                controller.systemBarsBehavior = originalBehavior
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PauseSheet(
    visualTheme: PlayerVisualTheme,
    title: String,
    platformSlug: String? = null,
    coreName: String? = null,
    currentSlot: Int,
    syncStatus: String?,
    hasLoadableStates: Boolean,
    onResume: () -> Unit,
    onOpenControls: () -> Unit,
    onOpenStateBrowser: () -> Unit,
    onQuickSave: () -> Unit,
    onReset: () -> Unit,
    onSync: () -> Unit,
    onLeaveGame: () -> Unit,
) {
    // docs/theme-layouts.md "Player" › Pause: a header, Resume (primary), then a two-column grid
    // of tiles; fits 430 dp without scrolling. The action words are the player's long-standing ones.
    val theme = io.github.bandoracer.rommio.ui.theme.RommioTheme.current
    val c = theme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = theme.spacing.gutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (theme.isRiso) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    io.github.bandoracer.rommio.ui.component.RoleText("Paused", theme.type.label, size = 10f, color = c.ink, modifier = Modifier.weight(1f))
                    io.github.bandoracer.rommio.ui.component.RoleText(
                        listOfNotNull(platformSlug?.uppercase(), coreName).joinToString(" · "),
                        theme.type.label,
                        size = 10f,
                        color = c.inkSecondary,
                        maxLines = 1,
                    )
                }
                io.github.bandoracer.rommio.ui.component.PrintRule(weight = io.github.bandoracer.rommio.ui.component.RuleWeight.HEAVY)
                io.github.bandoracer.rommio.ui.component.RoleText(title, theme.type.headline, size = 26f, color = c.ink, maxLines = 2)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                io.github.bandoracer.rommio.ui.component.RoleText("Paused", theme.type.section, color = c.inkSecondary)
                io.github.bandoracer.rommio.ui.component.RoleText(title, theme.type.headline, color = visualTheme.textColor, maxLines = 2)
            }
        }
        syncStatus?.let {
            io.github.bandoracer.rommio.ui.component.RoleText(it, theme.type.caption, color = c.inkSecondary, maxLines = 2)
        }
        io.github.bandoracer.rommio.ui.component.PrimaryButton(
            text = "Resume",
            onClick = onResume,
            icon = Icons.Outlined.PlayArrow,
            modifier = Modifier.fillMaxWidth(),
        )
        val tiles = listOf(
            PauseTile("Save snapshot", "Slot $currentSlot", Icons.Outlined.Save, onQuickSave),
            PauseTile("Snapshots & game saves", null, Icons.Outlined.Layers, onOpenStateBrowser, enabled = hasLoadableStates),
            PauseTile("Controls", null, Icons.Outlined.SportsEsports, onOpenControls),
            PauseTile("Sync progress", null, Icons.Outlined.Sync, onSync),
            PauseTile("Reset core", null, Icons.Outlined.Refresh, onReset),
            PauseTile("Leave game", null, Icons.AutoMirrored.Outlined.Logout, onLeaveGame, destructive = true),
        )
        tiles.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { column, tile ->
                    PauseActionTile(tile, number = row * 2 + column + 1, visualTheme = visualTheme, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

private class PauseTile(
    val title: String,
    val detail: String?,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/**
 * A pause tile. Aurora: a glass 16-radius tile, symbol in `tint`, `bodyEmphasis` 15 (Leave game in
 * `bad`). Riso: a numbered box — `statValue` 26 number in `accent2`, 1.5 dp ink outline, `section`
 * 15 title; Leave game is a pink block.
 */
@Composable
private fun PauseActionTile(tile: PauseTile, number: Int, visualTheme: PlayerVisualTheme, modifier: Modifier) {
    val theme = io.github.bandoracer.rommio.ui.theme.RommioTheme.current
    val c = theme.colors
    val enabled = tile.enabled
    if (theme.isRiso) {
        val pink = tile.destructive
        val ink = when {
            !enabled -> c.inkTertiary
            pink -> c.onAccent
            else -> c.ink
        }
        Column(
            modifier = modifier
                .heightIn(min = 64.dp)
                .then(if (pink) Modifier.background(c.accent1) else Modifier.border(1.5.dp, if (enabled) c.ink else c.hairline))
                .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = tile.onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            io.github.bandoracer.rommio.ui.component.RoleText(
                "$number",
                theme.type.statValue,
                size = 26f,
                color = if (pink) c.onAccent else if (enabled) c.accent2 else c.inkTertiary,
                modifier = Modifier.clearAndSetSemantics {},
            )
            io.github.bandoracer.rommio.ui.component.RoleText(tile.title, theme.type.section, size = 15f, color = ink, maxLines = 2)
            tile.detail?.let { io.github.bandoracer.rommio.ui.component.RoleText(it, theme.type.label, size = 9.5f, color = if (pink) c.onAccent else c.inkSecondary) }
        }
        return
    }
    val titleColor = when {
        !enabled -> c.inkTertiary
        tile.destructive -> c.badText
        else -> visualTheme.textColor
    }
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .themedSurface(theme, 16.dp, fill = c.surface)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = tile.onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = tile.icon,
            contentDescription = null,
            tint = when {
                !enabled -> c.inkTertiary
                tile.destructive -> c.badText
                else -> io.github.bandoracer.rommio.ui.theme.RommioTheme.light.tintText
            },
            modifier = Modifier.size(22.dp),
        )
        io.github.bandoracer.rommio.ui.component.RoleText(tile.title, theme.type.bodyEmphasis, size = 15f, color = titleColor, maxLines = 2)
        tile.detail?.let { io.github.bandoracer.rommio.ui.component.RoleText(it, theme.type.caption, color = c.inkSecondary) }
    }
}

@Composable
private fun ResumeConflictGate(
    conflict: io.github.bandoracer.rommio.model.ResumeConflict?,
    modifier: Modifier = Modifier,
    onResumeCloud: () -> Unit,
    onKeepLocal: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Newer resume available", style = MaterialTheme.typography.headlineSmall)
            Text(
                conflict?.remoteDeviceName?.let { "A newer resume is available from $it." }
                    ?: "A newer cloud resume is available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(shape = io.github.bandoracer.rommio.ui.theme.RommioTheme.radius.buttonShape,
                onClick = onResumeCloud,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use cloud resume")
            }
            Button(shape = io.github.bandoracer.rommio.ui.theme.RommioTheme.radius.buttonShape,
                onClick = onKeepLocal,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Keep this device's resume")
            }
        }
    }
}

private fun stateSubtitle(entry: BrowsableGameState): String {
    val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.updatedAtEpochMs))
    return when (entry.kind) {
        BrowsableGameStateKind.RECOVERY_HISTORY -> "Auto snapshot · $timestamp"
        BrowsableGameStateKind.IMPORTED_CLOUD -> "Imported cloud · $timestamp"
        BrowsableGameStateKind.MANUAL_SLOT -> "Manual slot · $timestamp"
    }
}

@Composable
private fun PlayerViewport(
    owner: ApplicationProgressSession?,
    session: PlayerSession,
    initialTarget: PlayerLaunchTarget?,
    start: io.github.bandoracer.rommio.domain.playdata.LaunchStart?,
    engine: PlayerEngine,
    modifier: Modifier = Modifier,
    onAttached: () -> Unit,
    onError: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var attached by remember(engine) { mutableStateOf(false) }
    val target = remember(session) { initialTarget }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            try {
                (owner?.attach(context, lifecycleOwner) ?: engine.createOrAttachView(context, lifecycleOwner, session)).also { attached = true }
            } catch (error: Exception) {
                onError(error.message ?: "Unable to attach player.")
                View(context)
            }
        },
    )
    LaunchedEffect(engine, attached) {
        if (!attached) return@LaunchedEffect
        try {
            if (owner != null) {
                if (owner.state.value.phase == ApplicationProgressPhase.WAITING_FOR_VIEW) {
                    // The Launch choice: the game save cold-boots from its own save; Resume is the usual plan.
                    owner.select(
                        if (start == io.github.bandoracer.rommio.domain.playdata.LaunchStart.GAME_SAVE) {
                            io.github.bandoracer.rommio.domain.progress.ProgressLaunchRequest.ColdBootCurrent
                        } else {
                            io.github.bandoracer.rommio.domain.progress.ProgressLaunchRequest.Automatic
                        },
                    ).await()
                }
                return@LaunchedEffect
            }
            startPreparedPlayer(
                awaitReady = engine::awaitReady,
                restore = target?.let { { engine.loadState(java.io.File(it.localStatePath)) } },
                start = { onAttached() },
            )
            // The parent's pause policy releases gameplay after this readiness notification.
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            onError(error.message ?: "Unable to start player.")
        }
    }
}

private fun ActiveInputMode.label(): String {
    return when (this) {
        ActiveInputMode.TOUCH -> "Touch"
        ActiveInputMode.HYBRID -> "Hybrid"
        ActiveInputMode.CONTROLLER -> "Controller"
        ActiveInputMode.CONTROLLER_REQUIRED -> "Controller required"
    }
}

private fun triggerDeviceRumble(
    context: Context,
    weakStrength: Float,
    strongStrength: Float,
) {
    val amplitude = ((weakStrength + strongStrength) * 0.5f * 255f).roundToInt().coerceIn(20, 180)
    val durationMs = 24L
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(VibratorManager::class.java)
        vibratorManager?.defaultVibrator?.vibrate(
            VibrationEffect.createOneShot(durationMs, amplitude),
        )
    } else {
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
    }
}

