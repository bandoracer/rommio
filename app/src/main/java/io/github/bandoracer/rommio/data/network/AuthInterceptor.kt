package io.github.bandoracer.rommio.data.network

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val basicAuthHeader: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
            .newBuilder()
            .header("Authorization", basicAuthHeader)
            .header("Accept", "application/json")
            .build()

        return chain.proceed(request)
    }
}
