package io.github.bandoracer.rommio.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * RomM metadata beyond name, summary and cover (see docs/play-data-local.md, "RomM metadata").
 * Every field is optional: older servers, unmatched games and partial list payloads decode to
 * the defaults, and nothing here is ever required to show or play a game.
 */
data class RomMetadata(
    /** First release, epoch milliseconds UTC. */
    val firstReleaseEpochMs: Long? = null,
    val genres: List<String> = emptyList(),
    val franchises: List<String> = emptyList(),
    val companies: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val developers: List<String> = emptyList(),
    /** As RomM sends it, e.g. "1", "1-4". */
    val playerCount: String? = null,
    /** 0–100. */
    val averageRating: Double? = null,
    /** Server-relative paths, resolved against the server like covers. */
    val screenshots: List<String> = emptyList(),
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
) {
    /** Release year in UTC (release dates are calendar dates, not moments). */
    val releaseYear: Int? get() = firstReleaseEpochMs?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year }

    /** Publisher line: explicit publishers when the server sends them, otherwise the companies. */
    val publisherLine: List<String> get() = publishers.ifEmpty { companies }

    /** Rating rounded to a whole number out of 100. */
    val ratingOutOf100: Int? get() = averageRating?.takeIf { it > 0 }?.coerceIn(0.0, 100.0)?.roundToInt()

    val isEmpty: Boolean get() = this == EMPTY

    companion object {
        val EMPTY = RomMetadata()

        /** Epoch values above this are milliseconds; at or below, seconds (IGDB sends seconds). */
        const val MILLISECONDS_THRESHOLD = 100_000_000_000L

        /**
         * Decodes the loosely typed RomM payload. Wrong types are ignored field by field so one
         * odd value never fails a catalogue refresh.
         */
        fun fromRemote(
            metadatum: Any?,
            mergedScreenshots: Any?,
            regions: Any?,
            languages: Any?,
        ): RomMetadata {
            val fields = metadatum as? Map<*, *>
            return RomMetadata(
                firstReleaseEpochMs = releaseEpochMs(fields?.get("first_release_date")),
                genres = strings(fields?.get("genres")),
                franchises = strings(fields?.get("franchises")),
                companies = strings(fields?.get("companies")),
                publishers = strings(fields?.get("publishers")),
                developers = strings(fields?.get("developers")),
                playerCount = playerCount(fields?.get("player_count")),
                averageRating = number(fields?.get("average_rating"))?.takeIf { it.isFinite() && it > 0 },
                screenshots = strings(mergedScreenshots),
                regions = strings(regions),
                languages = strings(languages),
            )
        }

        /**
         * Keeps what a partial payload does not mention: list endpoints may omit `metadatum` or
         * `merged_screenshots`, and that must not erase what a detail fetch stored.
         */
        fun merge(
            previous: RomMetadata?,
            metadatum: Any?,
            mergedScreenshots: Any?,
            regions: Any?,
            languages: Any?,
        ): RomMetadata {
            val base = previous ?: EMPTY
            val incoming = fromRemote(metadatum, mergedScreenshots, regions, languages)
            return RomMetadata(
                firstReleaseEpochMs = if (metadatum != null) incoming.firstReleaseEpochMs else base.firstReleaseEpochMs,
                genres = if (metadatum != null) incoming.genres else base.genres,
                franchises = if (metadatum != null) incoming.franchises else base.franchises,
                companies = if (metadatum != null) incoming.companies else base.companies,
                publishers = if (metadatum != null) incoming.publishers else base.publishers,
                developers = if (metadatum != null) incoming.developers else base.developers,
                playerCount = if (metadatum != null) incoming.playerCount else base.playerCount,
                averageRating = if (metadatum != null) incoming.averageRating else base.averageRating,
                screenshots = if (mergedScreenshots != null) incoming.screenshots else base.screenshots,
                regions = if (regions != null) incoming.regions else base.regions,
                languages = if (languages != null) incoming.languages else base.languages,
            )
        }

        /** Seconds or milliseconds by magnitude; numbers, numeric strings and ISO dates accepted. */
        fun releaseEpochMs(raw: Any?): Long? {
            val value: Long = when (raw) {
                null -> return null
                is Number -> raw.toDouble().takeIf { it.isFinite() }?.toLong() ?: return null
                is String -> raw.trim().toLongOrNull()
                    ?: raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
                    ?: return runCatching { LocalDate.parse(raw.trim().take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
                else -> return null
            }
            if (value == 0L) return null
            return if (abs(value) > MILLISECONDS_THRESHOLD) value else value * 1000L
        }

        private fun number(raw: Any?): Double? = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }

        private fun playerCount(raw: Any?): String? = when (raw) {
            is String -> raw.trim().takeIf { it.isNotEmpty() && it != "0" }
            is Number -> raw.toDouble().takeIf { it.isFinite() && it >= 1 }?.toLong()?.toString()
            else -> null
        }

        private fun strings(raw: Any?): List<String> = (raw as? List<*>).orEmpty()
            .mapNotNull { item ->
                when (item) {
                    is String -> item
                    // Some RomM versions send named objects ({"name": "..."}) for companies/genres.
                    is Map<*, *> -> item["name"] as? String
                    else -> null
                }
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
