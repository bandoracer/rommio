package io.github.bandoracer.rommio.ui.screen.auth

import io.github.bandoracer.rommio.model.AuthCapabilities
import io.github.bandoracer.rommio.model.AuthStatus
import io.github.bandoracer.rommio.model.EdgeAuthMode
import io.github.bandoracer.rommio.model.OfflineState
import io.github.bandoracer.rommio.model.OriginAuthMode
import io.github.bandoracer.rommio.model.ServerAccessState
import io.github.bandoracer.rommio.model.ServerAccessStatus
import io.github.bandoracer.rommio.model.ServerProfile
import io.github.bandoracer.rommio.model.SessionState
import io.github.bandoracer.rommio.ui.navigation.NavRoutes
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthGateRoutingTest {
    @Test
    fun nullProfileRoutesToWelcome() {
        assertEquals(NavRoutes.ONBOARDING_WELCOME, authRouteForProfile(null))
    }

    @Test
    fun readyConnectedProfileRoutesToAppShell() {
        assertEquals(NavRoutes.APP, authRouteForProfile(profile(access = ServerAccessStatus.READY, auth = AuthStatus.CONNECTED)))
    }

    @Test
    fun readyButUnauthedProfileRoutesToLogin() {
        assertEquals(
            NavRoutes.ONBOARDING_LOGIN,
            authRouteForProfile(profile(access = ServerAccessStatus.READY, auth = AuthStatus.REAUTH_REQUIRED_ORIGIN)),
        )
    }

    @Test
    fun accessFailureRoutesToServerSetup() {
        assertEquals(
            NavRoutes.ONBOARDING_SERVER,
            authRouteForProfile(profile(access = ServerAccessStatus.FAILED, auth = AuthStatus.REAUTH_REQUIRED_EDGE)),
        )
    }

    @Test
    fun offlineHydratedProfileRoutesToAppShell() {
        assertEquals(
            NavRoutes.APP,
            authRouteForProfile(
                profile(
                    access = ServerAccessStatus.READY,
                    auth = AuthStatus.REAUTH_REQUIRED_ORIGIN,
                    sessionState = SessionState(hasOriginSession = true),
                ),
                offlineState = OfflineState(
                    connectivity = io.github.bandoracer.rommio.model.ConnectivityState.OFFLINE,
                    catalogReady = true,
                ),
            ),
        )
    }

    private fun profile(
        access: ServerAccessStatus,
        auth: AuthStatus,
        sessionState: SessionState = SessionState(),
    ): ServerProfile {
        return ServerProfile(
            id = "server_1",
            label = "Test",
            baseUrl = "https://romm.example",
            edgeAuthMode = EdgeAuthMode.NONE,
            originAuthMode = OriginAuthMode.ROMM_BEARER_PASSWORD,
            capabilities = AuthCapabilities(),
            serverAccess = ServerAccessState(status = access),
            sessionState = sessionState,
            isActive = true,
            status = auth,
            lastValidationAt = null,
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-01T00:00:00Z",
        )
    }
}
