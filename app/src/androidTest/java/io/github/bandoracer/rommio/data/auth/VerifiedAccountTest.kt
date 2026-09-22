package io.github.bandoracer.rommio.data.auth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VerifiedAccountTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var secrets: AuthSecretStore
    private lateinit var auth: AuthManager
    private lateinit var server: MockWebServer
    private lateinit var profile: ServerProfile
    @Volatile private var userId = 1
    @Volatile private var userStatus = 200
    @Volatile private var tokenValue = "original-token"
    private val credentials = DirectLoginCredentials("player", "password")
    private fun manager() = AuthManager(db.serverProfileDao(), secrets, AuthDiscovery(), AuthJsonCodec())

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        secrets = AuthSecretStore(context)
        auth = manager()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/token" -> json("""{"access_token":"$tokenValue","refresh_token":"refresh","token_type":"Bearer","expires_in":3600}""")
                "/api/heartbeat" -> json("""{"SYSTEM":{"VERSION":"test","SHOW_SETUP_WIZARD":false}}""")
                "/api/users/me" -> json("""{"id":$userId,"username":"player","email":"player@example.com","role":"admin"}""").setResponseCode(userStatus)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val base = server.url("/").toString().removeSuffix("/")
        profile = auth.configureServerProfile(base, discoveryResult = AuthDiscoveryResult(base, AuthCapabilities(), EdgeAuthMode.NONE, OriginAuthMode.ROMM_BEARER_PASSWORD, emptyList()))
        val row = requireNotNull(db.serverProfileDao().getById(profile.id))
        db.serverProfileDao().upsert(row.copy(serverAccessJson = AuthJsonCodec().encodeServerAccess(ServerAccessState(status = ServerAccessStatus.READY))))
    }
    @After fun cleanup() { secrets.clearAll(profile.id); db.close(); server.shutdown() }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private suspend fun mismatch(block: suspend () -> Unit) {
        try { block(); fail("Account was rebound") } catch (_: AccountBindingException) { }
    }

    @Test fun bindingSurvivesLogoutAndOwnerRecreation() = runBlocking {
        auth.loginWithDirectCredentials(profile.id, credentials)
        val owner = VerifiedAccount(profile.baseUrl, 1)
        assertEquals(owner, auth.verifiedAccount(profile.id))
        auth.logout(profile.id)
        secrets = AuthSecretStore(context)
        auth = manager()
        userId = 2
        mismatch { auth.loginWithDirectCredentials(profile.id, credentials) }
        assertNull(secrets.getTokenBundle(profile.id))
        assertEquals(owner, auth.verifiedAccount(profile.id))
        userId = 1
        auth.loginWithDirectCredentials(profile.id, credentials)
        assertNotNull(secrets.getTokenBundle(profile.id))
    }

    @Test fun wrongBearerOrBasicCandidateDoesNotReplaceOwnedCredentials() = runBlocking {
        for (mode in listOf(OriginAuthMode.ROMM_BEARER_PASSWORD, OriginAuthMode.ROMM_BASIC_LEGACY)) {
            userId = 1
            val row = requireNotNull(db.serverProfileDao().getById(profile.id))
            db.serverProfileDao().upsert(row.copy(originAuthMode = mode.name))
            auth.loginWithDirectCredentials(profile.id, credentials)
            val previousToken = secrets.getTokenBundle(profile.id)
            val previousBasic = secrets.getBasicCredentials(profile.id)
            userId = 2; tokenValue = "wrong-token"
            mismatch { auth.loginWithDirectCredentials(profile.id, credentials.copy(username = "different")) }
            assertEquals(previousToken, secrets.getTokenBundle(profile.id))
            assertEquals(previousBasic, secrets.getBasicCredentials(profile.id))
            assertEquals(1, auth.verifiedAccount(profile.id)?.userId)
        }
    }

    @Test fun failedCandidateVerificationDoesNotInstallTokenOrBinding() = runBlocking {
        userStatus = 500
        try { auth.loginWithDirectCredentials(profile.id, credentials); fail("Unverified token installed") } catch (_: java.io.IOException) { }
        assertNull(secrets.getTokenBundle(profile.id))
        assertNull(auth.verifiedAccount(profile.id))
    }

    @Test fun changedServerCannotUseOwnedCredentials() = runBlocking {
        auth.loginWithDirectCredentials(profile.id, credentials)
        val row = requireNotNull(db.serverProfileDao().getById(profile.id))
        val changed = "https://another.example.com"
        db.serverProfileDao().upsert(row.copy(baseUrl = changed))
        mismatch { auth.decorateRequest(profile.id, "$changed/api/roms", true) }
    }

    @Test fun validationRejectsChangedAccountAndRetainsBinding() = runBlocking {
        assertEquals(AuthStatus.CONNECTED, auth.validateProfile(profile.id))
        userId = 2
        mismatch { auth.validateProfile(profile.id) }
        assertEquals(1, auth.verifiedAccount(profile.id)?.userId)
        assertFalse(requireNotNull(auth.getProfile(profile.id)).sessionState.hasOriginSession)
    }

    @Test fun refreshDoesNotInstallTokenForAnotherAccount() = runBlocking {
        auth.loginWithDirectCredentials(profile.id, credentials)
        val previous = requireNotNull(secrets.getTokenBundle(profile.id)).copy(expiresAt = "2000-01-01T00:00:00Z")
        secrets.storeTokenBundle(profile.id, previous)
        userId = 2; tokenValue = "wrong-refresh"
        mismatch { auth.decorateRequest(profile.id, "${profile.baseUrl}/api/roms", true) }
        assertEquals(previous, secrets.getTokenBundle(profile.id))
    }
}
