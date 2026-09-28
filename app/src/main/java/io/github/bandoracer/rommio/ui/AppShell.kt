package io.github.bandoracer.rommio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.bandoracer.rommio.AppContainer
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RommCollectionDto
import io.github.bandoracer.rommio.ui.component.LocalPageArt
import io.github.bandoracer.rommio.ui.component.PageArtState
import io.github.bandoracer.rommio.ui.component.PageField
import io.github.bandoracer.rommio.ui.component.PageFieldStyle
import io.github.bandoracer.rommio.ui.component.LocalShellArt
import io.github.bandoracer.rommio.ui.component.rememberHeldLight
import io.github.bandoracer.rommio.ui.theme.ProvidePageLight
import io.github.bandoracer.rommio.ui.navigation.NavRoutes
import io.github.bandoracer.rommio.ui.navigation.ShellStartRoute
import io.github.bandoracer.rommio.ui.navigation.TopLevelDestinations
import io.github.bandoracer.rommio.ui.screen.collection.CollectionDetailScreen
import io.github.bandoracer.rommio.ui.screen.downloads.DownloadsScreen
import io.github.bandoracer.rommio.ui.screen.game.GameDetailScreen
import io.github.bandoracer.rommio.ui.screen.library.LibraryScreen
import io.github.bandoracer.rommio.ui.screen.platform.PlatformScreen
import io.github.bandoracer.rommio.ui.screen.play.PlayScreen
import io.github.bandoracer.rommio.ui.screen.search.SearchScreen
import io.github.bandoracer.rommio.ui.screen.settings.SettingsBackupScreen
import io.github.bandoracer.rommio.ui.screen.settings.SettingsCoresScreen
import io.github.bandoracer.rommio.ui.screen.settings.SettingsPlayerScreen
import io.github.bandoracer.rommio.ui.screen.settings.SettingsProfilesScreen
import io.github.bandoracer.rommio.ui.screen.settings.SettingsScreen
import io.github.bandoracer.rommio.ui.theme.RommioTheme
import kotlinx.coroutines.launch

private val TopLevelRoutes = TopLevelDestinations.map { it.route }.toSet()

/**
 * The signed-in app: tabs Play · Library · Search · Stats · Settings, with pushed pages above
 * them. The player is a separate full-screen route outside the shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    container: AppContainer,
    imageBaseUrl: String?,
    onLogout: suspend () -> Unit,
    onReconfigureServer: () -> Unit,
    onReauthenticate: () -> Unit,
    onProfileActivated: suspend () -> Unit,
    /** Opens the player (with the Conflict sheet's or the Launch choice's decision, when made). */
    onOpenPlayer: io.github.bandoracer.rommio.ui.screen.PlayerOpener,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val isTopLevel = destination?.route == null || destination.route in TopLevelRoutes
    // The page's subject game (Aurora, docs/theme-layouts.md §1.2): pages declare it; the shell
    // lights the field behind the pages and the bars with it. Each page lights its own content
    // (LitPage), so a change of light here never recomposes a page.
    val pageArt = remember { PageArtState() }

    val regular = io.github.bandoracer.rommio.ui.component.isRegularWidth()
    // Starting a game from any page: the Conflict sheet and the Launch choice come first when needed.
    val launchFlow = io.github.bandoracer.rommio.ui.screen.rememberLaunchFlow(container, onOpenPlayer)

    val theme = RommioTheme.current
    val c = theme.colors
    // Glass surfaces blur the field under them (§1.6, API 31+).
    val backdrop = if (theme.isGlass) io.github.bandoracer.rommio.ui.component.rememberGlassBackdrop() else null
    ProvideShellLight(pageArt) {
    androidx.compose.runtime.CompositionLocalProvider(io.github.bandoracer.rommio.ui.component.LocalGlassBackdrop provides backdrop) {
    Box(modifier = Modifier.fillMaxSize()) {
        ShellField(pageArt, backdrop)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                val route = destination?.route ?: ShellStartRoute
                if (route == NavRoutes.GAME || (route == NavRoutes.PLAY && theme.isGlass && !regular)) {
                    // The Game page and Aurora's Home run their art under a bar of their own (clear over the art).
                } else if (isTopLevel) {
                    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars))
                } else {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = RommioTheme.light.ground,
                            titleContentColor = c.ink,
                            navigationIconContentColor = c.ink,
                            actionIconContentColor = c.ink,
                        ),
                        title = {
                            Text(
                                destinationTitle(destination),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.testTag("shell.back")) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                            }
                        },
                    )
                }
            },
            bottomBar = {
                if (isTopLevel && !regular) {
                    val tint = RommioTheme.light.tint
                    Column {
                        Box(Modifier.fillMaxWidth().height(theme.strokeWidth).background(theme.strokeColor))
                        NavigationBar(
                            containerColor = c.groundRaised,
                            contentColor = c.ink,
                            tonalElevation = 0.dp,
                        ) {
                            TopLevelDestinations.forEach { item ->
                                val selected = destination?.hierarchy?.any { it.route == item.route } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navController.navigateToTab(item.route) },
                                    modifier = Modifier.testTag(item.testTag),
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = c.ink,
                                        selectedTextColor = c.ink,
                                        unselectedIconColor = c.inkSecondary,
                                        unselectedTextColor = c.inkSecondary,
                                        indicatorColor = tint.copy(alpha = if (theme.isDark) 0.28f else 0.2f),
                                    ),
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    label = { Text(item.label, style = theme.type.label.style) },
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(start = if (isTopLevel && regular) RailWidth else 0.dp)
                    .then(if (isTopLevel && !regular) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars)),
            ) {
                val openGame: (Int) -> Unit = { romId -> navController.navigate(NavRoutes.game(romId)) }
                val openPlatform: (PlatformDto) -> Unit = { platform ->
                    navController.navigate(NavRoutes.platform(platform.id, platform.name))
                }
                val openCollection: (RommCollectionDto) -> Unit = { collection ->
                    navController.navigate(
                        NavRoutes.collection(
                            kind = collection.kind.name.lowercase(),
                            collectionId = collection.id,
                            collectionName = collection.name,
                        ),
                    )
                }
                CompositionLocalProvider(LocalPageArt provides pageArt) {
                NavHost(
                    navController = navController,
                    startDestination = ShellStartRoute,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(NavRoutes.PLAY) {
                        PlayScreen(
                            container = container,
                            imageBaseUrl = imageBaseUrl,
                            onOpenGame = openGame,
                            launchFlow = launchFlow,
                            onOpenDownloads = { navController.navigate(NavRoutes.DOWNLOADS) },
                            onOpenCollection = openCollection,
                        )
                    }
                    composable(NavRoutes.LIBRARY) {
                        LibraryScreen(
                            container = container,
                            imageBaseUrl = imageBaseUrl,
                            onPlatformSelected = openPlatform,
                            onOpenGame = openGame,
                            onCollectionSelected = openCollection,
                        )
                    }
                    composable(NavRoutes.SEARCH) {
                        SearchScreen(
                            container = container,
                            imageBaseUrl = imageBaseUrl,
                            onOpenGame = openGame,
                            onPlatformSelected = openPlatform,
                            onCollectionSelected = openCollection,
                            launchFlow = launchFlow,
                        )
                    }
                    composable(NavRoutes.STATS) {
                        io.github.bandoracer.rommio.ui.screen.stats.StatsScreen(
                            container = container,
                            imageBaseUrl = imageBaseUrl,
                            onOpenGame = openGame,
                            launchFlow = launchFlow,
                        )
                    }
                    composable(NavRoutes.SETTINGS) {
                        SettingsScreen(
                            container = container,
                            onOpenProfiles = { navController.navigate(NavRoutes.SETTINGS_PROFILES) },
                            onOpenPlayer = { navController.navigate(NavRoutes.SETTINGS_PLAYER) },
                            onOpenCores = { navController.navigate(NavRoutes.SETTINGS_CORES) },
                            onOpenBackup = { navController.navigate(NavRoutes.SETTINGS_BACKUP) },
                            onReauthenticate = onReauthenticate,
                            onLogout = onLogout,
                        )
                    }
                    composable(NavRoutes.SETTINGS_PROFILES) {
                        SettingsProfilesScreen(
                            container = container,
                            onReconfigureServer = onReconfigureServer,
                            onActivateProfile = { profile ->
                                container.repository.activateProfile(profile.id)
                                onProfileActivated()
                            },
                            onActiveProfileDeleted = onProfileActivated,
                        )
                    }
                    composable(NavRoutes.SETTINGS_PLAYER) { SettingsPlayerScreen(container) }
                    composable(NavRoutes.SETTINGS_CORES) { SettingsCoresScreen(container) }
                    composable(NavRoutes.SETTINGS_BACKUP) {
                        SettingsBackupScreen(
                            container = container,
                            onMigrationImported = { scope.launch { onProfileActivated() } },
                        )
                    }
                    composable(NavRoutes.DOWNLOADS) {
                        DownloadsScreen(container = container, onOpenGame = openGame, imageBaseUrl = imageBaseUrl)
                    }
                    composable(
                        route = NavRoutes.PLATFORM,
                        arguments = listOf(
                            navArgument("platformId") { type = NavType.IntType },
                            navArgument("platformName") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        PlatformScreen(
                            container = container,
                            platformId = entry.arguments?.getInt("platformId") ?: 0,
                            platformName = entry.arguments?.getString("platformName").orEmpty(),
                            imageBaseUrl = imageBaseUrl,
                            onOpenGame = openGame,
                        )
                    }
                    composable(
                        route = NavRoutes.COLLECTION_DETAIL,
                        arguments = listOf(
                            navArgument("kind") { type = NavType.StringType },
                            navArgument("collectionId") { type = NavType.StringType },
                            navArgument("collectionName") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        CollectionDetailScreen(
                            container = container,
                            kind = entry.arguments?.getString("kind").orEmpty(),
                            collectionId = entry.arguments?.getString("collectionId").orEmpty(),
                            collectionName = entry.arguments?.getString("collectionName").orEmpty(),
                            imageBaseUrl = imageBaseUrl,
                            onOpenGame = openGame,
                        )
                    }
                    composable(
                        route = NavRoutes.GAME,
                        arguments = listOf(navArgument("romId") { type = NavType.IntType }),
                    ) { entry ->
                        GameDetailScreen(
                            container = container,
                            romId = entry.arguments?.getInt("romId") ?: 0,
                            onBack = { navController.popBackStack() },
                            launchFlow = launchFlow,
                        )
                    }
                }
                }
            }
        }
        io.github.bandoracer.rommio.ui.screen.LaunchFlowSheets(launchFlow)
        // Regular width: the tabs move to a rail at the start (Material's adaptive navigation);
        // same destinations, order and test tags as the bottom bar.
        if (isTopLevel && regular) {
            val tint = RommioTheme.light.tint
            Row(Modifier.fillMaxHeight()) {
                NavigationRail(
                    modifier = Modifier.width(RailWidth).fillMaxHeight(),
                    containerColor = c.groundRaised,
                    contentColor = c.ink,
                    windowInsets = WindowInsets.systemBars,
                ) {
                    Box(Modifier.weight(1f))
                    TopLevelDestinations.forEach { item ->
                        val selected = destination?.hierarchy?.any { it.route == item.route } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(item.route) },
                            modifier = Modifier.testTag(item.testTag),
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = c.ink,
                                selectedTextColor = c.ink,
                                unselectedIconColor = c.inkSecondary,
                                unselectedTextColor = c.inkSecondary,
                                indicatorColor = tint.copy(alpha = if (theme.isDark) 0.28f else 0.2f),
                            ),
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label, style = theme.type.label.style) },
                        )
                    }
                    Box(Modifier.weight(1f))
                }
                Box(Modifier.width(theme.strokeWidth).fillMaxHeight().background(theme.strokeColor))
            }
        }
    }
    }
    }
}

private val RailWidth = 88.dp

/**
 * The shell's light: the declared subject's (docs/theme-layouts.md §1.2). Only its readers — the
 * field, the bars, the launch sheets, and pages without a light of their own — see a change.
 */
@Composable
private fun ProvideShellLight(pageArt: PageArtState, content: @Composable () -> Unit) {
    // The light shown stays until the next subject's is ready; the field blurs the held light's cover.
    val held = rememberHeldLight(pageArt.subject?.coverUrl)
    CompositionLocalProvider(LocalShellArt provides held.artUrl) {
        ProvidePageLight(held.light, content)
    }
}

/** The field behind every page, lit by the declared subject (it crossfades on its own layer). */
@Composable
private fun ShellField(pageArt: PageArtState, backdrop: io.github.bandoracer.rommio.ui.component.GlassBackdrop?) {
    PageField(
        modifier = Modifier.fillMaxSize(),
        style = pageArt.subject?.style ?: PageFieldStyle.QUIET,
        coverUrl = LocalShellArt.current,
        backdrop = backdrop,
    )
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun destinationTitle(destination: NavDestination?): String {
    return when (destination?.route) {
        NavRoutes.DOWNLOADS -> "Downloads"
        NavRoutes.SETTINGS_PROFILES -> "Profiles and servers"
        NavRoutes.SETTINGS_PLAYER -> "Player and controls"
        NavRoutes.SETTINGS_CORES -> "Cores and BIOS"
        NavRoutes.SETTINGS_BACKUP -> "Backup and recovery"
        // These pages show their own large heading.
        NavRoutes.PLATFORM, NavRoutes.COLLECTION_DETAIL, NavRoutes.GAME -> ""
        else -> TopLevelDestinations.firstOrNull { it.route == destination?.route }?.label ?: "Rommio"
    }
}
