package io.github.bandoracer.rommio.ui.screen.library

import io.github.bandoracer.rommio.model.RomDto

/** An era of the Aurora Library's era ruler, by release year (docs/theme-layouts.md "Library"). */
data class LibraryEra(val id: String, val name: String, val years: IntRange)

/** The games of one era that have a release year, oldest first, and the years they span. */
data class EraGroup(val era: LibraryEra, val games: List<RomDto>, val firstYear: Int, val lastYear: Int) {
    /** The ruler label: "1994", "1986–88", "1998–2001". */
    val short: String
        get() = when {
            firstYear == lastYear -> "$firstYear"
            firstYear / 100 == lastYear / 100 -> "$firstYear–${(lastYear % 100).toString().padStart(2, '0')}"
            else -> "$firstYear–$lastYear"
        }
}

/**
 * Eras by release year (RomM metadata `first_release_date`, UTC year). Games without a year are
 * in no era: they show only with "All years". The ruler appears when at least two eras have games.
 */
object LibraryEras {
    val all: List<LibraryEra> = listOf(
        LibraryEra("e80", "Arcades & cartridges", Int.MIN_VALUE..1988),
        LibraryEra("e16", "The 16-bit years", 1989..1995),
        LibraryEra("e3d", "Polygons & pockets", 1996..2000),
        LibraryEra("e00", "The 2000s", 2001..2011),
        LibraryEra("e12", "Since 2012", 2012..Int.MAX_VALUE),
    )

    fun of(year: Int): LibraryEra = all.first { year in it.years }

    /** Eras that have games, oldest first; each era's games by release year, then A–Z. */
    fun group(roms: List<RomDto>): List<EraGroup> = roms
        .mapNotNull { rom -> rom.metadata.releaseYear?.let { rom to it } }
        .groupBy { (_, year) -> of(year) }
        .map { (era, dated) ->
            val sorted = dated.sortedWith(compareBy<Pair<RomDto, Int>> { it.second }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.displayName })
            EraGroup(era, sorted.map { it.first }, sorted.first().second, sorted.last().second)
        }
        .sortedBy { all.indexOf(it.era) }

    /** Whether the ruler shows: release years place games in at least two eras. */
    fun showsRuler(groups: List<EraGroup>): Boolean = groups.size >= 2

    /** The era to open on: that of the most recent game in progress with a year, else none (all years). */
    fun defaultEra(groups: List<EraGroup>, inProgress: List<RomDto>): String? {
        if (!showsRuler(groups)) return null
        val year = inProgress.firstNotNullOfOrNull { it.metadata.releaseYear } ?: return null
        return of(year).id.takeIf { id -> groups.any { it.era.id == id } }
    }
}
