package io.github.bandoracer.rommio.ui.component

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The formatting rules of docs/theme-layouts.md §4 ("Copy and formatting"), one set for every
 * theme and the same words as iOS. Pure functions of their inputs (clock, zone and locale are
 * parameters) so `FormattersTest` pins them.
 */
object Fmt {
    /** The separator between facts on one line (never "•"). */
    const val SEP = " · "

    /** A count with the locale's grouping: "1,234". */
    fun count(value: Long, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    fun count(value: Int, locale: Locale = Locale.getDefault()): String = count(value.toLong(), locale)

    /** A counted noun: "1 game", "1,234 games". */
    fun count(value: Int, singular: String, plural: String = "${singular}s", locale: Locale = Locale.getDefault()): String =
        "${count(value, locale)} ${if (value == 1) singular else plural}"

    /**
     * A size in decimal units, exactly as Android's `Formatter.formatShortFileSize` writes it
     * (API 26+: SI units, 1000 per step, "shorter" rounding; `FormattersPlatformTest` checks the two
     * agree on a device). Zero is "0 kB" — the platform's kilobyte, as iOS writes "0 KB".
     */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes <= 0L) return "0 ${SIZE_UNITS[1]}"
        var result = bytes.toDouble()
        var unit = 0
        while (result > 900 && unit < SIZE_UNITS.lastIndex) {
            result /= 1000.0
            unit += 1
        }
        val decimals = when {
            unit == 0 || result >= 100 -> 0
            result < 1 -> 2
            result < 10 -> 1
            else -> 0
        }
        val factor = when (decimals) { 2 -> 100.0; 1 -> 10.0; else -> 1.0 }
        val rounded = Math.round(result * factor) / factor
        return String.format(locale, "%.${decimals}f", rounded) + " " + SIZE_UNITS[unit]
    }

    private val SIZE_UNITS = listOf("B", "kB", "MB", "GB", "TB", "PB")

    /**
     * How long ago: under a minute "Just now"; under an hour "N min ago"; the same calendar day
     * "N h ago"; the previous day "Yesterday"; within six days the weekday ("Monday"); otherwise
     * the month and day ("Sep 21"), with the year when it isn't this year ("Sep 21, 2025").
     */
    fun relative(
        epochMs: Long,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val delta = now - epochMs
        if (delta < 60_000L) return "Just now"
        if (delta < 3_600_000L) return "${delta / 60_000L} min ago"
        val today = day(now, zone)
        val day = day(epochMs, zone)
        if (day == today) return "${delta / 3_600_000L} h ago"
        return dayName(day, today, locale)
    }

    /** A day group's heading: [title] and, when the title isn't already the date, the full date. */
    data class DayGroup(val title: String, val caption: String?)

    /**
     * Groups by local day: "Today" / "Yesterday" / the weekday within six days / the month and day;
     * the caption is the full date ("Wednesday, September 23") only when the title isn't that date.
     */
    fun dayGroup(
        epochMs: Long,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): DayGroup = dayGroup(day(epochMs, zone), day(now, zone), locale)

    fun dayGroup(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): DayGroup {
        val title = dayName(day, today, locale)
        val named = day == today || day == today.minusDays(1) || isThisWeek(day, today)
        return DayGroup(title, if (named) fullDate(day, today, locale) else null)
    }

    /** "Wednesday, September 23" ("Monday, September 21, 2025" in another year). */
    fun fullDate(day: LocalDate, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(if (day.year == today.year) "EEEE, MMMM d" else "EEEE, MMMM d, yyyy", locale).format(day)

    /** Play time: "Under a minute", "45 min", "2 h", "2 h 05 min"; "0 min" for nothing. */
    fun duration(durationMs: Long): String {
        val totalMinutes = durationMs / 60_000L
        if (totalMinutes <= 0L) return if (durationMs > 0L) "Under a minute" else "0 min"
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours == 0L -> "$minutes min"
            minutes == 0L -> "$hours h"
            else -> "$hours h ${minutes.toString().padStart(2, '0')} min"
        }
    }

    /** Joins the present facts with [SEP]. */
    fun join(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(SEP)

    private fun dayName(day: LocalDate, today: LocalDate, locale: Locale): String = when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        isThisWeek(day, today) -> DateTimeFormatter.ofPattern("EEEE", locale).format(day)
        day.year == today.year -> DateTimeFormatter.ofPattern("MMM d", locale).format(day)
        else -> DateTimeFormatter.ofPattern("MMM d, yyyy", locale).format(day)
    }

    private fun isThisWeek(day: LocalDate, today: LocalDate): Boolean {
        val days = ChronoUnit.DAYS.between(day, today)
        return days in 2L..6L
    }

    private fun day(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}

/** A size in decimal units ([Fmt.size]). */
fun formatBytes(value: Long): String = Fmt.size(value)

/** Play time as "2 h 05 min", "45 min" or "Under a minute" ([Fmt.duration]). */
fun formatPlayTime(durationMs: Long): String = Fmt.duration(durationMs)

/** How long ago ([Fmt.relative]). */
fun formatRelativeTime(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
    Fmt.relative(epochMs, now, zone)

/** The day-group title: "Today", "Yesterday", a weekday, or "Sep 21" ([Fmt.dayGroup]). */
fun formatDayLabel(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
    Fmt.dayGroup(epochMs, now, zone).title

fun formatClockTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** A counted noun with grouping: "1 game", "1,234 games" ([Fmt.count]). */
fun pluralize(count: Int, singular: String, plural: String = "${singular}s"): String = Fmt.count(count, singular, plural)

/**
 * Plain-language times for Play, Stats, Search and Game, the same words as iOS `PlayFormat`.
 * Durations use [Fmt.duration] everywhere ("2 h 05 min", never "2 m").
 */
object PlayFormat {
    /** "1 game", "1,234 games". */
    fun games(count: Int): String = Fmt.count(count, "game")

    /** Timeline marks: "Now", "3 min", "2 h", "Yesterday", "Monday", "Sep 21". */
    fun short(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val seconds = (now - epochMs) / 1000
        if (seconds < 60) return "Now"
        if (seconds < 3600) return "${seconds / 60} min"
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        if (day == today) return "${seconds / 3600} h"
        return Fmt.dayGroup(day, today).title
    }

    /** Past times for figures ([Fmt.relative]): "Just now", "3 min ago", "2 h ago", "Yesterday", "Monday", "Sep 21". */
    fun shortAgo(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
        Fmt.relative(epochMs, now, zone)

    /** A day group's title ([Fmt.dayGroup]). */
    fun dayTitle(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
        Fmt.dayGroup(epochMs, now, zone).title

    /** A day group ([Fmt.dayGroup]): its title and the full date when the title isn't the date. */
    fun dayGroup(day: LocalDate, today: LocalDate = LocalDate.now()): Fmt.DayGroup = Fmt.dayGroup(day, today)

    /** "Wednesday, September 23". */
    fun longDay(day: LocalDate): String = Fmt.fullDate(day)

    /** "Wednesday, September 23, 2026" (Riso's dateline). */
    fun longDate(day: LocalDate): String = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault()).format(day)

    /** [Fmt.relative] inside a sentence: "Played just now", "synced yesterday", "3 min ago", "Monday" (as iOS `inline`). */
    fun relative(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val value = Fmt.relative(epochMs, now, zone)
        return if (value == "Just now" || value == "Yesterday") value.lowercase() else value
    }
}
