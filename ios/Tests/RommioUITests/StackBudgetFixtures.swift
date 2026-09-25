import Foundation
@testable import RommioContract
@testable import RommioFoundation
@testable import RommioUI

/// Realistic page content for the stack budget: a library of 60 games on 24
/// platforms, 30+ items wherever a page lists things, saves, conflicts and
/// downloads. Deterministic apart from the clock.
enum StackFixtures {
    static let now = Date()

    static let platformNames: [(slug: String, name: String)] = [
        ("snes", "Super Nintendo"), ("nes", "Nintendo Entertainment System"), ("gba", "Game Boy Advance"), ("gb", "Game Boy"),
        ("gbc", "Game Boy Color"), ("n64", "Nintendo 64"), ("nds", "Nintendo DS"), ("genesis", "Sega Genesis"),
        ("sms", "Sega Master System"), ("gg", "Game Gear"), ("psx", "PlayStation"), ("segacd", "Sega CD"),
        ("pce", "PC Engine"), ("ngp", "Neo Geo Pocket"), ("ws", "WonderSwan"), ("atari2600", "Atari 2600"),
        ("atari7800", "Atari 7800"), ("lynx", "Atari Lynx"), ("3ds", "Nintendo 3DS"), ("vb", "Virtual Boy"),
        ("32x", "Sega 32X"), ("saturn", "Sega Saturn"), ("dc", "Dreamcast"), ("arcade", "Arcade"),
    ]

    static func platform(_ index: Int) -> PlatformDTO {
        let entry = platformNames[index % platformNames.count]
        return PlatformDTO(id: index + 1, slug: entry.slug, name: entry.name, fsSlug: entry.slug, romCount: 40 + index * 7)
    }

    static let platforms: [PlatformDTO] = (0 ..< platformNames.count).map(platform)

    static func artwork(_ id: Int) -> URL? { URL(string: "https://rommio.invalid/assets/covers/\(id).png") }

    static func year(_ id: Int) -> Int { 1983 + (id * 7) % 38 }

    static func rom(_ id: Int, platformIndex: Int? = nil) -> RomDTO {
        let platform = platform(platformIndex ?? (id % 6))
        let metadata = RomMetadata(firstReleaseEpochMs: Int64((Double(year(id) - 1970) * 365.25 + 40) * 86_400_000),
                                   genres: ["Platform", "Adventure"], franchises: ["Series \(id % 5)"], companies: ["Studio \(id % 7)"],
                                   publishers: ["Publisher \(id % 4)"], developers: ["Developer \(id % 9)"], playerCount: "1-2",
                                   averageRating: 70 + Double(id % 25), screenshots: (0 ..< 4).map { "/assets/shots/\(id)-\($0).png" },
                                   regions: ["USA"], languages: ["English", "Japanese"])
        let names = ["Super Metroid", "EarthBound", "Chrono Trigger", "A Link to the Past", "Kirby's Adventure", "Mega Man X",
                     "Pattern Test", "Sonic the Hedgehog", "Castlevania", "Final Fantasy VI", "Donkey Kong Country", "Star Fox"]
        return RomDTO(id: id, name: "\(names[id % names.count]) \(id)", summary: "A long summary of game \(id). It has a first sentence. Then more text that runs on for a while so the About section wraps several lines on a phone.",
                      platformID: platform.id, platformName: platform.name, platformSlug: platform.slug, fsName: "game-\(id).sfc",
                      files: [RomFileDTO(id: id * 10, romID: id, fileName: "game-\(id).sfc", fileExtension: "sfc", fileSizeBytes: 3_145_728)],
                      urlCover: "/assets/covers/\(id).png", metadata: metadata, metadataFields: .all)
    }

    static let roms: [RomDTO] = (1 ... 60).map { rom($0) }

    static func minutesAgo(_ minutes: Double) -> Date { now.addingTimeInterval(-minutes * 60) }
    static func epoch(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }

    static func entry(_ kind: SaveKind, romId: Int, minutesAgo: Double, label: String? = nil, slot: Int? = nil,
                      sync: SaveSyncStatus = .synced, resume: Bool = false, thumbnail: Bool = false) -> SaveEntry {
        SaveEntry(key: SaveKey(namespaceId: "n\(romId)", contentId: "c\(romId)", checkpointId: "cp-\(romId)-\(kind)-\(Int(minutesAgo))-\(slot ?? 0)"),
                  romId: Int64(romId), fileId: Int64(romId * 10), operationId: "op-\(romId)-\(Int(minutesAgo))", kind: kind,
                  capturedAtEpochMs: epoch(self.minutesAgo(minutesAgo)), captureReason: kind == .saveState ? "manual" : "exit",
                  deviceId: "device", fromThisDevice: true, deviceLabel: "This iPhone", sync: sync, label: label, slotNumber: slot,
                  isCurrentResume: resume, isCurrentSlot: slot != nil, loadable: kind != .gameSave,
                  thumbnailURL: thumbnail ? URL(fileURLWithPath: "/tmp/rommio-missing-preview-\(romId).jpg") : nil)
    }

    static func gameSaves(_ romId: Int) -> GameSaves {
        GameSaves(resume: entry(.resumePoint, romId: romId, minutesAgo: 5, resume: true, thumbnail: true),
                  saveStates: (1 ... 8).map { entry(.saveState, romId: romId, minutesAgo: Double($0 * 90), label: $0 == 1 ? "Before the boss" : nil, slot: $0) },
                  history: (1 ... 30).map { entry(.resumePoint, romId: romId, minutesAgo: Double($0 * 240), sync: $0 % 3 == 0 ? .waiting : .synced) },
                  gameSaveData: (1 ... 4).map { entry(.gameSave, romId: romId, minutesAgo: Double($0 * 60)) })
    }

    static func conflict(_ romId: Int) -> ProgressConflict {
        let identity = ProgressIdentity(namespaceId: "n\(romId)", contentId: "c\(romId)", binding: ProgressBinding(serverKey: "http://romm.local", accountId: "1", romId: Int64(romId),
            files: [ProgressContentFile(fileId: Int64(romId * 10), discIndex: 0, contentSha256: String(repeating: "a", count: 64), containerSha256: nil, memberPath: nil)]))
        return ProgressConflict(identity: identity, reference: "resume", kind: .resume, choices: [
            .init(operationId: "a\(romId)", entry: entry(.resumePoint, romId: romId, minutesAgo: 3, sync: .conflict, resume: true, thumbnail: true)),
            .init(operationId: "b\(romId)", entry: entry(.resumePoint, romId: romId, minutesAgo: 40, sync: .conflict, resume: true)),
        ])
    }

    static func conflictItem(_ romId: Int = 2) -> ConflictItem {
        ConflictItem(conflict: conflict(romId), rom: rom(romId), artworkURL: artwork(romId), installed: true)
    }

    static let collections: [RommCollectionDTO] = (1 ... 10).map { index in
        RommCollectionDTO(kind: index <= 6 ? .regular : (index <= 8 ? .smart : .virtual), id: "col-\(index)", name: "Collection \(index)",
                          description: index % 2 == 0 ? "Games to finish this year." : "", romIDs: Set((1 ... 12).map { $0 + index }),
                          romCount: 12, pathCoverLarge: "/assets/collections/\(index).png",
                          isVirtual: index > 8, isSmart: index > 6 && index <= 8)
    }

    static var platformSections: [PlatformSupportSection] {
        [
            PlatformSupportSection(supportTier: .touchSupported, title: "Touch", supportingText: "", platforms: Array(platforms.prefix(10))),
            PlatformSupportSection(supportTier: .controllerSupported, title: "Controller", supportingText: "", platforms: Array(platforms[10 ..< 16])),
            PlatformSupportSection(supportTier: .unsupported, title: "Not playable here", supportingText: "", platforms: Array(platforms[16...])),
        ]
    }

    static var collectionSections: [CollectionTypeSection] {
        groupedCollectionSections(collections: collections)
    }

    // MARK: Pages

    static func playGame(_ id: Int, conflict: Bool = false) -> PlayGameItem {
        // Play v2 screens: most games have a resume point (some with a preview), some a save state.
        let latest: SaveEntry? = id % 4 == 3 ? nil
            : entry(id % 4 == 2 ? .saveState : .resumePoint, romId: id, minutesAgo: Double(id * 40), label: id == 2 ? "Before the boss" : nil,
                    sync: id == 1 ? .waiting : .synced, resume: id % 4 != 2, thumbnail: id % 2 == 1)
        return PlayGameItem(rom: rom(id), artworkURL: artwork(id), lastPlayedAt: minutesAgo(Double(id * 50)), playtime: Double(id) * 3_700,
                            sessionCount: id + 2, conflict: conflict ? conflictItem(id) : nil, latest: latest)
    }

    static var playContent: PlayContent {
        var content = PlayContent()
        content.continuePlaying = (1 ... 8).map { playGame($0, conflict: $0 == 2) }
        content.recentSaves = (1 ... 14).map { index in
            let romId = index % 5 + 1
            let save = entry(index % 3 == 0 ? .saveState : .resumePoint, romId: romId, minutesAgo: Double(index * 300),
                             label: index == 3 ? "Before the boss" : nil, slot: index % 3 == 0 ? index : nil,
                             sync: index == 2 ? .conflict : .synced, resume: index == 2, thumbnail: index % 2 == 0)
            return RecentSaveItem(entry: save, rom: rom(romId), artworkURL: artwork(romId))
        }
        content.conflicts = [conflictItem(2)]
        content.collection = PlayCollectionItem(collection: collections[1], games: (1 ... 10).map { playGame($0) }, playedCount: 6, playtime: 90_000)
        content.download = PlayDownloadItem(name: "Chrono Trigger 3", progress: 0.42, waiting: 3, failed: 1)
        content.installedCount = 18
        content.lastSync = minutesAgo(12)
        content.serverName = "Home RomM"
        return content
    }

    static var libraryContent: LibraryContent {
        var content = LibraryContent()
        content.sections = platformSections
        content.collectionSections = collectionSections
        content.installedByPlatform = Dictionary(uniqueKeysWithValues: platforms.prefix(8).map { ($0.slug, 3) })
        content.inProgress = (1 ... 6).map { rom($0) }
        content.games = roms.map { LibraryGame(rom: $0, artworkURL: artwork($0.id)) }
        content.collections = collections.map { LibraryCollection(collection: $0, artworkURL: URL(string: "https://rommio.invalid/assets/collections/\($0.id).png")) }
        content.installedRomIDs = Set(1 ... 18)
        return content
    }

    static func platformGames(_ count: Int = 45) -> [PlatformGame] {
        (1 ... count).map { id in
            PlatformGame(rom: rom(id, platformIndex: 0), artworkURL: artwork(id), installed: id % 3 == 0,
                         playtime: id % 4 == 0 ? Double(id) * 1_800 : 0, lastPlayedAt: id % 4 == 0 ? minutesAgo(Double(id) * 100) : nil)
        }
    }

    static var platformContent: PlatformContent {
        var content = PlatformContent(platform: platforms[0])
        content.tier = .touchSupported
        content.games = platformGames()
        return content
    }

    static func searchHit(_ id: Int) -> SearchHit {
        SearchHit(rom: rom(id), artworkURL: artwork(id), lastPlayedAt: id % 3 == 0 ? minutesAgo(Double(id) * 60) : nil,
                  installed: id % 2 == 0, inProgress: id <= 3)
    }

    static func searchContent(query: String) -> SearchContent {
        var content = SearchContent(query: query)
        content.libraryCount = roms.count
        content.platforms = platforms
        content.inProgress = (1 ... 6).map(searchHit)
        if !query.isEmpty {
            content.hits = (1 ... 40).map(searchHit)
            content.related = platforms.prefix(4).map { SearchRelated(kind: .platform($0), title: $0.name, count: $0.romCount) }
                + collections.prefix(3).map { SearchRelated(kind: .collection($0), title: $0.name, count: $0.romCount) }
        }
        return content
    }

    static var collectionContent: CollectionContent {
        var content = CollectionContent(collection: collections[1])
        content.games = roms.prefix(36).map { ShelfGame(rom: $0, url: artwork($0.id)) }
        return content
    }

    static var statsContent: StatsContent {
        let games = (1 ... 12).map { rom($0) }
        var content = StatsContent()
        content.total = 420_000
        content.sessionCount = 40
        content.streakDays = 3
        content.lastPlayedAt = minutesAgo(20)
        content.games = games.map {
            StatsGameItem(rom: $0, artworkURL: artwork($0.id), playtime: 36_000 / Double($0.id), sessionCount: 4, lastPlayedAt: minutesAgo(Double($0.id) * 90), playable: $0.id % 2 == 1)
        }
        content.sessions = (0 ..< 40).map { (index: Int) -> PlaySessionItem in
            let game: RomDTO = games[index % games.count]
            let ended: Date = minutesAgo(Double(index) * 400)
            let reason: PlaySessionEndReason = index % 2 == 0 ? .resume : .quit
            return PlaySessionItem(id: "s\(index)", rom: game, artworkURL: artwork(game.id), startedAt: ended.addingTimeInterval(-1_800),
                                   endedAt: ended, playtime: 1_800, endReason: reason)
        }
        return content
    }

    static var gameContent: GameContent {
        let game = rom(2)
        let saves = gameSaves(2)
        let file = GameDetailSelectedFilePresentation(title: "game-2.sfc", metaLine: "SFC · 3.1 MB", availabilityLabel: "On this device",
                                                      availabilityTone: .ok, playabilityLabel: "Touch", downloadStatusText: nil,
                                                      downloadProgressValue: nil, progressDetailText: nil, errorMessage: nil,
                                                      readinessMessage: "Ready to play.", localFileName: "game-2.sfc")
        let deck = GameDetailActionDeckPresentation(subtitle: "Choose the next action for this title.", badge: "Ready",
                                                    recommendedCoreName: "Snes9x", downloadProgressValue: nil, progressDetailText: nil,
                                                    errorMessage: nil, notes: ["Local file: game-2.sfc"],
                                                    primary: GameDetailAction(kind: .play),
                                                    secondary: [GameDetailAction(kind: .deleteLocal, recordID: "r2")])
        return GameContent(
            rom: game, artworkURL: artwork(2), tier: .touchSupported,
            hero: GameDetailHeroPresentation(statusText: "On this device", statusTone: .ok, supportMessage: nil,
                                             summaryText: game.summary, fileCountText: "1 file available"),
            deck: deck, fileID: 20, file: file, fileOptions: [GameDetailFileOption(id: 20, title: "SFC")], showsFilePicker: false,
            selectedFileID: 20, savedSummary: nil, timeline: SaveTimelineItem.stops(saves), saves: saves, usesProgressStore: true,
            conflict: conflictItem(2), details: GameDetailItem.items(game.metadata),
            screenshots: (0 ..< 4).compactMap { URL(string: "https://rommio.invalid/assets/shots/2-\($0).png") },
            playtime: 12_000, sessionCount: 6, lastPlayedAt: minutesAgo(30), preserved: nil,
            statusTone: .ok, availabilityTone: .ok, playabilityTone: .ok, showsStartOptions: true)
    }

    static var launchChoice: LaunchChoice {
        LaunchChoice.make(rom: rom(2), artworkURL: artwork(2), saves: gameSaves(2), note: "The cloud check didn't finish, so Rommio asked first.")!
    }

    static var stateBrowser: GameStateBrowser {
        GameStateBrowser()
    }

    // MARK: A model for pages that read feature models directly

    static func downloadRecords(profileID: String) -> [DownloadRecord] {
        (1 ... 36).map { id in
            let status: DownloadStatus = id == 1 ? .running : id <= 6 ? .queued : id <= 9 ? .failed : .completed
            return DownloadRecord(profileID: profileID, romID: id, fileID: id * 10, romName: rom(id).displayName, platformSlug: rom(id).platformSlug,
                                  fileName: "game-\(id).sfc", fileSizeBytes: 3_145_728, status: status, progressPercent: status == .running ? 42 : status == .completed ? 100 : 0,
                                  bytesDownloaded: status == .running ? 1_321_205 : 0, totalBytes: 3_145_728,
                                  localPath: status == .completed ? "/tmp/rommio-missing/game-\(id).sfc" : nil,
                                  lastError: status == .failed ? "The server closed the connection." : nil,
                                  enqueuedAtEpochMS: epoch(minutesAgo(Double(id))), updatedAtEpochMS: epoch(minutesAgo(Double(id))))
        }
    }
}

/// A signed-in app model on throwaway storage, with feature models filled for
/// pages that read them (Downloads, Settings, First sync, setup).
@MainActor
final class StackModelHarness {
    let root: URL
    let model: RommioAppModel
    let appearance: AppearanceStore

    init() throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("rommio-stack-\(UUID().uuidString)", isDirectory: true)
        let database = try AppDatabase(rootDirectory: root.appending(path: "Database"))
        let secretStore = try FileSecretStore(rootDirectory: root.appending(path: "Secrets"))
        let profileStore = GRDBServerProfileStore(database: database)
        let offlineStore = GRDBOfflineReadinessStore(database: database)
        let authController = AuthSessionController(profileStore: profileStore, secretStore: secretStore, session: .shared)
        let libraryStore = AppManagedLibraryStore(rootDirectory: root.appending(path: "Library"))
        try libraryStore.ensureRootLayout()
        let libraryRepository = DefaultLibraryRepository(database: database, profileStore: profileStore, offlineStore: offlineStore, authController: authController)
        let downloadQueue = ManagedDownloadQueue(database: database, profileStore: profileStore, authController: authController, libraryStore: libraryStore)
        let services = RommioServices(database: database, secretStore: secretStore, profileStore: profileStore, offlineStore: offlineStore,
                                      authController: authController, libraryRepository: libraryRepository, downloadQueue: downloadQueue,
                                      libraryStore: libraryStore, networkMonitor: NetworkMonitor())
        model = RommioAppModel(services: services)
        appearance = AppearanceStore(defaults: UserDefaults(suiteName: "rommio-stack-\(UUID().uuidString)")!)

        let stamp = ISO8601DateFormatter().string(from: StackFixtures.now)
        let profile = ServerProfile(id: "profile-1", label: "Home RomM", baseURL: URL(string: "https://romm.example.com")!, edgeAuthMode: .none,
                                    originAuthMode: .rommBearerPassword, capabilities: AuthCapabilities(),
                                    serverAccess: ServerAccessState(status: .ready, verifiedAt: stamp),
                                    sessionState: SessionState(hasOriginSession: true, lastValidatedAt: stamp), isActive: true,
                                    status: .connected, lastValidationAt: stamp, createdAt: stamp, updatedAt: stamp)
        model.activeProfile = profile
        model.profiles = [profile]
        let offline = OfflineState(connectivity: .online, activeProfileID: profile.id, catalogReady: true, mediaReady: true,
                                   lastFullSyncAtEpochMS: StackFixtures.epoch(StackFixtures.minutesAgo(12)), lastMediaSyncAtEpochMS: 1, cacheBytes: 48_000_000)
        model.offlineState = offline
        model.libraryFeature.platforms = StackFixtures.platforms
        model.collectionsFeature.collections = StackFixtures.collections
        model.downloadsFeature.records = StackFixtures.downloadRecords(profileID: profile.id)
        model.downloadsFeature.offlineState = offline
        model.statsFeature.games = Dictionary(uniqueKeysWithValues: StackFixtures.roms.map { ($0.id, $0) })
        let settings = model.settingsFeature
        settings.activeProfile = profile
        settings.profiles = (0 ..< 4).map { index in
            var copy = profile
            copy.id = "profile-\(index + 1)"
            copy.label = index == 0 ? "Home RomM" : "Server \(index + 1)"
            return copy
        }
        settings.offlineState = offline
        settings.storageSummary = LocalStorageSummary(installedGameCount: 27, installedFileCount: 29, totalBytes: 9_400_000_000, cacheBytes: 48_000_000)
        settings.runtimeInventory = model.runtimeInventory()
    }

    deinit { try? FileManager.default.removeItem(at: root) }
}
