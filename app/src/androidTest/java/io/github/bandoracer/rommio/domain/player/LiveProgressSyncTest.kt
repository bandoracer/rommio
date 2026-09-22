package io.github.bandoracer.rommio.domain.player

import android.content.Context
import androidx.lifecycle.Lifecycle
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

/** Opt-in against the disposable localhost RomM stack; two independent client stores and native sessions. */
class LiveProgressSyncTest {
    @Test fun twoClientsSyncNativeGameSavesAndSnapshotsThroughRealRomm() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val credentials = File(context.filesDir, "isolated-progress-sync.json")
        assumeTrue("Run scripts/android/verify_progress_sync.py to provision isolated credentials", credentials.isFile)
        val config = JSONObject(credentials.readText())
        require(config.getString("baseUrl") == "http://127.0.0.1:18765")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${config.getString("token")}").build())
        }.build()
        val service = Retrofit.Builder().baseUrl("http://127.0.0.1:18765/").client(http)
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build())).build().create(RommService::class.java)
        val root = File(context.filesDir, "live-sync-${UUID.randomUUID()}").apply { mkdirs() }
        val databases = (0..1).map { Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build() }
        val stores = databases.mapIndexed { i, db -> ProgressStore(db, File(root, "client-$i/store")) }
        val profile = buildPlatformRuntimeFamilies().first { it.familyId == "nes" }.runtimeOptions.first()
        val artifacts = CoreArtifactStore(File(root, "cores"))
        val packaged = PackagedCoreArtifacts(File(root, "cores"), artifacts) { context.assets.open(it) }
        val core = packaged.install(profile, requireNotNull(currentCoreAbi()))
        // Original mapper-0 fixture, with a unique unused program padding for this test run.
        val rom = File(root, "counter.nes").apply { writeBytes(counterRom().also { bytes ->
            UUID.randomUUID().toString().take(6).toByteArray().copyInto(bytes, 200)
        }) }
        val binding = ProgressBinding(config.getString("baseUrl"), config.getString("accountId"), config.getLong("romId"),
            listOf(ProgressContentFile(1, 0, CoreBinary.sha256(rom), null, null)))
        val identities = stores.map { it.resolveIdentity(binding) }
        val remote = RommProgressTransport(service, binding).also { it.qualify() }
        val managers = stores.map { store -> ApplicationProgressSessions({ store }, FceummPersistenceRegistry(packaged),
            { UUID.randomUUID().toString() }, modern = true) }
        val scenario = ActivityScenario.launch(NativePlayerTestActivity::class.java)
        fun source(client: Int): PlayerSession {
            val files = File(root, "client-$client/native").apply { mkdirs() }
            return PlayerSession(binding.romId.toInt(), "Sync fixture", rom, core.file, profile.copy(shader = PlayerShader.DEFAULT),
                File(files, "system").apply { mkdirs() }, File(files, "save").apply { mkdirs() }, File(files, "save/legacy.srm"),
                File(files, "states").apply { mkdirs() }, coreIdentity = core.identity, progressBinding = binding)
        }
        suspend fun open(client: Int, request: ProgressLaunchRequest = ProgressLaunchRequest.Automatic): ApplicationProgressSession {
            val owner = managers[client].prepare(source(client), false, true).await()
            scenario.onActivity { it.setContentView(owner.attach(it, it)) }
            assertTrue(owner.select(request).await() is ProgressLaunchDecision.Ready)
            return owner
        }
        suspend fun sync(client: Int): ProgressSyncResult = ProgressSync(stores[client], File(root, "transfer-$client")).synchronize(identities[client], remote)
        try { withTimeout(120_000) {
            val a = open(0); a.resume().await(); delay(180)
            a.checkpoint("pause").await()
            val expected = a.engine.captureMemorySnapshot(true)
            val saved = a.finishExit().await()
            val stateHash = saved.checkpoint.artifacts.single { it.role == "state" }.sha256
            var loseResponse = true
            val interrupted = object : ProgressTransport by remote {
                override suspend fun upload(name: String, source: File): ProgressRemoteRecord {
                    val record = remote.upload(name, source)
                    if (loseResponse && name.contains(stateHash)) {
                        loseResponse = false
                        throw java.io.IOException("Synthetic lost response after real state upload")
                    }
                    return record
                }
            }
            val partial = ProgressSync(stores[0], File(root, "interrupted")).synchronize(identities[0], interrupted)
            assertEquals(0, partial.gamePending); assertTrue(partial.statePending > 0)
            assertTrue(sync(1).errors.isEmpty())
            assertNotNull(stores[1].checkpoint(identities[1], "game/current"))
            assertTrue(stores[1].referenceSnapshot(identities[1]).keys.none { it.startsWith("state/") })
            for (row in databases[0].progressDao().outbox(identities[0].namespaceId, identities[0].contentId)) {
                if (row.status == "PENDING") databases[0].progressDao().updateOutbox(row.copy(nextAttempt = 0))
            }
            assertTrue(sync(0).errors.isEmpty()); assertTrue(sync(1).errors.isEmpty())
            val b = open(1)
            assertTrue((b.state.value.decision as ProgressLaunchDecision.Ready).useState)
            val actual = b.engine.captureMemorySnapshot(true)
            assertArrayEquals(expected.state, actual.state)
            expected.memory.forEach { (region, bytes) -> assertArrayEquals(bytes, actual.memory[region]) }
            // Game-save-only launch works even with an incompatible state descriptor.
            b.exitWithoutLatestProgress().await()
            val cold = open(1, ProgressLaunchRequest.ColdBootCurrent)
            assertFalse((cold.state.value.decision as ProgressLaunchDecision.Ready).useState)
            val coldMemory = cold.engine.captureMemorySnapshot(false)
            expected.memory.forEach { (region, bytes) -> assertArrayEquals(bytes, coldMemory.memory[region]) }
            cold.resume().await(); delay(150); cold.checkpoint("pause").await()
            val newer = cold.engine.captureMemorySnapshot(true)
            // Paused compare rejects rollback before creating a safety save or changing either stream.
            val before = stores[1].operationPayloads(identities[1]).size
            val rejection = runCatching { cold.restore(saved.checkpoint.checkpointId).await() }.exceptionOrNull()
            assertTrue(rejection is GameSaveRollbackRequired)
            assertEquals(before, stores[1].operationPayloads(identities[1]).size)
            assertArrayEquals(newer.state, cold.engine.captureMemorySnapshot(true).state)
            cold.restore(saved.checkpoint.checkpointId, confirmGameRollback = true).await()
            assertArrayEquals(expected.state, cold.engine.captureMemorySnapshot(true).state)
            assertTrue(cold.history().await().entries.any { it.checkpoint.captureReason == "safety" && it.pinned })
            cold.finishExit().await()
            assertTrue(sync(1).errors.isEmpty()); assertTrue(sync(0).errors.isEmpty())
            assertEquals(stores[0].referenceSnapshot(identities[0]), stores[1].referenceSnapshot(identities[1]))
            assertTrue(stores.all { it.publicationStatus(identities[stores.indexOf(it)]).all { row -> row.status == "PUBLISHED" } })
        } } finally {
            withContext(NonCancellable) {
                managers.forEach { it.active.value?.exitWithoutLatestProgress()?.await() }
                if (scenario.state == Lifecycle.State.CREATED) scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.close(); stores.forEach { it.close() }; databases.forEach { it.close() }; root.deleteRecursively()
            }
        }
    }
}
