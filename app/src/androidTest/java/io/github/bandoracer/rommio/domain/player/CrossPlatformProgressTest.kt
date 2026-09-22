package io.github.bandoracer.rommio.domain.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.data.network.RommService
import io.github.bandoracer.rommio.domain.progress.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.UUID

class CrossPlatformProgressTest {
    @Test fun receivesIosGameAndPublishesAndroidSuccessorKeepingBothSnapshotStreams() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val input = File(context.filesDir, "isolated-cross-platform.json")
        assumeTrue("Provision with scripts/ios/verify_progress_parity.py", input.isFile)
        val config = JSONObject(input.readText())
        val baseUrl = config.getString("baseUrl")
        require(baseUrl == "http://127.0.0.1:18765" || (config.optBoolean("allowLiveServer") && config.optString("accountName").startsWith("codex-")))
        val http = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${config.getString("token")}").build()) }.build()
        val service = Retrofit.Builder().baseUrl(baseUrl.trimEnd('/') + "/").client(http)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build())).build().create(RommService::class.java)
        val root = File(context.filesDir, "cross-platform-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val store = ProgressStore(db, File(root, "store"))
        val profile = buildPlatformRuntimeFamilies().flatMap { it.runtimeOptions }.first { it.runtimeId == config.optString("runtimeId", "fceumm") }
        val packaged = PackagedCoreArtifacts(File(root, "cores"), CoreArtifactStore(File(root, "cores"))) { context.assets.open(it) }
        val core = packaged.install(profile, requireNotNull(currentCoreAbi()))
        val rom = if (config.has("romFixtureFile")) {
            val name = config.getString("romFixtureFile")
            require(name.matches(Regex("fixture\\.(nes|gb|gbc|gba|sfc|nds|z64|n64|v64|bin|md|sms|gg|prx)")))
            File(context.filesDir, "cartridge-fixtures/$name")
        } else File(root, "counter.nes").apply { writeBytes(counterRom().also { config.getString("padding").toByteArray().copyInto(it, 200) }) }
        val binding = ProgressBinding(config.getString("baseUrl"), config.getString("accountId"), config.getLong("romId"), listOf(ProgressContentFile(config.getLong("fileId"), 0, CoreBinary.sha256(rom), null, null)))
        val identity = store.resolveIdentity(binding)
        val remote = RommProgressTransport(service, binding).also { it.qualify() }
        val manager = ApplicationProgressSessions({ store }, FceummPersistenceRegistry(packaged), { UUID.randomUUID().toString() }, modern = true)
        val scenario = ActivityScenario.launch(NativePlayerTestActivity::class.java)
        try { withTimeout(if (config.optBoolean("allowLiveServer")) 600_000 else 90_000) {
            println("Cloud fixture ${binding.romId}: receiving game and snapshot history")
            val envelopeReads = mutableMapOf<Int, Int>()
            val measured = object : ProgressTransport by remote {
                override suspend fun read(record: ProgressRemoteRecord, destination: File, maxBytes: Long) {
                    if (record.name.contains("_op_")) envelopeReads[record.id] = (envelopeReads[record.id] ?: 0) + 1
                    remote.read(record, destination, maxBytes)
                }
            }
            val received = ProgressSync(store, File(root, "receive")).synchronize(identity, measured)
            assertTrue(received.errors.joinToString(), received.errors.isEmpty())
            assertTrue("History envelopes must download once per reconciliation", envelopeReads.isNotEmpty() && envelopeReads.values.all { it == 1 })
            assertTrue(store.referenceSnapshot(identity).keys.any { it.startsWith("state/resume/") })
            val files = File(root, "native").apply { mkdirs() }
            val session = PlayerSession(binding.romId.toInt(), "Cross-platform fixture", rom, core.file, profile.copy(shader = PlayerShader.DEFAULT),
                File(files, "system").apply { mkdirs() }, File(files, "save").apply { mkdirs() }, File(files, "save/legacy.srm"), File(files, "states").apply { mkdirs() }, variables = profile.defaultVariables, coreIdentity = core.identity, progressBinding = binding)
            val owner = manager.prepare(session, false, true).await()
            scenario.onActivity { it.setContentView(owner.attach(it, it)) }
            // The isolated fixture has only an iOS snapshot. A live QA account
            // can retain compatible Android history from an earlier run.
            if (!config.optBoolean("allowLiveServer")) {
                val automatic = owner.select(ProgressLaunchRequest.Automatic).await()
                assertTrue(automatic is ProgressLaunchDecision.OfferColdBoot)
            }
            val decision = owner.select(ProgressLaunchRequest.ColdBootCurrent).await() as ProgressLaunchDecision.Ready
            assertFalse("iOS state must not be loaded into the Android core", decision.useState)
            val receivedMemory = owner.engine.captureMemorySnapshot(false).memory
            assertEquals(config.getString("expectedGame"), ProgressProtocol.sha256(receivedMemory.getValue(PersistentMemory.SRAM)))
            if (config.has("expectedClock")) assertEquals(config.getString("expectedClock"), ProgressProtocol.sha256(receivedMemory[PersistentMemory.RTC] ?: byteArrayOf()))
            owner.resume().await(); delay(200)
            if (config.optBoolean("n64FileMenu")) {
                delay(7_000)
                repeat(2) {
                    owner.engine.dispatchDigital(android.view.KeyEvent.KEYCODE_BUTTON_START, true)
                    delay(150)
                    owner.engine.dispatchDigital(android.view.KeyEvent.KEYCODE_BUTTON_START, false)
                    delay(3_000)
                }
                val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "progress-n64-cold-boot.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            assertFalse("Android snapshot must be committed", owner.checkpoint("pause").await().stateRejected)
            val actual = owner.engine.captureMemorySnapshot(true)
            val committed = owner.finishExit().await()
            println("Cloud fixture ${binding.romId}: publishing Android successor")
            // Lose the response only after RomM actually accepts the state blob.
            // The independent game-save lane must finish; retry must discover
            // the accepted blob without losing or replacing either platform stream.
            val stateHash = committed.checkpoint.artifacts.single { it.role == "state" }.sha256
            var lostResponse = false
            val interrupted = object : ProgressTransport by remote {
                override suspend fun upload(name: String, source: File): ProgressRemoteRecord {
                    val record = remote.upload(name, source)
                    if (!lostResponse && name.contains(stateHash)) {
                        lostResponse = true
                        throw java.io.IOException("QA response lost after server accepted snapshot")
                    }
                    return record
                }
            }
            val partial = ProgressSync(store, File(root, "interrupted")).synchronize(identity, interrupted)
            assertTrue("Snapshot upload fault was exercised", lostResponse)
            assertEquals("Game saves must publish despite interrupted snapshot", 0, partial.gamePending)
            assertTrue("Snapshot must remain pending until verified", partial.statePending > 0)
            for (row in db.progressDao().outbox(identity.namespaceId, identity.contentId)) {
                if (row.status == "PENDING") db.progressDao().updateOutbox(row.copy(nextAttempt = 0))
            }
            val sent = ProgressSync(store, File(root, "publish")).synchronize(identity, remote)
            assertTrue(sent.errors.joinToString(), sent.errors.isEmpty())
            assertEquals(0, sent.gamePending); assertEquals(0, sent.statePending)
            assertEquals("Lost-response retry must not duplicate the accepted blob", 1,
                remote.inventory().count { it.name.endsWith("_blob_$stateHash.bin") })
            val streams = store.referenceSnapshot(identity).filterKeys { it.startsWith("state/resume/") }
            assertTrue(streams.size >= 2)
            val platforms = streams.values.flatten().mapNotNull { it.checkpoint?.stateCompatibility?.platform }.toSet()
            assertTrue("Both platform snapshot streams must remain: $platforms", "android" in platforms && "ios-simulator" in platforms)
            // A second database/workspace must recover the Android snapshot from
            // the server, with no access to the first store's checkpoints or blobs.
            val recoveryDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val recoveryStore = ProgressStore(recoveryDb, File(root, "fresh-store"))
            val recoveryManager = ApplicationProgressSessions({ recoveryStore }, FceummPersistenceRegistry(packaged), { UUID.randomUUID().toString() }, modern = true)
            try {
                val recoveryIdentity = recoveryStore.resolveIdentity(binding)
                println("Cloud fixture ${binding.romId}: downloading into fresh Android store")
                val restoredCloud = ProgressSync(recoveryStore, File(root, "fresh-receive")).synchronize(recoveryIdentity, remote)
                assertTrue(restoredCloud.errors.joinToString(), restoredCloud.errors.isEmpty())
                val recoveredOwner = recoveryManager.prepare(session, false, true).await()
                scenario.onActivity { it.setContentView(recoveredOwner.attach(it, it)) }
                println("Cloud fixture ${binding.romId}: loading downloaded Android snapshot")
                val restoredDecision = recoveredOwner.select(ProgressLaunchRequest.Automatic).await()
                assertTrue("Fresh Android cloud restore: $restoredDecision", restoredDecision is ProgressLaunchDecision.Ready)
                assertTrue("Fresh Android store must load its cloud snapshot", (restoredDecision as ProgressLaunchDecision.Ready).useState)
                val restoredMemory = recoveredOwner.engine.captureMemorySnapshot(true)
                assertNotNull(restoredMemory.state)
                actual.memory.forEach { (id, bytes) -> assertArrayEquals("Cloud-restored region $id", bytes, restoredMemory.memory[id]) }
            } finally {
                withContext(NonCancellable) {
                    recoveryManager.active.value?.exitWithoutLatestProgress()?.await()
                    recoveryStore.close(); recoveryDb.close()
                }
            }
            File(context.filesDir, "cross-platform-result.json").writeText(JSONObject().put("status", "passed").put("freshAndroidSnapshotRestored", true).put("lostUploadResponseRecovered", lostResponse).put("gamePublishedDuringSnapshotFailure", partial.gamePending == 0).put("game", ProgressProtocol.sha256(actual.memory.getValue(PersistentMemory.SRAM))).put("clock", ProgressProtocol.sha256(actual.memory[PersistentMemory.RTC] ?: byteArrayOf())).toString())
        } } finally {
            withContext(NonCancellable) { manager.active.value?.exitWithoutLatestProgress()?.await(); scenario.close(); store.close(); db.close(); root.deleteRecursively() }
        }
    }
}
