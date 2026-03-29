import Foundation

public struct RuntimeMatrixManifest: Codable, Hashable, Sendable {
    public var families: [RuntimeMatrixManifestFamily]

    public init(families: [RuntimeMatrixManifestFamily]) {
        self.families = families
    }
}

public struct RuntimeMatrixManifestFamily: Codable, Hashable, Sendable {
    public var familyID: String
    public var displayName: String
    public var platformSlugs: [String]
    public var supportTier: String
    public var defaultRuntimeID: String
    public var ios: RuntimeMatrixManifestIOSRuntime

    enum CodingKeys: String, CodingKey {
        case familyID = "family_id"
        case displayName = "display_name"
        case platformSlugs = "platform_slugs"
        case supportTier = "support_tier"
        case defaultRuntimeID = "default_runtime_id"
        case ios
    }

    public init(
        familyID: String,
        displayName: String,
        platformSlugs: [String],
        supportTier: String,
        defaultRuntimeID: String,
        ios: RuntimeMatrixManifestIOSRuntime
    ) {
        self.familyID = familyID
        self.displayName = displayName
        self.platformSlugs = platformSlugs
        self.supportTier = supportTier
        self.defaultRuntimeID = defaultRuntimeID
        self.ios = ios
    }
}

public struct RuntimeMatrixManifestIOSRuntime: Codable, Hashable, Sendable {
    public var renderBackend: IOSRenderBackend
    public var interactionProfile: PlayerInteractionProfile
    public var bundleRelativePath: String
    public var supportedExtensions: [String]
    public var requiredBIOSFiles: [String]
    public var validationState: IOSRuntimeValidationState
    public var validationBlockReason: String?

    enum CodingKeys: String, CodingKey {
        case renderBackend = "render_backend"
        case interactionProfile = "interaction_profile"
        case bundleRelativePath = "bundle_relative_path"
        case supportedExtensions = "supported_extensions"
        case requiredBIOSFiles = "required_bios_files"
        case validationState = "validation_state"
        case validationBlockReason = "validation_block_reason"
    }

    public init(
        renderBackend: IOSRenderBackend,
        interactionProfile: PlayerInteractionProfile,
        bundleRelativePath: String,
        supportedExtensions: [String],
        requiredBIOSFiles: [String],
        validationState: IOSRuntimeValidationState,
        validationBlockReason: String? = nil
    ) {
        self.renderBackend = renderBackend
        self.interactionProfile = interactionProfile
        self.bundleRelativePath = bundleRelativePath
        self.supportedExtensions = supportedExtensions
        self.requiredBIOSFiles = requiredBIOSFiles
        self.validationState = validationState
        self.validationBlockReason = validationBlockReason
    }
}
