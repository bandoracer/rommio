import Foundation
import RommioContract
import RommioFoundation

/// A suggestion on Aurora's Play page (docs/theme-layouts.md, Play): a game and why,
/// worked out only from this device's play and RomM's metadata, never guessed.
struct PlayPick: Identifiable, Hashable {
    enum Kind: String, CaseIterable, Sendable {
        /// Another game in progress, the one you've put the most into.
        case keepGoing
        /// A game in the library you've never played, closest to what you play.
        case somethingNew
        /// An arcade or puzzle game for a short turn.
        case quickHit
    }

    let kind: Kind
    let rom: RomDTO
    let artworkURL: URL?
    let reason: String
    let installed: Bool
    var id: String { kind.rawValue }

    var title: String {
        switch kind {
        case .keepGoing: "Keep going"
        case .somethingNew: "Something new"
        case .quickHit: "Quick hit"
        }
    }

    var systemImage: String {
        switch kind {
        case .keepGoing: "arrow.clockwise"
        case .somethingNew: "sparkles"
        case .quickHit: "bolt"
        }
    }
}

enum PlayPicks {
    /// Genres RomM (IGDB) uses for games you can finish a turn of in minutes.
    static let quickGenres: Set<String> = ["Arcade", "Puzzle", "Pinball", "Quiz/Trivia", "Card & Board Game"]

    /// Up to three picks, each game once: keep going, something new, quick hit.
    /// - Parameters:
    ///   - library: every game Rommio knows (the cached library).
    ///   - playtimes: this device's play by game.
    ///   - continuing: games in progress, most recent first; the first is Play's hero, so not a pick.
    ///   - playable: whether the game's platform plays in the app (touch or controller).
    static func build(library: [RomDTO], playtimes: [GamePlaytime], continuing: [RomDTO], installed: Set<Int>,
                      playable: (RomDTO) -> Bool, artwork: (RomDTO) -> URL?) -> [PlayPick] {
        let played = Dictionary(playtimes.map { ($0.romID, $0) }, uniquingKeysWith: { first, _ in first })
        let continuingIDs = Set(continuing.map(\.id))
        var used = Set(continuing.prefix(1).map(\.id))
        var picks: [PlayPick] = []
        func pick(_ kind: PlayPick.Kind, _ rom: RomDTO, _ reason: String) {
            used.insert(rom.id)
            picks.append(PlayPick(kind: kind, rom: rom, artworkURL: artwork(rom), reason: reason, installed: installed.contains(rom.id)))
        }

        // Keep going: the other game in progress you've played most.
        let others = continuing.dropFirst().filter { playable($0) }
        if let rom = others.max(by: { (played[$0.id]?.playtime ?? 0) < (played[$1.id]?.playtime ?? 0) }) {
            if let play = played[rom.id], play.playtime >= 600 {
                pick(.keepGoing, rom, play.sessionCount > 1 ? "\(PlayFormat.duration(play.playtime)) in · \(play.sessionCount) sessions"
                                                           : "\(PlayFormat.duration(play.playtime)) in")
            } else if let play = played[rom.id] {
                pick(.keepGoing, rom, "Last played \(PlayFormat.inline(play.lastPlayedAt))")
            } else {
                pick(.keepGoing, rom, "In progress · \(rom.platformAndYear)")
            }
        }

        // Something new: never played here, closest to your most played game, best rated.
        let top = playtimes.max { $0.playtime < $1.playtime }.flatMap { best in library.first { $0.id == best.romID } }
        let topGenres = Set(top?.metadata.genres ?? [])
        let fresh = library.filter { played[$0.id] == nil && !continuingIDs.contains($0.id) && !used.contains($0.id) && playable($0) }
        func score(_ rom: RomDTO) -> Double {
            Double(rom.metadata.ratingOutOf100 ?? 0) + (topGenres.isDisjoint(with: rom.metadata.genres) ? 0 : 20)
                + (installed.contains(rom.id) ? 10 : 0)
        }
        if let rom = fresh.max(by: { score($0) < score($1) }), score(rom) > 0 {
            var parts: [String] = []
            if let rating = rom.metadata.ratingOutOf100 { parts.append("Rated \(rating)") }
            if let top, let shared = rom.metadata.genres.first(where: topGenres.contains) {
                parts.append("\(LibraryWallOrder.genreName(shared)), like \(top.displayName)")
            }
            if installed.contains(rom.id) { parts.append("On this device") }
            pick(.somethingNew, rom, parts.isEmpty ? rom.platformAndYear : parts.prefix(2).joined(separator: " · "))
        }

        // Quick hit: an arcade or puzzle game, one you've played in short sessions first.
        let quick = library.filter { rom in
            !used.contains(rom.id) && playable(rom)
                && (rom.platformSlug == "arcade" || !quickGenres.isDisjoint(with: rom.metadata.genres.map(LibraryWallOrder.genreName)))
        }
        let quickOrder = quick.sorted { lhs, rhs in
            let a = played[lhs.id]?.lastPlayedAt ?? .distantPast, b = played[rhs.id]?.lastPlayedAt ?? .distantPast
            if a != b { return a > b }
            let i = installed.contains(lhs.id), j = installed.contains(rhs.id)
            return i != j ? i : lhs.displayName.localizedStandardCompare(rhs.displayName) == .orderedAscending
        }
        if let rom = quickOrder.first {
            if let play = played[rom.id] {
                pick(.quickHit, rom, "Last played \(PlayFormat.inline(play.lastPlayedAt))"
                    + (play.playtime >= 60 ? " · \(PlayFormat.duration(play.playtime)) so far" : ""))
            } else {
                let genre = rom.metadata.genres.map(LibraryWallOrder.genreName).first { quickGenres.contains($0) } ?? "Arcade"
                pick(.quickHit, rom, [genre, rom.releaseYear.map(String.init), installed.contains(rom.id) ? "On this device" : nil]
                    .compactMap { $0 }.joined(separator: " · "))
            }
        }
        return picks
    }
}
