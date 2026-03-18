package io.github.mattsays.rommnative.domain.input

import android.view.KeyEvent
import io.github.mattsays.rommnative.domain.player.PlayerMotionSource

enum class TouchSupportMode {
    FULL,
    CONTROLLER_FIRST,
}

enum class ActiveInputMode {
    TOUCH,
    HYBRID,
    CONTROLLER,
    CONTROLLER_REQUIRED,
}

enum class PlayerOrientationPolicy {
    AUTO,
    LANDSCAPE_ONLY,
}

enum class HotkeyAction {
    PAUSE_MENU,
    QUICK_SAVE,
    QUICK_LOAD,
    RESET,
}

sealed interface ControlAction {
    data class Digital(
        val actionId: String,
        val keyCode: Int,
        val label: String,
    ) : ControlAction

    data class Analog(
        val actionId: String,
        val label: String,
        val motionSource: PlayerMotionSource,
    ) : ControlAction

    data class Pointer(
        val actionId: String = "pointer",
        val label: String = "Touch",
    ) : ControlAction
}

data class ControllerTypeOption(
    val id: Int,
    val description: String,
)

data class ConnectedController(
    val deviceId: Int,
    val name: String,
)

enum class TouchElementLayoutKind {
    DPAD_CROSS,
    FACE_DIAGONAL,
    FACE_DIAMOND,
    BUTTON_ROW,
    BUTTON_COLUMN,
}

data class TouchButtonSpec(
    val id: String,
    val label: String,
    val action: ControlAction.Digital,
)

data class TouchElementSpec(
    val id: String,
    val label: String,
    val layoutKind: TouchElementLayoutKind,
    val buttons: List<TouchButtonSpec>,
    val centerX: Float,
    val centerY: Float,
    val baseScale: Float = 1f,
)

data class TouchLayoutPreset(
    val presetId: String,
    val displayName: String,
    val elements: List<TouchElementSpec>,
)

data class TouchElementState(
    val elementId: String,
    val centerX: Float,
    val centerY: Float,
    val scale: Float = 1f,
)

data class TouchLayoutProfile(
    val platformFamilyId: String,
    val presetId: String,
    val elementStates: List<TouchElementState>,
    val opacity: Float = 0.72f,
    val globalScale: Float = 1f,
    val leftHanded: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

data class HardwareBindingProfile(
    val platformFamilyId: String,
    val controllerTypeId: Int? = null,
    val deadzone: Float = 0.2f,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

data class PlatformControlProfile(
    val familyId: String,
    val displayName: String,
    val platformSlugs: Set<String>,
    val touchSupportMode: TouchSupportMode,
    val playerOrientationPolicy: PlayerOrientationPolicy = PlayerOrientationPolicy.AUTO,
    val preferredViewportAspectRatio: Float? = null,
    val defaultPresetId: String? = null,
    val presets: List<TouchLayoutPreset> = emptyList(),
    val hotkeys: Set<HotkeyAction> = setOf(
        HotkeyAction.PAUSE_MENU,
        HotkeyAction.QUICK_SAVE,
        HotkeyAction.QUICK_LOAD,
        HotkeyAction.RESET,
    ),
    val controllerFallbackMessage: String? = null,
)

data class PlayerControlsPreferences(
    val touchControlsEnabled: Boolean = true,
    val autoHideTouchOnController: Boolean = true,
    val rumbleToDeviceEnabled: Boolean = true,
    val oledBlackModeEnabled: Boolean = false,
    val consoleColorsEnabled: Boolean = false,
)

data class PlayerControlsState(
    val platformProfile: PlatformControlProfile,
    val touchLayout: TouchLayoutProfile?,
    val hardwareBinding: HardwareBindingProfile,
    val preferences: PlayerControlsPreferences,
    val connectedControllers: List<ConnectedController>,
    val inputMode: ActiveInputMode,
    val showTouchControls: Boolean,
)

interface ControlProfileResolver {
    fun resolve(platformSlug: String): PlatformControlProfile
    fun supportedProfiles(): List<PlatformControlProfile>
}

fun PlatformControlProfile.defaultTouchLayout(): TouchLayoutProfile? {
    val presetId = defaultPresetId ?: return null
    val preset = presets.firstOrNull { it.presetId == presetId } ?: return null
    return TouchLayoutProfile(
        platformFamilyId = familyId,
        presetId = presetId,
        elementStates = preset.elements.map { element ->
            TouchElementState(
                elementId = element.id,
                centerX = element.centerX,
                centerY = element.centerY,
                scale = element.baseScale,
            )
        },
    )
}

fun PlatformControlProfile.defaultHardwareBinding(): HardwareBindingProfile {
    return HardwareBindingProfile(platformFamilyId = familyId)
}

fun standardDpad(id: String = "dpad"): TouchElementSpec {
    return TouchElementSpec(
        id = id,
        label = "D-Pad",
        layoutKind = TouchElementLayoutKind.DPAD_CROSS,
        buttons = listOf(
            digitalButton("up", "Up", KeyEvent.KEYCODE_DPAD_UP),
            digitalButton("left", "Left", KeyEvent.KEYCODE_DPAD_LEFT),
            digitalButton("right", "Right", KeyEvent.KEYCODE_DPAD_RIGHT),
            digitalButton("down", "Down", KeyEvent.KEYCODE_DPAD_DOWN),
        ),
        centerX = 0.18f,
        centerY = 0.73f,
    )
}

fun standardStartSelect(
    id: String = "system",
    selectLabel: String = "Select",
    startLabel: String = "Start",
): TouchElementSpec {
    return TouchElementSpec(
        id = id,
        label = "System",
        layoutKind = TouchElementLayoutKind.BUTTON_ROW,
        buttons = listOf(
            digitalButton("select", selectLabel, KeyEvent.KEYCODE_BUTTON_SELECT),
            digitalButton("start", startLabel, KeyEvent.KEYCODE_BUTTON_START),
        ),
        centerX = 0.50f,
        centerY = 0.83f,
        baseScale = 0.9f,
    )
}

fun standardShoulders(
    id: String = "shoulders",
    labels: Pair<String, String> = "L" to "R",
    keyCodes: Pair<Int, Int> = KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_R1,
): TouchElementSpec {
    return TouchElementSpec(
        id = id,
        label = "Shoulders",
        layoutKind = TouchElementLayoutKind.BUTTON_ROW,
        buttons = listOf(
            digitalButton("left_shoulder", labels.first, keyCodes.first),
            digitalButton("right_shoulder", labels.second, keyCodes.second),
        ),
        centerX = 0.50f,
        centerY = 0.15f,
        baseScale = 0.92f,
    )
}

fun faceTwo(
    id: String = "face",
    primaryLabel: String,
    secondaryLabel: String,
    primaryKey: Int,
    secondaryKey: Int,
): TouchElementSpec {
    return TouchElementSpec(
        id = id,
        label = "Face",
        layoutKind = TouchElementLayoutKind.FACE_DIAGONAL,
        buttons = listOf(
            digitalButton("secondary", secondaryLabel, secondaryKey),
            digitalButton("primary", primaryLabel, primaryKey),
        ),
        centerX = 0.82f,
        centerY = 0.72f,
    )
}

fun faceFour(
    id: String = "face",
    left: Pair<String, Int>,
    bottom: Pair<String, Int>,
    right: Pair<String, Int>,
    top: Pair<String, Int>,
): TouchElementSpec {
    return TouchElementSpec(
        id = id,
        label = "Face",
        layoutKind = TouchElementLayoutKind.FACE_DIAMOND,
        buttons = listOf(
            digitalButton("left", left.first, left.second),
            digitalButton("bottom", bottom.first, bottom.second),
            digitalButton("right", right.first, right.second),
            digitalButton("top", top.first, top.second),
        ),
        centerX = 0.82f,
        centerY = 0.68f,
        baseScale = 1.02f,
    )
}

private fun digitalButton(
    id: String,
    label: String,
    keyCode: Int,
): TouchButtonSpec {
    return TouchButtonSpec(
        id = id,
        label = label,
        action = ControlAction.Digital(
            actionId = id,
            keyCode = keyCode,
            label = label,
        ),
    )
}
