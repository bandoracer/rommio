package io.github.bandoracer.rommio.domain.player

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import com.swordfish.libretrodroid.Controller
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.RumbleEvent
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import io.github.bandoracer.rommio.domain.input.HotkeyAction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sign

class LibretroPlayerEngine : PlayerEngine {
    private var retroView: GLRetroView? = null
    private var hostView: PlayerHostView? = null
    private var currentSession: PlayerSession? = null
    private var inputConfiguration = PlayerInputConfiguration()
    private val hotkeyEvents = MutableSharedFlow<HotkeyAction>(extraBufferCapacity = 8)
    private var isPaused = false

    override fun createOrAttachView(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        session: PlayerSession,
    ): View {
        currentSession = session
        hostView?.let { return it }

        val data = GLRetroViewData(context).apply {
            coreFilePath = session.coreLibrary.absolutePath
            gameFilePath = session.romPath.absolutePath
            systemDirectory = session.systemDirectory.absolutePath
            savesDirectory = session.savesDirectory.absolutePath
            variables = session.variables.map { Variable(it.key, it.value) }.toTypedArray()
            saveRAMState = session.initialSaveRam
            shader = session.runtimeProfile.shader.toLibretroShader()
            preferLowLatencyAudio = true
            rumbleEventsEnabled = true
            skipDuplicateFrames = false
            enableMicrophone = false
        }

        val view = GLRetroView(context, data).also { glView ->
            lifecycleOwner.lifecycle.addObserver(glView)
            glView.isFocusable = true
            glView.isFocusableInTouchMode = true
            glView.requestFocus()
        }

        retroView = view
        hostView = PlayerHostView(
            context = context,
            retroView = view,
            hotkeySink = { hotkeyEvents.tryEmit(it) },
        ).also { host ->
            host.applyInputConfiguration(inputConfiguration)
            host.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        return hostView!!
    }

    override suspend fun persistSaveRam(): File? = withContext(Dispatchers.IO) {
        val session = currentSession ?: return@withContext null
        val bytes = retroView?.serializeSRAM() ?: return@withContext null
        session.saveRamFile.parentFile?.mkdirs()
        session.saveRamFile.writeBytes(bytes)
        session.saveRamFile
    }

    override suspend fun saveState(slot: Int): File = withContext(Dispatchers.IO) {
        val session = currentSession ?: error("Player session is not active.")
        val bytes = retroView?.serializeState() ?: error("Player is not attached.")
        val target = File(session.saveStatesDirectory, "${session.romId}_slot$slot.state")
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        target
    }

    override suspend fun loadState(file: File): Boolean = withContext(Dispatchers.IO) {
        val bytes = if (file.exists()) file.readBytes() else return@withContext false
        retroView?.unserializeState(bytes) ?: false
    }

    override suspend fun setPaused(paused: Boolean): Unit = withContext(Dispatchers.Main) {
        isPaused = paused
        retroView?.apply {
            frameSpeed = if (paused) 0 else 1
            audioEnabled = !paused
        }
    }

    override suspend fun reset(): Unit = withContext(Dispatchers.Main) {
        retroView?.reset()
    }

    override suspend fun updateVariables(variables: Map<String, String>): Unit = withContext(Dispatchers.Main) {
        val updates = variables.map { Variable(it.key, it.value) }.toTypedArray()
        retroView?.updateVariables(*updates)
    }

    override suspend fun dispatchDigital(keyCode: Int, pressed: Boolean, port: Int): Unit = withContext(Dispatchers.Main) {
        retroView?.sendKeyEvent(if (pressed) ACTION_DOWN else ACTION_UP, keyCode, port)
    }

    override suspend fun dispatchMotion(source: PlayerMotionSource, x: Float, y: Float, port: Int): Unit =
        withContext(Dispatchers.Main) {
            retroView?.sendMotionEvent(source.toLibretroSource(), x, y, port)
        }

    override suspend fun updateInputConfiguration(configuration: PlayerInputConfiguration): Unit =
        withContext(Dispatchers.Main) {
            inputConfiguration = configuration
            hostView?.applyInputConfiguration(configuration)
        }

    override suspend fun availableControllerTypes(port: Int): List<PlayerControllerDescriptor> = withContext(Dispatchers.Main) {
        retroView?.getControllers()?.getOrNull(port)?.map { controller ->
            controller.asDescriptor()
        } ?: emptyList()
    }

    override suspend fun setControllerType(port: Int, controllerTypeId: Int): Unit = withContext(Dispatchers.Main) {
        retroView?.setControllerType(port, controllerTypeId)
    }

    override fun rumbleSignals(): Flow<PlayerRumbleSignal> {
        return retroView?.getRumbleEvents()?.map { rumble ->
            rumble.asSignal()
        } ?: emptyFlow()
    }

    override fun hotkeySignals(): Flow<HotkeyAction> = hotkeyEvents.asSharedFlow()

    override fun detach() {
        hostView?.removeAllViews()
        hostView = null
        retroView = null
        currentSession = null
        isPaused = false
    }

    private inner class PlayerHostView(
        context: Context,
        private val retroView: GLRetroView,
        private val hotkeySink: (HotkeyAction) -> Unit,
    ) : FrameLayout(context) {
        private var deadzone: Float = inputConfiguration.deadzone

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(
                retroView,
                LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            requestFocus()
        }

        fun applyInputConfiguration(configuration: PlayerInputConfiguration) {
            deadzone = configuration.deadzone.coerceIn(0f, 0.95f)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_UP && isPauseKey(event.keyCode)) {
                hotkeySink(HotkeyAction.PAUSE_MENU)
                return true
            }

            if (event.action !in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                return super.dispatchKeyEvent(event)
            }

            val sources = event.source
            val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                event.keyCode in GAMEPAD_KEYS

            if (!isGamepad || event.keyCode !in GAMEPAD_KEYS) {
                return super.dispatchKeyEvent(event)
            }

            val port = controllerPort(event.device)
            val mappedKey = mapGamepadKey(event.keyCode)
            retroView.sendKeyEvent(
                if (event.action == KeyEvent.ACTION_DOWN) ACTION_DOWN else ACTION_UP,
                mappedKey,
                port,
            )
            return true
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            val sources = event.source
            val isJoystick = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (!isJoystick) {
                return super.dispatchGenericMotionEvent(event)
            }

            val port = controllerPort(event.device)
            retroView.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_DPAD,
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                port,
            )
            retroView.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
                applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X)),
                applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y)),
                port,
            )
            retroView.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
                applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Z)),
                applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RZ)),
                port,
            )
            return true
        }

        private fun applyDeadzone(value: Float): Float {
            val magnitude = abs(value)
            if (magnitude <= deadzone) return 0f
            val normalized = (magnitude - deadzone) / (1f - deadzone)
            return normalized.coerceIn(0f, 1f) * sign(value)
        }

        private fun controllerPort(device: InputDevice?): Int {
            return ((device?.controllerNumber ?: 1) - 1).coerceAtLeast(0)
        }
    }

    companion object {
        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 1

        private val GAMEPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_MODE,
        )

        private fun mapGamepadKey(keyCode: Int): Int {
            return when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
                KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
                KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
                KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
                else -> keyCode
            }
        }

        private fun isPauseKey(keyCode: Int): Boolean {
            return keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_ESCAPE ||
                keyCode == KeyEvent.KEYCODE_BUTTON_MODE
        }

    }
}

private fun Controller.asDescriptor(): PlayerControllerDescriptor {
    return PlayerControllerDescriptor(
        id = id,
        description = description ?: "Controller $id",
    )
}

private fun RumbleEvent.asSignal(): PlayerRumbleSignal {
    return PlayerRumbleSignal(
        port = port,
        weakStrength = strengthWeak,
        strongStrength = strengthStrong,
    )
}

private fun PlayerMotionSource.toLibretroSource(): Int {
    return when (this) {
        PlayerMotionSource.DPAD -> GLRetroView.MOTION_SOURCE_DPAD
        PlayerMotionSource.ANALOG_LEFT -> GLRetroView.MOTION_SOURCE_ANALOG_LEFT
        PlayerMotionSource.ANALOG_RIGHT -> GLRetroView.MOTION_SOURCE_ANALOG_RIGHT
        PlayerMotionSource.POINTER -> GLRetroView.MOTION_SOURCE_POINTER
    }
}

private fun PlayerShader.toLibretroShader(): ShaderConfig {
    return when (this) {
        PlayerShader.DEFAULT -> ShaderConfig.Default
        PlayerShader.CRT -> ShaderConfig.CRT
        PlayerShader.LCD -> ShaderConfig.LCD
        PlayerShader.SHARP -> ShaderConfig.Sharp
    }
}
