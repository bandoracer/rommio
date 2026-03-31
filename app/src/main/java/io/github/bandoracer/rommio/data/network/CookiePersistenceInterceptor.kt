package io.github.bandoracer.rommio.data.network

import android.webkit.CookieManager
import okhttp3.Interceptor
import okhttp3.Response

class CookiePersistenceInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val url = response.request.url.toString()
        val cookieManager = CookieManager.getInstance()
        response.headers("Set-Cookie").forEach { cookie ->
            cookieManager.setCookie(url, cookie)
        }
        cookieManager.flush()
        return response
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
