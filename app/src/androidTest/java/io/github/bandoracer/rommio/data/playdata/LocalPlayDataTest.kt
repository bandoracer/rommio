package io.github.bandoracer.rommio.data.playdata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.data.local.PlaySessionEntity
import io.github.bandoracer.rommio.domain.playdata.PlaySessionStart
import io.github.bandoracer.rommio.domain.playdata.SaveKey
import io.github.bandoracer.rommio.domain.progress.CheckpointWriteResult
import io.github.bandoracer.rommio.domain.progress.ProgressBinding
import io.github.bandoracer.rommio.domain.progress.ProgressCheckpoint
import io.github.bandoracer.rommio.domain.progress.ProgressContentFile
import io.github.bandoracer.rommio.domain.progress.ProgressIdentity
import io.github.bandoracer.rommio.domain.progress.ProgressSaveCompatibility
import io.github.bandoracer.rommio.domain.progress.ProgressSessionPhase
import io.github.bandoracer.rommio.domain.progress.ProgressSessionStatus
import io.github.bandoracer.rommio.domain.progress.ProgressStateCompatibility
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Session recording, process-death closing and thumbnail capture against a real Room database. */
@RunWith(AndroidJUnit4::class)
class LocalPlayDataTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val dao = db.playDataDao()
    private val root = File(context.cacheDir, "play-data-${UUID.randomUUID()}")
    private val thumbnails = SaveThumbnailStore(root)
    private val identity = ProgressIdentity(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        ProgressBinding("https://romm.example", "1", 12, listOf(ProgressContentFile(120, 0, "a".repeat(64), null, null))))
    private var wall = 1_000_000L
    private var mono = 0L

    @After fun cleanup() { db.close(); root.deleteRecursively() }

    private fun recorder(frame: Bitmap? = null) = LocalPlayData(dao, thumbnails, { wall }, { mono }, { CompletableDeferred(frame) })

    private fun checkpoint(reason: String, state: Boolean) = ProgressCheckpoint(3, UUID.randomUUID().toString(), identity.binding,
        ProgressSaveCompatibility("nes", "sram", "1", "fceumm", false),
        if (state) ProgressStateCompatibility("fceumm", "b".repeat(64), "libretro", "android", "x86_64", emptyMap(), emptyMap()) else null,
        true, emptyList(), "dev", "sess", reason, wall)

    private fun result(reason: String, state: Boolean = true) = CheckpointWriteResult(UUID.randomUUID().toString(), checkpoint(reason, state),
        if (state) "state/resume/x" else null, stateRejected = false)

    private suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(20) }

    @Test
    fun recordsActiveTimeAndEndsWithAResumePoint() = runBlocking {
        val data = recorder()
        val session = UUID.randomUUID().toString()
        val observer = data.begin(PlaySessionStart(session, identity, "dev", "nes", "fceumm")) { null }
        waitFor { dao.session(session) != null }
        val started = dao.session(session)!!
        assertEquals(identity.namespaceId, started.namespaceId)
        assertEquals(120L, started.fileId)
        assertEquals("nes", started.platformSlug)
        assertNull(started.endedAtEpochMs)

        observer.onStatus(ProgressSessionStatus(ProgressSessionPhase.PAUSED))
        mono += 5_000 // loading, paused: not play
        observer.onStatus(ProgressSessionStatus(ProgressSessionPhase.RUNNING))
        mono += 60_000
        observer.onStatus(ProgressSessionStatus(ProgressSessionPhase.PAUSED))
        mono += 600_000 // pause menu open for ten minutes
        observer.onStatus(ProgressSessionStatus(ProgressSessionPhase.RUNNING))
        mono += 30_000
        wall += 700_000
        observer.onStatus(ProgressSessionStatus(ProgressSessionPhase.CLOSED, lastCommitted = result("exit")))
        waitFor { dao.session(session)?.endedAtEpochMs != null }
        val row = dao.session(session)!!
        assertEquals(90_000L, row.activeDurationMs)
        assertEquals("resume", row.endReason)
        assertEquals(wall, row.endedAtEpochMs)
    }

    @Test
    fun leavingRightAfterASaveStateEndsOnTheStateAndFailuresQuit() = runBlocking {
        val data = recorder()
        val a = UUID.randomUUID().toString()
        val first = data.begin(PlaySessionStart(a, identity, "dev", "nes", "fceumm")) { null }
        first.onStatus(ProgressSessionStatus(ProgressSessionPhase.RUNNING))
        mono += 120_000
        first.onCommitted(result("manual"))
        mono += 1_000
        first.onStatus(ProgressSessionStatus(ProgressSessionPhase.CLOSED, lastCommitted = result("exit")))
        waitFor { dao.session(a)?.endReason != null }
        assertEquals("state", dao.session(a)!!.endReason)

        val b = UUID.randomUUID().toString()
        val second = data.begin(PlaySessionStart(b, identity, "dev", "nes", "fceumm")) { null }
        second.onStatus(ProgressSessionStatus(ProgressSessionPhase.RUNNING))
        mono += 10_000
        second.onStatus(ProgressSessionStatus(ProgressSessionPhase.STOPPED, lastCommitted = result("periodic"), error = "core failed"))
        waitFor { dao.session(b)?.endReason != null }
        assertEquals("quit", dao.session(b)!!.endReason)
        assertEquals(10_000L, dao.session(b)!!.activeDurationMs)
    }

    @Test
    fun sessionsLeftOpenByProcessDeathCloseAtTheirLastHeartbeat() = runBlocking {
        dao.upsertSession(PlaySessionEntity("dead", identity.namespaceId, identity.contentId, "https://romm.example", "1", 12, 120, "nes",
            "dev", "fceumm", 1_000, null, 91_000, 75_000, null))
        val data = recorder() // a new launch
        waitFor { dao.session("dead")?.endedAtEpochMs != null }
        val row = dao.session("dead")!!
        assertEquals(91_000L, row.endedAtEpochMs)
        assertEquals(75_000L, row.activeDurationMs)
        assertEquals("background", row.endReason)
        waitFor { data.changes.value > 0 }
    }

    @Test
    fun stateCapturesGetAThumbnailAndRestoresReuseTheSourceFrame() = runBlocking {
        val frame = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 120, 200)) }
        val scaled = Bitmap.createScaledBitmap(frame, 320, 240, true)
        val data = recorder(scaled)
        val observer = data.begin(PlaySessionStart(UUID.randomUUID().toString(), identity, "dev", "nes", "fceumm")) { null }

        observer.onCaptureStarting(includeState = true)
        val manual = result("manual")
        observer.onCommitted(manual)
        val key = SaveKey(identity.namespaceId, identity.contentId, manual.checkpoint.checkpointId)
        waitFor { thumbnails.pathIfPresent(key) != null }
        val decoded = BitmapFactory.decodeFile(thumbnails.pathIfPresent(key))
        assertNotNull("Encoded as a readable image", decoded)
        assertEquals(320, decoded.width)
        assertEquals(240, decoded.height)

        // Game-save-only captures and restore captures get no frame of their own.
        observer.onCaptureStarting(includeState = false)
        val gameOnly = result("periodic", state = false)
        observer.onCommitted(gameOnly)
        val restore = result("restore")
        observer.onCaptureStarting(includeState = true)
        observer.onCommitted(restore)
        observer.onRestored(manual.checkpoint.checkpointId, restore)
        val restoredKey = SaveKey(identity.namespaceId, identity.contentId, restore.checkpoint.checkpointId)
        waitFor { thumbnails.pathIfPresent(restoredKey) != null }
        assertEquals(File(thumbnails.pathIfPresent(key)!!).length(), File(thumbnails.pathIfPresent(restoredKey)!!).length())
        assertNull(thumbnails.pathIfPresent(SaveKey(identity.namespaceId, identity.contentId, gameOnly.checkpoint.checkpointId)))

        // Pruning removes previews of checkpoints no longer kept, after the grace period.
        assertEquals(0, thumbnails.prune(identity.namespaceId, identity.contentId, keep = emptySet()))
        assertEquals(1, thumbnails.prune(identity.namespaceId, identity.contentId, keep = setOf(key.checkpointId), now = System.currentTimeMillis() + SaveThumbnailStore.PRUNE_GRACE_MS))
        assertNotNull(thumbnails.pathIfPresent(key))
        assertNull(thumbnails.pathIfPresent(restoredKey))
    }

    @Test
    fun aFailedFrameNeverBlocksOrFailsTheCommit() = runBlocking {
        val data = LocalPlayData(dao, thumbnails, { wall }, { mono }, { error("PixelCopy unavailable") })
        val observer = data.begin(PlaySessionStart(UUID.randomUUID().toString(), identity, "dev", "nes", "fceumm")) { null }
        observer.onCaptureStarting(includeState = true) // throws inside: swallowed
        val committed = result("pause")
        observer.onCommitted(committed)
        delay(300)
        assertNull(thumbnails.pathIfPresent(SaveKey(identity.namespaceId, identity.contentId, committed.checkpoint.checkpointId)))
    }
}
