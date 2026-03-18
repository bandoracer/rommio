package io.github.mattsays.rommnative.data.auth

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.mattsays.rommnative.model.CloudflareServiceCredentials
import io.github.mattsays.rommnative.model.DirectLoginCredentials
import io.github.mattsays.rommnative.model.TokenBundle

class AuthSecretStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "romm_native_auth_secrets",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun storeBasicCredentials(profileId: String, credentials: DirectLoginCredentials) {
        prefs.edit(commit = true) {
            putString(key(profileId, "username"), credentials.username.trim())
            putString(key(profileId, "password"), credentials.password)
        }
    }

    fun getBasicCredentials(profileId: String): DirectLoginCredentials? {
        val username = prefs.getString(key(profileId, "username"), null)
        val password = prefs.getString(key(profileId, "password"), null)
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            return null
        }
        return DirectLoginCredentials(username = username, password = password)
    }

    fun clearBasicCredentials(profileId: String) {
        prefs.edit(commit = true) {
            remove(key(profileId, "username"))
            remove(key(profileId, "password"))
        }
    }

    fun storeTokenBundle(profileId: String, tokenBundle: TokenBundle) {
        prefs.edit(commit = true) {
            putString(key(profileId, "access_token"), tokenBundle.accessToken)
            putString(key(profileId, "refresh_token"), tokenBundle.refreshToken)
            putString(key(profileId, "token_type"), tokenBundle.tokenType)
            putString(key(profileId, "expires_at"), tokenBundle.expiresAt)
        }
    }

    fun getTokenBundle(profileId: String): TokenBundle? {
        val accessToken = prefs.getString(key(profileId, "access_token"), null) ?: return null
        val tokenType = prefs.getString(key(profileId, "token_type"), null) ?: "Bearer"
        return TokenBundle(
            accessToken = accessToken,
            refreshToken = prefs.getString(key(profileId, "refresh_token"), null),
            tokenType = tokenType,
            expiresAt = prefs.getString(key(profileId, "expires_at"), null),
        )
    }

    fun clearTokenBundle(profileId: String) {
        prefs.edit(commit = true) {
            remove(key(profileId, "access_token"))
            remove(key(profileId, "refresh_token"))
            remove(key(profileId, "token_type"))
            remove(key(profileId, "expires_at"))
        }
    }

    fun storeCloudflareCredentials(profileId: String, credentials: CloudflareServiceCredentials) {
        prefs.edit(commit = true) {
            putString(key(profileId, "cf_client_id"), credentials.clientId.trim())
            putString(key(profileId, "cf_client_secret"), credentials.clientSecret.trim())
        }
    }

    fun getCloudflareCredentials(profileId: String): CloudflareServiceCredentials? {
        val clientId = prefs.getString(key(profileId, "cf_client_id"), null)
        val clientSecret = prefs.getString(key(profileId, "cf_client_secret"), null)
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            return null
        }
        return CloudflareServiceCredentials(clientId = clientId, clientSecret = clientSecret)
    }

    fun clearCloudflareCredentials(profileId: String) {
        prefs.edit(commit = true) {
            remove(key(profileId, "cf_client_id"))
            remove(key(profileId, "cf_client_secret"))
        }
    }

    fun storeDeviceId(profileId: String, deviceId: String) {
        prefs.edit(commit = true) {
            putString(key(profileId, "device_id"), deviceId)
        }
    }

    fun getDeviceId(profileId: String): String? = prefs.getString(key(profileId, "device_id"), null)

    fun clearDeviceId(profileId: String) {
        prefs.edit(commit = true) {
            remove(key(profileId, "device_id"))
        }
    }

    fun clearAll(profileId: String) {
        prefs.edit(commit = true) {
            remove(key(profileId, "username"))
            remove(key(profileId, "password"))
            remove(key(profileId, "access_token"))
            remove(key(profileId, "refresh_token"))
            remove(key(profileId, "token_type"))
            remove(key(profileId, "expires_at"))
            remove(key(profileId, "cf_client_id"))
            remove(key(profileId, "cf_client_secret"))
            remove(key(profileId, "device_id"))
        }
    }

    private fun key(profileId: String, suffix: String): String {
        val sanitizedId = profileId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val sanitizedSuffix = suffix.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "auth_${sanitizedId}_$sanitizedSuffix"
    }
}
