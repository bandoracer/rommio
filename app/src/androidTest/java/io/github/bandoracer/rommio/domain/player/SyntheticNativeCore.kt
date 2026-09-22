package io.github.bandoracer.rommio.domain.player

import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

internal suspend fun withSyntheticNativeCore(
    gameName: String = "game.fixture",
    variables: Map<String, String> = emptyMap(),
    initialSaveRam: ByteArray? = null,
    awaitReady: Boolean = true,
    block: suspend (LibretroPlayerEngine, PlayerSession) -> Unit,
) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val root = File(context.filesDir, "native-fixture-${UUID.randomUUID()}").apply { mkdirs() }
    val core = File(root, "test_core.so")
    ZipFile(context.applicationInfo.sourceDir).use { apk ->
        val entry = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { apk.getEntry("lib/$it/librommio_test_core.so") }
        requireNotNull(entry) { "Debug build must package the synthetic persistence core." }
        apk.getInputStream(entry).use { input -> core.outputStream().use { input.copyTo(it) } }
    }
    val session = PlayerSession(10, "Native fixture", File(root, gameName).apply { writeText("fixture") }, core,
        RuntimeProfile("capture-test", "Capture test", setOf("fixture"), core.name),
        File(root, "system").apply { mkdirs() }, File(root, "save").apply { mkdirs() },
        File(root, "save/progress.srm"), File(root, "states").apply { mkdirs() },
        variables = variables, initialSaveRam = initialSaveRam,
        coreIdentity = CoreArtifactIdentity("capture-test", requireNotNull(currentCoreAbi()).folderName,
            io.github.bandoracer.rommio.domain.progress.ProgressProtocol.sha256(core.readBytes()), core.length(), "synthetic-test"),
        progressBinding = io.github.bandoracer.rommio.domain.progress.ProgressBinding("https://fixture.example", "test", 10,
            listOf(io.github.bandoracer.rommio.domain.progress.ProgressContentFile(11, 0,
                io.github.bandoracer.rommio.domain.progress.ProgressProtocol.sha256(File(root, gameName).readBytes()), null, null))))
    val engine = LibretroPlayerEngine()
    val scenario = ActivityScenario.launch(NativePlayerTestActivity::class.java)
    try {
        scenario.onActivity { it.setContentView(engine.createOrAttachView(it, it, session)) }
        withTimeout(15_000) { if (awaitReady) engine.awaitReady(); block(engine, session) }
    } finally {
        withContext(NonCancellable + Dispatchers.Main) { engine.detach() }
        NativePlayerOwnership.acquire().close()
        scenario.close(); root.deleteRecursively()
    }
}
