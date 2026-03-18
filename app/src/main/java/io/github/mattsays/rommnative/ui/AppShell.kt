package io.github.mattsays.rommnative.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.model.OfflineState
import io.github.mattsays.rommnative.model.PlatformDto
import io.github.mattsays.rommnative.model.RomDto
import io.github.mattsays.rommnative.model.RommCollectionDto
import io.github.mattsays.rommnative.ui.component.RommGradientBackdrop
import io.github.mattsays.rommnative.ui.navigation.NavRoutes
import io.github.mattsays.rommnative.ui.navigation.TopLevelDestinations
import io.github.mattsays.rommnative.ui.screen.collection.CollectionDetailScreen
import io.github.mattsays.rommnative.ui.screen.collections.CollectionsScreen
import io.github.mattsays.rommnative.ui.screen.downloads.DownloadsScreen
import io.github.mattsays.rommnative.ui.screen.game.GameDetailScreen
import io.github.mattsays.rommnative.ui.screen.home.HomeScreen
import io.github.mattsays.rommnative.ui.screen.library.LibraryScreen
import io.github.mattsays.rommnative.ui.screen.platform.PlatformScreen
import io.github.mattsays.rommnative.ui.screen.settings.SettingsScreen
import io.github.mattsays.rommnative.ui.theme.BrandMuted
import io.github.mattsays.rommnative.ui.theme.BrandPanel
import io.github.mattsays.rommnative.ui.theme.BrandPanelAlt
import io.github.mattsays.rommnative.ui.theme.BrandText
import java.net.URLDecoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    container: AppContainer,
    imageBaseUrl: String?,
    onLogout: suspend () -> Unit,
    onReconfigureServer: () -> Unit,
    onReauthenticate: () -> Unit,
    onProfileActivated: suspend () -> Unit,
    onLaunchPlayer: (romId: Int, fileId: Int) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val title = destinationTitle(destination, backStackEntry?.arguments)
    val canGoBack = destination?.route !in TopLevelDestinations.map { it.route }
    val showBottomBar = destination?.route in TopLevelDestinations.map { it.route }
    val downloadAttentionCount by container.repository.observeDownloadAttentionCount().collectAsStateWithLifecycle(initialValue = 0)
    val offlineState by container.repository.observeOfflineState().collectAsStateWithLifecycle(initialValue = OfflineState())

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandPanelAlt.copy(alpha = 0.94f),
                    scrolledContainerColor = BrandPanelAlt.copy(alpha = 0.98f),
                    titleContentColor = BrandText,
                    navigationIconContentColor = BrandText,
                    actionIconContentColor = BrandText,
                ),
                title = { Text(title) },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (offlineState.isOffline) {
                        Text(
                            text = "Offline",
                            color = BrandText,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    if (destination?.route != NavRoutes.DOWNLOADS) {
                        IconButton(
                            modifier = Modifier.padding(end = 6.dp),
                            onClick = { navController.navigate(NavRoutes.DOWNLOADS) },
                        ) {
                            BadgedBox(
                                badge = {
                                    if (downloadAttentionCount > 0) {
                                        Badge(modifier = Modifier.padding(top = 2.dp, end = 2.dp)) {
                                            Text(downloadAttentionCount.toString())
                                        }
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.Download, contentDescription = "Downloads")
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = BrandPanel.copy(alpha = 0.94f),
                ) {
                    TopLevelDestinations.forEach { destination ->
                        val selected = backStackEntry?.destination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BrandText,
                                selectedTextColor = BrandText,
                                unselectedIconColor = BrandMuted,
                                unselectedTextColor = BrandMuted,
                                indicatorColor = BrandPanelAlt,
                            ),
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            RommGradientBackdrop(modifier = Modifier.fillMaxSize())
            NavHost(
                navController = navController,
                startDestination = NavRoutes.HOME,
                modifier = Modifier.fillMaxSize(),
            ) {
            composable(NavRoutes.HOME) {
                HomeScreen(
                    container = container,
                    imageBaseUrl = imageBaseUrl,
                    onRomSelected = { navController.navigate(NavRoutes.game(it.id)) },
                    onCollectionSelected = { collection ->
                        navController.navigate(
                            NavRoutes.collection(
                                kind = collection.kind.name.lowercase(),
                                collectionId = collection.id,
                                collectionName = collection.name,
                            ),
                        )
                    },
                    onOpenLibrary = { navController.navigate(NavRoutes.LIBRARY) },
                    onOpenDownloads = { navController.navigate(NavRoutes.DOWNLOADS) },
                )
            }
            composable(NavRoutes.LIBRARY) {
                LibraryScreen(
                    container = container,
                    imageBaseUrl = imageBaseUrl,
                    onPlatformSelected = { platform: PlatformDto ->
                        navController.navigate(NavRoutes.platform(platform.id, platform.name))
                    },
                    onRomSelected = { rom: RomDto ->
                        navController.navigate(NavRoutes.game(rom.id))
                    },
                )
            }
            composable(NavRoutes.COLLECTIONS) {
                CollectionsScreen(
                    container = container,
                    imageBaseUrl = imageBaseUrl,
                    onCollectionSelected = { collection: RommCollectionDto ->
                        navController.navigate(
                            NavRoutes.collection(
                                kind = collection.kind.name.lowercase(),
                                collectionId = collection.id,
                                collectionName = collection.name,
                            ),
                        )
                    },
                )
            }
            composable(NavRoutes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onReconfigureServer = onReconfigureServer,
                    onReauthenticate = onReauthenticate,
                    onLogout = onLogout,
                    onActivateProfile = { profile ->
                        container.repository.activateProfile(profile.id)
                        onProfileActivated()
                    },
                    onActiveProfileDeleted = onProfileActivated,
                )
            }
            composable(NavRoutes.DOWNLOADS) {
                DownloadsScreen(container = container)
            }
            composable(
                route = NavRoutes.PLATFORM,
                arguments = listOf(
                    navArgument("platformId") { type = androidx.navigation.NavType.IntType },
                    navArgument("platformName") { type = androidx.navigation.NavType.StringType },
                ),
            ) { entry ->
                PlatformScreen(
                    container = container,
                    platformId = entry.arguments?.getInt("platformId") ?: 0,
                    platformName = entry.arguments?.getString("platformName").orEmpty(),
                    imageBaseUrl = imageBaseUrl,
                    onBack = { navController.popBackStack() },
                    onRomSelected = { rom -> navController.navigate(NavRoutes.game(rom.id)) },
                )
            }
            composable(
                route = NavRoutes.COLLECTION_DETAIL,
                arguments = listOf(
                    navArgument("kind") { type = androidx.navigation.NavType.StringType },
                    navArgument("collectionId") { type = androidx.navigation.NavType.StringType },
                    navArgument("collectionName") { type = androidx.navigation.NavType.StringType },
                ),
            ) { entry ->
                CollectionDetailScreen(
                    container = container,
                    kind = entry.arguments?.getString("kind").orEmpty(),
                    collectionId = entry.arguments?.getString("collectionId").orEmpty(),
                    collectionName = entry.arguments?.getString("collectionName").orEmpty(),
                    imageBaseUrl = imageBaseUrl,
                    onRomSelected = { rom -> navController.navigate(NavRoutes.game(rom.id)) },
                )
            }
            composable(
                route = NavRoutes.GAME,
                arguments = listOf(navArgument("romId") { type = androidx.navigation.NavType.IntType }),
            ) { entry ->
                GameDetailScreen(
                    container = container,
                    romId = entry.arguments?.getInt("romId") ?: 0,
                    onBack = { navController.popBackStack() },
                    onPlay = onLaunchPlayer,
                )
            }
            }
        }
    }
}

private fun destinationTitle(
    destination: NavDestination?,
    arguments: android.os.Bundle?,
): String {
    return when (destination?.route) {
        NavRoutes.HOME -> "Home"
        NavRoutes.LIBRARY -> "Library"
        NavRoutes.COLLECTIONS -> "Collections"
        NavRoutes.SETTINGS -> "Settings"
        NavRoutes.DOWNLOADS -> "Downloads"
        NavRoutes.PLATFORM -> URLDecoder.decode(arguments?.getString("platformName").orEmpty(), Charsets.UTF_8.name())
        NavRoutes.COLLECTION_DETAIL -> URLDecoder.decode(arguments?.getString("collectionName").orEmpty(), Charsets.UTF_8.name())
        NavRoutes.GAME -> "Game"
        else -> "Rommio"
    }
}
