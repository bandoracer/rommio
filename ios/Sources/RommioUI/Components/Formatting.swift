import Foundation

/// Counts, sizes, times and durations in the words both platforms use
/// (docs/theme-layouts.md §4). Every function takes its clock, calendar and
/// locale so the rules are testable; the defaults are the device's.
enum PlayFormat {
    /// Joins parts of a line: " · " (never "•").
    static let separator = " · "

    static func line(_ parts: [String?]) -> String {
        parts.compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: separator)
    }

    // MARK: Counts and sizes

    /// A count with the locale's grouping: "1,234".
    static func count(_ value: Int, locale: Locale = .current) -> String {
        value.formatted(.number.locale(locale))
    }

    /// "1 game", "1,234 games".
    static func count(_ value: Int, _ singular: String, _ plural: String? = nil, locale: Locale = .current) -> String {
        "\(count(value, locale: locale)) \(value == 1 ? singular : (plural ?? singular + "s"))"
    }

    static func games(_ value: Int, locale: Locale = .current) -> String {
        count(value, "game", locale: locale)
    }

    /// Decimal file sizes from the platform formatter, never in bytes: "0 KB",
    /// "1.2 MB", "3.4 GB".
    static func bytes(_ value: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.allowsNonnumericFormatting = false
        formatter.allowedUnits = [.useKB, .useMB, .useGB, .useTB]
        return formatter.string(fromByteCount: max(0, value))
    }

    // MARK: Durations

    /// Play time: "2 h 05 min", "2 h", "45 min", "Under a minute", "0 min"
    /// (whole minutes, rounded down).
    static func duration(_ interval: TimeInterval) -> String {
        let minutes = Int((max(0, interval) / 60).rounded(.down))
        if minutes < 1 { return interval > 0 ? "Under a minute" : "0 min" }
        let hours = minutes / 60
        let rest = minutes % 60
        if hours == 0 { return "\(minutes) min" }
        return rest == 0 ? "\(hours) h" : "\(hours) h \(String(format: "%02d", rest)) min"
    }

    /// Figures for stat cards and totals: "12 h 05 min", "2 min", "0 min".
    static func compactDuration(_ interval: TimeInterval) -> String {
        let minutes = Int((max(0, interval) / 60).rounded(.down))
        if minutes < 1 { return "0 min" }
        return duration(interval)
    }

    /// Whole hours and the remaining minutes.
    static func hoursAndMinutes(_ interval: TimeInterval) -> (hours: Int, minutes: Int) {
        let minutes = Int((max(0, interval) / 60).rounded(.down))
        return (minutes / 60, minutes % 60)
    }

    // MARK: Times

    /// Relative time: "Just now" (under a minute), "3 min ago" (under an hour),
    /// "2 h ago" (same calendar day), "Yesterday", the weekday within six days
    /// ("Monday"), then the locale's month-day ("Sep 21"), with the year when it
    /// is not this year ("Sep 21, 2025").
    static func relative(_ date: Date, now: Date = Date(), calendar: Calendar = .current, locale: Locale = .current) -> String {
        let seconds = now.timeIntervalSince(date)
        if seconds < 60 { return "Just now" }
        if seconds < 3600 { return "\(Int(seconds / 60)) min ago" }
        if calendar.isDate(date, inSameDayAs: now) { return "\(Int(seconds / 3600)) h ago" }
        return dayName(date, now: now, calendar: calendar, locale: locale)
    }

    /// `relative` inside a sentence: "Played just now", "synced yesterday",
    /// "last Monday".
    static func inline(_ date: Date, now: Date = Date(), calendar: Calendar = .current, locale: Locale = .current) -> String {
        let value = relative(date, now: now, calendar: calendar, locale: locale)
        return value == "Just now" || value == "Yesterday" ? value.lowercased() : value
    }

    /// Timeline marks: "Now", "3 min", "2 h", then as `relative` ("Yesterday", "Monday", "Sep 21").
    static func short(_ date: Date, now: Date = Date(), calendar: Calendar = .current, locale: Locale = .current) -> String {
        let seconds = now.timeIntervalSince(date)
        if seconds < 60 { return "Now" }
        if seconds < 3600 { return "\(Int(seconds / 60)) min" }
        if calendar.isDate(date, inSameDayAs: now) { return "\(Int(seconds / 3600)) h" }
        return dayName(date, now: now, calendar: calendar, locale: locale)
    }

    /// "7:07 PM": clock time in the locale.
    static func clock(_ date: Date, calendar: Calendar = .current, locale: Locale = .current) -> String {
        date.formatted(style(calendar, locale).hour().minute())
    }

    // MARK: Days

    /// A day group's title: "Today", "Yesterday", the weekday within six days,
    /// then the month-day ("Sep 21"; "Sep 21, 2025" in another year).
    static func dayTitle(_ date: Date, now: Date = Date(), calendar: Calendar = .current, locale: Locale = .current) -> String {
        if calendar.isDate(date, inSameDayAs: now) { return "Today" }
        return dayName(date, now: now, calendar: calendar, locale: locale)
    }

    /// A day group's caption: the full date ("Wednesday, September 23"), only when
    /// the title is not already that date.
    static func dayCaption(_ date: Date, now: Date = Date(), calendar: Calendar = .current, locale: Locale = .current) -> String? {
        daysBefore(date, now: now, calendar: calendar) <= 6 ? longDay(date, now: now, calendar: calendar, locale: locale) : nil
    }

    /// "Wednesday, September 23" (with the year when it is not this year).
    static func longDay(_ date: Date, now: Date = Date(), calendar: Calendar = .current, locale: Locale = .current) -> String {
        let base = style(calendar, locale).weekday(.wide).month(.wide).day()
        return date.formatted(sameYear(date, now, calendar) ? base : base.year())
    }

    /// "Wednesday, September 23, 2026" (Riso's dateline).
    static func longDate(_ date: Date, calendar: Calendar = .current, locale: Locale = .current) -> String {
        date.formatted(style(calendar, locale).weekday(.wide).month(.wide).day().year())
    }

    /// "Yesterday", the weekday within six days, else the month-day.
    private static func dayName(_ date: Date, now: Date, calendar: Calendar, locale: Locale) -> String {
        let days = daysBefore(date, now: now, calendar: calendar)
        if days == 1 { return "Yesterday" }
        if (2...6).contains(days) { return date.formatted(style(calendar, locale).weekday(.wide)) }
        let base = style(calendar, locale).month(.abbreviated).day()
        return date.formatted(sameYear(date, now, calendar) ? base : base.year())
    }

    /// Calendar days from `date` to `now` (0 = the same day; negative in the future).
    private static func daysBefore(_ date: Date, now: Date, calendar: Calendar) -> Int {
        calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: now)).day ?? 0
    }

    private static func sameYear(_ date: Date, _ now: Date, _ calendar: Calendar) -> Bool {
        calendar.component(.year, from: date) == calendar.component(.year, from: now)
    }

    private static func style(_ calendar: Calendar, _ locale: Locale) -> Date.FormatStyle {
        Date.FormatStyle(locale: locale, calendar: calendar, timeZone: calendar.timeZone)
    }
}
