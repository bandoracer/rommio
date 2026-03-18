package io.github.mattsays.rommnative.ui.screen.auth

import io.github.mattsays.rommnative.model.AuthCapabilities
import io.github.mattsays.rommnative.model.AuthStatus
import io.github.mattsays.rommnative.model.EdgeAuthMode
import io.github.mattsays.rommnative.model.OriginAuthMode
import io.github.mattsays.rommnative.model.ServerAccessState
import io.github.mattsays.rommnative.model.ServerAccessStatus
import io.github.mattsays.rommnative.model.ServerProfile
import io.github.mattsays.rommnative.model.SessionState
import io.github.mattsays.rommnative.ui.navigation.NavRoutes
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

    private fun profile(
        access: ServerAccessStatus,
        auth: AuthStatus,
    ): ServerProfile {
        return ServerProfile(
            id = "server_1",
            label = "Test",
            baseUrl = "https://romm.example",
            edgeAuthMode = EdgeAuthMode.NONE,
            originAuthMode = OriginAuthMode.ROMM_BEARER_PASSWORD,
            capabilities = AuthCapabilities(),
            serverAccess = ServerAccessState(status = access),
            sessionState = SessionState(),
            isActive = true,
            status = auth,
            lastValidationAt = null,
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-01T00:00:00Z",
        )
    }
}
