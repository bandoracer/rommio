package io.github.bandoracer.rommio.data.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import io.github.bandoracer.rommio.domain.input.ConnectedController
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class ExternalControllerMonitor(context: Context) {
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun observeControllers(): Flow<List<ConnectedController>> = callbackFlow {
        fun emitSnapshot() {
            trySend(snapshotControllers())
        }

        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = emitSnapshot()
            override fun onInputDeviceRemoved(deviceId: Int) = emitSnapshot()
            override fun onInputDeviceChanged(deviceId: Int) = emitSnapshot()
        }

        emitSnapshot()
        inputManager.registerInputDeviceListener(listener, mainHandler)
        awaitClose { inputManager.unregisterInputDeviceListener(listener) }
    }

    private fun snapshotControllers(): List<ConnectedController> {
        return InputDevice.getDeviceIds()
            .toList()
            .mapNotNull { deviceId: Int -> InputDevice.getDevice(deviceId) }
            .filter { device: InputDevice ->
                val sources = device.sources
                (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            }
            .map { device: InputDevice ->
                ConnectedController(
                    deviceId = device.id,
                    name = device.name,
                )
            }
            .distinctBy { it.deviceId }
    }
}
