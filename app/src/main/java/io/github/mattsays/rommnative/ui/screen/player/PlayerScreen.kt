package io.github.mattsays.rommnative.ui.screen.player

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
import io.github.mattsays.rommnative.domain.input.PlayerOrientationPolicy
import io.github.mattsays.rommnative.domain.input.TouchLayoutProfile
import io.github.mattsays.rommnative.domain.input.TouchSupportMode
import io.github.mattsays.rommnative.domain.player.PlayerControllerDescriptor
import io.github.mattsays.rommnative.domain.player.PlayerEngine
import io.github.mattsays.rommnative.domain.player.PlayerInputConfiguration
import io.github.mattsays.rommnative.ui.screen.collectAsStateWithLifecycleCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var slot by rememberSaveable { mutableIntStateOf(1) }
    var showPauseSheet by rememberSaveable { mutableStateOf(false) }
    var showControlsSheet by rememberSaveable { mutableStateOf(false) }
    var playerAttached by remember { mutableStateOf(false) }
    var runtimeControllers by remember { mutableStateOf<List<PlayerControllerDescriptor>>(emptyList()) }
    val engine = remember { container.createPlayerEngine() }
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

    LaunchedEffect(playerAttached, showPauseSheet) {
        if (playerAttached) {
            engine.setPaused(showPauseSheet)
        }
    }

    LaunchedEffect(showPauseSheet, showControlsSheet, state.isLoading) {
        viewModel.setOverlayFadeSuspended(
            suspended = showPauseSheet || showControlsSheet || state.isLoading,
        )
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

                    if (controls?.showTouchControls == true && controls.touchLayout != null) {
                        TouchControlsOverlay(
                            controlsState = controls,
                            layout = controls.touchLayout,
                            visualTheme = visualTheme,
                            viewportFrame = viewportFrame,
                            isLandscape = isLandscape,
                            bottomInset = bottomInset,
                            primaryControlsAlpha = primaryOverlayAlpha,
                            tertiaryControlsAlpha = tertiaryOverlayAlpha,
                            modifier = Modifier
                                .fillMaxSize(),
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

                    if (controls?.inputMode == ActiveInputMode.CONTROLLER_REQUIRED) {
                        ControllerFirstHint(
                            message = controls.platformProfile.controllerFallbackMessage
                                ?: "Connect a controller for this platform.",
                            visualTheme = visualTheme,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .alpha(primaryOverlayAlpha)
                                .padding(bottom = 32.dp + bottomInset),
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
                    Text(
                        latestStateLabel?.let { "Latest state: $it" } ?: "No save state available yet.",
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
                    title = if (latestStateLabel == null) "Load state" else "Load $latestStateLabel",
                    subtitle = latestStateLabel?.let { "Load $it." } ?: "No save state available yet.",
                    icon = Icons.Outlined.Refresh,
                    width = actionWidth,
                    enabled = latestStateLabel != null,
                    visualTheme = visualTheme,
                    onClick = onQuickLoad,
                )
                PauseActionButton(
                    title = "Sync saves",
                    subtitle = "Upload saves and download the latest states.",
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
                Icon(imageVector = it, contentDescription = null)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = if (enabled) 0.76f else 0.55f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsSheet(
    visualTheme: PlayerVisualTheme,
    controls: io.github.mattsays.rommnative.domain.input.PlayerControlsState,
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("${controls.platformProfile.displayName} controls", style = MaterialTheme.typography.headlineSmall)
        Text("Input mode: ${controls.inputMode.label()}", style = MaterialTheme.typography.bodyMedium)
        if (currentLayout != null) {
            Text(
                "Touch controls auto-fade during play. Primary controls and menu/system controls fade separately. Layouts are fixed by orientation; use size, opacity, and handedness to tune them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = visualTheme.panelAltColor,
            contentColor = visualTheme.textColor,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Player theme", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("OLED black mode")
                        Text(
                            "Use true black for the player background and sheets.",
                            style = MaterialTheme.typography.bodySmall,
                            color = visualTheme.textColor.copy(alpha = 0.76f),
                        )
                    }
                    Switch(
                        checked = controls.preferences.oledBlackModeEnabled,
                        onCheckedChange = onOledBlackModeEnabled,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Console colors")
                        Text(
                            "Tint face buttons to match the original hardware.",
                            style = MaterialTheme.typography.bodySmall,
                            color = visualTheme.textColor.copy(alpha = 0.76f),
                        )
                    }
                    Switch(
                        checked = controls.preferences.consoleColorsEnabled,
                        onCheckedChange = onConsoleColorsEnabled,
                    )
                }
            }
        }
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
            Button(onClick = onResetLayout) {
                Text("Reset control tuning")
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
    visualTheme: PlayerVisualTheme,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(visualTheme.panelAltColor.copy(alpha = 0.9f), MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = visualTheme.textColor)
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
