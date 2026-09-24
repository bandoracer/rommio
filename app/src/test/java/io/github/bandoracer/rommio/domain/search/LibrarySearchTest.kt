package io.github.bandoracer.rommio.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySearchTest {
    private data class Game(val id: Int, val name: String)

    private val games = listOf(
        Game(1, "Metroid Fusion"),
        Game(2, "Super Metroid"),
        Game(3, "Metroid"),
        Game(4, "Metroidvania Collection"),
        Game(5, "The Legend of Zelda"),
        Game(6, "Pokémon Émeraude"),
    )

    private fun search(query: String, lastPlayed: Map<Int, Long> = emptyMap()) = LibrarySearch.rank(
        query = query,
        candidates = games.map { LibrarySearch.Candidate(it, it.name) },
        lastPlayed = { lastPlayed[it.id] },
    ).map { it.item.id }

    @Test
    fun bestMatchFirst() {
        // exact, then prefix (alphabetical), then word prefix
        assertEquals(listOf(3, 1, 4, 2), search("metroid"))
    }

    @Test
    fun recentlyPlayedBreaksTiesWithinAMatchLevel() {
        val played = mapOf(4 to 2_000L, 1 to 1_000L, 2 to 5_000L)
        assertEquals(listOf(3, 4, 1, 2), search("metroid", played))
    }

    @Test
    fun ignoresCaseAccentsAndPunctuation() {
        assertEquals(listOf(6), search("pokemon emer"))
        assertEquals(listOf(5), search("LEGEND zelda"))
        assertEquals(listOf(5), search("zel"))
    }

    @Test
    fun blankOrMissingQueriesMatchNothing() {
        assertEquals(emptyList<Int>(), search("   "))
        assertEquals(emptyList<Int>(), search("castlevania"))
        assertNull(LibrarySearch.match("", "Metroid"))
    }

    @Test
    fun matchLevels() {
        assertEquals(LibrarySearch.Match.EXACT, LibrarySearch.match("super metroid", "Super Metroid"))
        assertEquals(LibrarySearch.Match.PREFIX, LibrarySearch.match("super", "Super Metroid"))
        assertEquals(LibrarySearch.Match.WORD_PREFIX, LibrarySearch.match("met", "Super Metroid"))
        assertEquals(LibrarySearch.Match.CONTAINS, LibrarySearch.match("troid", "Super Metroid"))
        assertEquals(LibrarySearch.Match.ALL_WORDS, LibrarySearch.match("zelda legend", "The Legend of Zelda"))
    }

    @Test
    fun secondaryTextMatchesRankAfterNameMatches() {
        data class Rom(val id: Int, val name: String, val platform: String)
        val roms = listOf(Rom(1, "Chrono Trigger", "Super Nintendo"), Rom(2, "Super Mario 64", "Nintendo 64"))
        val result = LibrarySearch.rank(
            query = "super",
            candidates = roms.map { LibrarySearch.Candidate(it, it.name, it.platform) },
            lastPlayed = { null },
        ).map { it.item.id }
        assertEquals(listOf(2, 1), result)
    }
}
