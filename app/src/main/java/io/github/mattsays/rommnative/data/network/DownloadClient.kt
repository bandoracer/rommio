package io.github.mattsays.rommnative.data.network

import io.github.mattsays.rommnative.data.auth.AuthManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class DownloadClient(
    private val authManager: AuthManager,
) {
    private val client = OkHttpClient.Builder()
        .addInterceptor(CookiePersistenceInterceptor())
        .build()

    suspend fun downloadToFile(
        profileId: String,
        absoluteUrl: String,
        target: File,
        includeOriginAuth: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val decoration = authManager.decorateRequest(
            profileId = profileId,
            url = absoluteUrl,
            includeOriginAuth = includeOriginAuth,
        )
        val request = Request.Builder()
            .url(absoluteUrl)
            .header("Accept", "*/*")
            .apply {
                decoration.headers.forEach { (key, value) -> header(key, value) }
            }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Download failed with ${response.code}")
            }

            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.contains("text/html")) {
                error("Download returned an HTML login page instead of ROM data.")
            }

            val body = response.body ?: error("Download returned an empty body.")
            target.outputStream().use { output ->
                body.byteStream().copyTo(output)
            }
        }
    }
}
