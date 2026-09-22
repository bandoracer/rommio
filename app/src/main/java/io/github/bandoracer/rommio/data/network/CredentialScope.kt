package io.github.bandoracer.rommio.data.network

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** The configured deployment, carried on the request through OkHttp redirects. */
data class CredentialScope(val baseUrl: String) {
    fun contains(url: HttpUrl): Boolean {
        val base = baseUrl.toHttpUrlOrNull() ?: return false
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty()
        ) return false
        if (url.pathSegments.any { it.contains('/') || it.contains('\\') || it == "." || it == ".." }) return false
        val prefix = base.encodedPath.trimEnd('/')
        return url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/")
    }

    fun sanitize(request: Request): Request {
        if (contains(request.url)) return request
        if (request.body != null) throw IOException("Refusing to send a request body outside the configured server scope.")
        return request.newBuilder().apply {
            CREDENTIAL_HEADERS.forEach(::removeHeader)
        }.build()
    }

    companion object {
        val CREDENTIAL_HEADERS = listOf("Authorization", "Cookie", "Proxy-Authorization", "CF-Access-Client-Id", "CF-Access-Client-Secret")
    }
}

/** Network interceptors also run on redirects, unlike application interceptors. */
class CredentialScopeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val scope = request.tag(CredentialScope::class.java)
        return chain.proceed(scope?.sanitize(request) ?: request)
    }
}
