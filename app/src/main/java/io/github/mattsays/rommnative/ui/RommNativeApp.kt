package io.github.mattsays.rommnative.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.mattsays.rommnative.RommNativeApplication
import io.github.mattsays.rommnative.model.InteractiveSessionProvider
import io.github.mattsays.rommnative.ui.navigation.NavRoutes
import io.github.mattsays.rommnative.ui.screen.auth.AuthGateScreen
import io.github.mattsays.rommnative.ui.screen.auth.InteractiveAuthScreen
import io.github.mattsays.rommnative.ui.screen.auth.ServerAccessScreen
import io.github.mattsays.rommnative.ui.screen.game.GameDetailScreen
import io.github.mattsays.rommnative.ui.screen.home.HomeScreen
import io.github.mattsays.rommnative.ui.screen.login.LoginScreen
import io.github.mattsays.rommnative.ui.screen.platform.PlatformScreen
import io.github.mattsays.rommnative.ui.screen.player.PlayerScreen

@Composable
fun RommNativeApp() {
    val app = LocalContext.current.applicationContext as RommNativeApplication
    val navController = rememberNavController()

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
        composable(NavRoutes.SERVER_ACCESS) {
            ServerAccessScreen(
                container = app.container,
                onInteractiveAuthRequested = { provider ->
                    navController.navigate(NavRoutes.interactive(provider))
                },
                onContinueToLogin = {
                    navController.resetRoot(NavRoutes.LOGIN)
                },
            )
        }
        composable(NavRoutes.LOGIN) {
            LoginScreen(
                container = app.container,
                onBackToServerAccess = {
                    navController.resetRoot(NavRoutes.SERVER_ACCESS)
                },
                onInteractiveAuthRequested = { provider ->
                    navController.navigate(NavRoutes.interactive(provider))
                },
                onLoginSuccess = {
                    navController.resetRoot(NavRoutes.GATE)
                },
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
                    navController.resetRoot(NavRoutes.SERVER_ACCESS)
                },
                onOriginFinished = {
                    navController.resetRoot(NavRoutes.GATE)
                },
            )
        }
        composable(NavRoutes.HOME) {
            HomeScreen(
                container = app.container,
                onPlatformSelected = { platform ->
                    navController.navigate(NavRoutes.platform(platform.id, platform.name))
                },
                onRomSelected = { rom ->
                    navController.navigate(NavRoutes.game(rom.id))
                },
                onLogout = {
                    navController.resetRoot(NavRoutes.GATE)
                },
            )
        }
        composable(
            route = NavRoutes.PLATFORM,
            arguments = listOf(
                navArgument("platformId") { type = NavType.IntType },
                navArgument("platformName") { type = NavType.StringType },
            ),
        ) { entry ->
            PlatformScreen(
                container = app.container,
                platformId = entry.arguments?.getInt("platformId") ?: 0,
                platformName = entry.arguments?.getString("platformName").orEmpty(),
                onBack = { navController.popBackStack() },
                onRomSelected = { rom ->
                    navController.navigate(NavRoutes.game(rom.id))
                },
            )
        }
        composable(
            route = NavRoutes.GAME,
            arguments = listOf(navArgument("romId") { type = NavType.IntType }),
        ) { entry ->
            GameDetailScreen(
                container = app.container,
                romId = entry.arguments?.getInt("romId") ?: 0,
                onBack = { navController.popBackStack() },
                onPlay = { romId, fileId ->
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
