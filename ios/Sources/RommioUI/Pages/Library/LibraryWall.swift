import Foundation
import RommioContract
import RommioFoundation

/// How Aurora's Library wall groups every game (docs/theme-layouts.md, Library): one
/// wall of covers, one choice of order. Replaces the era ruler, platform facets and
/// tier chips.
enum LibraryLens: String, CaseIterable, Identifiable, Sendable {
    case console, series, year, genre, alphabetical

    var id: String { rawValue }

    var title: String {
        switch self {
        case .console: "Console"
        case .series: "Series"
        case .year: "Year"
        case .genre: "Genre"
        case .alphabetical: "A–Z"
        }
    }

    var systemImage: String {
        switch self {
        case .console: "gamecontroller"
        case .series: "square.stack"
        case .year: "calendar"
        case .genre: "theatermasks"
        case .alphabetical: "textformat"
        }
    }
}

/// One group of the wall: a sticky header over its covers.
struct LibraryWallSection: Identifiable, Hashable {
    let id: String
    let title: String
    /// The index rail's label ("NDS", "P", "'96").
    let index: String
    let games: [LibraryGame]
    let installed: Int
    /// Console sections open their platform.
    var platform: PlatformDTO? = nil
    /// Series sections drop the series name from titles ("Pokémon Black Version" reads "Black Version").
    var titlePrefix: String? = nil
    /// A second line under the name (an era's years, "1990–95").
    var subtitle: String? = nil

    /// "11 games · 3 on this device" (a console's count is the server's).
    var detail: String {
        let count = platform?.romCount ?? games.count
        return "\(PlayFormat.games(count)) · \(installed) on this device"
    }

    /// A game's title within this section.
    func title(of game: LibraryGame) -> String {
        let name = game.rom.displayName
        guard let prefix = titlePrefix, name.count > prefix.count + 2,
              name.lowercased().hasPrefix(prefix.lowercased()) else { return name }
        let rest = name.dropFirst(prefix.count).drop { $0 == " " || $0 == ":" || $0 == "-" || $0 == "–" }
        return rest.isEmpty ? name : String(rest)
    }
}

extension LibraryContent {
    /// The wall in `lens` order. Games appear once; games without the lens's metadata
    /// (no series, genre or year from RomM) gather in a last section, so nothing is lost.
    func wall(_ lens: LibraryLens) -> [LibraryWallSection] {
        let installed = installedRomIDs
        func section(_ id: String, _ title: String, _ index: String, _ games: [LibraryGame],
                     platform: PlatformDTO? = nil, prefix: String? = nil, subtitle: String? = nil) -> LibraryWallSection {
            LibraryWallSection(id: "\(lens.rawValue).\(id)", title: title, index: index, games: games,
                               installed: games.filter { installed.contains($0.id) }.count, platform: platform, titlePrefix: prefix,
                               subtitle: subtitle)
        }
        let byTitle = games.sorted { LibraryWallOrder.titleKey($0) < LibraryWallOrder.titleKey($1) }

        switch lens {
        case .console:
            // Tier order (touch, controller, browse only), each platform's games A–Z.
            let grouped = Dictionary(grouping: byTitle) { $0.rom.platformSlug }
            var sections = platforms.compactMap { platform -> LibraryWallSection? in
                guard let games = grouped[platform.slug], !games.isEmpty else { return nil }
                return section(platform.slug, platform.name, LibraryWallOrder.shortName(platform), games, platform: platform)
            }
            let known = Set(platforms.map(\.slug))
            let orphans = byTitle.filter { !known.contains($0.rom.platformSlug) }
            if !orphans.isEmpty { sections.append(section("other", "Other consoles", "•", orphans)) }
            return sections

        case .series:
            // Series by size (then name), each by release year; one-game series gather at the end.
            let grouped = Dictionary(grouping: games) { $0.rom.metadata.franchises.first ?? "" }
            let named = grouped.filter { !$0.key.isEmpty && $0.value.count > 1 }
            var sections = named.sorted { lhs, rhs in
                lhs.value.count != rhs.value.count ? lhs.value.count > rhs.value.count
                    : lhs.key.localizedStandardCompare(rhs.key) == .orderedAscending
            }.map { name, games in
                section(name, name, String(name.prefix(1)).uppercased(), LibraryWallOrder.byYear(games), prefix: name)
            }
            let rest = byTitle.filter { named[$0.rom.metadata.franchises.first ?? ""] == nil }
            if !rest.isEmpty { sections.append(section("other", sections.isEmpty ? "All games" : "Everything else", "•", rest)) }
            return sections

        case .year:
            // Eras (the 16-bit years…), oldest first, each by year; no year last.
            var sections = eras.map { group in
                section(group.era.id, group.era.name, LibraryWallOrder.yearIndex(group.games), group.games, subtitle: group.span)
            }
            let dated = Set(eras.flatMap(\.games).map(\.id))
            let undated = byTitle.filter { !dated.contains($0.id) }
            if !undated.isEmpty { sections.append(section("unknown", "Year unknown", "?", undated)) }
            return sections

        case .genre:
            // Each game under its first genre from RomM; genres by size.
            let grouped = Dictionary(grouping: byTitle) { $0.rom.metadata.genres.first.map(LibraryWallOrder.genreName) ?? "" }
            var sections = grouped.filter { !$0.key.isEmpty }.sorted { lhs, rhs in
                lhs.value.count != rhs.value.count ? lhs.value.count > rhs.value.count
                    : lhs.key.localizedStandardCompare(rhs.key) == .orderedAscending
            }.map { name, games in section(name, name, String(name.prefix(1)).uppercased(), games) }
            if let rest = grouped[""], !rest.isEmpty { sections.append(section("other", "No genre from RomM", "•", rest)) }
            return sections

        case .alphabetical:
            let grouped = Dictionary(grouping: byTitle) { LibraryWallOrder.letter($0) }
            return grouped.keys.sorted { lhs, rhs in lhs == "#" ? true : rhs == "#" ? false : lhs < rhs }
                .map { letter in section(letter, letter, letter, grouped[letter] ?? []) }
        }
    }
}

enum LibraryWallOrder {
    /// A–Z ignoring a leading "The"/"A".
    static func titleKey(_ game: LibraryGame) -> String {
        var name = game.rom.displayName.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        for article in ["the ", "a "] where name.hasPrefix(article) { name.removeFirst(article.count) }
        return name
    }

    static func letter(_ game: LibraryGame) -> String {
        guard let first = titleKey(game).first else { return "#" }
        return first.isLetter ? String(first).uppercased() : "#"
    }

    static func byYear(_ games: [LibraryGame]) -> [LibraryGame] {
        games.sorted { lhs, rhs in
            let a = lhs.rom.releaseYear ?? .max, b = rhs.rom.releaseYear ?? .max
            return a != b ? a < b : titleKey(lhs) < titleKey(rhs)
        }
    }

    /// "'96" for the first year in the group.
    static func yearIndex(_ games: [LibraryGame]) -> String {
        guard let year = games.compactMap(\.rom.releaseYear).min() else { return "?" }
        return "’" + String(format: "%02d", year % 100)
    }

    /// RomM's genre names without their IGDB abbreviations ("Role-playing (RPG)" → "Role-playing").
    static func genreName(_ genre: String) -> String {
        guard let open = genre.firstIndex(of: "("), genre.hasSuffix(")") else { return genre }
        let base = genre[..<open].trimmingCharacters(in: .whitespaces)
        return base.isEmpty ? genre : base
    }

    /// The rail's short console name: RomM's slug in capitals, trimmed.
    static func shortName(_ platform: PlatformDTO) -> String {
        let slug = platform.slug.uppercased().replacingOccurrences(of: "NINTENDO-", with: "")
        return slug.count <= 6 ? slug : String(slug.prefix(5))
    }
}
