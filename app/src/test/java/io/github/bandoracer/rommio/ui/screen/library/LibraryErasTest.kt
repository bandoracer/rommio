package io.github.bandoracer.rommio.ui.screen.library

import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.model.RomMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class LibraryErasTest {
    private fun rom(id: Int, name: String, year: Int?) = RomDto(
        id = id,
        name = name,
        platformId = 1,
        platformName = "Super Nintendo",
        platformSlug = "snes",
        cachedMetadata = year?.let { RomMetadata(firstReleaseEpochMs = LocalDate.of(it, 6, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()) } ?: RomMetadata(),
    )

    @Test
    fun gamesGroupByReleaseYearIntoErasOldestFirst() {
        val roms = listOf(
            rom(1, "Super Metroid", 1994),
            rom(2, "Metroid", 1986),
            rom(3, "Mega Man 2", 1988),
            rom(4, "Chrono Trigger", 1995),
            rom(5, "No date", null),
            rom(6, "Metroid Fusion", 2002),
            rom(7, "A Link to the Past", 1991),
        )
        val groups = LibraryEras.group(roms)
        assertEquals(listOf("e80", "e16", "e00"), groups.map { it.era.id })
        assertEquals(listOf("Metroid", "Mega Man 2"), groups[0].games.map { it.displayName })
        assertEquals(listOf("A Link to the Past", "Super Metroid", "Chrono Trigger"), groups[1].games.map { it.displayName })
        assertEquals("1986–88", groups[0].short)
        assertEquals("1991–95", groups[1].short)
        assertEquals("2002", groups[2].short)
        // Undated games are in no era.
        assertTrue(groups.none { g -> g.games.any { it.id == 5 } })
    }

    @Test
    fun eraBoundariesAndLabels() {
        assertEquals("e80", LibraryEras.of(1988).id)
        assertEquals("e16", LibraryEras.of(1989).id)
        assertEquals("e16", LibraryEras.of(1995).id)
        assertEquals("e3d", LibraryEras.of(1996).id)
        assertEquals("e00", LibraryEras.of(2011).id)
        assertEquals("e12", LibraryEras.of(2012).id)
        assertEquals("1998–2001", EraGroup(LibraryEras.of(1998), emptyList(), 1998, 2001).short)
        assertEquals("2001–09", EraGroup(LibraryEras.of(2001), emptyList(), 2001, 2009).short)
    }

    @Test
    fun theRulerNeedsTwoErasAndOpensOnTheGameInProgress() {
        val one = LibraryEras.group(listOf(rom(1, "Super Metroid", 1994), rom(2, "EarthBound", 1994)))
        assertFalse(LibraryEras.showsRuler(one))
        assertNull(LibraryEras.defaultEra(one, listOf(rom(1, "Super Metroid", 1994))))
        val two = LibraryEras.group(listOf(rom(1, "Super Metroid", 1994), rom(2, "Metroid", 1986)))
        assertTrue(LibraryEras.showsRuler(two))
        assertEquals("e16", LibraryEras.defaultEra(two, listOf(rom(9, "No date", null), rom(1, "Super Metroid", 1994))))
        assertNull(LibraryEras.defaultEra(two, emptyList()))
    }
}
