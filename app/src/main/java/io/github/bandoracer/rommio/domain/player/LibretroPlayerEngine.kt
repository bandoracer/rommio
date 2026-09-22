package io.github.bandoracer.rommio.domain.player

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleEventObserver
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.Controller
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.RumbleEvent
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import io.github.bandoracer.rommio.domain.input.HotkeyAction
import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import java.io.File
import io.github.bandoracer.rommio.domain.storage.StorageAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sign

class LibretroPlayerEngine : PlayerEngine {
    private var storageLease: AutoCloseable? = StorageAccess.session()
    private var retroView: GLRetroView? = null
    private var hostView: PlayerHostView? = null
    private var currentSession: PlayerSession? = null
    private var inputConfiguration = PlayerInputConfiguration()
    private val hotkeyEvents = MutableSharedFlow<HotkeyAction>(extraBufferCapacity = 8)
    @Volatile private var isPaused = true
    @Volatile private var closing = false
    @Volatile private var isInForeground = false
    private val operations = Mutex()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val failures = MutableStateFlow<String?>(null)
    private var commands: CoreCommandQueue? = null
    private var viewCommands: RetainedViewCommands? = null
    private var sourceLifecycle: Lifecycle? = null
    private var sourceObserver: LifecycleEventObserver? = null
    private var nativeOwner: NativeLifecycleOwner? = null
    private var nativeOwnership: AutoCloseable? = null
    private val closed = CompletableDeferred<Unit>()
    private var gameplayStarted = false
    private var packagedCores: PackagedCoreArtifacts? = null
    private var memoryInventory: Set<PersistentMemory>? = null

    // Called under operations; verification is off the UI/GL thread and cached for this session only.
    private suspend fun memoryInventory(session: PlayerSession): Set<PersistentMemory> =
        memoryInventory ?: withContext(Dispatchers.IO) {
            session.coreIdentity?.let { identity ->
                packagedCores?.verifiedMemoryInventory(session.runtimeProfile, InstalledCore(session.coreLibrary, identity))
            } ?: PersistentMemory.STANDARD
        }.also { memoryInventory = it }

    private class NativeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    override fun createOrAttachView(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        session: PlayerSession,
    ): View {
        check(!closing) { "This player has closed." }
        check(lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED) { "The player screen has closed." }
        hostView?.let {
            check(currentSession === session) { "An attached player cannot switch content." }
            return it
        }
        nativeOwnership = NativePlayerOwnership.acquire()
        currentSession = session
        val library = io.github.bandoracer.rommio.domain.storage.AppManagedLibraryStore(context.applicationContext)
        packagedCores = PackagedCoreArtifacts(context.applicationContext, library, CoreArtifactStore(library.coresDirectory()))

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

        val owner = NativeLifecycleOwner()
        nativeOwner = owner
        val view = GLRetroView(context, data)
        retroView = view
        val retainedCommands = RetainedViewCommands { callback -> view.queueEvent(callback) }
        viewCommands = retainedCommands
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { retainedCommands.attached() }
            override fun onViewDetachedFromWindow(v: View) { retainedCommands.detached() }
        })
        val queue = CoreCommandQueue(post = retainedCommands::post,
            onFailure = { failures.value = it.message ?: "The native core stopped responding." })
        commands = queue
        engineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            view.getGLRetroErrors().collect { code ->
                val error = IllegalStateException("The native core failed (code $code). Close the player and check its core and game files.")
                failures.value = error.message
                queue.fail(error)
            }
        }
        engineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            view.getGLRetroEvents().collect { event ->
                // SurfaceCreated alone also occurs after a failed initializeCore in 0.13.2.
                // A rendered callback requires a successfully loaded and resumed core.
                if (event == GLRetroView.GLRetroEvents.FrameRendered) queue.markReady()
            }
        }
        owner.registry.addObserver(view)
        owner.registry.currentState = Lifecycle.State.CREATED
        // Do this before attaching the surface: the readiness frame must not advance gameplay.
        view.frameSpeed = 0
        view.audioEnabled = false
        sourceLifecycle = lifecycleOwner.lifecycle
        sourceObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isInForeground = true
            if (event in setOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY)) isInForeground = false
            if (event == Lifecycle.Event.ON_DESTROY) detach()
            else if (!closing) owner.registry.handleLifecycleEvent(event)
        }.also { lifecycleOwner.lifecycle.addObserver(it) }
        view.isFocusable = true
        view.isFocusableInTouchMode = true
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

    override suspend fun awaitReady() { requireNotNull(commands) { "Player is not attached." }.awaitReady() }
    override fun failureSignals(): Flow<String> = failures.filterNotNull()

    private suspend fun <T> native(block: (GLRetroView) -> T): T {
        check(!closing) { "This player has closed." }
        val view = requireNotNull(retroView) { "Player is not attached." }
        return requireNotNull(commands).execute { block(view) }
    }

    override suspend fun prepareForProgressSelection(): CoreMemorySnapshot = operations.withLock {
        check(!gameplayStarted && isPaused) { "Progress selection requires a fresh paused core." }
        captureMemorySnapshotLocked(false)
    }

    override suspend fun captureMemorySnapshot(includeState: Boolean): CoreMemorySnapshot = operations.withLock {
        captureMemorySnapshotLocked(includeState)
    }

    private suspend fun captureMemorySnapshotLocked(includeState: Boolean): CoreMemorySnapshot {
        val session = requireNotNull(currentSession) { "Player is not attached." }
        val inventory = memoryInventory(session)
        // One GL callback is the barrier: no retro_run or queued input/options command can
        // occur between the state, memory regions, and effective-option reads.
        return native { view ->
            var stateRejected = false
            val state = if (includeState) try { view.serializeState(false) }
                catch (error: com.swordfish.libretrodroid.RetroException) {
                    if (error.errorCode != LibretroDroid.ERROR_SERIALIZATION) throw error
                    stateRejected = true
                    null
                } else null
            var totalBytes = 0L
            val memory = inventory.associateWith { region ->
                LibretroDroid.serializeMemory(region.id).also {
                    totalBytes += it.size
                    check(totalBytes <= PersistentMemory.MAX_TOTAL_BYTES) { "Persistent memory exceeds restore budget." }
                }
            }
            CoreMemorySnapshot(memory, state, stateRejected,
                view.getVariables().associate { requireNotNull(it.key) to requireNotNull(it.value) }.toSortedMap(),
                session.coreIdentity, session.firmwareSha256.toSortedMap(), session.progressBinding,
                LibretroDroid.getPersistenceDescriptor())
        }
    }

    override suspend fun restoreMemorySnapshot(snapshot: CoreMemorySnapshot): Boolean = operations.withLock {
        check(isPaused) { "Pause before restoring progress." }
        val session = requireNotNull(currentSession)
        require(snapshot.binding != null && snapshot.coreIdentity != null &&
            snapshot.binding == session.progressBinding && snapshot.coreIdentity == session.coreIdentity) {
            "Restore belongs to different content or core bytes."
        }
        require(snapshot.firmwareSha256 == session.firmwareSha256)
        val inventory = memoryInventory(session)
        require(snapshot.memory.keys == inventory && !snapshot.stateRejected)
        require(snapshot.memory.values.sumOf { it.size.toLong() } <= PersistentMemory.MAX_TOTAL_BYTES)
        check(snapshot.state != null || !gameplayStarted) { "Save-only progress requires a fresh cold boot." }
        native { view ->
            val options = view.getVariables().associate { requireNotNull(it.key) to requireNotNull(it.value) }
            require(options == snapshot.effectiveOptions) { "Core options changed before restore." }
            val descriptor = LibretroDroid.getPersistenceDescriptor()
            require(descriptor == snapshot.persistenceDescriptor) { "Loaded persistence layout changed." }
            // Preflight sizes before a state can mutate the core. The native memory writer
            // validates both destinations again after the state has been applied.
            val fileBundle = descriptor == "rommio-files-v1:ppsspp:psp"
            if (!fileBundle && inventory.any { LibretroDroid.serializeMemory(it.id).size != snapshot.memory.getValue(it).size }) {
                return@native false
            }
            if (!fileBundle && snapshot.state != null && !view.unserializeState(snapshot.state, false)) return@native false
            val regions = inventory.sortedBy { it.id }
            if (!LibretroDroid.unserializeMemoryRegions(regions.map { it.id }.toIntArray(),
                    regions.map { snapshot.memory.getValue(it) }.toTypedArray())) return@native false
            if (fileBundle && snapshot.state != null && !view.unserializeState(snapshot.state, false)) return@native false
            inventory.all { LibretroDroid.serializeMemory(it.id).contentEquals(snapshot.memory.getValue(it)) }
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.Main.immediate) { detach() }
        closed.await()
    }

    override suspend fun persistSaveRam(): File? = operations.withLock { StorageAccess.normal { withContext(Dispatchers.IO) {
        val session = currentSession ?: return@withContext null
        val bytes = native { it.serializeSRAM(false) }
        session.saveRamFile.parentFile?.mkdirs()
        AtomicFiles.writeBytes(session.saveRamFile, bytes)
        session.saveRamFile
    } } }

    private suspend fun captureStateBytes(): ByteArray = try {
        native { it.serializeState(false) }
    } catch (error: com.swordfish.libretrodroid.RetroException) {
        throw IllegalStateException("The core could not capture its state. The previous save was retained.", error)
    }

    override suspend fun saveState(slot: Int): File = operations.withLock { StorageAccess.normal { withContext(Dispatchers.IO) {
        val session = currentSession ?: error("Player session is not active.")
        val bytes = captureStateBytes()
        val target = File(session.saveStatesDirectory, "${session.romId}_slot$slot.state")
        target.parentFile?.mkdirs()
        AtomicFiles.writeBytes(target, bytes)
        target
    } } }

    override suspend fun saveResumeState(target: File): File = operations.withLock { StorageAccess.normal { withContext(Dispatchers.IO) {
        check(currentSession != null) { "Player session is not active." }
        val bytes = captureStateBytes()
        AtomicFiles.writeResume(target, bytes)
        target
    } } }

    override suspend fun loadState(file: File): Boolean = operations.withLock { StorageAccess.normal { withContext(Dispatchers.IO) {
        val bytes = if (file.exists()) file.readBytes() else return@withContext false
        if (bytes.isEmpty()) return@withContext false
        native { it.unserializeState(bytes, false) }
    } } }

    override suspend fun setPaused(paused: Boolean): Unit = operations.withLock {
        native { view ->
            if (paused) {
                // A disappearing touch view or disconnected controller may never send
                // its final release. Neutralize the native inputs before suspending.
                val keys = listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) +
                    (KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE)
                for (port in 0..3) {
                    keys.forEach { LibretroDroid.onKeyEvent(port, ACTION_UP, it) }
                    listOf(PlayerMotionSource.ANALOG_LEFT, PlayerMotionSource.ANALOG_RIGHT, PlayerMotionSource.DPAD).forEach {
                        LibretroDroid.onMotionEvent(port, it.toLibretroSource(), 0f, 0f)
                    }
                }
            }
            view.frameSpeed = if (paused) 0 else 1
            view.audioEnabled = !paused
        }
        isPaused = paused
        if (!paused) gameplayStarted = true
    }

    override suspend fun reset(): Unit = operations.withLock { native { it.reset(false) } }

    override suspend fun updateVariables(variables: Map<String, String>): Unit = operations.withLock {
        val updates = variables.map { Variable(it.key, it.value) }.toTypedArray()
        native { it.updateVariables(*updates) }
    }

    override suspend fun effectiveVariables(): Map<String, String> = operations.withLock {
        native { view -> view.getVariables().associate { variable ->
            requireNotNull(variable.key) to requireNotNull(variable.value)
        }.toSortedMap() }
    }

    override suspend fun dispatchDigital(keyCode: Int, pressed: Boolean, port: Int): Unit = withContext(Dispatchers.Main) {
        if (!closing && (!isPaused || !pressed)) sendKey(if (pressed) ACTION_DOWN else ACTION_UP, keyCode, port)
    }

    override suspend fun dispatchMotion(source: PlayerMotionSource, x: Float, y: Float, port: Int): Unit =
        withContext(Dispatchers.Main) {
            if (!closing && (!isPaused || (x == 0f && y == 0f))) sendMotion(source.toLibretroSource(), x, y, port)
        }

    private fun sendKey(action: Int, key: Int, port: Int) {
        commands?.offer { if (!closing && isInForeground && (!isPaused || action == ACTION_UP)) LibretroDroid.onKeyEvent(port, action, key) }
    }

    private fun sendMotion(source: Int, x: Float, y: Float, port: Int) {
        commands?.offer { if (!closing && isInForeground && (!isPaused || (x == 0f && y == 0f))) LibretroDroid.onMotionEvent(port, source, x, y) }
    }

    override suspend fun updateInputConfiguration(configuration: PlayerInputConfiguration): Unit =
        withContext(Dispatchers.Main) {
            inputConfiguration = configuration
            hostView?.applyInputConfiguration(configuration)
        }

    override suspend fun availableControllerTypes(port: Int): List<PlayerControllerDescriptor> = operations.withLock {
        native { view -> view.getControllers().getOrNull(port)?.map { it.asDescriptor() } ?: emptyList() }
    }

    override suspend fun setControllerType(port: Int, controllerTypeId: Int): Unit = operations.withLock {
        native { it.setControllerType(port, controllerTypeId) }
    }

    override fun rumbleSignals(): Flow<PlayerRumbleSignal> {
        return retroView?.getRumbleEvents()?.map { rumble ->
            rumble.asSignal()
        } ?: emptyFlow()
    }

    override fun hotkeySignals(): Flow<HotkeyAction> = hotkeyEvents.asSharedFlow()

    override fun detach() {
        if (closing) return
        closing = true
        isPaused = true
        sourceObserver?.let { sourceLifecycle?.removeObserver(it) }
        commands?.fail(IllegalStateException("This player has closed."), report = false)
        viewCommands?.close()
        // Drain accepted file work before destroying the native owner or releasing import exclusion.
        // This scope survives removal of the Compose screen.
        engineScope.launch {
            var disposed = retroView == null
            try {
                operations.withLock {
                    // Wait for a currently executing GL callback even if the source lifecycle
                    // already paused or its surface was detached. Closed queued callbacks are inert.
                    retroView?.onPause()
                    nativeOwner?.registry?.let { registry ->
                        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.DESTROYED
                    }
                    disposed = true
                    hostView?.removeAllViews()
                    hostView = null
                    retroView = null
                    currentSession = null
                }
            } catch (error: Throwable) {
                closed.completeExceptionally(error)
                failures.value = "Native shutdown failed. Restart the app before opening another game."
            } finally {
                if (disposed) {
                    storageLease?.close(); storageLease = null
                    nativeOwnership?.close(); nativeOwnership = null
                    closed.complete(Unit)
                }
                // Keep the process-wide lease if disposal failed: an unknown live core
                // must not be replaced by another session or touched by migration.
                engineScope.cancel()
            }
        }
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

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (closing || isPaused) return true
            val position = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> Pair(
                    (2f * event.x / width.coerceAtLeast(1) - 1f).coerceIn(-1f, 1f),
                    (2f * event.y / height.coerceAtLeast(1) - 1f).coerceIn(-1f, 1f))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Pair(-10f, 10f)
                else -> null
            }
            position?.let { (x, y) -> commands?.offer {
                if (!closing && isInForeground && !isPaused) LibretroDroid.onTouchEvent(x, y)
            } }
            return true
        }

        fun applyInputConfiguration(configuration: PlayerInputConfiguration) {
            deadzone = configuration.deadzone.coerceIn(0f, 0.95f)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (closing) return true
            if (event.action == KeyEvent.ACTION_UP && isPauseKey(event.keyCode)) {
                hotkeySink(HotkeyAction.PAUSE_MENU)
                return true
            }
            if (isPaused && event.action != KeyEvent.ACTION_UP) return true

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
            sendKey(
                if (event.action == KeyEvent.ACTION_DOWN) ACTION_DOWN else ACTION_UP,
                mappedKey,
                port,
            )
            return true
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (closing || isPaused) return true
            val sources = event.source
            val isJoystick = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (!isJoystick) {
                return super.dispatchGenericMotionEvent(event)
            }

            val port = controllerPort(event.device)
            sendMotion(
                GLRetroView.MOTION_SOURCE_DPAD,
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                port,
            )
            sendMotion(
                GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
                applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X)),
                applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y)),
                port,
            )
            sendMotion(
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
