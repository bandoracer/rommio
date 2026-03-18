package io.github.mattsays.rommnative.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.ui.graphics.vector.ImageVector

data class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val TopLevelDestinations = listOf(
    AppDestination(
        route = NavRoutes.HOME,
        label = "Home",
        icon = Icons.Outlined.Home,
    ),
    AppDestination(
        route = NavRoutes.LIBRARY,
        label = "Library",
        icon = Icons.Outlined.ViewModule,
    ),
    AppDestination(
        route = NavRoutes.COLLECTIONS,
        label = "Collections",
        icon = Icons.Outlined.CollectionsBookmark,
    ),
    AppDestination(
        route = NavRoutes.SETTINGS,
        label = "Settings",
        icon = Icons.Outlined.Settings,
    ),
)
