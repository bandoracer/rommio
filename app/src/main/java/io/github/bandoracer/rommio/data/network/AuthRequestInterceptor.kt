package io.github.bandoracer.rommio.data.network

import io.github.bandoracer.rommio.data.auth.AuthManager
import io.github.bandoracer.rommio.model.RequestDecoration
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class AuthRequestInterceptor internal constructor(
    private val decorate: suspend (String) -> RequestDecoration,
    private val refreshRejected: suspend (String, String) -> Boolean,
) : Interceptor {
    constructor(authManager: AuthManager, profileId: String, includeOriginAuth: Boolean = true) : this(
        decorate = { url -> authManager.decorateRequest(profileId, url, includeOriginAuth) },
        refreshRejected = { url, rejected ->
            includeOriginAuth && authManager.refreshAfterUnauthorized(profileId, url, rejected)
        },
    )

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val original = chain.request()
        val request = decorated(original)
        val response = chain.proceed(request)
        val authorization = request.header("Authorization")
        val html = response.code == 401 && (response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true) ||
            response.peekBody(512).string().trimStart().startsWith("<"))
        val scope = request.tag(CredentialScope::class.java)
        if (response.code != 401 || html || original.method !in listOf("GET", "HEAD") || original.body != null ||
            authorization == null || scope?.contains(response.request.url) != true) return@runBlocking response
        // No mutation replay here. Publication needs its own persisted operation/readback contract.
        val shouldRetry = try {
            refreshRejected(original.url.toString(), authorization)
        } catch (error: Exception) {
            response.close()
            throw error
        }
        if (!shouldRetry) return@runBlocking response
        response.close()
        chain.proceed(decorated(original))
    }

    private suspend fun decorated(request: Request): Request {
        val decoration = decorate(request.url.toString())
        val builder = request.newBuilder()
        if (request.header("Accept") == null) builder.header("Accept", "application/json")
        CredentialScope.CREDENTIAL_HEADERS.forEach(builder::removeHeader)
        decoration.credentialBaseUrl?.let { builder.tag(CredentialScope::class.java, CredentialScope(it)) }
        builder.tag(io.github.bandoracer.rommio.data.auth.ProfileCookieContext::class.java, decoration.cookieContext)
        decoration.headers.forEach { (key, value) -> builder.header(key, value) }
        return builder.build()
    }
}
