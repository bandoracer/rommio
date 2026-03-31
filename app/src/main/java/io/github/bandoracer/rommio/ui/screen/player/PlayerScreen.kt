package io.github.bandoracer.rommio.ui.screen.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
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
import io.github.bandoracer.rommio.domain.input.PlayerControlsState
import io.github.bandoracer.rommio.domain.input.TouchLayoutProfile
import io.github.bandoracer.rommio.domain.input.TouchSupportMode
import io.github.bandoracer.rommio.domain.player.PlayerControllerDescriptor
import io.github.bandoracer.rommio.domain.player.PlayerEngine
import io.github.bandoracer.rommio.domain.player.PlayerInputConfiguration
import io.github.bandoracer.rommio.model.GameSyncStatusKind
import io.github.bandoracer.rommio.model.BrowsableGameState
import io.github.bandoracer.rommio.model.BrowsableGameStateKind
import io.github.bandoracer.rommio.ui.component.StateBrowserContent
import io.github.bandoracer.rommio.ui.screen.collectAsStateWithLifecycleCompat
import io.github.bandoracer.rommio.ui.theme.BrandPanel
import io.github.bandoracer.rommio.ui.theme.BrandText
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
    onBack: () -> Unit,
) {
    val viewModel: PlayerViewModel = viewModel(
        key = "player-$romId-$fileId",
        factory = viewModelFactory {
            initializer {
                PlayerViewModel(
                    repository = container.repository,
                    controlsRepository = container.playerControlsRepository,
                    romId = romId,
                    fileId = fileId,
                )
            }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val visualTheme = remember(state.controls) { resolvePlayerVisualTheme(state.controls) }
    val sheetTheme = remember { resolvePlayerVisualTheme(null) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val rootView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var slot by rememberSaveable { mutableIntStateOf(1) }
    var showPauseSheet by rememberSaveable { mutableStateOf(false) }
    var showStateBrowserSheet by rememberSaveable { mutableStateOf(false) }
    var showControlsSheet by rememberSaveable { mutableStateOf(false) }
    var playerAttached by remember { mutableStateOf(false) }
    var runtimeControllers by remember { mutableStateOf<List<PlayerControllerDescriptor>>(emptyList()) }
    val engine = remember { container.createPlayerEngine() }
    val latestUiState = rememberUpdatedState(state)
    val latestPlayerAttached = rememberUpdatedState(playerAttached)
    val primaryOverlayAlpha by animateFloatAsState(
        targetValue = when {
            state.overlay.fadeSuspended -> 1f
            state.overlay.primaryPhase == PlayerOverlayPhase.IDLE -> 0.22f
            else -> 1f
        },
        animationSpec = tween(durationMillis = 440),
        label = "playerPrimaryOverlayAlpha",
    )
    val tertiaryOverlayAlpha by animateFloatAsState(
        targetValue = when {
            state.overlay.fadeSuspended -> 1f
            state.overlay.tertiaryPhase == PlayerOverlayPhase.IDLE -> 0.22f
            else -> 1f
        },
        animationSpec = tween(durationMillis = 440),
        label = "playerTertiaryOverlayAlpha",
    )

    ImmersivePlayerMode(enabled = true)
    PlayerOrientationMode(policy = state.controls?.platformProfile?.playerOrientationPolicy)

    DisposableEffect(engine) {
        onDispose { engine.detach() }
    }

    suspend fun captureContinuity(sessionActive: Boolean, onFinished: ((String) -> Unit)? = null) {
        val currentState = latestUiState.value
        if (!latestPlayerAttached.value || currentState.isLoading || currentState.resumeConflict != null) {
            return
        }
        if (currentState.installation == null || currentState.rom == null) {
            return
        }
        runCatching { engine.persistSaveRam() }
        runCatching { engine.saveState(0) }
        viewModel.flushContinuity(sessionActive = sessionActive, onFinished = onFinished)
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
            runtimeControllers = engine.availableControllerTypes()
            state.controls?.hardwareBinding?.controllerTypeId?.let { controllerTypeId ->
                engine.setControllerType(port = 0, controllerTypeId = controllerTypeId)
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

    LaunchedEffect(playerAttached, showPauseSheet, showStateBrowserSheet) {
        if (playerAttached) {
            engine.setPaused(showPauseSheet || showStateBrowserSheet)
        }
    }

    LaunchedEffect(showPauseSheet, showStateBrowserSheet, showControlsSheet, state.isLoading) {
        viewModel.setOverlayFadeSuspended(
            suspended = showPauseSheet || showStateBrowserSheet || showControlsSheet || state.isLoading,
        )
    }

    LaunchedEffect(playerAttached, state.pendingLaunchTarget, state.resumeConflict) {
        if (!playerAttached || state.resumeConflict != null) return@LaunchedEffect
        val launchTarget = state.pendingLaunchTarget ?: return@LaunchedEffect
        val loaded = runCatching { engine.loadState(java.io.File(launchTarget.localStatePath)) }.getOrDefault(false)
        viewModel.consumePendingLaunchTarget()
        if (!loaded) {
            snackbarHostState.showSnackbar("Unable to apply ${launchTarget.label ?: "selected"} state.")
        }
    }

    LaunchedEffect(playerAttached, state.resumeConflict, state.isLoading) {
        if (!playerAttached || state.resumeConflict != null || state.isLoading) return@LaunchedEffect
        while (true) {
            delay(120_000)
            captureContinuity(sessionActive = true)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
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

    LaunchedEffect(playerAttached, state.overlay.primaryLastInteractionEpochMs, state.overlay.fadeSuspended) {
        if (!playerAttached || state.overlay.fadeSuspended) return@LaunchedEffect
        delay(10_000)
        viewModel.setPrimaryOverlayIdle()
    }

    LaunchedEffect(playerAttached, state.overlay.tertiaryLastInteractionEpochMs, state.overlay.fadeSuspended) {
        if (!playerAttached || state.overlay.fadeSuspended) return@LaunchedEffect
        delay(10_000)
        viewModel.setTertiaryOverlayIdle()
    }

    BackHandler(enabled = !state.isLoading) {
        when {
            showControlsSheet -> showControlsSheet = false
            showStateBrowserSheet -> {
                showStateBrowserSheet = false
                showPauseSheet = true
            }
            showPauseSheet -> showPauseSheet = false
            else -> showPauseSheet = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(visualTheme.canvasColor),
    ) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.errorMessage != null -> {
                    Text(
                        text = state.errorMessage ?: "Unable to prepare player.",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> {
                    val controls = state.controls
                    val isLandscape = availableWidth > availableHeight
                    val systemBars = WindowInsets.systemBars.only(WindowInsetsSides.Vertical).asPaddingValues()
                    val portraitCutoutTopInset = with(density) {
                        (ViewCompat.getRootWindowInsets(rootView)?.displayCutout?.safeInsetTop ?: 0).toDp()
                    }
                    val portraitTopInset = if (isLandscape) {
                        0.dp
                    } else {
                        maxOf(
                            systemBars.calculateTopPadding(),
                            portraitCutoutTopInset,
                        )
                    }
                    val bottomInset = systemBars.calculateBottomPadding()
                    val viewportFrame = calculateViewportFrame(
                        containerWidth = availableWidth,
                        containerHeight = availableHeight,
                        aspectRatio = controls?.platformProfile?.preferredViewportAspectRatio,
                        isLandscape = isLandscape,
                        portraitTopInset = portraitTopInset,
                    )

                    if (state.resumeConflict == null) {
                        PlayerViewport(
                            viewModel = viewModel,
                            engine = engine,
                            modifier = Modifier
                                .offset(x = viewportFrame.left, y = viewportFrame.top)
                                .size(width = viewportFrame.width, height = viewportFrame.height),
                            onAttached = { playerAttached = true },
                            onError = { message ->
                                scope.launch { snackbarHostState.showSnackbar(message) }
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

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInteropFilter { motionEvent ->
                                if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                                    viewModel.markPrimaryOverlayInteraction()
                                }
                                false
                            },
                    )

                    val overlayLayout = controls?.touchLayout
                        ?: controls?.platformProfile?.let { platformProfile ->
                            if (platformProfile.touchSupportMode == TouchSupportMode.CONTROLLER_FIRST) {
                                TouchLayoutProfile(
                                    platformFamilyId = platformProfile.familyId,
                                    presetId = "controller-overlay",
                                    elementStates = emptyList(),
                                    updatedAtEpochMs = 0L,
                                )
                            } else {
                                null
                            }
                        }

                    if (controls != null && overlayLayout != null && (controls.showTouchControls || controls.platformProfile.touchSupportMode == TouchSupportMode.CONTROLLER_FIRST)) {
                        TouchControlsOverlay(
                            controlsState = controls,
                            layout = overlayLayout,
                            visualTheme = visualTheme,
                            viewportFrame = viewportFrame,
                            isLandscape = isLandscape,
                            bottomInset = bottomInset,
                            showPrimaryControls = controls.showTouchControls,
                            primaryControlsAlpha = primaryOverlayAlpha,
                            tertiaryControlsAlpha = tertiaryOverlayAlpha,
                            modifier = Modifier.fillMaxSize(),
                            onDigitalInput = { keyCode, pressed ->
                                scope.launch { engine.dispatchDigital(keyCode = keyCode, pressed = pressed) }
                            },
                            onMenuClick = {
                                viewModel.markTertiaryOverlayInteraction()
                                showPauseSheet = true
                            },
                            onPrimaryInteraction = viewModel::markPrimaryOverlayInteraction,
                            onTertiaryInteraction = viewModel::markTertiaryOverlayInteraction,
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
        }
    }

    if (showPauseSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPauseSheet = false },
            containerColor = sheetTheme.panelColor,
            scrimColor = Color.Black.copy(alpha = 0.72f),
        ) {
            PauseSheet(
                visualTheme = sheetTheme,
                title = state.rom?.displayName ?: "Player",
                currentSlot = slot,
                syncStatus = state.syncPresentation.takeUnless {
                    it.kind == GameSyncStatusKind.IDLE && it.lastSuccessfulSyncAtEpochMs == null
                }?.message,
                hasLoadableStates = viewModel.hasBrowsableStates(),
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
                    scope.launch {
                        val file = engine.saveState(slot)
                        viewModel.recordState(slot, file)
                        snackbarHostState.showSnackbar("Saved slot $slot.")
                        slot = if (slot >= 4) 1 else slot + 1
                    }
                },
                onReset = {
                    scope.launch {
                        engine.reset()
                        snackbarHostState.showSnackbar("Core reset.")
                    }
                },
                onSync = {
                    scope.launch {
                        captureContinuity(sessionActive = true)
                        viewModel.syncNow { message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        }
                    }
                },
                onLeaveGame = {
                    scope.launch {
                        captureContinuity(sessionActive = false)
                        showPauseSheet = false
                        onBack()
                    }
                },
            )
        }
    }

    if (showStateBrowserSheet) {
        ModalBottomSheet(
            onDismissRequest = { showStateBrowserSheet = false },
            containerColor = sheetTheme.panelColor,
            scrimColor = Color.Black.copy(alpha = 0.72f),
        ) {
            StateBrowserContent(
                resume = state.stateRecovery.resume,
                saveSlots = state.stateRecovery.saveSlots,
                snapshots = state.stateRecovery.snapshots,
                onUseResume = state.stateRecovery.resume?.localPath?.let { resumePath ->
                    {
                        scope.launch {
                            val ok = engine.loadState(java.io.File(resumePath))
                            snackbarHostState.showSnackbar(
                                if (ok) "Loaded resume." else "Unable to load resume.",
                            )
                        }
                        showStateBrowserSheet = false
                    }
                },
                onLoadState = { entry ->
                    scope.launch {
                        val ok = engine.loadState(java.io.File(entry.localPath))
                        snackbarHostState.showSnackbar(
                            if (ok) "Loaded ${entry.label}." else "Unable to load ${entry.label}.",
                        )
                    }
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
            scrimColor = Color.Black.copy(alpha = 0.72f),
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
    onLayoutUpdated: (TouchLayoutProfile) -> Unit,
    onResetLayout: () -> Unit,
    onDeadzoneChanged: (Float) -> Unit,
    onControllerTypeSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "${controls.platformProfile.displayName} controls",
            style = MaterialTheme.typography.headlineSmall,
            color = visualTheme.textColor,
        )
        Text(
            text = "Input mode: ${controls.inputMode.label()}",
            style = MaterialTheme.typography.bodyMedium,
            color = visualTheme.textColor,
        )

        ControlToggleRow(
            label = "Touch controls",
            checked = controls.preferences.touchControlsEnabled,
            onCheckedChange = onTouchControlsEnabled,
        )
        ControlToggleRow(
            label = "Auto-hide touch controls",
            checked = controls.preferences.autoHideTouchOnController,
            onCheckedChange = onAutoHideTouchControls,
        )
        ControlToggleRow(
            label = "Send rumble to device",
            checked = controls.preferences.rumbleToDeviceEnabled,
            onCheckedChange = onRumbleToDeviceEnabled,
        )
        ControlToggleRow(
            label = "OLED black mode",
            checked = controls.preferences.oledBlackModeEnabled,
            onCheckedChange = onOledBlackModeEnabled,
        )
        ControlToggleRow(
            label = "Console colors",
            checked = controls.preferences.consoleColorsEnabled,
            onCheckedChange = onConsoleColorsEnabled,
        )

        Text(
            text = "Controller deadzone",
            style = MaterialTheme.typography.titleMedium,
            color = visualTheme.textColor,
        )
        Slider(
            value = controls.hardwareBinding.deadzone,
            onValueChange = onDeadzoneChanged,
            valueRange = 0f..0.5f,
        )
        Text(
            text = "${(controls.hardwareBinding.deadzone * 100f).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = visualTheme.textColor,
        )

        if (runtimeControllers.isNotEmpty()) {
            Text(
                text = "Controller type",
                style = MaterialTheme.typography.titleMedium,
                color = visualTheme.textColor,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                runtimeControllers.forEach { controller ->
                    FilterChip(
                        selected = controls.hardwareBinding.controllerTypeId == controller.id,
                        onClick = { onControllerTypeSelected(controller.id) },
                        label = { Text(controller.description) },
                    )
                }
            }
        }

        if (currentLayout != null) {
            Text(
                text = "Touch layout",
                style = MaterialTheme.typography.titleMedium,
                color = visualTheme.textColor,
            )
            Text(
                text = "Opacity",
                style = MaterialTheme.typography.bodyMedium,
                color = visualTheme.textColor,
            )
            Slider(
                value = currentLayout.opacity,
                onValueChange = { opacity ->
                    onLayoutUpdated(
                        currentLayout.copy(
                            opacity = opacity,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                },
                valueRange = 0.2f..1f,
            )
            Text(
                text = "Scale",
                style = MaterialTheme.typography.bodyMedium,
                color = visualTheme.textColor,
            )
            Slider(
                value = currentLayout.globalScale,
                onValueChange = { scale ->
                    onLayoutUpdated(
                        currentLayout.copy(
                            globalScale = scale,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                },
                valueRange = 0.6f..1.4f,
            )
            Button(onClick = onResetLayout, modifier = Modifier.fillMaxWidth()) {
                Text("Reset layout")
            }
        }

        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}

@Composable
private fun ControlToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
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

private fun calculateViewportFrame(
    containerWidth: androidx.compose.ui.unit.Dp,
    containerHeight: androidx.compose.ui.unit.Dp,
    aspectRatio: Float?,
    isLandscape: Boolean,
    portraitTopInset: androidx.compose.ui.unit.Dp,
): PlayerViewportFrame {
    if (aspectRatio == null) {
        return PlayerViewportFrame(
            left = 0.dp,
            top = 0.dp,
            width = containerWidth,
            height = containerHeight,
        )
    }

    return if (isLandscape) {
        var height = containerHeight
        var width = height * aspectRatio
        if (width > containerWidth) {
            width = containerWidth
            height = width / aspectRatio
        }
        PlayerViewportFrame(
            left = (containerWidth - width) / 2f,
            top = (containerHeight - height) / 2f,
            width = width,
            height = height,
        )
    } else {
        val maxHeight = containerHeight - portraitTopInset
        var width = containerWidth
        var height = width / aspectRatio
        if (height > maxHeight) {
            height = maxHeight
            width = height * aspectRatio
        }
        PlayerViewportFrame(
            left = (containerWidth - width) / 2f,
            top = portraitTopInset,
            width = width,
            height = height,
        )
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
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        val wideLayout = maxWidth >= 560.dp
        val sheetWidth = maxWidth
        val actionGap = 12.dp
        val actionWidth = if (wideLayout) (maxWidth - actionGap) / 2f else maxWidth
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                color = visualTheme.panelAltColor,
                contentColor = visualTheme.textColor,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text("Quick-save slot $currentSlot", style = MaterialTheme.typography.titleMedium)
                    syncStatus?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = visualTheme.accentColor,
                        )
                    }
                    Text(
                        if (hasLoadableStates) {
                            "Browse imported cloud states, recovery history, and manual slots."
                        } else {
                            "No save state available yet."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = visualTheme.textColor.copy(alpha = 0.78f),
                    )
                }
            }

            FlowRow(
                maxItemsInEachRow = if (wideLayout) 2 else 1,
                horizontalArrangement = Arrangement.spacedBy(actionGap),
                verticalArrangement = Arrangement.spacedBy(actionGap),
            ) {
                PauseActionButton(
                    title = "Resume",
                    subtitle = "Return to the game immediately.",
                    icon = null,
                    width = actionWidth,
                    primary = true,
                    visualTheme = visualTheme,
                    onClick = onResume,
                )
                PauseActionButton(
                    title = "Controls",
                    subtitle = "Adjust touch layout, theme, and controller options.",
                    icon = Icons.Outlined.Settings,
                    width = actionWidth,
                    visualTheme = visualTheme,
                    onClick = onOpenControls,
                )
                PauseActionButton(
                    title = "Quick save",
                    subtitle = "Write the current state to slot $currentSlot.",
                    icon = Icons.Outlined.Save,
                    width = actionWidth,
                    visualTheme = visualTheme,
                    onClick = onQuickSave,
                )
                PauseActionButton(
                    title = "Browse states",
                    subtitle = if (hasLoadableStates) {
                        "Review resume, save slots, and snapshots."
                    } else {
                        "No emulator state available yet."
                    },
                    icon = Icons.Outlined.Refresh,
                    width = actionWidth,
                    enabled = hasLoadableStates,
                    visualTheme = visualTheme,
                    onClick = onOpenStateBrowser,
                )
                PauseActionButton(
                    title = "Advanced sync",
                    subtitle = "Force reconcile cloud continuity and manual save slots.",
                    icon = Icons.Outlined.Sync,
                    width = actionWidth,
                    visualTheme = visualTheme,
                    onClick = onSync,
                )
                PauseActionButton(
                    title = "Reset core",
                    subtitle = "Hard reset the running core.",
                    icon = Icons.Outlined.Refresh,
                    width = actionWidth,
                    visualTheme = visualTheme,
                    onClick = onReset,
                )
            }

            PauseActionButton(
                title = "Leave game",
                subtitle = "Exit the player and return to the library.",
                icon = null,
                width = sheetWidth,
                visualTheme = visualTheme,
                destructive = true,
                onClick = onLeaveGame,
            )
        }
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
        color = BrandPanel,
        contentColor = BrandText,
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
                color = BrandText.copy(alpha = 0.82f),
            )
            Button(
                onClick = onResumeCloud,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use cloud resume")
            }
            Button(
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
        BrowsableGameStateKind.RECOVERY_HISTORY -> "Auto snapshot • $timestamp"
        BrowsableGameStateKind.IMPORTED_CLOUD -> "Imported cloud • $timestamp"
        BrowsableGameStateKind.MANUAL_SLOT -> "Manual slot • $timestamp"
    }
}

@Composable
private fun PauseActionButton(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    width: androidx.compose.ui.unit.Dp,
    visualTheme: PlayerVisualTheme,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val containerColor = when {
        primary -> visualTheme.accentColor
        destructive -> visualTheme.panelAltColor
        else -> visualTheme.panelAltColor
    }
    val contentColor = when {
        primary -> Color(0xFF151515)
        else -> visualTheme.textColor
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .width(width)
            .heightIn(min = 76.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = visualTheme.panelAltColor.copy(alpha = 0.42f),
            disabledContentColor = visualTheme.textColor.copy(alpha = 0.45f),
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = if (enabled) 0.78f else 0.52f),
                )
            }
        }
    }
}

@Composable
private fun PlayerViewport(
    viewModel: PlayerViewModel,
    engine: PlayerEngine,
    modifier: Modifier = Modifier,
    onAttached: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = {
            val session = runCatching { kotlinx.coroutines.runBlocking { viewModel.buildSession() } }.getOrElse {
                onError(it.message ?: "Unable to create player session.")
                return@AndroidView View(context)
            }
            onAttached()
            engine.createOrAttachView(context, lifecycleOwner, session)
        },
        update = { onAttached() },
    )
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
