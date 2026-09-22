package io.github.bandoracer.rommio.domain.player

import android.view.KeyEvent
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ConsoleNativeInputTest {
    @Test fun allConsoleDigitalAndAnalogInputsReachTheNativeCore() = runBlocking {
        withSyntheticNativeCore(gameName = "input-probe.fixture") { engine, _ ->
            engine.setPaused(false)
            suspend fun sample(): ByteArray { delay(70); return engine.persistSaveRam()!!.readBytes() }
            val keys = listOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_BUTTON_X,KeyEvent.KEYCODE_BUTTON_L1,KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_L2,KeyEvent.KEYCODE_BUTTON_R2,KeyEvent.KEYCODE_BUTTON_THUMBL,KeyEvent.KEYCODE_BUTTON_THUMBR)
            for ((id,key) in keys.withIndex()) {
                engine.dispatchDigital(key,true)
                val held = sample()
                assertEquals("native key $id",1,held[id].toInt())
                assertEquals(1,held.take(16).sumOf { it.toInt() })
                engine.dispatchDigital(key,false)
                assertTrue(sample().take(16).all { it == 0.toByte() })
            }
            engine.dispatchMotion(PlayerMotionSource.ANALOG_LEFT,1f,-1f)
            engine.dispatchMotion(PlayerMotionSource.ANALOG_RIGHT,-1f,1f)
            val analog = ByteBuffer.wrap(sample()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(32767,analog.getShort(16).toInt()); assertEquals(-32767,analog.getShort(18).toInt())
            assertEquals(-32767,analog.getShort(20).toInt()); assertEquals(32767,analog.getShort(22).toInt())
            engine.dispatchDigital(KeyEvent.KEYCODE_BUTTON_B, true)
            engine.setPaused(true)
            engine.setPaused(false)
            assertTrue(sample().all { it == 0.toByte() })
        }
    }
}
