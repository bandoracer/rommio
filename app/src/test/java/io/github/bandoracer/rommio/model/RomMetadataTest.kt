package io.github.bandoracer.rommio.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.data.repository.OfflineCacheJsonCodec
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomMetadataTest {
    // The same Moshi setup RommServiceFactory uses for API responses.
    private val romAdapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(RomDto::class.java)

    private fun epochSeconds(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toEpochSecond()

    @Test
    fun decodesARomM52DetailPayload() {
        val released = epochSeconds(1994, 8, 27)
        val rom = romAdapter.fromJson(
            """
            {"id": 12, "name": "EarthBound", "platform_slug": "snes", "fs_name": "EarthBound (USA).sfc",
             "metadatum": {"rom_id": 12, "genres": ["Role-playing (RPG)", "Adventure"], "franchises": ["Mother"],
               "collections": [], "companies": ["Ape", "HAL Laboratory", "Nintendo"], "game_modes": ["Single player"],
               "age_ratings": ["T"], "player_count": "1", "first_release_date": $released, "average_rating": 86.4},
             "merged_screenshots": ["/assets/romm/resources/roms/1/12/screenshots/0.jpg", "/assets/romm/resources/roms/1/12/screenshots/1.jpg"],
             "regions": ["USA"], "languages": ["English"], "alternative_names": ["Mother 2"], "youtube_video_id": null}
            """.trimIndent(),
        )!!
        val metadata = rom.metadata
        assertEquals(1994, metadata.releaseYear)
        assertEquals(released * 1000, metadata.firstReleaseEpochMs)
        assertEquals(listOf("Role-playing (RPG)", "Adventure"), metadata.genres)
        assertEquals(listOf("Mother"), metadata.franchises)
        assertEquals(listOf("Ape", "HAL Laboratory", "Nintendo"), metadata.companies)
        assertEquals(listOf("Ape", "HAL Laboratory", "Nintendo"), metadata.publisherLine)
        assertEquals("1", metadata.playerCount)
        assertEquals(86, metadata.ratingOutOf100)
        assertEquals(2, metadata.screenshots.size)
        assertEquals(listOf("USA"), metadata.regions)
        assertEquals(listOf("English"), metadata.languages)
        assertTrue(rom.carriesMetadata)
    }

    @Test
    fun olderServersAndListPayloadsDecodeWithoutMetadata() {
        val rom = romAdapter.fromJson("""{"id": 3, "name": "Metroid", "platform_slug": "nes"}""")!!
        assertFalse(rom.carriesMetadata)
        assertTrue(rom.metadata.isEmpty)
        assertNull(rom.metadata.releaseYear)
        val nullMeta = romAdapter.fromJson("""{"id": 3, "metadatum": null, "merged_screenshots": null}""")!!
        assertTrue(nullMeta.metadata.isEmpty)
    }

    @Test
    fun oddTypesAreIgnoredFieldByFieldInsteadOfFailingTheCatalogue() {
        val rom = romAdapter.fromJson(
            """{"id": 5, "metadatum": {"genres": "Action", "companies": [{"name": "Capcom"}, 7, " "], "player_count": 2,
               "first_release_date": "1987-12-17", "average_rating": "n/a", "publishers": ["Capcom"], "developers": null},
               "regions": [1, "Japan"], "languages": {"en": true}}""",
        )!!
        val metadata = rom.metadata
        assertTrue(metadata.genres.isEmpty())
        assertEquals(listOf("Capcom"), metadata.companies)
        assertEquals("2", metadata.playerCount)
        assertEquals(1987, metadata.releaseYear)
        assertNull(metadata.averageRating)
        assertEquals(listOf("Capcom"), metadata.publisherLine)
        assertEquals(listOf("Japan"), metadata.regions)
        assertTrue(metadata.languages.isEmpty())
    }

    @Test
    fun releaseDateUnitsAreDetectedByMagnitude() {
        val seconds = epochSeconds(2001, 3, 21)
        assertEquals(seconds * 1000, RomMetadata.releaseEpochMs(seconds))
        assertEquals(seconds * 1000, RomMetadata.releaseEpochMs(seconds * 1000))
        assertEquals(seconds * 1000, RomMetadata.releaseEpochMs(seconds.toDouble()))
        assertEquals(seconds * 1000, RomMetadata.releaseEpochMs(seconds.toString()))
        // Before 1970 is negative seconds.
        assertEquals(1962, RomMetadata(firstReleaseEpochMs = RomMetadata.releaseEpochMs(epochSeconds(1962, 4, 1))).releaseYear)
        assertNull(RomMetadata.releaseEpochMs(0))
        assertNull(RomMetadata.releaseEpochMs(null))
        assertNull(RomMetadata.releaseEpochMs("soon"))
    }

    @Test
    fun partialPayloadsKeepWhatAnEarlierFetchStored() {
        val detail = RomMetadata.fromRemote(mapOf("genres" to listOf("Platform"), "first_release_date" to 1.0E9), listOf("/s/0.png"), listOf("USA"), null)
        // A list payload with no metadatum or screenshots must not erase them.
        val merged = RomMetadata.merge(detail, metadatum = null, mergedScreenshots = null, regions = listOf("Europe"), languages = null)
        assertEquals(listOf("Platform"), merged.genres)
        assertEquals(listOf("/s/0.png"), merged.screenshots)
        assertEquals(listOf("Europe"), merged.regions)
        // A payload that does carry metadatum replaces those fields.
        val replaced = RomMetadata.merge(detail, metadatum = mapOf("genres" to listOf("Action")), mergedScreenshots = null, regions = null, languages = null)
        assertEquals(listOf("Action"), replaced.genres)
        assertNull(replaced.firstReleaseEpochMs)
        assertEquals(listOf("/s/0.png"), replaced.screenshots)
    }

    @Test
    fun cacheCodecRoundTripsAndToleratesDamage() {
        val codec = OfflineCacheJsonCodec()
        val metadata = RomMetadata(firstReleaseEpochMs = 777_600_000_000, genres = listOf("RPG"), publishers = listOf("Square"),
            playerCount = "1-2", averageRating = 91.0, screenshots = listOf("/a.png"), regions = listOf("Japan"), languages = listOf("Japanese"))
        assertEquals(metadata, codec.decodeMetadata(codec.encodeMetadata(metadata)))
        assertEquals("{}", codec.encodeMetadata(RomMetadata.EMPTY))
        assertEquals(RomMetadata.EMPTY, codec.decodeMetadata("{}"))
        assertEquals(RomMetadata.EMPTY, codec.decodeMetadata("not json"))
        assertEquals(RomMetadata.EMPTY, codec.decodeMetadata(null))
    }

    @Test
    fun ratingIsBoundedAndPublishersPreferred() {
        assertEquals(100, RomMetadata(averageRating = 140.0).ratingOutOf100)
        assertNull(RomMetadata(averageRating = 0.0).ratingOutOf100)
        assertEquals(listOf("Nintendo"), RomMetadata(companies = listOf("HAL"), publishers = listOf("Nintendo")).publisherLine)
    }
}
