package io.github.bandoracer.rommio.data.network

import io.github.bandoracer.rommio.data.auth.ProfileCookies
import io.github.bandoracer.rommio.data.auth.ProfileCookieContext
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/** Runs on every network hop, so path changes and Set-Cookie redirects use the correct jar. */
class CookiePersistenceInterceptor(private val cookies: ProfileCookies) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        val context = request.tag(ProfileCookieContext::class.java)
        val builder = request.newBuilder().removeHeader("Cookie")
        cookies.header(context, request.url)?.let { builder.header("Cookie", it) }
        val response = chain.proceed(builder.build())
        try { cookies.persist(context, response.request.url, response.headers("Set-Cookie")) }
        catch (error: Exception) { response.close(); throw error }
        response
    }
}

class JsonUnauthorizedInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) {
            return response
        }

        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val preview = response.peekBody(512).string().trimStart().lowercase()
        val looksHtml = contentType.contains("text/html") ||
            preview.startsWith("<!doctype") ||
            preview.startsWith("<html") ||
            preview.startsWith("<")

        if (!looksHtml) {
            return response
        }

        return response.newBuilder()
            .code(401)
            .message("Unauthorized")
            .build()
    }
}
