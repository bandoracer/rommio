package io.github.bandoracer.rommio.ui.screen.library

import io.github.bandoracer.rommio.model.PlatformDto
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.component.PlayFormat
import java.text.Normalizer

/**
 * How Aurora's Library wall groups every game (docs/theme-layouts.md, Library): one wall of covers,
 * one choice of order. Replaces the era ruler, platform facets and tier chips. As iOS `LibraryLens`;
 * [key] is the stored choice and the identifiers' suffix.
 */
enum class LibraryLens(val key: String, val title: String) {
    CONSOLE("console", "Console"),
    SERIES("series", "Series"),
    YEAR("year", "Year"),
    GENRE("genre", "Genre"),
    ALPHABETICAL("alphabetical", "A–Z"),
    ;

    companion object {
        fun of(key: String?): LibraryLens = entries.firstOrNull { it.key == key } ?: CONSOLE
    }
}

/** One group of the wall: the tile that names it, then its covers. */
data class LibraryWallSection(
    val id: String,
    val title: String,
    /** The index rail's label ("NDS", "P", "’96"). */
    val index: String,
    val games: List<RomDto>,
    val installed: Int,
    /** Console sections open their platform. */
    val platform: PlatformDto? = null,
    /** Series sections drop the series name from titles ("Pokémon Black Version" reads "Black Version"). */
    val titlePrefix: String? = null,
    /** A second line under the name (an era's years, "1990–95"). */
    val subtitle: String? = null,
) {
    /** "11 games · 3 on this device" (a console's count is the server's). */
    val detail: String get() = "${PlayFormat.games(platform?.romCount ?: games.size)} · $installed on this device"

    /** A game's title within this section. */
    fun titleOf(game: RomDto): String {
        val name = game.displayName
        val prefix = titlePrefix ?: return name
        if (name.length <= prefix.length + 2 || !name.lowercase().startsWith(prefix.lowercase())) return name
        val rest = name.drop(prefix.length).dropWhile { it == ' ' || it == ':' || it == '-' || it == '–' }
        return rest.ifEmpty { name }
    }
}

object LibraryWall {
    /**
     * The wall in [lens] order (iOS `LibraryContent.wall`). Games appear once; games without the
     * lens's metadata (no series, genre or year from RomM) gather in a last section, so nothing is
     * lost.
     *
     * @param platforms in the Library's order: by tier (touch, controller, browse only), then name.
     * @param eras the library's eras by release year ([LibraryEras.group]).
     */
    fun sections(
        games: List<RomDto>,
        platforms: List<PlatformDto>,
        installed: Set<Int>,
        eras: List<EraGroup>,
        lens: LibraryLens,
    ): List<LibraryWallSection> {
        fun section(
            id: String,
            title: String,
            index: String,
            members: List<RomDto>,
            platform: PlatformDto? = null,
            prefix: String? = null,
            subtitle: String? = null,
        ) = LibraryWallSection(
            id = "${lens.key}.$id",
            title = title,
            index = index,
            games = members,
            installed = members.count { it.id in installed },
            platform = platform,
            titlePrefix = prefix,
            subtitle = subtitle,
        )
        val byTitle = games.sortedBy(LibraryWallOrder::titleKey)

        return when (lens) {
            LibraryLens.CONSOLE -> {
                // Tier order (touch, controller, browse only), each platform's games A–Z.
                val grouped = byTitle.groupBy { it.platformSlug }
                val sections = platforms.mapNotNull { platform ->
                    val members = grouped[platform.slug]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    section(platform.slug, platform.name, LibraryWallOrder.shortName(platform), members, platform = platform)
                }.toMutableList()
                val known = platforms.map { it.slug }.toSet()
                val orphans = byTitle.filter { it.platformSlug !in known }
                if (orphans.isNotEmpty()) sections += section("other", "Other consoles", "•", orphans)
                sections
            }

            LibraryLens.SERIES -> {
                // Series by size (then name), each by release year; one-game series gather at the end.
                val grouped = games.groupBy { it.metadata.franchises.firstOrNull().orEmpty() }
                val named = grouped.filter { (name, members) -> name.isNotEmpty() && members.size > 1 }
                val sections = named.entries
                    .sortedWith(compareByDescending<Map.Entry<String, List<RomDto>>> { it.value.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                    .map { (name, members) -> section(name, name, name.take(1).uppercase(), LibraryWallOrder.byYear(members), prefix = name) }
                    .toMutableList()
                val rest = byTitle.filter { named[it.metadata.franchises.firstOrNull().orEmpty()] == null }
                if (rest.isNotEmpty()) sections += section("other", if (sections.isEmpty()) "All games" else "Everything else", "•", rest)
                sections
            }

            LibraryLens.YEAR -> {
                // Eras (the 16-bit years…), oldest first, each by year; no year last.
                val sections = eras.map { group ->
                    section(group.era.id, group.era.name, LibraryWallOrder.yearIndex(group.games), group.games, subtitle = group.short)
                }.toMutableList()
                val dated = eras.flatMap { group -> group.games.map { it.id } }.toSet()
                val undated = byTitle.filter { it.id !in dated }
                if (undated.isNotEmpty()) sections += section("unknown", "Year unknown", "?", undated)
                sections
            }

            LibraryLens.GENRE -> {
                // Each game under its first genre from RomM; genres by size.
                val grouped = byTitle.groupBy { rom -> rom.metadata.genres.firstOrNull()?.let(LibraryWallOrder::genreName).orEmpty() }
                val sections = grouped.entries
                    .filter { it.key.isNotEmpty() }
                    .sortedWith(compareByDescending<Map.Entry<String, List<RomDto>>> { it.value.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                    .map { (name, members) -> section(name, name, name.take(1).uppercase(), members) }
                    .toMutableList()
                grouped[""]?.takeIf { it.isNotEmpty() }?.let { sections += section("other", "No genre from RomM", "•", it) }
                sections
            }

            LibraryLens.ALPHABETICAL -> {
                val grouped = byTitle.groupBy(LibraryWallOrder::letter)
                grouped.keys
                    .sortedWith { a, b -> if (a == "#") (if (b == "#") 0 else -1) else if (b == "#") 1 else a.compareTo(b) }
                    .map { letter -> section(letter, letter, letter, grouped.getValue(letter)) }
            }
        }
    }
}

/** Orders and labels on the wall (iOS `LibraryWallOrder`). */
object LibraryWallOrder {
    private val marks = Regex("\\p{Mn}+")

    /** A–Z ignoring case, accents and a leading "The" or "A". */
    fun titleKey(game: RomDto): String {
        var name = Normalizer.normalize(game.displayName, Normalizer.Form.NFD).replace(marks, "").lowercase()
        for (article in listOf("the ", "a ")) if (name.startsWith(article)) name = name.removePrefix(article)
        return name
    }

    /** The A–Z group: the title's first letter, or "#". */
    fun letter(game: RomDto): String {
        val first = titleKey(game).firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercase() else "#"
    }

    /** By release year (games without one last), then A–Z. */
    fun byYear(games: List<RomDto>): List<RomDto> =
        games.sortedWith(compareBy<RomDto> { it.metadata.releaseYear ?: Int.MAX_VALUE }.thenBy(::titleKey))

    /** "’96" for the first year in the group. */
    fun yearIndex(games: List<RomDto>): String {
        val year = games.mapNotNull { it.metadata.releaseYear }.minOrNull() ?: return "?"
        return "’" + (year % 100).toString().padStart(2, '0')
    }

    /** RomM's genre names without their IGDB abbreviations ("Role-playing (RPG)" → "Role-playing"). */
    fun genreName(genre: String): String {
        val open = genre.indexOf('(')
        if (open < 0 || !genre.endsWith(")")) return genre
        return genre.substring(0, open).trim().ifEmpty { genre }
    }

    /** The rail's short console name: RomM's slug in capitals, trimmed. */
    fun shortName(platform: PlatformDto): String {
        val slug = platform.slug.uppercase().replace("NINTENDO-", "")
        return if (slug.length <= 6) slug else slug.take(5)
    }
}
