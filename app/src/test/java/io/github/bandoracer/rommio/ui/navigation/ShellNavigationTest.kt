package io.github.bandoracer.rommio.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavigationTest {
    @Test
    fun tabsArePlayLibrarySearchStatsSettingsInOrder() {
        assertEquals(listOf("Play", "Library", "Search", "Stats", "Settings"), TopLevelDestinations.map { it.label })
        assertEquals(
            listOf(NavRoutes.PLAY, NavRoutes.LIBRARY, NavRoutes.SEARCH, NavRoutes.STATS, NavRoutes.SETTINGS),
            TopLevelDestinations.map { it.route },
        )
    }

    @Test
    fun shellStartsOnPlay() {
        assertEquals(NavRoutes.PLAY, ShellStartRoute)
        assertEquals(ShellStartRoute, TopLevelDestinations.first().route)
    }

    @Test
    fun tabTestTagsAreStable() {
        assertEquals(
            listOf("shell.tab.play", "shell.tab.library", "shell.tab.search", "shell.tab.stats", "shell.tab.settings"),
            TopLevelDestinations.map { it.testTag },
        )
    }

    @Test
    fun collectionsAndDownloadsArePushedPagesNotTabs() {
        val tabRoutes = TopLevelDestinations.map { it.route }.toSet()
        listOf(
            NavRoutes.COLLECTION_DETAIL,
            NavRoutes.DOWNLOADS,
            NavRoutes.SETTINGS_PROFILES,
            NavRoutes.SETTINGS_PLAYER,
            NavRoutes.SETTINGS_CORES,
            NavRoutes.SETTINGS_BACKUP,
            NavRoutes.GAME,
        ).forEach { route -> assertFalse("$route must not be a tab", route in tabRoutes) }
    }

    @Test
    fun shellPagesLiveUnderTheAppRouteAndThePlayerStaysOutside() {
        val shellRoutes = TopLevelDestinations.map { it.route } + listOf(
            NavRoutes.DOWNLOADS, NavRoutes.SETTINGS_PROFILES, NavRoutes.SETTINGS_PLAYER,
            NavRoutes.SETTINGS_CORES, NavRoutes.SETTINGS_BACKUP, NavRoutes.PLATFORM, NavRoutes.COLLECTION_DETAIL, NavRoutes.GAME,
        )
        shellRoutes.forEach { assertTrue(it, it.startsWith("${NavRoutes.APP}/")) }
        assertEquals(shellRoutes.size, shellRoutes.toSet().size)
        assertFalse(NavRoutes.PLAYER.startsWith("${NavRoutes.APP}/"))
        assertEquals("player/7/9", NavRoutes.player(7, 9))
        // The Conflict sheet's choice rides along as an optional argument.
        assertEquals("player/7/9?keep=0f3a-cp", NavRoutes.player(7, 9, "0f3a-cp"))
        // So does the Launch choice sheet's start.
        assertEquals("player/7/9?start=gameSave", NavRoutes.player(7, 9, start = "gameSave"))
        assertEquals("player/7/9?keep=cp&start=resume", NavRoutes.player(7, 9, "cp", "resume"))
        assertEquals("app/game/42", NavRoutes.game(42))
    }
}
