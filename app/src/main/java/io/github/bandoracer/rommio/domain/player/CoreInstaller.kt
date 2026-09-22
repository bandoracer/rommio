package io.github.bandoracer.rommio.domain.player

import android.os.Build
import android.os.Process
import io.github.bandoracer.rommio.data.network.downloadVerifiedFile
import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import io.github.bandoracer.rommio.domain.storage.StorageAccess
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request

interface CoreInstaller { suspend fun installCore(profile: RuntimeProfile): File }

internal fun currentCoreAbi(): AndroidCoreAbi? = AndroidCoreAbi.fromDeviceAbis(
    if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS,
)

class LibretroCoreInstaller(
    private val libraryStore: LibraryStore,
    private val artifacts: CoreArtifactStore = CoreArtifactStore(libraryStore.coresDirectory()),
    private val client: OkHttpClient = OkHttpClient.Builder().followSslRedirects(false).build(),
    private val packaged: PackagedCoreArtifacts? = null,
    private val abiProvider: () -> AndroidCoreAbi? = ::currentCoreAbi,
) : CoreInstaller {
    private val lock = Mutex()
    override suspend fun installCore(profile: RuntimeProfile): File = StorageAccess.normal { withContext(Dispatchers.IO) {
        lock.withLock {
            val descriptor = profile.download ?: error("No download is configured for ${profile.displayName}.")
            val abi = abiProvider() ?: error("This app process ABI is not supported for core downloads.")
            val pin = descriptor.artifacts[abi.folderName]
            // An invalid selection can be repaired without changing the old artifact. No automatic update of valid selections.
            val previous = runCatching { artifacts.selected(profile, abi, fresh = true) }.getOrNull()
            if (previous != null && (pin == null || previous.identity.sha256 == pin.librarySha256)) return@withLock previous.file
            if (packaged?.find(profile, abi) != null) return@withLock packaged.install(profile, abi).file
            val archive = AtomicFiles.stagingFile(File(libraryStore.coresDirectory(), "staging/archive.zip"))
            val extracted = AtomicFiles.stagingFile(File(libraryStore.coresDirectory(), "staging/core.so"))
            try {
                downloadVerifiedFile(client, Request.Builder().url(descriptor.archiveUrl(abi)).header("Accept", "application/zip").build(), archive) { downloaded, _ ->
                    check(downloaded <= MAX_ARCHIVE_BYTES) { "Core archive exceeds the download size limit." }
                }
                val archiveHash = CoreBinary.sha256(archive)
                if (pin != null) check(archiveHash == pin.archiveSha256) { "Downloaded archive does not match the approved core artifact." }
                extract(archive, descriptor.extractedFileName(), extracted)
                coroutineContext.ensureActive()
                artifacts.install(profile, abi, extracted, "download", descriptor.archiveUrl(abi), archiveHash, pin?.librarySha256).file
            } finally { archive.delete(); extracted.delete() }
        }
    } }

    private suspend fun extract(archive: File, expected: String, target: File) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries().toList()
            check(entries.size <= 128) { "Core archive has too many entries." }
            entries.forEach { entry ->
                check(entry.name.removeSuffix("/").split('/').all { it.isNotEmpty() && it !in setOf(".", "..") } && '\\' !in entry.name) { "Invalid archive entry path." }
                check(entry.size in 0..CoreBinary.MAX_BYTES) { "Unbounded core archive entry." }
            }
            val selected = entries.filter { !it.isDirectory && it.name == expected }
            check(selected.size == 1) { "Core archive must contain exactly one $expected." }
            check(entries.none { !it.isDirectory && it.name.endsWith(".so") && it.name != expected }) { "Core archive contains an unexpected library." }
            val entry = selected.single()
            val crc = CRC32()
            java.io.FileOutputStream(target).use { output ->
                var count = 0L
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer); if (read < 0) break
                        count += read; check(count <= CoreBinary.MAX_BYTES) { "Core library exceeds the size limit." }
                        crc.update(buffer, 0, read); output.write(buffer, 0, read)
                    }
                }
                check(count == entry.size && crc.value == entry.crc) { "Extracted core is incomplete or corrupt." }
                output.fd.sync()
            }
        }
    }
    companion object { private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024 }
}
