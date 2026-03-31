package io.github.bandoracer.rommio.data.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.data.auth.AuthManager
import io.github.bandoracer.rommio.model.ServerProfile
import io.github.bandoracer.rommio.util.retrofitBaseUrl
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class RommServiceFactory(
    private val authManager: AuthManager,
) {
    private val cache = ConcurrentHashMap<String, RommService>()

    fun create(profile: ServerProfile): RommService {
        val baseUrl = retrofitBaseUrl(profile.baseUrl)
        val cacheKey = "${profile.id}|$baseUrl"

        return cache.getOrPut(cacheKey) {
            val logger = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(AuthRequestInterceptor(authManager, profile.id))
                .addInterceptor(CookiePersistenceInterceptor())
                .addInterceptor(JsonUnauthorizedInterceptor())
                .addInterceptor(logger)
                .build()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(RommService::class.java)
        }
    }
}
