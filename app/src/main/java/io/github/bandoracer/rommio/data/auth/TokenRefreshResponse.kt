package io.github.bandoracer.rommio.data.auth

import com.squareup.moshi.Moshi
import io.github.bandoracer.rommio.model.TokenBundle
import java.io.IOException
import java.time.Instant

internal class TokenRefreshException(val reason: Reason, status: Int? = null) : IOException(
    when (reason) {
        Reason.INVALID_CREDENTIALS -> "RomM sign-in has expired. Sign in again to resume pending work."
        Reason.ACCESS_REQUIRED -> "Protected server access needs attention. Your RomM credentials have been kept."
        Reason.UNAVAILABLE -> "Token refresh failed (HTTP $status). Your credentials have been kept; retry when the server is available."
        Reason.INVALID_RESPONSE -> "RomM returned an invalid token response. Your credentials have been kept."
    },
) {
    enum class Reason { INVALID_CREDENTIALS, ACCESS_REQUIRED, UNAVAILABLE, INVALID_RESPONSE }
}

internal object TokenRefreshResponse {
    private val adapter = Moshi.Builder().build().adapter(Map::class.java)

    fun decode(status: Int, contentType: String?, body: String, previous: TokenBundle, now: Long = System.currentTimeMillis()): TokenBundle {
        if (contentType.orEmpty().contains("text/html", ignoreCase = true) || body.trimStart().startsWith("<")) {
            throw TokenRefreshException(TokenRefreshException.Reason.ACCESS_REQUIRED)
        }
        val json = runCatching { adapter.fromJson(body) }.getOrNull()
        if (status !in 200..299) {
            val confirmed = status in listOf(400, 401, 403) &&
                (json?.get("error") == "invalid_grant" ||
                    (status == 401 && json?.get("detail") == "Invalid refresh token") ||
                    (status == 403 && json?.get("detail") == "User account is disabled"))
            throw TokenRefreshException(
                if (confirmed) TokenRefreshException.Reason.INVALID_CREDENTIALS else TokenRefreshException.Reason.UNAVAILABLE,
                status,
            )
        }
        val accessToken = (json?.get("access_token") as? String)?.takeIf { it.isNotBlank() }
            ?: throw TokenRefreshException(TokenRefreshException.Reason.INVALID_RESPONSE)
        val seconds = (json["expires_in"] ?: json["expires"]) as? Number
        return TokenBundle(
            accessToken = accessToken,
            refreshToken = json["refresh_token"] as? String ?: previous.refreshToken,
            tokenType = json["token_type"] as? String ?: previous.tokenType,
            expiresAt = seconds?.let { Instant.ofEpochMilli(now + it.toLong() * 1000).toString() },
        )
    }
}
