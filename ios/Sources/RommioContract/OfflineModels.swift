import Foundation

public enum ConnectivityState: String, Codable, Sendable, CaseIterable {
    case online = "ONLINE"
    case offline = "OFFLINE"
}

public struct OfflineState: Codable, Hashable, Sendable {
    public var connectivity: ConnectivityState
    public var activeProfileID: String?
    public var catalogReady: Bool
    public var mediaReady: Bool
    public var isRefreshing: Bool
    public var lastFullSyncAtEpochMS: Int64?
    public var lastMediaSyncAtEpochMS: Int64?
    public var lastError: String?
    public var cacheBytes: Int64

    public init(
        connectivity: ConnectivityState,
        activeProfileID: String? = nil,
        catalogReady: Bool = false,
        mediaReady: Bool = false,
        isRefreshing: Bool = false,
        lastFullSyncAtEpochMS: Int64? = nil,
        lastMediaSyncAtEpochMS: Int64? = nil,
        lastError: String? = nil,
        cacheBytes: Int64 = 0
    ) {
        self.connectivity = connectivity
        self.activeProfileID = activeProfileID
        self.catalogReady = catalogReady
        self.mediaReady = mediaReady
        self.isRefreshing = isRefreshing
        self.lastFullSyncAtEpochMS = lastFullSyncAtEpochMS
        self.lastMediaSyncAtEpochMS = lastMediaSyncAtEpochMS
        self.lastError = lastError
        self.cacheBytes = cacheBytes
    }

    enum CodingKeys: String, CodingKey {
        case connectivity
        case activeProfileID = "active_profile_id"
        case catalogReady = "catalog_ready"
        case mediaReady = "media_ready"
        case isRefreshing = "is_refreshing"
        case lastFullSyncAtEpochMS = "last_full_sync_at_epoch_ms"
        case lastMediaSyncAtEpochMS = "last_media_sync_at_epoch_ms"
        case lastError = "last_error"
        case cacheBytes = "cache_bytes"
    }
}
