package io.github.bandoracer.rommio.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

data class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** Stable test tag for the tab item: `shell.tab.<id>`. */
    val testTag: String,
)

/** One navigation structure for every theme: Play · Library · Search · Stats · Settings. */
val TopLevelDestinations = listOf(
    AppDestination(route = NavRoutes.PLAY, label = "Play", icon = Icons.Outlined.PlayCircle, testTag = "shell.tab.play"),
    AppDestination(route = NavRoutes.LIBRARY, label = "Library", icon = Icons.Outlined.VideoLibrary, testTag = "shell.tab.library"),
    AppDestination(route = NavRoutes.SEARCH, label = "Search", icon = Icons.Outlined.Search, testTag = "shell.tab.search"),
    AppDestination(route = NavRoutes.STATS, label = "Stats", icon = Icons.Outlined.Insights, testTag = "shell.tab.stats"),
    AppDestination(route = NavRoutes.SETTINGS, label = "Settings", icon = Icons.Outlined.Settings, testTag = "shell.tab.settings"),
)

/** The shell opens on Play. */
const val ShellStartRoute: String = NavRoutes.PLAY
