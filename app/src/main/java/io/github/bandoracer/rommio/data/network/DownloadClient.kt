package io.github.bandoracer.rommio.data.network

import io.github.bandoracer.rommio.data.auth.AuthManager
import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import java.io.FileOutputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class DownloadHttpException(val status: Int) : java.io.IOException("Download failed with HTTP $status")

class DownloadClient(
    private val authManager: AuthManager,
) {
    private val client = OkHttpClient.Builder()
        .addNetworkInterceptor(CredentialScopeInterceptor())
        .addNetworkInterceptor(authManager.cookieInterceptor())
        .build()

    suspend fun downloadToFile(
        profileId: String,
        absoluteUrl: String,
        target: File,
        includeOriginAuth: Boolean = true,
        expectedSize: Long? = null,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val request = Request.Builder().url(absoluteUrl).header("Accept", "*/*").build()
        val authenticatedClient = client.newBuilder()
            .addInterceptor(AuthRequestInterceptor(authManager, profileId, includeOriginAuth))
            .build()
        downloadVerifiedFile(authenticatedClient, request, target, expectedSize, onProgress)
    }
}

internal suspend fun downloadVerifiedFile(
    client: OkHttpClient,
    request: Request,
    target: File,
    expectedSize: Long? = null,
    onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
) = withContext(Dispatchers.IO) {
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful || response.code == 206) throw DownloadHttpException(response.code)
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val preview = response.peekBody(512).string().trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase()
        check(!contentType.contains("text/html") && !preview.startsWith("<!doctype html") && !preview.startsWith("<html")) {
            "Download returned an HTML login page instead of game data."
        }
        val body = response.body ?: error("Download returned an empty body.")
        val contentLength = body.contentLength()
        val totalBytes = (expectedSize ?: contentLength).coerceAtLeast(0L)
        val staged = AtomicFiles.stagingFile(target)
        try {
            FileOutputStream(staged).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                    check(contentLength < 0 || downloaded == contentLength) { "Download was incomplete." }
                    check(expectedSize == null || downloaded == expectedSize) { "Downloaded size does not match the library file." }
                }
                output.flush()
                output.fd.sync()
            }
            coroutineContext.ensureActive()
            AtomicFiles.install(staged, target)
        } finally {
            staged.delete()
        }
    }
}
