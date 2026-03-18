package io.github.mattsays.rommnative.data.network

import io.github.mattsays.rommnative.data.auth.AuthManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class AuthRequestInterceptor(
    private val authManager: AuthManager,
    private val profileId: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val decorated = runBlocking {
            authManager.decorateRequest(profileId = profileId, url = request.url.toString(), includeOriginAuth = true)
        }

        val builder = request.newBuilder()
            .header("Accept", "application/json")
        decorated.headers.forEach { (key, value) ->
            builder.header(key, value)
        }

        return chain.proceed(builder.build())
    }
}
