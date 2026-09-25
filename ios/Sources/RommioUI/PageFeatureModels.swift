import Foundation
import Observation
import RommioContract
import RommioFoundation

extension RommioServices {
    /// The signed-in account of the active profile, as progress namespaces bind it.
    func activeProgressAccount() async -> ProgressAccount? {
        guard let profile = try? await profileStore.activeProfile(),
              let owner = try? await authController.verifiedAccount(profileID: profile.id) else { return nil }
        return ProgressAccount(serverKey: ApplicationProgress.serverKey(owner.baseURL), accountId: String(owner.userID))
    }
}

/// Stats: play sessions recorded on this device (docs/play-data-local.md §1), with
/// play from before recording estimated from progress checkpoints
/// (`PlayStatsDerivation.merge`). Nothing here is written or synced.
@MainActor
@Observable
final class StatsFeatureModel {
    var stats = PlayStats.empty
    /// Game details by ROM id, from the cached library and download records.
    var games: [Int: RomDTO] = [:]
    var isLoading = false
    var errorMessage: String?

    private let services: RommioServices

    init(services: RommioServices) {
        self.services = services
    }

    func game(_ romID: Int) -> RomDTO {
        games[romID] ?? RomDTO(id: romID, name: "Game \(romID)")
    }

    func refresh() async {
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }
        do {
            guard let profile = try await services.profileStore.activeProfile() else {
                update(\.stats, .empty)
                return
            }
            let store = try await services.progress.store()
            let deviceID = try await services.progress.deviceID()
            let account = await services.activeProgressAccount()
            let serverKey = account?.serverKey ?? ApplicationProgress.serverKey(profile.baseURL)
            var samples: [PlayCheckpointSample] = []
            for identity in try await store.identities() where identity.binding.serverKey == serverKey
                && (account == nil || identity.binding.accountId == account?.accountId) {
                for entry in try await store.history(identity) {
                    samples.append(PlayCheckpointSample(checkpoint: entry.checkpoint))
                }
            }
            // Writes of a session that just ended land before the table is read.
            await services.playData.flush()
            let recorded = try account.map { try services.playData.store.sessions(serverKey: $0.serverKey, accountId: $0.accountId) } ?? []
            let merged = PlayStatsDerivation.merge(recorded: recorded, samples: samples, deviceID: deviceID)

            var known: [Int: RomDTO] = [:]
            for record in try await services.downloadQueue.records(profileID: profile.id) where known[record.romID] == nil {
                known[record.romID] = RomDTO(id: record.romID, name: record.romName, platformSlug: record.platformSlug)
            }
            for rom in try await services.libraryRepository.cachedLibraryRoms() { known[rom.id] = rom }
            // One batch: the stats and the games they name change together.
            update(\.stats, merged)
            update(\.games, known)
            update(\.errorMessage, nil)
        } catch {
            update(\.errorMessage, error.localizedDescription)
        }
    }
}

/// Search over the cached library: best match first, recently played first
/// within a match (`LibrarySearch`).
@MainActor
@Observable
final class SearchFeatureModel {
    var query = ""
    var roms: [RomDTO] = []
    var lastPlayed: [Int: Date] = [:]
    /// Suggestions before typing: games in progress.
    var recent: [RomDTO] = []
    var isLoading = false

    private let services: RommioServices

    init(services: RommioServices) {
        self.services = services
    }

    var results: [LibrarySearch.Result] {
        LibrarySearch.rank(roms, query: query, lastPlayed: lastPlayed)
    }

    var isSearching: Bool { !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    func refresh(recentlyPlayed: [RomDTO], stats: PlayStats) async {
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }
        let library = (try? await services.libraryRepository.cachedLibraryRoms()) ?? roms
        // Local play first; RomM's Continue playing order ranks the rest.
        var played: [Int: Date] = [:]
        for (index, rom) in recentlyPlayed.enumerated() {
            played[rom.id] = Date.distantPast.addingTimeInterval(TimeInterval(recentlyPlayed.count - index))
        }
        for game in stats.games { played[game.romID] = game.lastPlayedAt }
        update(\.roms, library)
        update(\.lastPlayed, played)
        update(\.recent, recentlyPlayed)
    }
}

/// Saves across games for Play (Recent saves) and the conflicts that need a choice,
/// from the read-only saves reader (docs/play-data-local.md §3).
@MainActor
@Observable
final class SavedProgressFeatureModel {
    /// Newest first (at most 24 read; the pages show 12).
    var recent: [SaveEntry] = []
    /// Each game's latest place to continue from (its resume point, else a save state):
    /// the screens on Play.
    var latest: [Int64: SaveEntry] = [:]
    var conflicts: [ProgressConflict] = []
    /// Game details by ROM id, from the cached library.
    var games: [Int: RomDTO] = [:]
    var isLoading = false
    var errorMessage: String?

    @ObservationIgnored private let services: RommioServices

    init(services: RommioServices) {
        self.services = services
    }

    func game(_ romID: Int64) -> RomDTO {
        games[Int(romID)] ?? RomDTO(id: Int(romID), name: "Game \(romID)")
    }

    func refresh() async {
        if isLoading { return }
        isLoading = true
        defer { isLoading = false }
        guard let account = await services.activeProgressAccount() else {
            update(\.recent, [])
            update(\.latest, [:])
            update(\.conflicts, [])
            return
        }
        do {
            await services.playData.flush()
            let saves = try await services.savedProgress.recentAndLatest(account)
            let conflicts = try await services.savedProgress.conflicts(account)
            var known = games
            for rom in (try? await services.libraryRepository.cachedLibraryRoms()) ?? [] { known[rom.id] = rom }
            // One batch: saves, conflicts and the games they name.
            update(\.recent, saves.recent)
            update(\.latest, saves.latest)
            update(\.conflicts, conflicts)
            update(\.games, known)
            update(\.errorMessage, nil)
        } catch {
            // Storage busy (backup or recovery): keep what is shown.
            update(\.errorMessage, error.localizedDescription)
        }
    }

    func rename(_ entry: SaveEntry, to name: String) async {
        do {
            let label = try await services.savedProgress.rename(entry.key, to: name)
            recent = recent.map { $0.key == entry.key ? relabelled($0, label) : $0 }
            latest = latest.mapValues { $0.key == entry.key ? relabelled($0, label) : $0 }
        } catch { errorMessage = error.localizedDescription }
    }

}

func relabelled(_ entry: SaveEntry, _ label: String?) -> SaveEntry {
    var copy = entry
    copy.label = entry.kind == .gameSave ? nil : label
    return copy
}
