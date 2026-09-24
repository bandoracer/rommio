package io.github.bandoracer.rommio.data.setup

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A code the setup service allocated for this device (setup-service/README.md, step 1). */
data class SetupPairing(
    val code: String,
    val secret: String,
    val approveUrl: String,
    val expiresInSeconds: Int,
    val pollIntervalSeconds: Int,
)

/** What an approval delivers: the RomM address and the Cloudflare Access service token. */
data class SetupConfiguration(
    val rommUrl: String,
    val clientId: String,
    val clientSecret: String,
) {
    /** Only an https server with a complete service token is accepted (as on iOS). */
    fun validate() {
        val url = rommUrl.toHttpUrlOrNull()
        val ok = url != null && url.isHttps && url.host.isNotEmpty() && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null &&
            clientId.isNotBlank() && clientSecret.isNotBlank() &&
            clientId.none { it == '\n' || it == '\r' } && clientSecret.none { it == '\n' || it == '\r' }
        if (!ok) throw OneTouchSetupException(OneTouchSetupException.Kind.INVALID_RESPONSE)
    }

    override fun toString(): String = "SetupConfiguration(rommUrl=$rommUrl)"
}

sealed interface SetupPollResult {
    data object Pending : SetupPollResult
    data class Approved(val configuration: SetupConfiguration) : SetupPollResult
    data object Denied : SetupPollResult
    data object Expired : SetupPollResult
}

/** Setup failures in the words iOS uses (`OneTouchSetupError`). */
class OneTouchSetupException(val kind: Kind) : Exception(kind.message) {
    enum class Kind(val message: String) {
        INVALID_RESPONSE("The setup service returned an invalid configuration."),
        UNAVAILABLE("Could not reach the setup service. Check your connection and try again."),
        RATE_LIMITED("Too many setup attempts. Wait a minute and try again."),
        EXPIRED("This setup code expired. Start again for a new code."),
        DENIED("This setup request was denied. You can start again."),
    }
}

/** The device API (setup-service/README.md "Contract"); a fake stands in for it in tests. */
interface OneTouchSetupService {
    suspend fun start(deviceName: String): SetupPairing
    suspend fun poll(pairing: SetupPairing): SetupPollResult
    suspend fun complete(pairing: SetupPairing)
    suspend fun cancel(pairing: SetupPairing)
}

/**
 * The setup service over HTTPS. The pairing connection is its own client: no cookies, no
 * credentials of any server, no redirects; polling secrets travel in POST bodies, never in URLs or
 * the QR code. [allowLocalHttp] lets a debug build talk to a local mock over plain HTTP (the
 * emulator's host loopback); release builds require HTTPS for both origins.
 */
class OneTouchSetupClient(
    private val baseUrl: HttpUrl,
    private val approvalOrigin: HttpUrl,
    client: OkHttpClient = OkHttpClient(),
    private val allowLocalHttp: Boolean = false,
) : OneTouchSetupService {
    private val http = client.newBuilder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val moshi = Moshi.Builder().build()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    override suspend fun start(deviceName: String): SetupPairing {
        val body = post("start", mapOf("deviceName" to deviceName.take(64)))
        val code = body["code"] as? String
        val secret = body["secret"] as? String
        val approveUrl = (body["approveUrl"] as? String)?.toHttpUrlOrNull()
        val expires = (body["expiresInSeconds"] as? Number)?.toInt()
        val poll = (body["pollIntervalSeconds"] as? Number)?.toInt()
        val valid = code != null && CODE.matches(code) && secret != null && SECRET.matches(secret) &&
            approveUrl != null && secure(approveUrl) && approveUrl.host == approvalOrigin.host && approveUrl.port == approvalOrigin.port &&
            approveUrl.scheme == approvalOrigin.scheme && approveUrl.encodedPath == "/a/$code" && approveUrl.query == null &&
            expires != null && expires in 1..600 && poll != null && poll in 1..30
        if (!valid) throw OneTouchSetupException(OneTouchSetupException.Kind.INVALID_RESPONSE)
        return SetupPairing(code!!, secret!!, approveUrl.toString(), expires!!, poll!!)
    }

    override suspend fun poll(pairing: SetupPairing): SetupPollResult {
        val body = try {
            post("poll", credentials(pairing))
        } catch (error: OneTouchSetupException) {
            if (error.kind == OneTouchSetupException.Kind.EXPIRED) return SetupPollResult.Expired
            throw error
        }
        return when (body["state"]) {
            "pending" -> SetupPollResult.Pending
            "denied" -> SetupPollResult.Denied
            "expired" -> SetupPollResult.Expired
            "approved" -> {
                val profile = body["accessProfile"] as? Map<*, *>
                if (profile?.get("type") != "serviceToken") throw OneTouchSetupException(OneTouchSetupException.Kind.INVALID_RESPONSE)
                val configuration = SetupConfiguration(
                    rommUrl = body["rommUrl"] as? String ?: "",
                    clientId = profile["clientId"] as? String ?: "",
                    clientSecret = profile["clientSecret"] as? String ?: "",
                )
                configuration.validate()
                SetupPollResult.Approved(configuration)
            }
            else -> throw OneTouchSetupException(OneTouchSetupException.Kind.INVALID_RESPONSE)
        }
    }

    override suspend fun complete(pairing: SetupPairing) {
        post("complete", credentials(pairing))
    }

    override suspend fun cancel(pairing: SetupPairing) {
        try { post("cancel", credentials(pairing)) } catch (error: CancellationException) { throw error } catch (_: Exception) {}
    }

    private fun credentials(pairing: SetupPairing) = mapOf("code" to pairing.code, "secret" to pairing.secret)

    private fun secure(url: HttpUrl): Boolean {
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.host.isEmpty()) return false
        return url.isHttps || (allowLocalHttp && url.host in LOCAL_HOSTS)
    }

    private suspend fun post(endpoint: String, body: Map<String, String>): Map<String, Any?> {
        if (!secure(baseUrl) || !secure(approvalOrigin)) throw OneTouchSetupException(OneTouchSetupException.Kind.INVALID_RESPONSE)
        val url = baseUrl.newBuilder().addPathSegments("device/$endpoint").build()
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(mapAdapter.toJson(body).toRequestBody(JSON))
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            throw OneTouchSetupException(OneTouchSetupException.Kind.UNAVAILABLE)
        }
        return response.use {
            when (it.code) {
                200 -> withContext(Dispatchers.IO) {
                    val text = it.body?.string().orEmpty()
                    runCatching { mapAdapter.fromJson(text) }.getOrNull()
                        ?: throw OneTouchSetupException(OneTouchSetupException.Kind.INVALID_RESPONSE)
                }
                404 -> if (endpoint != "start") throw OneTouchSetupException(OneTouchSetupException.Kind.EXPIRED)
                    else throw OneTouchSetupException(OneTouchSetupException.Kind.UNAVAILABLE)
                429 -> throw OneTouchSetupException(OneTouchSetupException.Kind.RATE_LIMITED)
                else -> throw OneTouchSetupException(OneTouchSetupException.Kind.UNAVAILABLE)
            }
        }
    }

    companion object {
        private val CODE = Regex("^[A-Z2-9]{6}$")
        private val SECRET = Regex("^[A-Z2-9]{32}$")
        private val JSON = "application/json".toMediaType()
        private val LOCAL_HOSTS = setOf("10.0.2.2", "127.0.0.1", "localhost")

        /** The service this build talks to (debug builds: a local mock; see app/build.gradle.kts). */
        fun forBuild(serviceUrl: String, approvalOrigin: String, debug: Boolean): OneTouchSetupClient? {
            val base = serviceUrl.toHttpUrlOrNull() ?: return null
            val origin = approvalOrigin.toHttpUrlOrNull() ?: return null
            return OneTouchSetupClient(base, origin, allowLocalHttp = debug)
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
