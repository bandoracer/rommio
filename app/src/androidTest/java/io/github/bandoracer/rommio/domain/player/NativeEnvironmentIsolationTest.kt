package io.github.bandoracer.rommio.domain.player

import com.swordfish.libretrodroid.LibretroDroid
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeEnvironmentIsolationTest {
    private suspend fun assertReleased() = withContext(Dispatchers.Main) {
        assertTrue(LibretroDroid.getVariables().isEmpty())
        assertTrue(LibretroDroid.getControllers().isEmpty())
        assertEquals(0, LibretroDroid.availableDisks())
    }

    @Test fun optionsControllersAndCallbacksDoNotCrossSessionBoundaries() = runBlocking {
        withSyntheticNativeCore(gameName = "seed.fixture") { engine, _ ->
            assertEquals("seeded", engine.effectiveVariables()["rommio_seed_only"])
            assertEquals(257, engine.availableControllerTypes().single().id)
            assertEquals(2, withContext(Dispatchers.Main) { LibretroDroid.availableDisks() })
            engine.updateVariables(mapOf("rommio_test_capture" to "reject", "rommio_seed_only" to "changed"))
            assertEquals("reject", engine.effectiveVariables()["rommio_test_capture"])
        }
        assertReleased()
        withSyntheticNativeCore { engine, _ ->
            assertEquals(mapOf("rommio_test_capture" to "success", "rommio_test_memory" to "normal"), engine.effectiveVariables())
            assertTrue(engine.availableControllerTypes().isEmpty())
            assertEquals(8, engine.saveState(1).length().toInt())
        }
        assertReleased()
    }

    @Test fun failedLoadDeinitializesWithoutUnloadingAnUnloadedGame() = runBlocking {
        withSyntheticNativeCore(gameName = "seed-fail-load.fixture", awaitReady = false) { engine, session ->
            try { engine.awaitReady(); fail("Failed load became ready") } catch (_: IllegalStateException) { }
            withContext(Dispatchers.Main) { engine.detach() }
            assertReleased()
            assertEquals("load;deinit;", File(session.romPath.path + ".lifecycle").readText())
        }
        withSyntheticNativeCore { engine, _ -> assertEquals(8, engine.saveState(1).length().toInt()) }
    }

    @Test fun incompatibleInitialSramStopsLaunchAndDoesNotContaminateNextSession() = runBlocking {
        withSyntheticNativeCore(initialSaveRam = byteArrayOf(9, 9), awaitReady = false) { engine, session ->
            try { engine.awaitReady(); fail("Partial SRAM accepted") } catch (_: IllegalStateException) { }
            withContext(Dispatchers.Main) { engine.detach() }
            assertEquals("load;unload;deinit;", File(session.romPath.path + ".lifecycle").readText())
            assertReleased()
        }
        val initial = byteArrayOf(9, 8, 7, 6)
        withSyntheticNativeCore(initialSaveRam = initial) { engine, _ ->
            assertArrayEquals(initial, engine.persistSaveRam()!!.readBytes())
        }
    }

    @Test fun repeatedTeardownCannotReuseCallbacksOrDoubleUnload() = runBlocking {
        withSyntheticNativeCore(gameName = "seed.fixture") { engine, session ->
            withContext(Dispatchers.Main) { engine.detach(); engine.detach(); LibretroDroid.destroy() }
            assertEquals("load;unload;deinit;", File(session.romPath.path + ".lifecycle").readText())
            assertReleased()
        }
    }
}
