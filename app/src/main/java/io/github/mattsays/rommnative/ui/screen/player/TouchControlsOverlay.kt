package io.github.mattsays.rommnative.ui.screen.player

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mattsays.rommnative.domain.input.PlatformControlProfile
import io.github.mattsays.rommnative.domain.input.PlayerControlsState
import io.github.mattsays.rommnative.domain.input.TouchButtonSpec
import io.github.mattsays.rommnative.domain.input.TouchLayoutProfile

data class PlayerViewportFrame(
    val left: Dp,
    val top: Dp,
    val width: Dp,
    val height: Dp,
) {
    val right: Dp get() = left + width
    val bottom: Dp get() = top + height
    val centerX: Dp get() = left + (width / 2f)
}

@Composable
fun TouchControlsOverlay(
    controlsState: PlayerControlsState,
    layout: TouchLayoutProfile,
    visualTheme: PlayerVisualTheme,
    viewportFrame: PlayerViewportFrame,
    isLandscape: Boolean,
    bottomInset: Dp,
    primaryControlsAlpha: Float,
    tertiaryControlsAlpha: Float,
    modifier: Modifier = Modifier,
    onDigitalInput: (keyCode: Int, pressed: Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onPrimaryInteraction: () -> Unit,
    onTertiaryInteraction: () -> Unit,
) {
    val bindings = remember(controlsState.platformProfile.familyId) {
        fixedTouchBindingsFor(controlsState.platformProfile)
    } ?: return

    BoxWithConstraints(modifier = modifier.fillMaxSize().alpha(layout.opacity)) {
        val metrics = remember(maxWidth, maxHeight, layout.globalScale) {
            FixedTouchMetrics(
                screenWidth = maxWidth,
                screenHeight = maxHeight,
                scale = layout.globalScale,
            )
        }
        if (isLandscape) {
            LandscapeTouchOverlay(
                bindings = bindings,
                layout = layout,
                visualTheme = visualTheme,
                viewportFrame = viewportFrame,
                bottomInset = bottomInset,
                metrics = metrics,
                primaryControlsAlpha = primaryControlsAlpha,
                tertiaryControlsAlpha = tertiaryControlsAlpha,
                onDigitalInput = onDigitalInput,
                onMenuClick = onMenuClick,
                onPrimaryInteraction = onPrimaryInteraction,
                onTertiaryInteraction = onTertiaryInteraction,
            )
        } else {
            PortraitTouchOverlay(
                bindings = bindings,
                layout = layout,
                visualTheme = visualTheme,
                viewportFrame = viewportFrame,
                bottomInset = bottomInset,
                metrics = metrics,
                primaryControlsAlpha = primaryControlsAlpha,
                tertiaryControlsAlpha = tertiaryControlsAlpha,
                onDigitalInput = onDigitalInput,
                onMenuClick = onMenuClick,
                onPrimaryInteraction = onPrimaryInteraction,
                onTertiaryInteraction = onTertiaryInteraction,
            )
        }
    }
}

@Composable
private fun PortraitTouchOverlay(
    bindings: FixedTouchBindings,
    layout: TouchLayoutProfile,
    visualTheme: PlayerVisualTheme,
    viewportFrame: PlayerViewportFrame,
    bottomInset: Dp,
    metrics: FixedTouchMetrics,
    primaryControlsAlpha: Float,
    tertiaryControlsAlpha: Float,
    onDigitalInput: (keyCode: Int, pressed: Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onPrimaryInteraction: () -> Unit,
    onTertiaryInteraction: () -> Unit,
) {
    val isMirrored = layout.leftHanded
    val dpadX = if (isMirrored) metrics.screenWidth - metrics.portraitSideInset - metrics.zoneSize else metrics.portraitSideInset
    val buttonX = if (isMirrored) metrics.portraitSideInset else metrics.screenWidth - metrics.portraitSideInset - metrics.zoneSize
    val dpadTop = maxOf(viewportFrame.bottom + metrics.controlRowTopOffset + metrics.portraitControlRowHeight + 28.dp, 0.dp)
    val portraitFreeSpace = (metrics.portraitBandBottom(bottomInset) - dpadTop - metrics.zoneSize).coerceAtLeast(0.dp)
    val zoneTop = dpadTop + (portraitFreeSpace * 0.35f)

    val primarySystemButtons = bindings.centerSystemButtons
    val systemClusterWidth = if (primarySystemButtons.isEmpty()) 0.dp else {
        (metrics.portraitControlButtonWidth * primarySystemButtons.size) +
            (metrics.systemGap * (primarySystemButtons.size - 1).coerceAtLeast(0))
    }
    val systemClusterLeft = viewportFrame.centerX - (systemClusterWidth / 2f)
    val controlRowTop = viewportFrame.bottom + metrics.controlRowTopOffset

    if (bindings.dpadButtons.size == 4) {
        DpadZoneRenderer(
            buttons = bindings.dpadButtons,
            zoneSize = metrics.zoneSize,
            style = visualTheme.neutralControlStyle,
            panelAltColor = visualTheme.panelAltColor,
            accentColor = visualTheme.accentColor,
            modifier = Modifier.offset(x = dpadX, y = zoneTop).alpha(primaryControlsAlpha),
            onDigitalInput = onDigitalInput,
            onInteraction = onPrimaryInteraction,
        )
    }

    FaceButtonZoneRenderer(
        bindings = bindings,
        visualTheme = visualTheme,
        zoneSize = metrics.zoneSize,
        modifier = Modifier.offset(x = buttonX, y = zoneTop).alpha(primaryControlsAlpha),
        onDigitalInput = onDigitalInput,
        onInteraction = onPrimaryInteraction,
    )

    if (primarySystemButtons.isNotEmpty()) {
        SystemButtonsRow(
            buttons = primarySystemButtons,
            style = visualTheme.neutralControlStyle,
            modifier = Modifier.offset(x = systemClusterLeft, y = controlRowTop).alpha(tertiaryControlsAlpha),
            buttonWidth = metrics.portraitControlButtonWidth,
            buttonHeight = metrics.portraitControlButtonHeight,
            gap = metrics.systemGap,
            fontScale = 1f,
            onDigitalInput = onDigitalInput,
            onInteraction = onTertiaryInteraction,
        )
    }

    val menuX = if (isMirrored) metrics.screenWidth - metrics.menuButtonSize - metrics.screenEdgeInset else metrics.screenEdgeInset
    MenuTouchButton(
        modifier = Modifier
            .offset(x = menuX, y = controlRowTop - ((metrics.menuButtonSize - metrics.portraitControlButtonHeight) / 2f))
            .alpha(tertiaryControlsAlpha),
        style = visualTheme.neutralControlStyle,
        size = metrics.menuButtonSize,
        onClick = onMenuClick,
        onInteraction = onTertiaryInteraction,
    )
}

@Composable
private fun LandscapeTouchOverlay(
    bindings: FixedTouchBindings,
    layout: TouchLayoutProfile,
    visualTheme: PlayerVisualTheme,
    viewportFrame: PlayerViewportFrame,
    bottomInset: Dp,
    metrics: FixedTouchMetrics,
    primaryControlsAlpha: Float,
    tertiaryControlsAlpha: Float,
    onDigitalInput: (keyCode: Int, pressed: Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onPrimaryInteraction: () -> Unit,
    onTertiaryInteraction: () -> Unit,
) {
    val dpadRailOnLeft = !layout.leftHanded
    val dpadX = if (dpadRailOnLeft) metrics.landscapeSideInset else metrics.screenWidth - metrics.landscapeSideInset - metrics.zoneSize
    val buttonX = if (dpadRailOnLeft) metrics.screenWidth - metrics.landscapeSideInset - metrics.zoneSize else metrics.landscapeSideInset
    val triggerY = metrics.landscapeTopInset
    val bottomSafe = metrics.landscapeBottomInset(bottomInset)
    val preferredZoneTop = viewportFrame.top + (viewportFrame.height / 2f) - (metrics.zoneSize / 2f) + metrics.landscapeZoneDrop
    val lowerZoneY = minOf(
        maxOf(preferredZoneTop, triggerY + metrics.triggerZoneHeight + 18.dp),
        metrics.screenHeight - bottomSafe - metrics.zoneSize,
    )

    if (bindings.leftTriggers.isNotEmpty()) {
        TriggerZoneRenderer(
            buttons = bindings.leftTriggers,
            style = visualTheme.neutralControlStyle,
            zoneWidth = metrics.triggerZoneWidth,
            zoneHeight = metrics.triggerZoneHeight,
            modifier = Modifier.offset(
                x = if (dpadRailOnLeft) metrics.landscapeSideInset else metrics.screenWidth - metrics.landscapeSideInset - metrics.triggerZoneWidth,
                y = triggerY,
            ).alpha(primaryControlsAlpha),
            onDigitalInput = onDigitalInput,
            onInteraction = onPrimaryInteraction,
        )
    }

    if (bindings.rightTriggers.isNotEmpty()) {
        TriggerZoneRenderer(
            buttons = bindings.rightTriggers,
            style = visualTheme.neutralControlStyle,
            zoneWidth = metrics.triggerZoneWidth,
            zoneHeight = metrics.triggerZoneHeight,
            modifier = Modifier.offset(
                x = if (dpadRailOnLeft) metrics.screenWidth - metrics.landscapeSideInset - metrics.triggerZoneWidth else metrics.landscapeSideInset,
                y = triggerY,
            ).alpha(primaryControlsAlpha),
            onDigitalInput = onDigitalInput,
            onInteraction = onPrimaryInteraction,
        )
    }

    if (bindings.dpadButtons.size == 4) {
        DpadZoneRenderer(
            buttons = bindings.dpadButtons,
            zoneSize = metrics.zoneSize,
            style = visualTheme.neutralControlStyle,
            panelAltColor = visualTheme.panelAltColor,
            accentColor = visualTheme.accentColor,
            modifier = Modifier.offset(x = dpadX, y = lowerZoneY).alpha(primaryControlsAlpha),
            onDigitalInput = onDigitalInput,
            onInteraction = onPrimaryInteraction,
        )
    }

    FaceButtonZoneRenderer(
        bindings = bindings,
        visualTheme = visualTheme,
        zoneSize = metrics.zoneSize,
        modifier = Modifier.offset(x = buttonX, y = lowerZoneY).alpha(primaryControlsAlpha),
        onDigitalInput = onDigitalInput,
        onInteraction = onPrimaryInteraction,
    )

    val systemButtons = bindings.centerSystemButtons
    val menuY = metrics.screenHeight - bottomSafe - metrics.menuButtonSize
    val systemHeight = systemGroupHeight(systemButtons.size, metrics.controlButtonHeight, metrics.systemGap)
    val controlY = menuY + metrics.menuButtonSize - systemHeight
    if (systemButtons.isNotEmpty()) {
        val controlX = if (layout.leftHanded) {
            viewportFrame.left - metrics.controlAnchorGap - metrics.controlButtonWidth
        } else {
            viewportFrame.right + metrics.controlAnchorGap
        }
        SystemButtonsColumn(
            buttons = systemButtons,
            style = visualTheme.neutralControlStyle,
            modifier = Modifier.offset(
                x = controlX,
                y = controlY,
            ).alpha(tertiaryControlsAlpha),
            buttonWidth = metrics.controlButtonWidth,
            buttonHeight = metrics.controlButtonHeight,
            gap = metrics.systemGap,
            fontScale = 0.8f,
            onDigitalInput = onDigitalInput,
            onInteraction = onTertiaryInteraction,
        )
    }

    val menuX = if (layout.leftHanded) {
        viewportFrame.right + metrics.controlAnchorGap
    } else {
        viewportFrame.left - metrics.controlAnchorGap - metrics.menuButtonSize
    }
    MenuTouchButton(
        modifier = Modifier.offset(x = menuX, y = menuY).alpha(tertiaryControlsAlpha),
        style = visualTheme.neutralControlStyle,
        size = metrics.menuButtonSize,
        onClick = onMenuClick,
        onInteraction = onTertiaryInteraction,
    )
}

@Composable
private fun DpadZoneRenderer(
    buttons: List<TouchButtonSpec>,
    zoneSize: Dp,
    style: PlayerControlStyle,
    panelAltColor: androidx.compose.ui.graphics.Color,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onDigitalInput: (keyCode: Int, pressed: Boolean) -> Unit,
    onInteraction: () -> Unit,
) {
    val centerSize = zoneSize * 0.22f
    val armLength = zoneSize * 0.34f
    val armThickness = zoneSize * 0.24f
    val armGap = zoneSize * 0.02f
    val armTravel = (centerSize / 2f) + armGap + (armLength / 2f)
    Box(modifier = modifier.size(zoneSize)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(centerSize)
                .clip(RoundedCornerShape(18.dp))
                .background(panelAltColor.copy(alpha = 0.62f))
                .border(1.dp, accentColor.copy(alpha = 0.18f), RoundedCornerShape(18.dp)),
        )
        DpadPetalButton(
            label = buttons[0].label,
            icon = Icons.Outlined.KeyboardArrowUp,
            direction = PetalDirection.Up,
            style = style,
            width = armThickness,
            height = armLength,
            modifier = Modifier.align(Alignment.Center).offset(y = -armTravel),
            onDigitalInput = { onDigitalInput(buttons[0].action.keyCode, it) },
            onInteraction = onInteraction,
        )
        DpadPetalButton(
            label = buttons[1].label,
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            direction = PetalDirection.Left,
            style = style,
            width = armLength,
            height = armThickness,
            modifier = Modifier.align(Alignment.Center).offset(x = -armTravel),
            onDigitalInput = { onDigitalInput(buttons[1].action.keyCode, it) },
            onInteraction = onInteraction,
        )
        DpadPetalButton(
            label = buttons[2].label,
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            direction = PetalDirection.Right,
            style = style,
            width = armLength,
            height = armThickness,
            modifier = Modifier.align(Alignment.Center).offset(x = armTravel),
            onDigitalInput = { onDigitalInput(buttons[2].action.keyCode, it) },
            onInteraction = onInteraction,
        )
        DpadPetalButton(
            label = buttons[3].label,
            icon = Icons.Outlined.KeyboardArrowDown,
            direction = PetalDirection.Down,
            style = style,
            width = armThickness,
            height = armLength,
            modifier = Modifier.align(Alignment.Center).offset(y = armTravel),
            onDigitalInput = { onDigitalInput(buttons[3].action.keyCode, it) },
            onInteraction = onInteraction,
        )
    }
}

@Composable
private fun FaceButtonZoneRenderer(
    bindings: FixedTouchBindings,
    visualTheme: PlayerVisualTheme,
    zoneSize: Dp,
    modifier: Modifier = Modifier,
    onDigitalInput: (keyCode: Int, pressed: Boolean) -> Unit,
    onInteraction: () -> Unit,
) {
    val buttonSize = zoneSize * 0.34f
    val clusterInset = zoneSize * 0.10f
    Box(modifier = modifier.size(zoneSize)) {
        when (bindings.faceStyle) {
            FaceZoneStyle.TWO_DIAGONAL -> {
                val secondary = bindings.faceButtons.getOrNull(0)
                val primary = bindings.faceButtons.getOrNull(1)
                val diagonalInset = zoneSize * 0.06f
                secondary?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.TopStart).offset(x = diagonalInset, y = diagonalInset),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
                primary?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.BottomEnd).offset(x = -diagonalInset, y = -diagonalInset),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
            }

            FaceZoneStyle.DIAMOND -> {
                val left = bindings.faceButtons.getOrNull(0)
                val bottom = bindings.faceButtons.getOrNull(1)
                val right = bindings.faceButtons.getOrNull(2)
                val top = bindings.faceButtons.getOrNull(3)
                left?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.CenterStart).offset(x = -clusterInset),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
                bottom?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.BottomCenter).offset(y = clusterInset),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
                right?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.CenterEnd).offset(x = clusterInset),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
                top?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = -clusterInset),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
            }

            FaceZoneStyle.ARCADE_GRID -> {
                val orderedButtons = bindings.faceButtons.sortedBy { it.label }
                orderedButtons.getOrNull(0)?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.TopStart).offset(x = -clusterInset, y = -clusterInset),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
                orderedButtons.getOrNull(1)?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = clusterInset, y = -clusterInset),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
                orderedButtons.getOrNull(2)?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.BottomStart).offset(x = -clusterInset, y = clusterInset),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
                orderedButtons.getOrNull(3)?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.BottomEnd).offset(x = clusterInset, y = clusterInset),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
            }

            FaceZoneStyle.SEGA_ARC -> {
                val left = bindings.faceButtons.getOrNull(0)
                val bottom = bindings.faceButtons.getOrNull(1)
                val right = bindings.faceButtons.getOrNull(2)
                val top = bindings.faceButtons.getOrNull(3)
                left?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.CenterStart).offset(x = -clusterInset, y = buttonSize * 0.06f),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
                bottom?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.BottomCenter).offset(y = clusterInset),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
                right?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.CenterEnd).offset(x = clusterInset, y = buttonSize * 0.06f),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
                top?.let { button ->
                    FaceTouchButton(
                        label = button.label,
                        style = visualTheme.faceStyle(button.label),
                        size = buttonSize,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = -clusterInset),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
            }
        }
    }
}

@Composable
private fun TriggerZoneRenderer(
    buttons: List<TouchButtonSpec>,
    style: PlayerControlStyle,
    zoneWidth: Dp,
    zoneHeight: Dp,
    modifier: Modifier = Modifier,
    onDigitalInput: (keyCode: Int, pressed: Boolean) -> Unit,
    onInteraction: () -> Unit,
) {
    Box(modifier = modifier.size(zoneWidth, zoneHeight)) {
        if (buttons.size <= 1) {
            buttons.firstOrNull()?.let { button ->
                LabeledTouchButton(
                    label = button.label,
                    style = style,
                    width = zoneWidth,
                    height = zoneHeight,
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(26.dp),
                    onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                    onInteraction = onInteraction,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                buttons.forEach { button ->
                    LabeledTouchButton(
                        label = button.label,
                        style = style,
                        width = zoneWidth,
                        height = (zoneHeight - 10.dp) / 2f,
                        shape = RoundedCornerShape(24.dp),
                        onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                        onInteraction = onInteraction,
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemButtonsRow(
    buttons: List<TouchButtonSpec>,
    style: PlayerControlStyle,
    buttonWidth: Dp,
    buttonHeight: Dp,
    gap: Dp,
    fontScale: Float,
    modifier: Modifier = Modifier,
    onDigitalInput: (keyCode: Int, pressed: Boolean) -> Unit,
    onInteraction: () -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        buttons.forEach { button ->
            LabeledTouchButton(
                label = button.label,
                style = style,
                width = buttonWidth,
                height = buttonHeight,
                fontScale = fontScale,
                shape = RoundedCornerShape(18.dp),
                onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                onInteraction = onInteraction,
            )
        }
    }
}

@Composable
private fun SystemButtonsColumn(
    buttons: List<TouchButtonSpec>,
    style: PlayerControlStyle,
    buttonWidth: Dp,
    buttonHeight: Dp,
    gap: Dp,
    fontScale: Float,
    modifier: Modifier = Modifier,
    onDigitalInput: (keyCode: Int, pressed: Boolean) -> Unit,
    onInteraction: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(gap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        buttons.forEach { button ->
            LabeledTouchButton(
                label = button.label,
                style = style,
                width = buttonWidth,
                height = buttonHeight,
                fontScale = fontScale,
                shape = RoundedCornerShape(18.dp),
                onDigitalInput = { onDigitalInput(button.action.keyCode, it) },
                onInteraction = onInteraction,
            )
        }
    }
}

@Composable
private fun FaceTouchButton(
    label: String,
    style: PlayerControlStyle,
    size: Dp,
    modifier: Modifier = Modifier,
    onDigitalInput: (Boolean) -> Unit,
    onInteraction: () -> Unit,
) {
    LabeledTouchButton(
        label = label,
        style = style,
        width = size,
        height = size,
        touchWidth = size * 1.6f,
        touchHeight = size * 1.6f,
        modifier = modifier,
        shape = CircleShape,
        onDigitalInput = onDigitalInput,
        onInteraction = onInteraction,
    )
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun MenuTouchButton(
    size: Dp,
    style: PlayerControlStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onInteraction: () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (isPressed) style.pressedFillColor else style.fillColor,
            )
            .border(1.dp, if (isPressed) style.pressedBorderColor else style.borderColor, CircleShape)
            .pointerInteropFilter {
                when (it.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        isPressed = true
                        onInteraction()
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        isPressed = false
                        onClick()
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        isPressed = false
                        true
                    }

                    else -> true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Menu,
            contentDescription = "Menu",
            tint = style.contentColor,
        )
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun LabeledTouchButton(
    label: String,
    style: PlayerControlStyle,
    width: Dp,
    height: Dp,
    touchWidth: Dp = width,
    touchHeight: Dp = height,
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
    shape: Shape,
    onDigitalInput: (Boolean) -> Unit,
    onInteraction: () -> Unit,
) {
    var buttonWasPressed by remember { mutableStateOf(false) }
    val containerColor = if (buttonWasPressed) style.pressedFillColor else style.fillColor
    val borderColor = if (buttonWasPressed) style.pressedBorderColor else style.borderColor

    Box(
        modifier = modifier
            .size(touchWidth, touchHeight)
            .pointerInteropFilter {
                when (it.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!buttonWasPressed) {
                            buttonWasPressed = true
                            onInteraction()
                            onDigitalInput(true)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        if (buttonWasPressed) {
                            buttonWasPressed = false
                            onDigitalInput(false)
                        }
                        true
                    }

                    else -> true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width, height)
                .clip(shape)
                .background(containerColor)
                .border(1.dp, borderColor, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = MaterialTheme.typography.labelLarge.fontSize * fontScale,
                ),
                color = style.contentColor,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun DpadPetalButton(
    label: String,
    icon: ImageVector,
    direction: PetalDirection,
    style: PlayerControlStyle,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    onDigitalInput: (Boolean) -> Unit,
    onInteraction: () -> Unit,
) {
    var buttonWasPressed by remember { mutableStateOf(false) }
    val shape = remember(direction) { RoundedCornerShape(18.dp) }
    val touchWidth = width * 1.6f
    val touchHeight = height * 1.6f
    Box(
        modifier = modifier
            .size(touchWidth, touchHeight)
            .pointerInteropFilter {
                when (it.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!buttonWasPressed) {
                            buttonWasPressed = true
                            onInteraction()
                            onDigitalInput(true)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        if (buttonWasPressed) {
                            buttonWasPressed = false
                            onDigitalInput(false)
                        }
                        true
                    }

                    else -> true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width, height)
                .clip(shape)
                .background(
                    if (buttonWasPressed) style.pressedFillColor else style.fillColor,
                )
                .border(1.dp, if (buttonWasPressed) style.pressedBorderColor else style.borderColor, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = style.contentColor,
            )
        }
    }
}

private data class FixedTouchMetrics(
    val screenWidth: Dp,
    val screenHeight: Dp,
    val scale: Float,
) {
    private val clampedScale = scale.coerceIn(0.80f, 1.30f)
    val zoneSize: Dp = 141.dp * clampedScale
    val triggerZoneWidth: Dp = 96.dp * clampedScale
    val triggerZoneHeight: Dp = 40.dp * clampedScale
    val controlButtonWidth: Dp = 41.6.dp * clampedScale
    val controlButtonHeight: Dp = 24.dp * clampedScale
    val portraitControlButtonWidth: Dp = 52.dp * clampedScale
    val portraitControlButtonHeight: Dp = 30.dp * clampedScale
    val menuButtonSize: Dp = 40.dp * clampedScale
    val systemGap: Dp = 8.dp
    val portraitSideInset: Dp = 18.dp
    val screenEdgeInset: Dp = 8.dp
    val controlRowTopOffset: Dp = 14.dp
    val portraitControlRowHeight: Dp = maxOf(portraitControlButtonHeight, menuButtonSize)
    val controlAnchorGap: Dp = 12.dp
    val menuRowGap: Dp = 12.dp
    val landscapeSideInset: Dp = 14.dp
    val landscapeTopInset: Dp = 20.dp
    val landscapeZoneDrop: Dp = 34.dp

    fun portraitBandBottom(bottomInset: Dp): Dp = screenHeight - (24.dp + bottomInset)
    fun landscapeBottomInset(bottomInset: Dp): Dp = 18.dp + bottomInset
}

private enum class FaceZoneStyle {
    TWO_DIAGONAL,
    DIAMOND,
    ARCADE_GRID,
    SEGA_ARC,
}

private data class FixedTouchBindings(
    val dpadButtons: List<TouchButtonSpec>,
    val faceButtons: List<TouchButtonSpec>,
    val leftTriggers: List<TouchButtonSpec>,
    val rightTriggers: List<TouchButtonSpec>,
    val leftSystemButtons: List<TouchButtonSpec>,
    val centerSystemButtons: List<TouchButtonSpec>,
    val rightSystemButtons: List<TouchButtonSpec>,
    val faceStyle: FaceZoneStyle,
)

private fun fixedTouchBindingsFor(profile: PlatformControlProfile): FixedTouchBindings? {
    val preset = profile.presets.firstOrNull { it.presetId == profile.defaultPresetId } ?: profile.presets.firstOrNull() ?: return null
    val dpadButtons = preset.elements.firstOrNull {
        it.id.contains("dpad", ignoreCase = true) || it.label.equals("D-Pad", ignoreCase = true)
    }?.buttons.orEmpty()
    val faceButtons = preset.elements.firstOrNull { it.label.equals("Face", ignoreCase = true) }?.buttons.orEmpty()
    val shoulderButtons = preset.elements
        .filter { element ->
            element.label.equals("Shoulders", ignoreCase = true) || element.id.contains("shoulder", ignoreCase = true)
        }
        .flatMap { it.buttons }

    val leftTriggers = shoulderButtons
        .filter { it.id.contains("left", ignoreCase = true) || it.label.startsWith("L", ignoreCase = true) }
        .sortedBy { it.label }
    val rightTriggers = shoulderButtons
        .filter { it.id.contains("right", ignoreCase = true) || it.label.startsWith("R", ignoreCase = true) }
        .sortedBy { it.label }

    val systemButtons = preset.elements
        .firstOrNull { it.label.equals("System", ignoreCase = true) || it.id.equals("system", ignoreCase = true) }
        ?.buttons
        .orEmpty()

    val startButtons = systemButtons.filter { it.label.equals("Start", ignoreCase = true) }
    val otherSystemButtons = systemButtons.filterNot { it.label.equals("Start", ignoreCase = true) }

    val faceStyle = when {
        profile.familyId == "arcade" -> FaceZoneStyle.ARCADE_GRID
        profile.familyId == "sega16" -> FaceZoneStyle.SEGA_ARC
        faceButtons.size <= 2 -> FaceZoneStyle.TWO_DIAGONAL
        else -> FaceZoneStyle.DIAMOND
    }

    return FixedTouchBindings(
        dpadButtons = dpadButtons,
        faceButtons = faceButtons,
        leftTriggers = leftTriggers,
        rightTriggers = rightTriggers,
        leftSystemButtons = if (startButtons.isEmpty()) emptyList() else otherSystemButtons,
        centerSystemButtons = systemButtons,
        rightSystemButtons = if (startButtons.isEmpty()) systemButtons else startButtons,
        faceStyle = faceStyle,
    )
}

private fun systemGroupWidth(count: Int, buttonWidth: Dp, gap: Dp): Dp {
    if (count <= 0) return 0.dp
    return (buttonWidth * count) + (gap * (count - 1))
}

private fun systemGroupHeight(count: Int, buttonHeight: Dp, gap: Dp): Dp {
    if (count <= 0) return 0.dp
    return (buttonHeight * count) + (gap * (count - 1))
}

private enum class PetalDirection {
    Up,
    Left,
    Right,
    Down,
}
