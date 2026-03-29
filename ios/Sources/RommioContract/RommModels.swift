import Foundation

public struct UserDTO: Codable, Hashable, Sendable {
    public var id: Int
    public var username: String
    public var email: String
    public var role: String

    public init(id: Int, username: String, email: String, role: String) {
        self.id = id
        self.username = username
        self.email = email
        self.role = role
    }
}

public struct PlatformDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var slug: String
    public var name: String
    public var fsSlug: String
    public var urlLogo: String?
    public var romCount: Int

    public init(id: Int, slug: String, name: String, fsSlug: String, urlLogo: String? = nil, romCount: Int = 0) {
        self.id = id
        self.slug = slug
        self.name = name
        self.fsSlug = fsSlug
        self.urlLogo = urlLogo
        self.romCount = romCount
    }

    enum CodingKeys: String, CodingKey {
        case id
        case slug
        case name
        case fsSlug = "fs_slug"
        case urlLogo = "url_logo"
        case romCount = "rom_count"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int.self, forKey: .id)
        slug = try container.decode(String.self, forKey: .slug)
        name = try container.decode(String.self, forKey: .name)
        fsSlug = try container.decode(String.self, forKey: .fsSlug)
        urlLogo = try container.decodeIfPresent(String.self, forKey: .urlLogo)
        romCount = try container.decode(Int.self, forKey: .romCount, default: 0)
    }
}

public struct ItemsResponse<T: Codable & Hashable & Sendable>: Codable, Hashable, Sendable {
    public var items: [T]
    public var total: Int?
    public var page: Int?
    public var perPage: Int?

    public init(items: [T], total: Int? = nil, page: Int? = nil, perPage: Int? = nil) {
        self.items = items
        self.total = total
        self.page = page
        self.perPage = perPage
    }

    enum CodingKeys: String, CodingKey {
        case items
        case total
        case page
        case perPage = "per_page"
    }
}

public struct RomFileDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var romID: Int
    public var fileName: String
    public var fileExtension: String
    public var fileSizeBytes: Int64

    public init(id: Int, romID: Int, fileName: String, fileExtension: String = "", fileSizeBytes: Int64) {
        self.id = id
        self.romID = romID
        self.fileName = fileName
        self.fileExtension = fileExtension
        self.fileSizeBytes = fileSizeBytes
    }

    public var effectiveFileExtension: String {
        if fileExtension.isEmpty {
            return fileName.split(separator: ".").last.map(String.init) ?? ""
        }
        return fileExtension
    }

    enum CodingKeys: String, CodingKey {
        case id
        case romID = "rom_id"
        case fileName = "file_name"
        case fileExtension = "file_extension"
        case fileSizeBytes = "file_size_bytes"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int.self, forKey: .id)
        romID = try container.decode(Int.self, forKey: .romID)
        fileName = try container.decode(String.self, forKey: .fileName)
        fileExtension = try container.decode(String.self, forKey: .fileExtension, default: "")
        fileSizeBytes = try container.decode(Int64.self, forKey: .fileSizeBytes)
    }
}

public struct RomSiblingDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var name: String?

    public init(id: Int, name: String? = nil) {
        self.id = id
        self.name = name
    }

    enum CodingKeys: String, CodingKey {
        case id
        case name
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int.self, forKey: .id)
        name = try container.decodeIfPresent(String.self, forKey: .name)
    }
}

public struct RomDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var name: String?
    public var summary: String?
    public var platformID: Int
    public var platformName: String
    public var platformSlug: String
    public var fsName: String
    public var files: [RomFileDTO]
    public var siblings: [RomSiblingDTO]?
    public var urlCover: String?

    public init(
        id: Int,
        name: String? = nil,
        summary: String? = nil,
        platformID: Int = 0,
        platformName: String = "",
        platformSlug: String = "",
        fsName: String = "",
        files: [RomFileDTO] = [],
        siblings: [RomSiblingDTO]? = nil,
        urlCover: String? = nil
    ) {
        self.id = id
        self.name = name
        self.summary = summary
        self.platformID = platformID
        self.platformName = platformName
        self.platformSlug = platformSlug
        self.fsName = fsName
        self.files = files
        self.siblings = siblings
        self.urlCover = urlCover
    }

    public var displayName: String { name ?? fsName }

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case summary
        case platformID = "platform_id"
        case platformName = "platform_name"
        case platformSlug = "platform_slug"
        case fsName = "fs_name"
        case files
        case siblings
        case urlCover = "url_cover"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int.self, forKey: .id)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        summary = try container.decodeIfPresent(String.self, forKey: .summary)
        platformID = try container.decode(Int.self, forKey: .platformID, default: 0)
        platformName = try container.decode(String.self, forKey: .platformName, default: "")
        platformSlug = try container.decode(String.self, forKey: .platformSlug, default: "")
        fsName = try container.decode(String.self, forKey: .fsName, default: "")
        files = try container.decode([RomFileDTO].self, forKey: .files, default: [])
        siblings = try container.decodeIfPresent([RomSiblingDTO].self, forKey: .siblings)
        urlCover = try container.decodeIfPresent(String.self, forKey: .urlCover)
    }
}

public struct HeartbeatDTO: Codable, Hashable, Sendable {
    public var system: HeartbeatSystemDTO

    public init(system: HeartbeatSystemDTO) {
        self.system = system
    }

    enum CodingKeys: String, CodingKey {
        case system = "SYSTEM"
    }
}

public struct HeartbeatSystemDTO: Codable, Hashable, Sendable {
    public var version: String
    public var showSetupWizard: Bool

    public init(version: String, showSetupWizard: Bool) {
        self.version = version
        self.showSetupWizard = showSetupWizard
    }

    enum CodingKeys: String, CodingKey {
        case version = "VERSION"
        case showSetupWizard = "SHOW_SETUP_WIZARD"
    }
}

public struct BaseAssetDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var romID: Int
    public var fileName: String
    public var fileSizeBytes: Int64
    public var downloadPath: String
    public var updatedAt: String

    public init(id: Int, romID: Int, fileName: String, fileSizeBytes: Int64, downloadPath: String, updatedAt: String) {
        self.id = id
        self.romID = romID
        self.fileName = fileName
        self.fileSizeBytes = fileSizeBytes
        self.downloadPath = downloadPath
        self.updatedAt = updatedAt
    }

    enum CodingKeys: String, CodingKey {
        case id
        case romID = "rom_id"
        case fileName = "file_name"
        case fileSizeBytes = "file_size_bytes"
        case downloadPath = "download_path"
        case updatedAt = "updated_at"
    }
}

public struct SaveDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var romID: Int
    public var fileName: String
    public var fileSizeBytes: Int64
    public var downloadPath: String
    public var updatedAt: String
    public var emulator: String?

    public init(id: Int, romID: Int, fileName: String, fileSizeBytes: Int64, downloadPath: String, updatedAt: String, emulator: String? = nil) {
        self.id = id
        self.romID = romID
        self.fileName = fileName
        self.fileSizeBytes = fileSizeBytes
        self.downloadPath = downloadPath
        self.updatedAt = updatedAt
        self.emulator = emulator
    }

    enum CodingKeys: String, CodingKey {
        case id
        case romID = "rom_id"
        case fileName = "file_name"
        case fileSizeBytes = "file_size_bytes"
        case downloadPath = "download_path"
        case updatedAt = "updated_at"
        case emulator
    }
}

public struct StateDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var romID: Int
    public var fileName: String
    public var fileSizeBytes: Int64
    public var downloadPath: String
    public var updatedAt: String
    public var emulator: String?

    public init(id: Int, romID: Int, fileName: String, fileSizeBytes: Int64, downloadPath: String, updatedAt: String, emulator: String? = nil) {
        self.id = id
        self.romID = romID
        self.fileName = fileName
        self.fileSizeBytes = fileSizeBytes
        self.downloadPath = downloadPath
        self.updatedAt = updatedAt
        self.emulator = emulator
    }

    enum CodingKeys: String, CodingKey {
        case id
        case romID = "rom_id"
        case fileName = "file_name"
        case fileSizeBytes = "file_size_bytes"
        case downloadPath = "download_path"
        case updatedAt = "updated_at"
        case emulator
    }
}

public enum CollectionKind: String, Codable, Sendable, CaseIterable {
    case regular = "REGULAR"
    case smart = "SMART"
    case virtual = "VIRTUAL"
}

public struct RommCollectionDTO: Codable, Hashable, Sendable, Identifiable {
    public var kind: CollectionKind
    public var id: String
    public var name: String
    public var description: String
    public var romIDs: Set<Int>
    public var romCount: Int
    public var pathCoverSmall: String?
    public var pathCoverLarge: String?
    public var pathCoversSmall: [String]
    public var pathCoversLarge: [String]
    public var isPublic: Bool
    public var isFavorite: Bool
    public var isVirtual: Bool
    public var isSmart: Bool
    public var ownerUsername: String?

    public init(
        kind: CollectionKind,
        id: String,
        name: String,
        description: String = "",
        romIDs: Set<Int> = [],
        romCount: Int = 0,
        pathCoverSmall: String? = nil,
        pathCoverLarge: String? = nil,
        pathCoversSmall: [String] = [],
        pathCoversLarge: [String] = [],
        isPublic: Bool = false,
        isFavorite: Bool = false,
        isVirtual: Bool = false,
        isSmart: Bool = false,
        ownerUsername: String? = nil
    ) {
        self.kind = kind
        self.id = id
        self.name = name
        self.description = description
        self.romIDs = romIDs
        self.romCount = romCount
        self.pathCoverSmall = pathCoverSmall
        self.pathCoverLarge = pathCoverLarge
        self.pathCoversSmall = pathCoversSmall
        self.pathCoversLarge = pathCoversLarge
        self.isPublic = isPublic
        self.isFavorite = isFavorite
        self.isVirtual = isVirtual
        self.isSmart = isSmart
        self.ownerUsername = ownerUsername
    }
}

public struct CollectionResponseDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var name: String
    public var description: String
    public var romIDs: Set<Int>
    public var romCount: Int
    public var pathCoverSmall: String?
    public var pathCoverLarge: String?
    public var pathCoversSmall: [String]
    public var pathCoversLarge: [String]
    public var isPublic: Bool
    public var isFavorite: Bool
    public var ownerUsername: String?

    public init(
        id: Int,
        name: String,
        description: String = "",
        romIDs: Set<Int> = [],
        romCount: Int = 0,
        pathCoverSmall: String? = nil,
        pathCoverLarge: String? = nil,
        pathCoversSmall: [String] = [],
        pathCoversLarge: [String] = [],
        isPublic: Bool = false,
        isFavorite: Bool = false,
        ownerUsername: String? = nil
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.romIDs = romIDs
        self.romCount = romCount
        self.pathCoverSmall = pathCoverSmall
        self.pathCoverLarge = pathCoverLarge
        self.pathCoversSmall = pathCoversSmall
        self.pathCoversLarge = pathCoversLarge
        self.isPublic = isPublic
        self.isFavorite = isFavorite
        self.ownerUsername = ownerUsername
    }

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case description
        case romIDs = "rom_ids"
        case romCount = "rom_count"
        case pathCoverSmall = "path_cover_small"
        case pathCoverLarge = "path_cover_large"
        case pathCoversSmall = "path_covers_small"
        case pathCoversLarge = "path_covers_large"
        case isPublic = "is_public"
        case isFavorite = "is_favorite"
        case ownerUsername = "owner_username"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        description = try container.decode(String.self, forKey: .description, default: "")
        romIDs = try container.decode(Set<Int>.self, forKey: .romIDs, default: [])
        romCount = try container.decode(Int.self, forKey: .romCount, default: 0)
        pathCoverSmall = try container.decodeIfPresent(String.self, forKey: .pathCoverSmall)
        pathCoverLarge = try container.decodeIfPresent(String.self, forKey: .pathCoverLarge)
        pathCoversSmall = try container.decode([String].self, forKey: .pathCoversSmall, default: [])
        pathCoversLarge = try container.decode([String].self, forKey: .pathCoversLarge, default: [])
        isPublic = try container.decode(Bool.self, forKey: .isPublic, default: false)
        isFavorite = try container.decode(Bool.self, forKey: .isFavorite, default: false)
        ownerUsername = try container.decodeIfPresent(String.self, forKey: .ownerUsername)
    }
}

public struct SmartCollectionResponseDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var name: String
    public var description: String
    public var romIDs: Set<Int>
    public var romCount: Int
    public var pathCoverSmall: String?
    public var pathCoverLarge: String?
    public var pathCoversSmall: [String]
    public var pathCoversLarge: [String]
    public var isPublic: Bool
    public var ownerUsername: String?
    public var filterSummary: String?

    public init(
        id: Int,
        name: String,
        description: String = "",
        romIDs: Set<Int> = [],
        romCount: Int = 0,
        pathCoverSmall: String? = nil,
        pathCoverLarge: String? = nil,
        pathCoversSmall: [String] = [],
        pathCoversLarge: [String] = [],
        isPublic: Bool = false,
        ownerUsername: String? = nil,
        filterSummary: String? = nil
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.romIDs = romIDs
        self.romCount = romCount
        self.pathCoverSmall = pathCoverSmall
        self.pathCoverLarge = pathCoverLarge
        self.pathCoversSmall = pathCoversSmall
        self.pathCoversLarge = pathCoversLarge
        self.isPublic = isPublic
        self.ownerUsername = ownerUsername
        self.filterSummary = filterSummary
    }

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case description
        case romIDs = "rom_ids"
        case romCount = "rom_count"
        case pathCoverSmall = "path_cover_small"
        case pathCoverLarge = "path_cover_large"
        case pathCoversSmall = "path_covers_small"
        case pathCoversLarge = "path_covers_large"
        case isPublic = "is_public"
        case ownerUsername = "owner_username"
        case filterSummary = "filter_summary"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        description = try container.decode(String.self, forKey: .description, default: "")
        romIDs = try container.decode(Set<Int>.self, forKey: .romIDs, default: [])
        romCount = try container.decode(Int.self, forKey: .romCount, default: 0)
        pathCoverSmall = try container.decodeIfPresent(String.self, forKey: .pathCoverSmall)
        pathCoverLarge = try container.decodeIfPresent(String.self, forKey: .pathCoverLarge)
        pathCoversSmall = try container.decode([String].self, forKey: .pathCoversSmall, default: [])
        pathCoversLarge = try container.decode([String].self, forKey: .pathCoversLarge, default: [])
        isPublic = try container.decode(Bool.self, forKey: .isPublic, default: false)
        ownerUsername = try container.decodeIfPresent(String.self, forKey: .ownerUsername)
        filterSummary = try container.decodeIfPresent(String.self, forKey: .filterSummary)
    }
}

public struct VirtualCollectionResponseDTO: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var type: String
    public var name: String
    public var description: String
    public var romIDs: Set<Int>
    public var romCount: Int
    public var pathCoverSmall: String?
    public var pathCoverLarge: String?
    public var pathCoversSmall: [String]
    public var pathCoversLarge: [String]

    public init(
        id: String,
        type: String,
        name: String,
        description: String = "",
        romIDs: Set<Int> = [],
        romCount: Int = 0,
        pathCoverSmall: String? = nil,
        pathCoverLarge: String? = nil,
        pathCoversSmall: [String] = [],
        pathCoversLarge: [String] = []
    ) {
        self.id = id
        self.type = type
        self.name = name
        self.description = description
        self.romIDs = romIDs
        self.romCount = romCount
        self.pathCoverSmall = pathCoverSmall
        self.pathCoverLarge = pathCoverLarge
        self.pathCoversSmall = pathCoversSmall
        self.pathCoversLarge = pathCoversLarge
    }

    enum CodingKeys: String, CodingKey {
        case id
        case type
        case name
        case description
        case romIDs = "rom_ids"
        case romCount = "rom_count"
        case pathCoverSmall = "path_cover_small"
        case pathCoverLarge = "path_cover_large"
        case pathCoversSmall = "path_covers_small"
        case pathCoversLarge = "path_covers_large"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        type = try container.decode(String.self, forKey: .type)
        name = try container.decode(String.self, forKey: .name)
        description = try container.decode(String.self, forKey: .description, default: "")
        romIDs = try container.decode(Set<Int>.self, forKey: .romIDs, default: [])
        romCount = try container.decode(Int.self, forKey: .romCount, default: 0)
        pathCoverSmall = try container.decodeIfPresent(String.self, forKey: .pathCoverSmall)
        pathCoverLarge = try container.decodeIfPresent(String.self, forKey: .pathCoverLarge)
        pathCoversSmall = try container.decode([String].self, forKey: .pathCoversSmall, default: [])
        pathCoversLarge = try container.decode([String].self, forKey: .pathCoversLarge, default: [])
    }
}

private extension KeyedDecodingContainer {
    func decode<T: Decodable>(_ type: T.Type, forKey key: Key, default defaultValue: T) throws -> T {
        try decodeIfPresent(type, forKey: key) ?? defaultValue
    }
}

public enum DownloadStatus: String, Codable, Sendable, CaseIterable {
    case queued = "QUEUED"
    case running = "RUNNING"
    case failed = "FAILED"
    case canceled = "CANCELED"
    case completed = "COMPLETED"
}

public struct InstalledROMReference: Codable, Hashable, Sendable {
    public var romID: Int
    public var fileID: Int
    public var platformSlug: String
    public var romName: String
    public var fileName: String

    public init(romID: Int, fileID: Int, platformSlug: String, romName: String, fileName: String) {
        self.romID = romID
        self.fileID = fileID
        self.platformSlug = platformSlug
        self.romName = romName
        self.fileName = fileName
    }
}

public struct SyncSummary: Codable, Hashable, Sendable {
    public var uploaded: Int
    public var downloaded: Int
    public var notes: [String]

    public init(uploaded: Int = 0, downloaded: Int = 0, notes: [String] = []) {
        self.uploaded = uploaded
        self.downloaded = downloaded
        self.notes = notes
    }
}

public extension CollectionResponseDTO {
    func asDomain() -> RommCollectionDTO {
        RommCollectionDTO(
            kind: .regular,
            id: String(id),
            name: name,
            description: description,
            romIDs: romIDs,
            romCount: romCount,
            pathCoverSmall: pathCoverSmall,
            pathCoverLarge: pathCoverLarge,
            pathCoversSmall: pathCoversSmall,
            pathCoversLarge: pathCoversLarge,
            isPublic: isPublic,
            isFavorite: isFavorite,
            isVirtual: false,
            isSmart: false,
            ownerUsername: ownerUsername
        )
    }
}

public extension SmartCollectionResponseDTO {
    func asDomain() -> RommCollectionDTO {
        RommCollectionDTO(
            kind: .smart,
            id: String(id),
            name: name,
            description: description.isEmpty ? (filterSummary ?? "") : description,
            romIDs: romIDs,
            romCount: romCount,
            pathCoverSmall: pathCoverSmall,
            pathCoverLarge: pathCoverLarge,
            pathCoversSmall: pathCoversSmall,
            pathCoversLarge: pathCoversLarge,
            isPublic: isPublic,
            isFavorite: false,
            isVirtual: false,
            isSmart: true,
            ownerUsername: ownerUsername
        )
    }
}

public extension VirtualCollectionResponseDTO {
    func asDomain() -> RommCollectionDTO {
        RommCollectionDTO(
            kind: .virtual,
            id: id,
            name: name,
            description: description,
            romIDs: romIDs,
            romCount: romCount,
            pathCoverSmall: pathCoverSmall,
            pathCoverLarge: pathCoverLarge,
            pathCoversSmall: pathCoversSmall,
            pathCoversLarge: pathCoversLarge,
            isPublic: false,
            isFavorite: false,
            isVirtual: true,
            isSmart: false,
            ownerUsername: nil
        )
    }
}
