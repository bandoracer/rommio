package io.github.bandoracer.rommio.ui.component

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** docs/theme-layouts.md §4 "Copy and formatting". */
class FormattersTest {
    private val zone = ZoneId.of("America/New_York")
    private val us = Locale.US
    // Wednesday 23 September 2026, 18:00 local.
    private val now = LocalDateTime.of(2026, 9, 23, 18, 0).atZone(zone).toInstant().toEpochMilli()

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun countsUseTheLocaleGrouping() {
        assertEquals("1,234", Fmt.count(1234, us))
        assertEquals("1 game", Fmt.count(1, "game", locale = us))
        assertEquals("0 games", Fmt.count(0, "game", locale = us))
        assertEquals("1,234 games", Fmt.count(1234, "game", locale = us))
        assertEquals("2 saves", Fmt.count(2, "save", locale = us))
    }

    @Test
    fun sizesUseDecimalUnitsLikeThePlatform() {
        assertEquals("0 kB", Fmt.size(0, us))
        assertEquals("1 B", Fmt.size(1, us))
        assertEquals("900 B", Fmt.size(900, us))
        assertEquals("0.95 kB", Fmt.size(950, us))
        assertEquals("1.5 kB", Fmt.size(1_500, us))
        assertEquals("4.3 MB", Fmt.size(4_300_000, us))
        assertEquals("62 MB", Fmt.size(62_400_000, us))
        assertEquals("512 MB", Fmt.size(512_000_000, us))
        assertEquals("1.2 GB", Fmt.size(1_200_000_000, us))
    }

    @Test
    fun relativeTimeFollowsTheSpec() {
        assertEquals("Just now", Fmt.relative(now - 30_000, now, zone, us))
        assertEquals("1 min ago", Fmt.relative(now - 60_000, now, zone, us))
        assertEquals("59 min ago", Fmt.relative(now - 59 * 60_000, now, zone, us))
        assertEquals("2 h ago", Fmt.relative(now - 2 * 3_600_000, now, zone, us))
        assertEquals("Yesterday", Fmt.relative(at(2026, 9, 22, 23, 30), now, zone, us))
        assertEquals("Monday", Fmt.relative(at(2026, 9, 21), now, zone, us))
        assertEquals("Thursday", Fmt.relative(at(2026, 9, 17), now, zone, us))
        assertEquals("Sep 16", Fmt.relative(at(2026, 9, 16), now, zone, us))
        assertEquals("Sep 21, 2025", Fmt.relative(at(2025, 9, 21), now, zone, us))
    }

    @Test
    fun earlierTodayOverAnHourIsHours() {
        // 01:00 the same calendar day is 17 h ago, not "Yesterday".
        assertEquals("17 h ago", Fmt.relative(at(2026, 9, 23, 1, 0), now, zone, us))
    }

    @Test
    fun dayGroupsCaptionOnlyWhenTheTitleIsNotTheDate() {
        val today = LocalDate.of(2026, 9, 23)
        Fmt.dayGroup(today, today, us).let {
            assertEquals("Today", it.title)
            assertEquals("Wednesday, September 23", it.caption)
        }
        Fmt.dayGroup(today.minusDays(1), today, us).let {
            assertEquals("Yesterday", it.title)
            assertEquals("Tuesday, September 22", it.caption)
        }
        Fmt.dayGroup(today.minusDays(2), today, us).let {
            assertEquals("Monday", it.title)
            assertEquals("Monday, September 21", it.caption)
        }
        Fmt.dayGroup(today.minusDays(7), today, us).let {
            assertEquals("Sep 16", it.title)
            assertNull(it.caption)
        }
        Fmt.dayGroup(LocalDate.of(2025, 12, 31), today, us).let {
            assertEquals("Dec 31, 2025", it.title)
            assertNull(it.caption)
        }
        assertEquals("Wednesday, September 23", Fmt.fullDate(today, today, us))
        assertEquals("Wednesday, December 31, 2025", Fmt.fullDate(LocalDate.of(2025, 12, 31), today, us))
    }

    @Test
    fun durationsUseOneFormat() {
        assertEquals("0 min", Fmt.duration(0))
        assertEquals("Under a minute", Fmt.duration(59_000))
        assertEquals("45 min", Fmt.duration(45 * 60_000L))
        assertEquals("2 h", Fmt.duration(2 * 3_600_000L))
        assertEquals("2 h 05 min", Fmt.duration(2 * 3_600_000L + 5 * 60_000L))
        assertEquals(Fmt.duration(3_000_000L), formatPlayTime(3_000_000L))
    }

    @Test
    fun theSeparatorIsAMiddleDot() {
        assertEquals(" · ", Fmt.SEP)
        assertEquals("SNES · 1994 · on this device", Fmt.join("SNES", "1994", null, "", "on this device"))
    }
}
