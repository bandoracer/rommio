package io.github.bandoracer.rommio.ui.screen

import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier
import io.github.bandoracer.rommio.domain.playdata.SaveEntry
import io.github.bandoracer.rommio.domain.playdata.SaveKey
import io.github.bandoracer.rommio.domain.playdata.SaveKind
import io.github.bandoracer.rommio.domain.playdata.SaveSyncStatus
import io.github.bandoracer.rommio.domain.stats.GamePlayTotal
import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RomMetadata
import io.github.bandoracer.rommio.ui.art.ArtTone
import io.github.bandoracer.rommio.ui.art.ScreenTrim
import io.github.bandoracer.rommio.ui.screen.library.LibraryEras
import io.github.bandoracer.rommio.ui.screen.library.LibraryLens
import io.github.bandoracer.rommio.ui.screen.library.LibraryPage
import io.github.bandoracer.rommio.ui.screen.library.LibraryPlatform
import io.github.bandoracer.rommio.ui.screen.library.LibraryUiState
import io.github.bandoracer.rommio.ui.screen.library.LibraryWall
import io.github.bandoracer.rommio.ui.screen.library.LibraryWallChoice
import io.github.bandoracer.rommio.ui.screen.library.LibraryWallSection
import io.github.bandoracer.rommio.ui.screen.play.PlayPick
import io.github.bandoracer.rommio.ui.screen.play.PlayPicks
import io.github.bandoracer.rommio.ui.screen.play.inPlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Aurora's Home and Library (docs/theme-layouts.md): what's in play and in what order, the picks
 * and their reasons, the wall's groups, the status bar over art and trimming a screen's letterbox.
 * Mirrors iOS `AuroraHomeLibraryTests` case for case, with the same data and expectations.
 */
class AuroraHomeLibraryTest {
    private val now = 1_800_000_000_000L
    private val zone = ZoneOffset.UTC

    private fun rom(
        id: Int,
        name: String,
        platform: String = "gba",
        year: Int? = null,
        genres: List<String> = emptyList(),
        series: List<String> = emptyList(),
        rating: Double? = null,
    ) = RomDto(
        id = id,
        name = name,
        platformSlug = platform,
        cachedMetadata = RomMetadata(
            firstReleaseEpochMs = year?.let { LocalDate.of(it, 6, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() },
            genres = genres,
            franchises = series,
            averageRating = rating,
        ),
    )

    private fun save(romId: Int, at: Long) = SaveEntry(
        key = SaveKey("n", "c$romId", "cp-$romId-$at"), romId = romId.toLong(), fileId = null, operationId = "op", kind = SaveKind.RESUME_POINT,
        capturedAtEpochMs = at, captureReason = "exit", deviceId = "d", fromThisDevice = true, deviceLabel = "This phone",
        sync = SaveSyncStatus.SYNCED, label = null, slotNumber = null, isCurrentResume = true, isCurrentSlot = false, loadable = true,
        thumbnailPath = null,
    )

    // ---- Play ----

    @Test
    fun inPlayLeadsWithWhatThisDevicePlayedThenRomM() {
        val majora = rom(1, "Majora's Mask")
        val fireEmblem = rom(2, "Fire Emblem")
        val kart = rom(3, "Mario Kart DS")
        val galaga = rom(4, "Galaga")
        val library = mapOf(1 to majora, 2 to fireEmblem, 3 to kart, 4 to galaga)
        val order = inPlay(
            romm = listOf(kart, galaga, fireEmblem),
            latest = mapOf(1L to save(1, now - 3_600_000), 2L to save(2, now - 7_200_000)),
            playtimes = listOf(GamePlayTotal(romId = 4, playedMs = 60_000, sessionCount = 1, lastPlayedAtEpochMs = now - 86_400_000L * 2)),
            library = library::get,
        )
        assertEquals("saves and sessions here first (newest first), then RomM's list", listOf(1, 2, 4, 3), order.map { it.id })
        assertEquals("nothing played here: RomM's order", listOf(3), inPlay(listOf(kart), emptyMap(), emptyList(), { null }).map { it.id })
        // A game neither the library nor RomM's list knows is left out; the later of save and session counts.
        val both = inPlay(
            romm = emptyList(),
            latest = mapOf(1L to save(1, now - 7_200_000), 9L to save(9, now)),
            playtimes = listOf(GamePlayTotal(romId = 2, playedMs = 1, sessionCount = 1, lastPlayedAtEpochMs = now - 3_600_000), GamePlayTotal(1, 1, 1, now - 60_000)),
            library = library::get,
        )
        assertEquals(listOf(1, 2), both.map { it.id })
    }

    @Test
    fun picksComeWithReasonsFromTheData() {
        val hero = rom(1, "Majora's Mask", genres = listOf("Adventure"))
        val fireEmblem = rom(2, "Fire Emblem", genres = listOf("Role-playing (RPG)", "Strategy"))
        val emerald = rom(3, "Pokémon Emerald", genres = listOf("Role-playing (RPG)"), rating = 88.0)
        val sudoku = rom(4, "Platinum Sudoku", platform = "nds", genres = listOf("Puzzle"))
        val guitar = rom(5, "Guitar Hero", platform = "ps2", genres = listOf("Music"), rating = 95.0)
        val playtimes = listOf(GamePlayTotal(romId = 2, playedMs = 1_800_000, sessionCount = 3, lastPlayedAtEpochMs = now - 86_400_000))
        val picks = PlayPicks.build(
            library = listOf(hero, fireEmblem, emerald, sudoku, guitar),
            playtimes = playtimes,
            continuing = listOf(hero, fireEmblem),
            installed = setOf(3),
            playable = { it.platformSlug != "ps2" },
            now = now,
            zone = zone,
        )
        assertEquals(listOf(PlayPick.Kind.KEEP_GOING, PlayPick.Kind.SOMETHING_NEW, PlayPick.Kind.QUICK_HIT), picks.map { it.kind })
        assertEquals("never the hero; browse-only games are never picked", listOf(2, 3, 4), picks.map { it.rom.id })
        assertEquals("30 min in · 3 sessions", picks[0].reason)
        assertEquals("Rated 88 · Role-playing, like Fire Emblem", picks[1].reason)
        assertTrue(picks[1].installed)
        assertEquals("Puzzle", picks[2].reason)
        assertTrue(PlayPicks.build(emptyList(), emptyList(), emptyList(), emptySet(), { true }).isEmpty())
    }

    // ---- Library ----

    private val gba = PlatformDto(id = 1, slug = "gba", name = "Game Boy Advance", fsSlug = "gba", romCount = 3)
    private val gb = PlatformDto(id = 2, slug = "gb", name = "Game Boy", fsSlug = "gb", romCount = 1)
    private val ps2 = PlatformDto(id = 3, slug = "ps2", name = "PlayStation 2", fsSlug = "ps2", romCount = 1)
    private val games = listOf(
        rom(1, "Pokémon Emerald Version", year = 2004, genres = listOf("Role-playing (RPG)"), series = listOf("Pokémon")),
        rom(2, "Fire Emblem", year = 2003, genres = listOf("Strategy")),
        rom(3, "Pokémon Blue Version", platform = "gb", year = 1996, genres = listOf("Role-playing (RPG)"), series = listOf("Pokémon")),
        rom(4, "The Minish Cap", year = 2004),
        rom(5, "Guitar Hero", platform = "ps2", year = 2005, genres = listOf("Music")),
    )

    private fun wall(lens: LibraryLens): List<LibraryWallSection> =
        LibraryWall.sections(games, listOf(gba, gb, ps2), setOf(1, 3), LibraryEras.group(games), lens)

    @Test
    fun theWallGroupsEveryGameOnceInEachLens() {
        for (lens in LibraryLens.entries) {
            assertEquals("$lens: every game, once", listOf(1, 2, 3, 4, 5), wall(lens).flatMap { it.games }.map { it.id }.sorted())
        }
        val consoles = wall(LibraryLens.CONSOLE)
        assertEquals("tier order: playable first", listOf("Game Boy Advance", "Game Boy", "PlayStation 2"), consoles.map { it.title })
        assertEquals("A–Z, ignoring “The”", listOf(2, 4, 1), consoles.first().games.map { it.id })
        assertEquals("3 games · 1 on this device", consoles.first().detail)
        assertEquals(listOf("GBA", "GB", "PS2"), consoles.map { it.index })
    }

    @Test
    fun seriesTrimTheirNameAndTheRestGatherLast() {
        val series = wall(LibraryLens.SERIES)
        assertEquals(listOf("Pokémon", "Everything else"), series.map { it.title })
        val pokemon = series[0]
        assertEquals("by release year", listOf(3, 1), pokemon.games.map { it.id })
        assertEquals(listOf("Blue Version", "Emerald Version"), pokemon.games.map(pokemon::titleOf))
        assertEquals("no prefix outside a series", series[1].games[0].displayName, series[1].titleOf(series[1].games[0]))
    }

    @Test
    fun genresAndErasAndLetters() {
        val genres = wall(LibraryLens.GENRE)
        assertEquals("IGDB's abbreviation dropped; biggest first", "Role-playing", genres.first().title)
        assertEquals("No genre from RomM", genres.last().title)
        val eras = wall(LibraryLens.YEAR)
        assertEquals(listOf("Polygons & pockets", "The 2000s"), eras.map { it.title })
        assertEquals(listOf("1996", "2003–05"), eras.map { it.subtitle })
        assertEquals(listOf("’96", "’03"), eras.map { it.index })
        assertEquals("“The Minish Cap” files under M", listOf("F", "G", "M", "P"), wall(LibraryLens.ALPHABETICAL).map { it.title })
    }

    @Test
    fun theWallPageMarksCoversAndLightsWithTheGameInPlay() {
        val state = LibraryUiState(
            isLoading = false,
            platforms = listOf(
                LibraryPlatform(gba, EmbeddedSupportTier.TOUCH_SUPPORTED, null),
                LibraryPlatform(gb, EmbeddedSupportTier.TOUCH_SUPPORTED, null),
                LibraryPlatform(ps2, EmbeddedSupportTier.UNSUPPORTED, null),
            ),
            roms = games.map { it.copy(urlCover = "/covers/${it.id}.png") },
            inProgress = listOf(games[3]),
            installedRomIds = setOf(1, 3),
            eras = LibraryEras.group(games),
        )
        val page = LibraryPage.build(state, LibraryWallChoice(LibraryLens.CONSOLE, columns = 4), "https://romm.example")
        assertEquals(4, page.columns)
        assertEquals("the game in play lights the page", "https://romm.example/covers/4.png", page.lightUrl)
        val covers = page.wall.flatMap { it.covers }.associateBy { it.id }
        assertTrue(covers.getValue(4).inProgress)
        assertTrue(covers.getValue(1).installed && !covers.getValue(1).browseOnly)
        assertTrue("browse only by its platform's tier", covers.getValue(5).browseOnly)
        assertEquals("Game Boy Advance", page.wall.first().platform?.name)
        assertEquals("https://romm.example/covers/2.png", page.wall.first().tileArtUrl)
        // Nothing in play: the library's first cover.
        assertEquals("https://romm.example/covers/1.png", LibraryPage.build(state.copy(inProgress = emptyList()), LibraryWallChoice(), "https://romm.example").lightUrl)
    }

    // ---- Screens and the status bar ----

    /** A black picture of [width] × [height] with [content] (left, top, width, height; top-down) filled light blue. */
    private fun image(width: Int, height: Int, content: IntArray): IntArray {
        val (left, top, w, h) = content.toList()
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x in left until left + w && y in top until top + h) 0xFF3399E6.toInt() else 0xFF000000.toInt()
        }
    }

    @Test
    fun theStatusBarReadsTheArtUnderIt() {
        // A cover dark at the top and light below: the band under the bar decides.
        val darkTop = image(200, 300, intArrayOf(0, 60, 200, 240))
        assertTrue("black rows under the bar: light text", ArtTone.isDark(darkTop, 200, 300, 0.0..0.1))
        assertFalse("light rows: dark text", ArtTone.isDark(darkTop, 200, 300, 0.5..0.6))
        // A 2:3 cover filling a 402 × 400 hero is cropped about its centre (101.5 off the top of a
        // 603 tall drawing); the bar covers the first 62 of the hero.
        val band = ArtTone.statusBarBand(200, 300, 402.0, 400.0, 62.0)
        assertEquals(101.5 / 603, band.start, 0.001)
        assertEquals(163.5 / 603, band.endInclusive, 0.001)
        // A wide cover fills by height: nothing is cropped off the top.
        assertEquals(0.0, ArtTone.statusBarBand(400, 200, 402.0, 400.0, 62.0).start, 0.001)
        // Home's poster box is as wide as the page and 1.5 × as tall, from the top.
        assertEquals(0.0, ArtTone.statusBarBand(200, 300, 1.0, 1.5, 0.1).start, 0.001)
    }

    @Test
    fun aLetterboxedScreenIsTrimmed() {
        val letterboxed = ScreenTrim.region(image(320, 240, intArrayOf(0, 40, 320, 150)), 320, 240)
        assertEquals(ScreenTrim.Region(0, 40, 320, 150), letterboxed)
        assertNull("nothing to trim", ScreenTrim.region(image(240, 160, intArrayOf(0, 0, 240, 160)), 240, 160))
        assertNull("a dark scene is not cropped to a sliver", ScreenTrim.region(image(320, 240, intArrayOf(150, 110, 20, 20)), 320, 240))
    }
}
