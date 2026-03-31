package io.github.bandoracer.rommio.domain.player

import android.os.Build
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

interface CoreInstaller {
    suspend fun installCore(profile: RuntimeProfile): File
}

class LibretroCoreInstaller(
    private val libraryStore: LibraryStore,
) : CoreInstaller {
    private val client = OkHttpClient.Builder().build()

    override suspend fun installCore(profile: RuntimeProfile): File = withContext(Dispatchers.IO) {
        val descriptor = profile.download ?: error("No download is configured for ${profile.displayName}.")
        val abi = AndroidCoreAbi.fromDeviceAbis(Build.SUPPORTED_ABIS)
            ?: error("This device ABI is not supported for automated core downloads yet.")

        val target = File(libraryStore.coresDirectory(), profile.libraryFileName)
        if (target.exists() && target.length() > 0L) {
            return@withContext target
        }

        val archiveFile = kotlin.io.path.createTempFile(
            prefix = "${profile.runtimeId}_",
            suffix = ".zip",
        ).toFile()
        val extractedFile = kotlin.io.path.createTempFile(
            prefix = "${profile.runtimeId}_",
            suffix = ".so",
        ).toFile()

        try {
            downloadArchive(
                url = descriptor.archiveUrl(abi),
                target = archiveFile,
            )
            extractSharedLibrary(
                archive = archiveFile,
                expectedEntryName = descriptor.extractedFileName(),
                target = extractedFile,
            )

            target.parentFile?.mkdirs()
            extractedFile.copyTo(target, overwrite = true)
            target
        } finally {
            archiveFile.delete()
            extractedFile.delete()
        }
    }

    private fun downloadArchive(url: String, target: File) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/zip")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Core download failed with HTTP ${response.code}.")
            }
            val body = response.body ?: error("Core download returned an empty body.")
            target.outputStream().use { output ->
                body.byteStream().copyTo(output)
            }
        }
    }

    private fun extractSharedLibrary(archive: File, expectedEntryName: String, target: File) {
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }

                val entryName = entry.name.substringAfterLast('/')
                if (entryName == expectedEntryName || entryName.endsWith(".so")) {
                    target.outputStream().buffered().use { output ->
                        zip.copyTo(output)
                    }
                    zip.closeEntry()
                    return
                }
                zip.closeEntry()
            }
        }

        throw IOException("Unable to find $expectedEntryName inside ${archive.name}.")
    }
}
