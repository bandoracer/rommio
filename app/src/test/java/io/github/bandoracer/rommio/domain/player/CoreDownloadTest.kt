package io.github.bandoracer.rommio.domain.player

import android.content.Context
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CoreDownloadTest {
    private val root = Files.createTempDirectory("core-download").toFile()
    private val server = MockWebServer().apply { start() }
    private val store = CoreArtifactStore(File(root, "cores"))
    private val profile = buildPlatformRuntimeFamilies().first { it.familyId == "nes" }.runtimeOptions.first()
    private val library = object : LibraryStore {
        override fun rootDirectory() = root
        override fun coresDirectory() = File(root, "cores")
        override fun ensureRootLayout() { coresDirectory().mkdirs() }
        override fun romDirectory(platformSlug: String): File = error("unused")
        override fun romTarget(platformSlug: String, fileName: String): File = error("unused")
        override fun saveRamFile(installation: DownloadedRomEntity): File = error("unused")
        override fun saveStatesDirectory(installation: DownloadedRomEntity): File = error("unused")
        override fun continuityResumeStateFile(installation: DownloadedRomEntity): File = error("unused")
        override fun saveStateFile(installation: DownloadedRomEntity, slot: Int): File = error("unused")
        override fun screenshotsDirectory(installation: DownloadedRomEntity): File = error("unused")
        override fun systemDirectory(): File = error("unused")
        override fun biosDirectory(): File = error("unused")
        override fun resolveCoreLibrary(context: Context, libraryFileName: String): File? = null
    }
    private fun zip(bytes: ByteArray, name: String = profile.download!!.extractedFileName()) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry(name)); it.write(bytes); it.closeEntry() }
    }.toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun pinned(archive: ByteArray, bytes: ByteArray) = profile.copy(download = profile.download!!.copy(artifacts = mapOf("arm64-v8a" to
        CoreArtifactPin(server.url("/core.zip").toString(), hash(archive), hash(bytes)))))
    private fun installer(client: OkHttpClient = OkHttpClient()) = LibretroCoreInstaller(library, store, client) { AndroidCoreAbi.ARM64_V8A }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected download failure") } catch (_: IllegalStateException) { } catch (_: java.io.IOException) { }
    }
    private fun previous(): InstalledCore {
        val source = File(root, "previous.so").apply { writeBytes(syntheticCore().also { it[8000] = 7 }) }
        return store.install(profile, AndroidCoreAbi.ARM64_V8A, source, "existing-unpinned")
    }
    @After fun cleanup() { server.shutdown(); root.deleteRecursively() }

    @Test fun pinnedDownloadInstallsExactArchiveAndLibraryWithoutRepeatingNetwork() = runBlocking {
        val bytes = syntheticCore(); val archive = zip(bytes); val selected = pinned(archive, bytes)
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))
        val downloader = installer(); val installed = downloader.installCore(selected)
        assertArrayEquals(bytes, installed.readBytes())
        val record = store.selected(selected, AndroidCoreAbi.ARM64_V8A)!!
        assertTrue(record.identity.approvedPin); assertEquals(hash(archive), record.identity.archiveSha256)
        assertEquals(installed, downloader.installCore(selected)); assertEquals(1, server.requestCount)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }
    @Test fun badArchiveHashWrongLibraryAndWrongAbiPreservePriorSelection() = runBlocking {
        val old = previous(); val bytes = syntheticCore()
        val goodArchive = zip(bytes); val pinned = pinned(goodArchive, bytes)
        server.enqueue(MockResponse().setBody(Buffer().write(zip(syntheticCore(AndroidCoreAbi.X86_64)))))
        reject { installer().installCore(pinned) }
        val wrongName = zip(bytes, "another_libretro_android.so")
        server.enqueue(MockResponse().setBody(Buffer().write(wrongName)))
        reject { installer().installCore(pinned(wrongName, bytes)) }
        val wrongAbi = syntheticCore(AndroidCoreAbi.X86_64); val archive = zip(wrongAbi)
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))
        reject { installer().installCore(pinned(archive, wrongAbi)) }
        assertEquals(old, store.selected(profile, AndroidCoreAbi.ARM64_V8A, fresh = true))
    }
    @Test fun httpErrorHtmlAndTruncatedResponseCannotReplaceValidCore() = runBlocking {
        val old = previous(); val bytes = syntheticCore(); val archive = zip(bytes); val selected = pinned(archive, bytes)
        for (response in listOf(MockResponse().setResponseCode(503), MockResponse().setHeader("Content-Type", "text/html").setBody("<html>login</html>"),
            MockResponse().setBody(Buffer().write(archive)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))) {
            server.enqueue(response); reject { installer().installCore(selected) }
            assertEquals(old, store.selected(profile, AndroidCoreAbi.ARM64_V8A, fresh = true))
        }
    }
    @Test fun duplicateOrTraversalEntriesCannotSelectAnArbitraryLibrary() = runBlocking {
        val bytes = syntheticCore()
        val traversal = zip(bytes, "../${profile.download!!.extractedFileName()}")
        server.enqueue(MockResponse().setBody(Buffer().write(traversal)))
        reject { installer().installCore(pinned(traversal, bytes)) }
        val duplicate = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use {
            for (name in listOf("fceumm_libretro_android.so", "zceumm_libretro_android.so")) { it.putNextEntry(ZipEntry(name)); it.write(bytes); it.closeEntry() }
        } }.toByteArray()
        // Make both local and central headers name the same entry without changing payload CRCs.
        for (i in 0..duplicate.size - 6) if (String(duplicate, i, 6, Charsets.US_ASCII) == "zceumm") duplicate[i] = 'f'.code.toByte()
        server.enqueue(MockResponse().setBody(Buffer().write(duplicate)))
        reject { installer().installCore(pinned(duplicate, bytes)) }
        assertNull(store.selected(profile, AndroidCoreAbi.ARM64_V8A))
    }
    @Test fun unpinnedDownloadIsRecordedAsObservedAndThenRemainsStable() = runBlocking {
        val bytes = syntheticCore(); val archive = zip(bytes)
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))
        val redirectToFixture = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/core.zip")).build()) }.build()
        val downloader = installer(redirectToFixture)
        val file = downloader.installCore(profile)
        assertFalse(store.selected(profile, AndroidCoreAbi.ARM64_V8A)!!.identity.approvedPin)
        assertEquals(file, downloader.installCore(profile)); assertEquals(1, server.requestCount)
    }
    @Test fun canceledTransferDoesNotPublishOrRemovePreviousSelection() = runBlocking {
        val old = previous(); val bytes = syntheticCore(); val archive = zip(bytes); val selected = pinned(archive, bytes)
        server.enqueue(MockResponse().setBody(Buffer().write(archive)).throttleBody(64, 50, java.util.concurrent.TimeUnit.MILLISECONDS))
        val work = launch { installer().installCore(selected) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
        work.cancelAndJoin()
        assertEquals(old, store.selected(profile, AndroidCoreAbi.ARM64_V8A, fresh = true))
        assertTrue(File(root, "cores/staging").listFiles().orEmpty().none { it.isFile })
    }
}
