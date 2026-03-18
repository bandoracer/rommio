package io.github.mattsays.rommnative.data.auth

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.mattsays.rommnative.model.AuthCapabilities
import io.github.mattsays.rommnative.model.ServerAccessState
import io.github.mattsays.rommnative.model.SessionState

class AuthJsonCodec {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val capabilitiesAdapter = moshi.adapter(AuthCapabilities::class.java)
    private val serverAccessAdapter = moshi.adapter(ServerAccessState::class.java)
    private val sessionStateAdapter = moshi.adapter(SessionState::class.java)
    private val stringListAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java),
    )

    fun encodeCapabilities(value: AuthCapabilities): String = capabilitiesAdapter.toJson(value)
    fun decodeCapabilities(value: String): AuthCapabilities = capabilitiesAdapter.fromJson(value) ?: AuthCapabilities()

    fun encodeServerAccess(value: ServerAccessState): String = serverAccessAdapter.toJson(value)
    fun decodeServerAccess(value: String): ServerAccessState = serverAccessAdapter.fromJson(value) ?: ServerAccessState()

    fun encodeSessionState(value: SessionState): String = sessionStateAdapter.toJson(value)
    fun decodeSessionState(value: String): SessionState = sessionStateAdapter.fromJson(value) ?: SessionState()

    fun encodeCookieNames(value: List<String>): String = stringListAdapter.toJson(value)
}
