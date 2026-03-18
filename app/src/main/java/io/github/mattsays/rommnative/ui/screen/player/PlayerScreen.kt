package io.github.mattsays.rommnative.ui.screen.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.domain.input.ActiveInputMode
import io.github.mattsays.rommnative.domain.input.HotkeyAction
import io.github.mattsays.rommnative.domain.input.TouchLayoutProfile
import io.github.mattsays.rommnative.domain.player.PlayerControllerDescriptor
import io.github.mattsays.rommnative.domain.player.PlayerEngine
import io.github.mattsays.rommnative.domain.player.PlayerInputConfiguration
import io.github.mattsays.rommnative.ui.screen.collectAsStateWithLifecycleCompat
import io.github.mattsays.rommnative.ui.theme.BrandCanvas
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
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
    val context = LocalContext.current
    val density = LocalDensity.current
    val rootView = LocalView.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var slot by rememberSaveable { mutableIntStateOf(1) }
    var showPauseSheet by rememberSaveable { mutableStateOf(false) }
    var showControlsSheet by rememberSaveable { mutableStateOf(false) }
    var editMode by rememberSaveable { mutableStateOf(false) }
    var manualPause by rememberSaveable { mutableStateOf(false) }
    var playerAttached by remember { mutableStateOf(false) }
    var runtimeControllers by remember { mutableStateOf<List<PlayerControllerDescriptor>>(emptyList()) }
    var draftLayout by remember { mutableStateOf<TouchLayoutProfile?>(null) }
    val engine = remember { container.createPlayerEngine() }

    ImmersivePlayerMode(enabled = true)

    DisposableEffect(engine) {
        onDispose { engine.detach() }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(state.controls?.touchLayout?.updatedAtEpochMs, editMode) {
        if (!editMode) {
            draftLayout = state.controls?.touchLayout
        }
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

    LaunchedEffect(playerAttached, manualPause, showPauseSheet) {
        if (playerAttached) {
            engine.setPaused(manualPause || showPauseSheet)
        }
    }

    BackHandler(enabled = !state.isLoading) {
        when {
            showControlsSheet -> showControlsSheet = false
            showPauseSheet -> showPauseSheet = false
            else -> showPauseSheet = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandCanvas),
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
                    val portraitSystemBars = WindowInsets.systemBars.only(WindowInsetsSides.Vertical).asPaddingValues()
                    val portraitCutoutTopInset = with(density) {
                        (ViewCompat.getRootWindowInsets(rootView)?.displayCutout?.safeInsetTop ?: 0).toDp()
                    }
                    val portraitTopInset = if (isLandscape) {
                        0.dp
                    } else {
                        maxOf(
                            portraitSystemBars.calculateTopPadding(),
                            portraitCutoutTopInset,
                        )
                    }
                    val portraitBottomInset = if (isLandscape) 0.dp else portraitSystemBars.calculateBottomPadding()
                    val viewportModifier = controls?.platformProfile?.preferredViewportAspectRatio?.let { aspectRatio ->
                        if (isLandscape) {
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxHeight()
                                .aspectRatio(aspectRatio, matchHeightConstraintsFirst = true)
                        } else {
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = portraitTopInset)
                                .fillMaxWidth()
                                .aspectRatio(aspectRatio)
                        }
                    } ?: Modifier.fillMaxSize()

                    PlayerViewport(
                        viewModel = viewModel,
                        engine = engine,
                        modifier = viewportModifier,
                        onAttached = { playerAttached = true },
                        onError = { message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        },
                    )

                    if (controls?.showTouchControls == true && draftLayout != null) {
                        TouchControlsOverlay(
                            controlsState = controls,
                            layout = draftLayout!!,
                            editMode = editMode,
                            modifier = Modifier.fillMaxSize(),
                            onLayoutChange = { updated -> draftLayout = updated },
                            onLayoutCommit = { updated ->
                                draftLayout = updated
                                viewModel.saveTouchLayout(updated)
                            },
                            onDigitalInput = { keyCode, pressed ->
                                scope.launch { engine.dispatchDigital(keyCode = keyCode, pressed = pressed) }
                            },
                        )
                    }

                    if (controls?.inputMode == ActiveInputMode.CONTROLLER_REQUIRED) {
                        ControllerFirstHint(
                            message = controls.platformProfile.controllerFallbackMessage
                                ?: "Connect a controller for this platform.",
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp + portraitBottomInset),
                        )
                    } else if (controls?.connectedControllers?.isNotEmpty() == true) {
                        AssistChip(
                            onClick = { showControlsSheet = true },
                            label = {
                                Text(
                                    when (controls.inputMode) {
                                        ActiveInputMode.HYBRID -> "Controller + touch"
                                        ActiveInputMode.CONTROLLER -> "Controller active"
                                        else -> "Controller connected"
                                    },
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Gamepad, contentDescription = null)
                            },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 16.dp, top = 12.dp + portraitTopInset),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 16.dp, top = 12.dp + portraitTopInset),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalIconButton(
                            onClick = { manualPause = !manualPause },
                        ) {
                            Icon(
                                if (manualPause || showPauseSheet) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                                contentDescription = if (manualPause || showPauseSheet) "Resume emulation" else "Pause emulation",
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { showPauseSheet = true },
                        ) {
                            Icon(Icons.Outlined.Menu, contentDescription = "Menu")
                        }
                    }

                    if (editMode) {
                        AssistChip(
                            onClick = { editMode = false },
                            label = { Text("Editing layout") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp + portraitTopInset),
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
        ) {
            PauseSheet(
                title = state.rom?.displayName ?: "Player",
                latestStateLabel = state.saveStates.lastOrNull()?.label,
                currentSlot = slot,
                onResume = { showPauseSheet = false },
                onOpenControls = {
                    showPauseSheet = false
                    showControlsSheet = true
                },
                onQuickSave = {
                    scope.launch {
                        val file = engine.saveState(slot)
                        viewModel.recordState(slot, file)
                        snackbarHostState.showSnackbar("Saved slot $slot.")
                        slot = if (slot >= 4) 1 else slot + 1
                    }
                },
                onQuickLoad = {
                    val latest = state.saveStates.lastOrNull() ?: return@PauseSheet
                    scope.launch {
                        val ok = engine.loadState(java.io.File(latest.localPath))
                        snackbarHostState.showSnackbar(
                            if (ok) "Loaded ${latest.label}." else "Unable to load state.",
                        )
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
                        engine.persistSaveRam()
                        viewModel.syncNow { message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        }
                    }
                },
                onLeaveGame = {
                    showPauseSheet = false
                    onBack()
                },
            )
        }
    }

    if (showControlsSheet) {
        val controls = state.controls
        ModalBottomSheet(
            onDismissRequest = { showControlsSheet = false },
        ) {
            if (controls == null) {
                Text(
                    text = "Loading controls…",
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                ControlsSheet(
                    controls = controls,
                    runtimeControllers = runtimeControllers,
                    currentLayout = draftLayout ?: controls.touchLayout,
                    editMode = editMode,
                    onDismiss = { showControlsSheet = false },
                    onEnterEditMode = {
                        showControlsSheet = false
                        editMode = true
                    },
                    onExitEditMode = { editMode = false },
                    onTouchControlsEnabled = viewModel::setTouchControlsEnabled,
                    onAutoHideTouchControls = viewModel::setAutoHideTouchOnController,
                    onRumbleToDeviceEnabled = viewModel::setRumbleToDeviceEnabled,
                    onPresetSelected = { presetId ->
                        viewModel.resetTouchLayout(presetId)
                        editMode = false
                    },
                    onLayoutUpdated = { updated ->
                        draftLayout = updated
                        viewModel.saveTouchLayout(updated)
                    },
                    onResetLayout = {
                        viewModel.resetTouchLayout()
                        editMode = false
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

@Composable
private fun PauseSheet(
    title: String,
    latestStateLabel: String?,
    currentSlot: Int,
    onResume: () -> Unit,
    onOpenControls: () -> Unit,
    onQuickSave: () -> Unit,
    onQuickLoad: () -> Unit,
    onReset: () -> Unit,
    onSync: () -> Unit,
    onLeaveGame: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text("Quick-save slot: $currentSlot", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
            Text("Resume")
        }
        Button(onClick = onOpenControls, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Settings, contentDescription = null)
            Text("Controls", modifier = Modifier.padding(start = 8.dp))
        }
        Button(onClick = onQuickSave, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Save, contentDescription = null)
            Text("Quick save", modifier = Modifier.padding(start = 8.dp))
        }
        Button(
            onClick = onQuickLoad,
            enabled = latestStateLabel != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (latestStateLabel == null) "No state to load" else "Load $latestStateLabel")
        }
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Refresh, contentDescription = null)
            Text("Reset core", modifier = Modifier.padding(start = 8.dp))
        }
        Button(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Sync, contentDescription = null)
            Text("Sync saves and states", modifier = Modifier.padding(start = 8.dp))
        }
        Button(onClick = onLeaveGame, modifier = Modifier.fillMaxWidth()) {
            Text("Leave game")
        }
    }
}

@Composable
private fun ControlsSheet(
    controls: io.github.mattsays.rommnative.domain.input.PlayerControlsState,
    runtimeControllers: List<PlayerControllerDescriptor>,
    currentLayout: TouchLayoutProfile?,
    editMode: Boolean,
    onDismiss: () -> Unit,
    onEnterEditMode: () -> Unit,
    onExitEditMode: () -> Unit,
    onTouchControlsEnabled: (Boolean) -> Unit,
    onAutoHideTouchControls: (Boolean) -> Unit,
    onRumbleToDeviceEnabled: (Boolean) -> Unit,
    onPresetSelected: (String) -> Unit,
    onLayoutUpdated: (TouchLayoutProfile) -> Unit,
    onResetLayout: () -> Unit,
    onDeadzoneChanged: (Float) -> Unit,
    onControllerTypeSelected: (Int) -> Unit,
) {
    var pendingOpacity by remember(currentLayout?.updatedAtEpochMs) {
        mutableStateOf(currentLayout?.opacity ?: 0.72f)
    }
    var pendingScale by remember(currentLayout?.updatedAtEpochMs) {
        mutableStateOf(currentLayout?.globalScale ?: 1f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("${controls.platformProfile.displayName} controls", style = MaterialTheme.typography.headlineSmall)
        Text("Input mode: ${controls.inputMode.label()}", style = MaterialTheme.typography.bodyMedium)
        if (controls.connectedControllers.isNotEmpty()) {
            Text(
                "Connected: ${controls.connectedControllers.joinToString { it.name }}",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text("No controller connected.", style = MaterialTheme.typography.bodyMedium)
        }

        controls.platformProfile.controllerFallbackMessage?.let { message ->
            if (controls.platformProfile.touchSupportMode != io.github.mattsays.rommnative.domain.input.TouchSupportMode.FULL) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }

        if (currentLayout != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show touch controls")
                Switch(
                    checked = controls.preferences.touchControlsEnabled,
                    onCheckedChange = onTouchControlsEnabled,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-hide on controller")
                Switch(
                    checked = controls.preferences.autoHideTouchOnController,
                    onCheckedChange = onAutoHideTouchControls,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Left-handed swap")
                Switch(
                    checked = currentLayout.leftHanded,
                    onCheckedChange = { leftHanded ->
                        onLayoutUpdated(
                            currentLayout.copy(
                                leftHanded = leftHanded,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            ),
                        )
                    },
                )
            }

            Text("Preset", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                controls.platformProfile.presets.forEach { preset ->
                    FilterChip(
                        selected = preset.presetId == currentLayout.presetId,
                        onClick = { onPresetSelected(preset.presetId) },
                        label = { Text(preset.displayName) },
                    )
                }
            }

            Text("Opacity ${(pendingOpacity * 100f).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = pendingOpacity,
                onValueChange = { pendingOpacity = it },
                valueRange = 0.35f..1f,
                onValueChangeFinished = {
                    onLayoutUpdated(
                        currentLayout.copy(
                            opacity = pendingOpacity,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                },
            )

            Text("Size ${(pendingScale * 100f).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = pendingScale,
                onValueChange = { pendingScale = it },
                valueRange = 0.75f..1.35f,
                onValueChangeFinished = {
                    onLayoutUpdated(
                        currentLayout.copy(
                            globalScale = pendingScale,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = if (editMode) onExitEditMode else onEnterEditMode) {
                    Text(if (editMode) "Done editing" else "Edit layout")
                }
                Button(onClick = onResetLayout) {
                    Text("Reset layout")
                }
            }
        }

        Text("Analog deadzone ${(controls.hardwareBinding.deadzone * 100f).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = controls.hardwareBinding.deadzone,
            onValueChange = onDeadzoneChanged,
            valueRange = 0.05f..0.35f,
        )

        if (runtimeControllers.isNotEmpty()) {
            Text("Controller type", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                runtimeControllers.forEach { controller ->
                    FilterChip(
                        selected = controls.hardwareBinding.controllerTypeId == controller.id,
                        onClick = { onControllerTypeSelected(controller.id) },
                        label = { Text(controller.description) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mirror rumble to device")
            Switch(
                checked = controls.preferences.rumbleToDeviceEnabled,
                onCheckedChange = onRumbleToDeviceEnabled,
            )
        }

        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}

@Composable
private fun ControllerFirstHint(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f), MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
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
        modifier = modifier.fillMaxWidth(),
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
