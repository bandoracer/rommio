import Foundation

public struct RomQuery: Hashable, Sendable {
    public var platformIDs: Int?
    public var legacyPlatformID: Int?
    public var collectionID: Int?
    public var smartCollectionID: Int?
    public var virtualCollectionID: String?
    public var lastPlayed: Bool?
    public var limit: Int
    public var offset: Int
    public var groupByMetaID: Int
    public var orderBy: String?
    public var orderDirection: String?

    public init(
        platformIDs: Int? = nil,
        legacyPlatformID: Int? = nil,
        collectionID: Int? = nil,
        smartCollectionID: Int? = nil,
        virtualCollectionID: String? = nil,
        lastPlayed: Bool? = nil,
        limit: Int = 50,
        offset: Int = 0,
        groupByMetaID: Int = 1,
        orderBy: String? = nil,
        orderDirection: String? = nil
    ) {
        self.platformIDs = platformIDs
        self.legacyPlatformID = legacyPlatformID
        self.collectionID = collectionID
        self.smartCollectionID = smartCollectionID
        self.virtualCollectionID = virtualCollectionID
        self.lastPlayed = lastPlayed
        self.limit = limit
        self.offset = offset
        self.groupByMetaID = groupByMetaID
        self.orderBy = orderBy
        self.orderDirection = orderDirection
    }
}

public enum RommEndpoint: Hashable, Sendable {
    case currentUser
    case heartbeat
    case platforms
    case recentlyAdded
    case roms(RomQuery = RomQuery())
    case rom(id: Int)
    case collections
    case smartCollections
    case virtualCollections(type: String = "all", limit: Int? = nil)
    case saves(romID: Int, deviceID: String? = nil)
    case saveContent(saveID: Int, deviceID: String? = nil)
    case states(romID: Int)
    case registerDevice
    case uploadSave(romID: Int, emulator: String?, slot: String?, deviceID: String?, overwrite: Bool?)
    case uploadState(romID: Int, emulator: String?)
    case markSaveDownloaded(url: URL)

    public var method: String {
        switch self {
        case .currentUser, .heartbeat, .platforms, .recentlyAdded, .roms, .rom, .collections, .smartCollections, .virtualCollections, .saves, .saveContent, .states:
            return "GET"
        case .registerDevice, .uploadSave, .uploadState, .markSaveDownloaded:
            return "POST"
        }
    }

    public func url(baseURL: URL) throws -> URL {
        switch self {
        case .currentUser:
            return try pathURL(baseURL: baseURL, path: "api/users/me")
        case .heartbeat:
            return try pathURL(baseURL: baseURL, path: "api/heartbeat")
        case .platforms:
            return try pathURL(baseURL: baseURL, path: "api/platforms")
        case .recentlyAdded:
            return try components(baseURL: baseURL, path: "api/roms", queryItems: [
                .init(name: "order_by", value: "id"),
                .init(name: "order_dir", value: "desc"),
                .init(name: "limit", value: "15"),
                .init(name: "group_by_meta_id", value: "1"),
            ])
        case let .roms(query):
            let items = compact([
                optionalItem("platform_ids", query.platformIDs),
                optionalItem("platform_id", query.legacyPlatformID),
                optionalItem("collection_id", query.collectionID),
                optionalItem("smart_collection_id", query.smartCollectionID),
                optionalItem("virtual_collection_id", query.virtualCollectionID),
                optionalItem("last_played", query.lastPlayed.map { $0 ? "true" : "false" }),
                .init(name: "limit", value: String(query.limit)),
                .init(name: "offset", value: String(query.offset)),
                .init(name: "group_by_meta_id", value: String(query.groupByMetaID)),
                optionalItem("order_by", query.orderBy),
                optionalItem("order_dir", query.orderDirection),
            ])
            return try components(baseURL: baseURL, path: "api/roms", queryItems: items)
        case let .rom(id):
            return try pathURL(baseURL: baseURL, path: "api/roms/\(id)")
        case .collections:
            return try pathURL(baseURL: baseURL, path: "api/collections")
        case .smartCollections:
            return try pathURL(baseURL: baseURL, path: "api/collections/smart")
        case let .virtualCollections(type, limit):
            return try components(baseURL: baseURL, path: "api/collections/virtual", queryItems: compact([
                .init(name: "type", value: type),
                optionalItem("limit", limit),
            ]))
        case let .saves(romID, deviceID):
            return try components(baseURL: baseURL, path: "api/saves", queryItems: compact([
                .init(name: "rom_id", value: String(romID)),
                optionalItem("device_id", deviceID),
            ]))
        case let .saveContent(saveID, deviceID):
            return try components(baseURL: baseURL, path: "api/saves/\(saveID)/content", queryItems: compact([
                .init(name: "optimistic", value: "false"),
                optionalItem("device_id", deviceID),
            ]))
        case let .states(romID):
            return try components(baseURL: baseURL, path: "api/states", queryItems: [
                .init(name: "rom_id", value: String(romID)),
            ])
        case .registerDevice:
            return try pathURL(baseURL: baseURL, path: "api/devices")
        case let .uploadSave(romID, emulator, slot, deviceID, overwrite):
            return try components(baseURL: baseURL, path: "api/saves", queryItems: compact([
                .init(name: "rom_id", value: String(romID)),
                optionalItem("emulator", emulator),
                optionalItem("slot", slot),
                optionalItem("device_id", deviceID),
                optionalItem("overwrite", overwrite.map { $0 ? "true" : "false" }),
            ]))
        case let .uploadState(romID, emulator):
            return try components(baseURL: baseURL, path: "api/states", queryItems: compact([
                .init(name: "rom_id", value: String(romID)),
                optionalItem("emulator", emulator),
            ]))
        case let .markSaveDownloaded(url):
            return url
        }
    }

    private func pathURL(baseURL: URL, path: String) throws -> URL {
        let url = baseURL.appending(path: path)
        guard url.scheme != nil else {
            throw URLError(.badURL)
        }
        return url
    }

    private func components(baseURL: URL, path: String, queryItems: [URLQueryItem]) throws -> URL {
        var components = URLComponents(url: try pathURL(baseURL: baseURL, path: path), resolvingAgainstBaseURL: false)
        components?.queryItems = queryItems
        guard let url = components?.url else {
            throw URLError(.badURL)
        }
        return url
    }

    private func optionalItem(_ name: String, _ value: String?) -> URLQueryItem? {
        guard let value, !value.isEmpty else { return nil }
        return URLQueryItem(name: name, value: value)
    }

    private func optionalItem(_ name: String, _ value: Int?) -> URLQueryItem? {
        guard let value else { return nil }
        return URLQueryItem(name: name, value: String(value))
    }

    private func compact(_ items: [URLQueryItem?]) -> [URLQueryItem] {
        items.compactMap { $0 }
    }
}
