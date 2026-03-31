package io.github.bandoracer.rommio.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.BuildConfig
import io.github.bandoracer.rommio.RommNativeApplication
import io.github.bandoracer.rommio.model.AuthStatus
import io.github.bandoracer.rommio.model.CloudflareServiceCredentials
import io.github.bandoracer.rommio.model.DirectLoginCredentials
import io.github.bandoracer.rommio.model.EdgeAuthMode
import io.github.bandoracer.rommio.model.OriginAuthMode
import io.github.bandoracer.rommio.model.ServerAccessStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthLiveSmokeTest {
    @Test
    fun cloudflareServiceTokenPasswordGrant_connectsAndResumes() = runBlocking {
        assumeConfigured()
        val application = ApplicationProvider.getApplicationContext<RommNativeApplication>()
        val container = AppContainer(application.applicationContext)
        val repository = container.repository

        val discovery = repository.discoverServer(BuildConfig.DEBUG_TEST_BASE_URL)
        assertTrue("Expected Cloudflare Access detection.", discovery.capabilities.cloudflareAccessDetected)
        assertEquals(EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION, discovery.recommendedEdgeAuthMode)
        assertEquals(OriginAuthMode.ROMM_BEARER_PASSWORD, discovery.recommendedOriginAuthMode)

        var profile = repository.configureServerProfile(
            baseUrl = BuildConfig.DEBUG_TEST_BASE_URL,
            edgeAuthMode = EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
            originAuthMode = OriginAuthMode.ROMM_BEARER_PASSWORD,
            discoveryResult = discovery,
            makeActive = true,
        )

        repository.logout(clearServerAccess = true)

        profile = repository.configureServerProfile(
            baseUrl = BuildConfig.DEBUG_TEST_BASE_URL,
            edgeAuthMode = EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
            originAuthMode = OriginAuthMode.ROMM_BEARER_PASSWORD,
            discoveryResult = discovery,
            makeActive = true,
        )
        repository.setCloudflareServiceCredentials(
            profile.id,
            CloudflareServiceCredentials(
                clientId = BuildConfig.DEBUG_TEST_CLIENT_ID,
                clientSecret = BuildConfig.DEBUG_TEST_CLIENT_SECRET,
            ),
        )

        val accessResult = repository.testServerAccess(profile.id)
        assertEquals(ServerAccessStatus.READY, accessResult.status)

        repository.loginWithDirectCredentials(
            profile.id,
            DirectLoginCredentials(
                username = BuildConfig.DEBUG_TEST_USERNAME,
                password = BuildConfig.DEBUG_TEST_PASSWORD,
            ),
        )

        assertEquals(AuthStatus.CONNECTED, repository.validateProfile(profile.id))

        val restartedContainer = AppContainer(application.applicationContext)
        restartedContainer.repository.initializeAuth()
        val resumedProfile = restartedContainer.repository.currentProfile()
        requireNotNull(resumedProfile) { "Expected an active server profile after restart simulation." }
        assertEquals(ServerAccessStatus.READY, resumedProfile.serverAccess.status)
        assertEquals(AuthStatus.CONNECTED, restartedContainer.repository.validateProfile(resumedProfile.id))
    }

    private fun assumeConfigured() {
        assumeTrue("ROMM_TEST_BASE_URL is required for live auth tests.", BuildConfig.DEBUG_TEST_BASE_URL.isNotBlank())
        assumeTrue("ROMM_TEST_CLIENT_ID is required for live auth tests.", BuildConfig.DEBUG_TEST_CLIENT_ID.isNotBlank())
        assumeTrue("ROMM_TEST_CLIENT_SECRET is required for live auth tests.", BuildConfig.DEBUG_TEST_CLIENT_SECRET.isNotBlank())
        assumeTrue("ROMM_TEST_USERNAME is required for live auth tests.", BuildConfig.DEBUG_TEST_USERNAME.isNotBlank())
        assumeTrue("ROMM_TEST_PASSWORD is required for live auth tests.", BuildConfig.DEBUG_TEST_PASSWORD.isNotBlank())
    }
}
