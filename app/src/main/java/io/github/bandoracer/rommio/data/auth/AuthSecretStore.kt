package io.github.bandoracer.rommio.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.bandoracer.rommio.model.CloudflareServiceCredentials
import io.github.bandoracer.rommio.model.DirectLoginCredentials
import io.github.bandoracer.rommio.model.TokenBundle
import org.json.JSONObject

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
        write {
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
        write {
            remove(key(profileId, "username"))
            remove(key(profileId, "password"))
        }
    }

    fun storeTokenBundle(profileId: String, tokenBundle: TokenBundle) {
        write {
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
        write {
            remove(key(profileId, "access_token"))
            remove(key(profileId, "refresh_token"))
            remove(key(profileId, "token_type"))
            remove(key(profileId, "expires_at"))
        }
    }

    fun storeCloudflareCredentials(profileId: String, credentials: CloudflareServiceCredentials) {
        write {
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
        write {
            remove(key(profileId, "cf_client_id"))
            remove(key(profileId, "cf_client_secret"))
        }
    }

    fun storeDeviceId(profileId: String, deviceId: String) {
        write {
            putString(key(profileId, "device_id"), deviceId)
        }
    }

    fun getDeviceId(profileId: String): String? = prefs.getString(key(profileId, "device_id"), null)

    fun clearDeviceId(profileId: String) {
        write {
            remove(key(profileId, "device_id"))
        }
    }

    fun clearAll(profileId: String) {
        write {
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

    fun cookieStoreName(profileId: String, baseUrl: String): String? = prefs.getString(cookieKey(profileId, baseUrl), null)

    fun verifiedAccount(profileId: String): VerifiedAccount? {
        val raw = prefs.getString(accountKey(profileId), null) ?: return null
        val json = JSONObject(raw) // Corrupt ownership must fail closed, never become an unbound profile.
        return VerifiedAccount(json.getString("baseUrl"), json.getInt("userId"))
    }

    /** Called under the authentication generation lock; retained by logout and secret clearing. */
    fun bindVerifiedAccount(profileId: String, account: VerifiedAccount) {
        val previous = verifiedAccount(profileId)
        if (previous != null && previous != account) throw AccountBindingException()
        if (previous != null) return
        val raw = JSONObject().put("baseUrl", account.baseUrl).put("userId", account.userId).toString()
        check(prefs.edit().putString(accountKey(profileId), raw).commit()) { "Unable to persist verified account ownership" }
    }

    private fun accountKey(profileId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "verified_account_$digest"
    }

    fun storeCookieStoreName(profileId: String, baseUrl: String, name: String) {
        check(prefs.edit().putString(cookieKey(profileId, baseUrl), name).commit()) { "Unable to persist browser session ownership" }
    }

    private fun cookieKey(profileId: String, baseUrl: String): String {
        val raw = "${profileId.length}:$profileId$baseUrl".toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        return "cookie_store_$digest"
    }

    internal fun storeImportSecrets(id: String, json: String) {
        check(prefs.edit().putString("import_recovery_$id", json).commit()) { "Unable to preserve protected import credentials." }
    }
    internal fun importSecrets(id: String): String? = prefs.getString("import_recovery_$id", null)
    internal fun removeImportSecrets(id: String) {
        check(prefs.edit().remove("import_recovery_$id").commit()) { "Unable to finalize protected import credentials." }
    }
    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        check(prefs.edit().apply(block).commit()) { "Unable to persist authentication data." }
    }

    private fun key(profileId: String, suffix: String): String {
        val sanitizedId = profileId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val sanitizedSuffix = suffix.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "auth_${sanitizedId}_$sanitizedSuffix"
    }
}
