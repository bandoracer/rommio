package io.github.bandoracer.rommio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.github.bandoracer.rommio.domain.storage.StorageAccess
import kotlinx.coroutines.launch
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
import io.github.bandoracer.rommio.ui.screen.auth.SetupCodeSheet
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.bandoracer.rommio.ui.screen.login.LoginScreen
import io.github.bandoracer.rommio.ui.screen.player.PlayerScreen

@Composable
fun RommNativeApp() {
    val app = LocalContext.current.applicationContext as RommNativeApplication
    val scope = rememberCoroutineScope()
    val importError = app.container.migrationBundleManager.recoveryError.collectAsStateWithLifecycle().value
    val progressError = app.container.legacyProgressRecovery.recoveryError.collectAsStateWithLifecycle().value
    val recoveryError = importError ?: progressError
    val maintenance = StorageAccess.busy.collectAsStateWithLifecycle().value
    if (recoveryError != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Finish library recovery") },
            text = { Text(recoveryError) },
            confirmButton = { TextButton(enabled = !maintenance, onClick = {
                scope.launch { app.container.recoverStorage() }
            }) { Text(if (maintenance) "Recovering…" else "Retry recovery") } },
        )
        return
    }
    if (maintenance) {
        AlertDialog(onDismissRequest = {}, title = { Text("Preparing library") },
            text = { Text("Wait for the library transfer to finish.") }, confirmButton = {})
    }
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
        // Setup pages are pushed (Welcome → Connect → Sign in) so back returns step by step.
        composable(NavRoutes.ONBOARDING_WELCOME) {
            var showsSetupCode by rememberSaveable { mutableStateOf(false) }
            OnboardingWelcomeScreen(
                hasProfile = activeProfile != null,
                onStart = { navController.navigate(NavRoutes.ONBOARDING_SERVER) { launchSingleTop = true } },
                onResume = {
                    navController.navigate(
                        if (activeProfile?.serverAccess?.status == ServerAccessStatus.READY) {
                            NavRoutes.ONBOARDING_LOGIN
                        } else {
                            NavRoutes.ONBOARDING_SERVER
                        },
                    ) { launchSingleTop = true }
                },
                onSetupCode = { showsSetupCode = true },
            )
            if (showsSetupCode) {
                SetupCodeSheet(
                    apply = { io.github.bandoracer.rommio.ui.screen.auth.applyOneTouchConfiguration(app.container, it) },
                    onDone = {
                        showsSetupCode = false
                        navController.navigate(NavRoutes.ONBOARDING_LOGIN) { launchSingleTop = true }
                    },
                    onDismiss = { showsSetupCode = false },
                )
            }
        }
        composable(NavRoutes.ONBOARDING_SERVER) {
            ServerAccessScreen(
                container = app.container,
                onInteractiveAuthRequested = { provider ->
                    navController.navigate(NavRoutes.interactive(provider))
                },
                onContinueToLogin = {
                    navController.navigate(NavRoutes.ONBOARDING_LOGIN) { launchSingleTop = true }
                },
                onProfileSelected = { navController.resetRoot(NavRoutes.GATE) },
                onBack = if (navController.previousBackStackEntry != null) {
                    { navController.popBackStack() }
                } else {
                    null
                },
            )
        }
        composable(NavRoutes.ONBOARDING_LOGIN) {
            LoginScreen(
                container = app.container,
                onBackToServerAccess = {
                    if (navController.previousBackStackEntry?.destination?.route == NavRoutes.ONBOARDING_SERVER) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(NavRoutes.ONBOARDING_SERVER) {
                            popUpTo(NavRoutes.ONBOARDING_LOGIN) { inclusive = true }
                        }
                    }
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
                container = app.container,
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
                    // Back to Connect (still on the stack when it opened the browser login).
                    if (!navController.popBackStack(NavRoutes.ONBOARDING_SERVER, inclusive = false)) {
                        navController.resetRoot(NavRoutes.ONBOARDING_SERVER)
                    }
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
                onOpenPlayer = { romId, fileId, keep, start ->
                    navController.navigate(NavRoutes.player(romId, fileId, keep, start?.routeValue))
                },
            )
        }
        composable(
            route = NavRoutes.PLAYER,
            arguments = listOf(
                navArgument("romId") { type = NavType.IntType },
                navArgument("fileId") { type = NavType.IntType },
                navArgument("keep") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("start") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            PlayerScreen(
                container = app.container,
                romId = entry.arguments?.getInt("romId") ?: 0,
                fileId = entry.arguments?.getInt("fileId") ?: 0,
                keepResume = entry.arguments?.getString("keep"),
                start = io.github.bandoracer.rommio.domain.playdata.LaunchStart.fromRoute(entry.arguments?.getString("start")),
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
