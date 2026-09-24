import Foundation
import RommioContract

/// Ranks cached games for the Search page: the best match first, and within a
/// match quality the most recently played first, then by title.
public enum LibrarySearch {
    public enum MatchQuality: Int, Comparable, Sendable {
        case exactTitle = 0
        case titlePrefix
        case wordPrefixes
        case titleContains
        case platform

        public static func < (lhs: MatchQuality, rhs: MatchQuality) -> Bool { lhs.rawValue < rhs.rawValue }
    }

    public struct Result: Hashable, Sendable, Identifiable {
        public var id: Int { rom.id }
        public let rom: RomDTO
        public let quality: MatchQuality
        public let lastPlayedAt: Date?
    }

    /// - Parameter lastPlayed: when each game was last played, by ROM id.
    public static func rank(_ roms: [RomDTO], query: String, lastPlayed: [Int: Date] = [:]) -> [Result] {
        let needle = normalize(query)
        let tokens = needle.split(separator: " ").map(String.init)
        guard !tokens.isEmpty else { return [] }
        var seen = Set<Int>()
        var results: [Result] = []
        for rom in roms where seen.insert(rom.id).inserted {
            guard let quality = quality(of: rom, needle: needle, tokens: tokens) else { continue }
            results.append(Result(rom: rom, quality: quality, lastPlayedAt: lastPlayed[rom.id]))
        }
        return results.sorted { lhs, rhs in
            if lhs.quality != rhs.quality { return lhs.quality < rhs.quality }
            switch (lhs.lastPlayedAt, rhs.lastPlayedAt) {
            case let (left?, right?) where left != right: return left > right
            case (.some, nil): return true
            case (nil, .some): return false
            default: break
            }
            let order = lhs.rom.displayName.localizedStandardCompare(rhs.rom.displayName)
            return order == .orderedSame ? lhs.rom.id < rhs.rom.id : order == .orderedAscending
        }
    }

    static func quality(of rom: RomDTO, needle: String, tokens: [String]) -> MatchQuality? {
        let title = normalize(rom.displayName)
        let words = title.split(separator: " ").map(String.init)
        if title == needle { return .exactTitle }
        if title.hasPrefix(needle) { return .titlePrefix }
        if tokens.allSatisfy({ token in words.contains { $0.hasPrefix(token) } }) { return .wordPrefixes }
        if title.contains(needle) { return .titleContains }
        let platform = normalize(rom.platformName + " " + rom.platformSlug).split(separator: " ").map(String.init)
        if tokens.allSatisfy({ token in words.contains { $0.hasPrefix(token) } || platform.contains { $0.hasPrefix(token) } }) {
            return .platform
        }
        return nil
    }

    /// Case- and accent-insensitive, punctuation as spaces.
    static func normalize(_ text: String) -> String {
        let folded = text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
        let mapped = folded.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : " " }
        return String(mapped).split(separator: " ").joined(separator: " ")
    }
}
