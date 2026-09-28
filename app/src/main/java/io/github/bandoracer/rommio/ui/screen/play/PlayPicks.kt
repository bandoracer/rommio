package io.github.bandoracer.rommio.ui.screen.play

import io.github.bandoracer.rommio.domain.stats.GamePlayTotal
import io.github.bandoracer.rommio.model.RomDto
import io.github.bandoracer.rommio.ui.component.Fmt
import io.github.bandoracer.rommio.ui.component.PlayFormat
import io.github.bandoracer.rommio.ui.screen.library.LibraryWallOrder
import java.time.ZoneId

/**
 * A suggestion on Aurora's Home (docs/theme-layouts.md, Play): a game and why, worked out only from
 * this device's play and RomM's metadata, never guessed. As iOS `PlayPick`.
 */
data class PlayPick(
    val kind: Kind,
    val rom: RomDto,
    val reason: String,
    val installed: Boolean,
) {
    enum class Kind(val key: String, val title: String) {
        /** Another game in progress, the one you've put the most into. */
        KEEP_GOING("keepGoing", "Keep going"),

        /** A game in the library you've never played, closest to what you play. */
        SOMETHING_NEW("somethingNew", "Something new"),

        /** An arcade or puzzle game for a short turn. */
        QUICK_HIT("quickHit", "Quick hit"),
    }
}

object PlayPicks {
    /** Genres RomM (IGDB) uses for games you can finish a turn of in minutes. */
    val quickGenres: Set<String> = setOf("Arcade", "Puzzle", "Pinball", "Quiz/Trivia", "Card & Board Game")

    /**
     * Up to three picks, each game once: keep going, something new, quick hit.
     *
     * @param library every game Rommio knows (the cached library).
     * @param playtimes this device's play by game.
     * @param continuing games in progress, most recent first; the first is the Home hero, so not a pick.
     * @param playable whether the game's platform plays in the app (touch or controller).
     */
    fun build(
        library: List<RomDto>,
        playtimes: List<GamePlayTotal>,
        continuing: List<RomDto>,
        installed: Set<Int>,
        playable: (RomDto) -> Boolean,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<PlayPick> {
        val played = playtimes.associateBy { it.romId.toInt() }
        val continuingIds = continuing.map { it.id }.toSet()
        val used = continuing.take(1).map { it.id }.toMutableSet()
        val picks = mutableListOf<PlayPick>()
        fun pick(kind: PlayPick.Kind, rom: RomDto, reason: String) {
            used += rom.id
            picks += PlayPick(kind, rom, reason, rom.id in installed)
        }

        // Keep going: the other game in progress you've played most.
        val others = continuing.drop(1).filter(playable)
        others.maxByOrNull { played[it.id]?.playedMs ?: 0L }?.let { rom ->
            val play = played[rom.id]
            when {
                play != null && play.playedMs >= 600_000L -> pick(
                    PlayPick.Kind.KEEP_GOING, rom,
                    if (play.sessionCount > 1) "${Fmt.duration(play.playedMs)} in · ${play.sessionCount} sessions" else "${Fmt.duration(play.playedMs)} in",
                )
                play != null -> pick(PlayPick.Kind.KEEP_GOING, rom, "Last played ${PlayFormat.relative(play.lastPlayedAtEpochMs, now, zone)}")
                else -> pick(PlayPick.Kind.KEEP_GOING, rom, "In progress · ${platformAndYear(rom)}")
            }
        }

        // Something new: never played here, closest to your most played game, best rated.
        val top = playtimes.maxByOrNull { it.playedMs }?.let { best -> library.firstOrNull { it.id == best.romId.toInt() } }
        val topGenres = top?.metadata?.genres.orEmpty().toSet()
        val fresh = library.filter { it.id !in played && it.id !in continuingIds && it.id !in used && playable(it) }
        fun score(rom: RomDto): Double =
            (rom.metadata.ratingOutOf100 ?: 0).toDouble() +
                (if (rom.metadata.genres.none { it in topGenres }) 0.0 else 20.0) +
                (if (rom.id in installed) 10.0 else 0.0)
        fresh.maxByOrNull(::score)?.takeIf { score(it) > 0 }?.let { rom ->
            val parts = buildList {
                rom.metadata.ratingOutOf100?.let { add("Rated $it") }
                val shared = rom.metadata.genres.firstOrNull { it in topGenres }
                if (top != null && shared != null) add("${LibraryWallOrder.genreName(shared)}, like ${top.displayName}")
                if (rom.id in installed) add("On this device")
            }
            pick(PlayPick.Kind.SOMETHING_NEW, rom, if (parts.isEmpty()) platformAndYear(rom) else parts.take(2).joinToString(" · "))
        }

        // Quick hit: an arcade or puzzle game, one you've played in short sessions first.
        val quick = library.filter { rom ->
            rom.id !in used && playable(rom) &&
                (rom.platformSlug == "arcade" || rom.metadata.genres.map(LibraryWallOrder::genreName).any { it in quickGenres })
        }
        val quickOrder = quick.sortedWith(
            compareByDescending<RomDto> { played[it.id]?.lastPlayedAtEpochMs ?: Long.MIN_VALUE }
                .thenBy { if (it.id in installed) 0 else 1 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName },
        )
        quickOrder.firstOrNull()?.let { rom ->
            val play = played[rom.id]
            if (play != null) {
                pick(
                    PlayPick.Kind.QUICK_HIT, rom,
                    "Last played ${PlayFormat.relative(play.lastPlayedAtEpochMs, now, zone)}" +
                        if (play.playedMs >= 60_000L) " · ${Fmt.duration(play.playedMs)} so far" else "",
                )
            } else {
                val genre = rom.metadata.genres.map(LibraryWallOrder::genreName).firstOrNull { it in quickGenres } ?: "Arcade"
                pick(
                    PlayPick.Kind.QUICK_HIT, rom,
                    listOfNotNull(genre, rom.metadata.releaseYear?.toString(), if (rom.id in installed) "On this device" else null).joinToString(" · "),
                )
            }
        }
        return picks
    }

    /** "Super Nintendo · 1994" (the platform's slug in capitals when RomM sends no name, as iOS). */
    fun platformAndYear(rom: RomDto): String =
        Fmt.join(rom.platformName.ifBlank { rom.platformSlug.uppercase() }, rom.metadata.releaseYear?.toString())
}
