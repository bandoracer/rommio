package io.github.bandoracer.rommio.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.bandoracer.rommio.RommNativeApplication
import io.github.bandoracer.rommio.model.InteractiveSessionProvider
import io.github.bandoracer.rommio.model.ServerAccessStatus
import io.github.bandoracer.rommio.ui.navigation.NavRoutes
import io.github.bandoracer.rommio.ui.screen.auth.AuthGateScreen
import io.github.bandoracer.rommio.ui.screen.auth.InteractiveAuthScreen
import io.github.bandoracer.rommio.ui.screen.auth.OnboardingSuccessScreen
import io.github.bandoracer.rommio.ui.screen.auth.OnboardingWelcomeScreen
import io.github.bandoracer.rommio.ui.screen.auth.ServerAccessScreen
import io.github.bandoracer.rommio.ui.screen.login.LoginScreen
import io.github.bandoracer.rommio.ui.screen.player.PlayerScreen

@Composable
fun RommNativeApp() {
    val app = LocalContext.current.applicationContext as RommNativeApplication
    val navController = rememberNavController()
    val activeProfile = app.container.repository.activeProfileFlow().collectAsStateWithLifecycle(initialValue = null).value

    NavHost(
        navController = navController,
        startDestination = NavRoutes.GATE,
    ) {
        composable(NavRoutes.GATE) {
            AuthGateScreen(
                container = app.container,
                onResolved = { route -> navController.resetRoot(route) },
            )
        }
        composable(NavRoutes.ONBOARDING_WELCOME) {
            OnboardingWelcomeScreen(
                hasProfile = activeProfile != null,
                onStart = { navController.resetRoot(NavRoutes.ONBOARDING_SERVER) },
                onResume = {
                    navController.resetRoot(
                        if (activeProfile?.serverAccess?.status == ServerAccessStatus.READY) {
                            NavRoutes.ONBOARDING_LOGIN
                        } else {
                            NavRoutes.ONBOARDING_SERVER
                        },
                    )
                },
            )
        }
        composable(NavRoutes.ONBOARDING_SERVER) {
            ServerAccessScreen(
                container = app.container,
                onInteractiveAuthRequested = { provider ->
                    navController.navigate(NavRoutes.interactive(provider))
                },
                onContinueToLogin = {
                    navController.resetRoot(NavRoutes.ONBOARDING_LOGIN)
                },
            )
        }
        composable(NavRoutes.ONBOARDING_LOGIN) {
            LoginScreen(
                container = app.container,
                onBackToServerAccess = {
                    navController.resetRoot(NavRoutes.ONBOARDING_SERVER)
                },
                onInteractiveAuthRequested = { provider ->
                    navController.navigate(NavRoutes.interactive(provider))
                },
                onLoginSuccess = {
                    navController.resetRoot(NavRoutes.ONBOARDING_SUCCESS)
                },
            )
        }
        composable(NavRoutes.ONBOARDING_SUCCESS) {
            OnboardingSuccessScreen(
                onContinue = { navController.resetRoot(NavRoutes.APP) },
            )
        }
        composable(
            route = NavRoutes.INTERACTIVE,
            arguments = listOf(navArgument("provider") { type = NavType.StringType }),
        ) { entry ->
            val provider = if (entry.arguments?.getString("provider") == "origin") {
                InteractiveSessionProvider.ORIGIN
            } else {
                InteractiveSessionProvider.EDGE
            }
            InteractiveAuthScreen(
                container = app.container,
                provider = provider,
                onCancel = { navController.popBackStack() },
                onEdgeFinished = {
                    navController.resetRoot(NavRoutes.ONBOARDING_SERVER)
                },
                onOriginFinished = {
                    navController.resetRoot(NavRoutes.ONBOARDING_SUCCESS)
                },
            )
        }
        composable(NavRoutes.APP) {
            AppShell(
                container = app.container,
                imageBaseUrl = activeProfile?.baseUrl,
                onLogout = {
                    app.container.repository.logout()
                    navController.resetRoot(NavRoutes.GATE)
                },
                onReconfigureServer = {
                    navController.resetRoot(NavRoutes.ONBOARDING_SERVER)
                },
                onReauthenticate = {
                    navController.resetRoot(NavRoutes.ONBOARDING_LOGIN)
                },
                onProfileActivated = {
                    navController.resetRoot(NavRoutes.GATE)
                },
                onLaunchPlayer = { romId, fileId ->
                    navController.navigate(NavRoutes.player(romId, fileId))
                },
            )
        }
        composable(
            route = NavRoutes.PLAYER,
            arguments = listOf(
                navArgument("romId") { type = NavType.IntType },
                navArgument("fileId") { type = NavType.IntType },
            ),
        ) { entry ->
            PlayerScreen(
                container = app.container,
                romId = entry.arguments?.getInt("romId") ?: 0,
                fileId = entry.arguments?.getInt("fileId") ?: 0,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private fun NavHostController.resetRoot(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
