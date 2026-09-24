import Foundation

/// Any JSON value, decoded without failing. RomM metadata is loosely typed across
/// versions, so each field is read from this and a wrong type is ignored on its own
/// (docs/play-data-local.md §6, "Leniency").
public indirect enum LenientJSON: Decodable, Hashable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([LenientJSON])
    case object([String: LenientJSON])

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null; return }
        if let value = try? container.decode(Bool.self) { self = .bool(value); return }
        if let value = try? container.decode(Double.self) { self = .number(value); return }
        if let value = try? container.decode(String.self) { self = .string(value); return }
        if let value = try? container.decode([LenientJSON].self) { self = .array(value); return }
        if let value = try? container.decode([String: LenientJSON].self) { self = .object(value); return }
        self = .null
    }

    public var isNull: Bool { self == .null }

    subscript(key: String) -> LenientJSON? {
        if case let .object(fields) = self { return fields[key] }
        return nil
    }
}

/// Which metadata groups a RomM payload carried. List endpoints may omit some;
/// a group that is absent keeps what the cache already stored.
public struct RomMetadataFields: OptionSet, Hashable, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }
    /// `metadatum` (release date, genres, companies, players, rating…).
    public static let metadatum = RomMetadataFields(rawValue: 1 << 0)
    /// `merged_screenshots`.
    public static let screenshots = RomMetadataFields(rawValue: 1 << 1)
    public static let regions = RomMetadataFields(rawValue: 1 << 2)
    public static let languages = RomMetadataFields(rawValue: 1 << 3)
    public static let all: RomMetadataFields = [.metadatum, .screenshots, .regions, .languages]
}

/// RomM metadata beyond name, summary and cover (docs/play-data-local.md §6). Every
/// field is optional: older servers, unmatched games and partial list payloads decode
/// to the defaults, and nothing here is ever required to show or play a game.
public struct RomMetadata: Codable, Hashable, Sendable {
    /// First release, epoch milliseconds UTC.
    public var firstReleaseEpochMs: Int64?
    public var genres: [String]
    public var franchises: [String]
    public var companies: [String]
    /// Post-5.2 servers only.
    public var publishers: [String]
    /// Post-5.2 servers only.
    public var developers: [String]
    /// As RomM sends it, e.g. "1", "1-4".
    public var playerCount: String?
    /// 0–100.
    public var averageRating: Double?
    /// Server-relative paths, resolved against the server like covers.
    public var screenshots: [String]
    public var regions: [String]
    public var languages: [String]

    public init(
        firstReleaseEpochMs: Int64? = nil,
        genres: [String] = [],
        franchises: [String] = [],
        companies: [String] = [],
        publishers: [String] = [],
        developers: [String] = [],
        playerCount: String? = nil,
        averageRating: Double? = nil,
        screenshots: [String] = [],
        regions: [String] = [],
        languages: [String] = []
    ) {
        self.firstReleaseEpochMs = firstReleaseEpochMs
        self.genres = genres
        self.franchises = franchises
        self.companies = companies
        self.publishers = publishers
        self.developers = developers
        self.playerCount = playerCount
        self.averageRating = averageRating
        self.screenshots = screenshots
        self.regions = regions
        self.languages = languages
    }

    public static let empty = RomMetadata()

    /// Epoch values above this are milliseconds; at or below, seconds (IGDB sends seconds).
    public static let millisecondsThreshold: Int64 = 100_000_000_000

    public var isEmpty: Bool { self == .empty }

    /// Release year in UTC (release dates are calendar dates, not moments).
    public var releaseYear: Int? {
        guard let firstReleaseEpochMs else { return nil }
        let date = Date(timeIntervalSince1970: TimeInterval(firstReleaseEpochMs) / 1000)
        return Self.utcCalendar.component(.year, from: date)
    }

    /// Publisher line: explicit publishers when the server sends them, otherwise the companies.
    public var publisherLine: [String] { publishers.isEmpty ? companies : publishers }

    /// Rating rounded to a whole number out of 100.
    public var ratingOutOf100: Int? {
        guard let averageRating, averageRating > 0 else { return nil }
        return Int(min(max(averageRating, 0), 100).rounded())
    }

    /// Players as shown: "1-2" reads "1–2".
    public var playersText: String? { playerCount?.replacingOccurrences(of: "-", with: "–") }

    // MARK: Decoding RomM

    /// Decodes the loosely typed RomM payload. Wrong types are ignored field by
    /// field so one odd value never fails a catalogue refresh.
    public static func fromRemote(metadatum: LenientJSON?, mergedScreenshots: LenientJSON?, regions: LenientJSON?, languages: LenientJSON?) -> RomMetadata {
        RomMetadata(
            firstReleaseEpochMs: releaseEpochMs(metadatum?["first_release_date"]),
            genres: strings(metadatum?["genres"]),
            franchises: strings(metadatum?["franchises"]),
            companies: strings(metadatum?["companies"]),
            publishers: strings(metadatum?["publishers"]),
            developers: strings(metadatum?["developers"]),
            playerCount: playerCount(metadatum?["player_count"]),
            averageRating: number(metadatum?["average_rating"]).flatMap { $0.isFinite && $0 > 0 ? $0 : nil },
            screenshots: strings(mergedScreenshots),
            regions: strings(regions),
            languages: strings(languages)
        )
    }

    /// Keeps what a partial payload does not mention: list endpoints may omit
    /// `metadatum` or `merged_screenshots`, and that must not erase what a detail
    /// fetch stored. A payload with a field replaces it.
    public static func merge(previous: RomMetadata?, incoming: RomMetadata, carried: RomMetadataFields) -> RomMetadata {
        let base = previous ?? .empty
        var merged = base
        if carried.contains(.metadatum) {
            merged.firstReleaseEpochMs = incoming.firstReleaseEpochMs
            merged.genres = incoming.genres
            merged.franchises = incoming.franchises
            merged.companies = incoming.companies
            merged.publishers = incoming.publishers
            merged.developers = incoming.developers
            merged.playerCount = incoming.playerCount
            merged.averageRating = incoming.averageRating
        }
        if carried.contains(.screenshots) { merged.screenshots = incoming.screenshots }
        if carried.contains(.regions) { merged.regions = incoming.regions }
        if carried.contains(.languages) { merged.languages = incoming.languages }
        return merged
    }

    /// Seconds or milliseconds by magnitude; numbers, numeric strings and ISO dates
    /// (`yyyy-MM-dd…`, UTC midnight) are accepted. 0 means none.
    public static func releaseEpochMs(_ raw: LenientJSON?) -> Int64? {
        let value: Int64
        switch raw {
        case let .number(number)?:
            guard let whole = wholeNumber(number) else { return nil }
            value = whole
        case let .string(text)?:
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if let whole = Int64(trimmed) {
                value = whole
            } else if let number = Double(trimmed), let whole = wholeNumber(number) {
                value = whole
            } else {
                return isoDateEpochMs(String(trimmed.prefix(10)))
            }
        default:
            return nil
        }
        if value == 0 { return nil }
        if abs(value) > millisecondsThreshold { return value }
        let (milliseconds, overflow) = value.multipliedReportingOverflow(by: 1000)
        return overflow ? nil : milliseconds
    }

    private static func wholeNumber(_ number: Double) -> Int64? {
        guard number.isFinite, abs(number) < 9.2e18 else { return nil }
        return Int64(number)
    }

    private static func isoDateEpochMs(_ text: String) -> Int64? {
        let parts = text.split(separator: "-", omittingEmptySubsequences: false)
        guard text.count == 10, parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]) else { return nil }
        var components = DateComponents()
        components.year = year; components.month = month; components.day = day
        guard let date = utcCalendar.date(from: components) else { return nil }
        let check = utcCalendar.dateComponents([.year, .month, .day], from: date)
        guard check.year == year, check.month == month, check.day == day else { return nil }
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    private static func number(_ raw: LenientJSON?) -> Double? {
        switch raw {
        case let .number(number)?: number
        case let .string(text)?: Double(text.trimmingCharacters(in: .whitespacesAndNewlines))
        default: nil
        }
    }

    private static func playerCount(_ raw: LenientJSON?) -> String? {
        switch raw {
        case let .string(text)?:
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty || trimmed == "0" ? nil : trimmed
        case let .number(number)?:
            guard number.isFinite, number >= 1, let whole = wholeNumber(number) else { return nil }
            return String(whole)
        default:
            return nil
        }
    }

    /// Strings or `{name}` objects; trimmed, blanks dropped, de-duplicated in order.
    private static func strings(_ raw: LenientJSON?) -> [String] {
        guard case let .array(items)? = raw else { return [] }
        var seen = Set<String>()
        var result: [String] = []
        for item in items {
            let text: String?
            switch item {
            case let .string(value): text = value
            case let .object(fields):
                if case let .string(name)? = fields["name"] { text = name } else { text = nil }
            default: text = nil
            }
            guard let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty,
                  seen.insert(trimmed).inserted else { continue }
            result.append(trimmed)
        }
        return result
    }

    static let utcCalendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    // MARK: Cache coding (tolerant of missing fields)

    enum CodingKeys: String, CodingKey {
        case firstReleaseEpochMs, genres, franchises, companies, publishers, developers, playerCount, averageRating, screenshots, regions, languages
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        firstReleaseEpochMs = try? container.decodeIfPresent(Int64.self, forKey: .firstReleaseEpochMs)
        genres = (try? container.decodeIfPresent([String].self, forKey: .genres)) ?? []
        franchises = (try? container.decodeIfPresent([String].self, forKey: .franchises)) ?? []
        companies = (try? container.decodeIfPresent([String].self, forKey: .companies)) ?? []
        publishers = (try? container.decodeIfPresent([String].self, forKey: .publishers)) ?? []
        developers = (try? container.decodeIfPresent([String].self, forKey: .developers)) ?? []
        playerCount = try? container.decodeIfPresent(String.self, forKey: .playerCount)
        averageRating = try? container.decodeIfPresent(Double.self, forKey: .averageRating)
        screenshots = (try? container.decodeIfPresent([String].self, forKey: .screenshots)) ?? []
        regions = (try? container.decodeIfPresent([String].self, forKey: .regions)) ?? []
        languages = (try? container.decodeIfPresent([String].self, forKey: .languages)) ?? []
    }
}
