package io.github.bandoracer.rommio.domain.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CoreCaptureFailureTest {
    @Test fun rejectedSerializationCannotReplaceManualOrResumeAndCanBeRetried() = runBlocking {
        withSyntheticNativeCore { engine, session ->
            val slot = engine.saveState(1)
            val before = slot.readBytes()
            assertEquals(8, before.size)
            val resume = File(session.saveStatesDirectory, "resume.state")
            engine.saveResumeState(resume)
            engine.updateVariables(mapOf("rommio_test_capture" to "reject"))
            for (capture in listOf<suspend () -> Unit>({ engine.saveState(1) }, { engine.saveResumeState(resume) })) {
                val error = try { capture(); fail("Rejected bytes installed"); null } catch (e: IllegalStateException) { e }
                assertTrue(error!!.message.orEmpty().contains("could not capture its state"))
                assertArrayEquals(before, slot.readBytes()); assertArrayEquals(before, resume.readBytes())
            }
            // A failed state does not stop in-game SRAM capture or poison the GL command queue.
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), engine.persistSaveRam()!!.readBytes())
            engine.updateVariables(mapOf("rommio_test_capture" to "success"))
            engine.setPaused(false); delay(100); engine.setPaused(true)
            assertFalse(engine.saveState(1).readBytes().contentEquals(before))
        }
    }

    @Test fun zeroAndOversizedDeclarationsAreFailuresAndNeverInstallEmptyOrHugeState() = runBlocking {
        withSyntheticNativeCore { engine, session ->
            val resume = File(session.saveStatesDirectory, "resume.state").apply { writeText("retained") }
            for (mode in listOf("zero", "oversize")) {
                engine.updateVariables(mapOf("rommio_test_capture" to mode))
                try { engine.saveResumeState(resume); fail("Invalid size accepted") } catch (_: IllegalStateException) { }
                try { engine.saveState(1); fail("Invalid manual state accepted") } catch (_: IllegalStateException) { }
                assertEquals("retained", resume.readText())
                assertFalse(File(session.saveStatesDirectory, "10_slot1.state").exists())
            }
        }
    }

    @Test fun captureAfterDisposalCannotChangePreviouslySavedFiles() = runBlocking {
        withSyntheticNativeCore { engine, session ->
            val slot = engine.saveState(1)
            val before = slot.readBytes()
            withContext(Dispatchers.Main) { engine.detach() }
            try { engine.saveState(1); fail("Disposed capture accepted") } catch (_: IllegalStateException) { }
            assertArrayEquals(before, slot.readBytes())
        }
    }
}
