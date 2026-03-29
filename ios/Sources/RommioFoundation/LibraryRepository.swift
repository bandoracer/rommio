import Foundation
import GRDB
import RommioContract

public protocol LibraryRepository: Sendable {
    func refreshActiveProfile(force: Bool) async throws
    func cachedHome() async throws -> HomeSnapshot?
    func cachedPlatforms() async throws -> [PlatformDTO]
    func cachedCollections() async throws -> [RommCollectionDTO]
    func cachedRoms(platformID: Int) async throws -> [RomDTO]
    func cachedRoms(collection: RommCollectionDTO) async throws -> [RomDTO]
    func cachedRom(id: Int) async throws -> RomDTO?
    func loadHome() async throws -> HomeSnapshot
    func loadPlatforms() async throws -> [PlatformDTO]
    func loadCollections() async throws -> [RommCollectionDTO]
    func loadRoms(platformID: Int) async throws -> [RomDTO]
    func loadRoms(collection: RommCollectionDTO) async throws -> [RomDTO]
    func loadRom(id: Int) async throws -> RomDTO
}

public actor DefaultLibraryRepository: LibraryRepository {
    private let database: AppDatabase
    private let profileStore: ServerProfileStore
    private let offlineStore: OfflineReadinessStore
    private let authController: AuthSessionController

    public init(
        database: AppDatabase,
        profileStore: ServerProfileStore,
        offlineStore: OfflineReadinessStore,
        authController: AuthSessionController
    ) {
        self.database = database
        self.profileStore = profileStore
        self.offlineStore = offlineStore
        self.authController = authController
    }

    public func refreshActiveProfile(force: Bool = false) async throws {
        guard let context = try await authController.activeAuthenticatedContext() else { return }
        let client = await authController.client(for: context.profile.id, baseURL: context.profile.baseURL)
        let platforms = try await client.getPlatforms()
        let recent = try await client.getRecentlyAdded()
        let continuePlaying = try await client.getRoms(
            query: RomQuery(lastPlayed: true, limit: 12, orderBy: "last_played", orderDirection: "desc")
        )
        let collections = try await fetchCollections(client: client)

        try cachePlatforms(platforms, profileID: context.profile.id)
        try cacheRoms(recent.items + continuePlaying.items, profileID: context.profile.id)
        try cacheCollections(collections, profileID: context.profile.id)
        try cacheHome(
            HomeSnapshot(
                continuePlaying: continuePlaying.items,
                recentlyAdded: recent.items,
                highlightedCollections: Array(collections.prefix(8))
            ),
            profileID: context.profile.id
        )

        var offlineState = try await offlineStore.load(profileID: context.profile.id)
        offlineState.activeProfileID = context.profile.id
        offlineState.catalogReady = true
        offlineState.isRefreshing = false
        offlineState.lastFullSyncAtEpochMS = now()
        offlineState.lastError = nil
        try await offlineStore.save(offlineState, profileID: context.profile.id)
    }

    public func loadHome() async throws -> HomeSnapshot {
        let profile = try await requireActiveProfile()
        let client = await authController.client(for: profile.id, baseURL: profile.baseURL)

        do {
            let recent = try await client.getRecentlyAdded()
            let continuePlaying = try await client.getRoms(
                query: RomQuery(lastPlayed: true, limit: 12, orderBy: "last_played", orderDirection: "desc")
            )
            let collections = try await fetchCollections(client: client)
            let snapshot = HomeSnapshot(
                continuePlaying: continuePlaying.items,
                recentlyAdded: recent.items,
                highlightedCollections: Array(collections.prefix(8))
            )
            try cacheRoms(recent.items + continuePlaying.items, profileID: profile.id)
            try cacheCollections(collections, profileID: profile.id)
            try cacheHome(snapshot, profileID: profile.id)
            return snapshot
        } catch {
            if let cached = try cachedHome(profileID: profile.id) {
                return cached
            }
            throw error
        }
    }

    public func cachedHome() async throws -> HomeSnapshot? {
        let profile = try await requireActiveProfile()
        return try cachedHome(profileID: profile.id)
    }

    public func cachedPlatforms() async throws -> [PlatformDTO] {
        let profile = try await requireActiveProfile()
        return try cachedPlatforms(profileID: profile.id)
    }

    public func cachedCollections() async throws -> [RommCollectionDTO] {
        let profile = try await requireActiveProfile()
        return try cachedCollections(profileID: profile.id)
    }

    public func cachedRoms(platformID: Int) async throws -> [RomDTO] {
        let profile = try await requireActiveProfile()
        return try cachedRoms(profileID: profile.id).filter { $0.platformID == platformID }
    }

    public func cachedRoms(collection: RommCollectionDTO) async throws -> [RomDTO] {
        let profile = try await requireActiveProfile()
        return try cachedRoms(profileID: profile.id).filter { collection.romIDs.contains($0.id) }
    }

    public func cachedRom(id: Int) async throws -> RomDTO? {
        let profile = try await requireActiveProfile()
        return try cachedRoms(profileID: profile.id).first(where: { $0.id == id })
    }

    public func loadPlatforms() async throws -> [PlatformDTO] {
        let profile = try await requireActiveProfile()
        let client = await authController.client(for: profile.id, baseURL: profile.baseURL)

        do {
            let platforms = try await client.getPlatforms().sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            try cachePlatforms(platforms, profileID: profile.id)
            return platforms
        } catch {
            let cached = try cachedPlatforms(profileID: profile.id)
            if !cached.isEmpty { return cached }
            throw error
        }
    }

    public func loadCollections() async throws -> [RommCollectionDTO] {
        let profile = try await requireActiveProfile()
        let client = await authController.client(for: profile.id, baseURL: profile.baseURL)

        do {
            let collections = try await fetchCollections(client: client)
            try cacheCollections(collections, profileID: profile.id)
            return collections
        } catch {
            let cached = try cachedCollections(profileID: profile.id)
            if !cached.isEmpty { return cached }
            throw error
        }
    }

    public func loadRoms(platformID: Int) async throws -> [RomDTO] {
        let profile = try await requireActiveProfile()
        let client = await authController.client(for: profile.id, baseURL: profile.baseURL)

        do {
            let response = try await client.getRoms(query: RomQuery(platformIDs: platformID, legacyPlatformID: platformID))
            try cacheRoms(response.items, profileID: profile.id)
            return response.items
        } catch {
            let cached = try cachedRoms(profileID: profile.id).filter { $0.platformID == platformID }
            if !cached.isEmpty { return cached }
            throw error
        }
    }

    public func loadRoms(collection: RommCollectionDTO) async throws -> [RomDTO] {
        let profile = try await requireActiveProfile()
        let client = await authController.client(for: profile.id, baseURL: profile.baseURL)

        do {
            let roms: [RomDTO]
            switch collection.kind {
            case .regular:
                let response = try await client.getRoms(query: RomQuery(collectionID: Int(collection.id)))
                roms = response.items
            case .smart:
                let response = try await client.getRoms(query: RomQuery(smartCollectionID: Int(collection.id)))
                roms = response.items
            case .virtual:
                let response = try await client.getRoms(query: RomQuery(virtualCollectionID: collection.id))
                roms = response.items
            }
            try cacheRoms(roms, profileID: profile.id)
            return roms
        } catch {
            let cached = try cachedRoms(profileID: profile.id).filter { collection.romIDs.contains($0.id) }
            if !cached.isEmpty { return cached }
            throw error
        }
    }

    public func loadRom(id: Int) async throws -> RomDTO {
        let profile = try await requireActiveProfile()
        let client = await authController.client(for: profile.id, baseURL: profile.baseURL)

        do {
            let rom = try await client.getRom(id: id)
            try cacheRoms([rom], profileID: profile.id)
            return rom
        } catch {
            if let cached = try cachedRoms(profileID: profile.id).first(where: { $0.id == id }) {
                return cached
            }
            throw error
        }
    }

    private func fetchCollections(client: RommAPIClient) async throws -> [RommCollectionDTO] {
        let regular = try await client.getCollections()
        let smart = try await client.getSmartCollections()
        let virtual = try await client.getVirtualCollections()
        let regularDomain = regular.map { $0.asDomain() }
        let smartDomain = smart.map { $0.asDomain() }
        let virtualDomain = virtual.map { $0.asDomain() }
        return regularDomain + smartDomain + virtualDomain
    }

    private func requireActiveProfile() async throws -> ServerProfile {
        guard let profile = try await profileStore.activeProfile() else {
            throw URLError(.userAuthenticationRequired)
        }
        return profile
    }

    private func cachePlatforms(_ platforms: [PlatformDTO], profileID: String) throws {
        try database.write { db in
            try db.execute(sql: "DELETE FROM cached_platforms WHERE profile_id = ?", arguments: [profileID])
            for platform in platforms {
                try db.execute(
                    sql: "INSERT INTO cached_platforms (profile_id, platform_id, payload, updated_at_epoch_ms) VALUES (?, ?, ?, ?)",
                    arguments: [profileID, platform.id, try database.encoded(platform), now()]
                )
            }
        }
    }

    private func cacheRoms(_ roms: [RomDTO], profileID: String) throws {
        let uniqueRoms = roms.reduce(into: [Int: RomDTO]()) { partialResult, rom in
            partialResult[rom.id] = rom
        }.values
        try database.write { db in
            for rom in uniqueRoms {
                try db.execute(
                    sql: """
                        INSERT INTO cached_roms (profile_id, rom_id, platform_id, payload, updated_at_epoch_ms)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(profile_id, rom_id) DO UPDATE SET
                            platform_id = excluded.platform_id,
                            payload = excluded.payload,
                            updated_at_epoch_ms = excluded.updated_at_epoch_ms
                        """,
                    arguments: [profileID, rom.id, rom.platformID, try database.encoded(rom), now()]
                )
            }
        }
    }

    private func cacheCollections(_ collections: [RommCollectionDTO], profileID: String) throws {
        try database.write { db in
            try db.execute(sql: "DELETE FROM cached_collections WHERE profile_id = ?", arguments: [profileID])
            for collection in collections {
                try db.execute(
                    sql: "INSERT INTO cached_collections (profile_id, collection_id, kind, payload, updated_at_epoch_ms) VALUES (?, ?, ?, ?, ?)",
                    arguments: [profileID, collection.id, collection.kind.rawValue, try database.encoded(collection), now()]
                )
            }
        }
    }

    private func cacheHome(_ snapshot: HomeSnapshot, profileID: String) throws {
        try database.write { db in
            try db.execute(
                sql: """
                    INSERT INTO home_snapshots (profile_id, payload, updated_at_epoch_ms)
                    VALUES (?, ?, ?)
                    ON CONFLICT(profile_id) DO UPDATE SET
                        payload = excluded.payload,
                        updated_at_epoch_ms = excluded.updated_at_epoch_ms
                    """,
                arguments: [profileID, try database.encoded(snapshot), now()]
            )
        }
    }

    private func cachedHome(profileID: String) throws -> HomeSnapshot? {
        try database.read { db in
            guard let row = try Row.fetchOne(db, sql: "SELECT payload FROM home_snapshots WHERE profile_id = ?", arguments: [profileID]) else {
                return nil
            }
            return try database.decoded(HomeSnapshot.self, from: row["payload"])
        }
    }

    private func cachedPlatforms(profileID: String) throws -> [PlatformDTO] {
        try database.read { db in
            let rows = try Row.fetchAll(db, sql: "SELECT payload FROM cached_platforms WHERE profile_id = ? ORDER BY platform_id", arguments: [profileID])
            return try rows.map { try database.decoded(PlatformDTO.self, from: $0["payload"]) }
        }
    }

    private func cachedCollections(profileID: String) throws -> [RommCollectionDTO] {
        try database.read { db in
            let rows = try Row.fetchAll(db, sql: "SELECT payload FROM cached_collections WHERE profile_id = ? ORDER BY updated_at_epoch_ms DESC", arguments: [profileID])
            return try rows.map { try database.decoded(RommCollectionDTO.self, from: $0["payload"]) }
        }
    }

    private func cachedRoms(profileID: String) throws -> [RomDTO] {
        try database.read { db in
            let rows = try Row.fetchAll(db, sql: "SELECT payload FROM cached_roms WHERE profile_id = ? ORDER BY updated_at_epoch_ms DESC", arguments: [profileID])
            return try rows.map { try database.decoded(RomDTO.self, from: $0["payload"]) }
        }
    }

    private func now() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
