import Foundation
import RommioContract
import RommioFoundation

struct PlatformSupportSection: Identifiable, Hashable {
    let supportTier: EmbeddedSupportTier
    let title: String
    let supportingText: String
    let platforms: [PlatformDTO]

    var id: String { supportTier.rawValue }
}

struct RomSupportSection: Identifiable, Hashable {
    enum Context: Hashable {
        case platformDetail
        case collectionDetail
    }

    let supportTier: EmbeddedSupportTier
    let title: String
    let supportingText: String
    let roms: [RomDTO]

    var id: String { supportTier.rawValue }
}

struct CollectionTypeSection: Identifiable, Hashable {
    let kind: CollectionKind
    let title: String
    let supportingText: String
    let collections: [RommCollectionDTO]

    var id: String { kind.rawValue }
}


func groupedPlatformSections(
    platforms: [PlatformDTO],
    resolveProfile: (String) -> PlatformControlProfile
) -> [PlatformSupportSection] {
    // Resolving a profile inspects the platform's cores on disk: once per platform, not per filter.
    var profiles: [String: PlatformControlProfile] = [:]
    func resolve(_ slug: String) -> PlatformControlProfile {
        if let hit = profiles[slug] { return hit }
        let profile = resolveProfile(slug)
        profiles[slug] = profile
        return profile
    }
    let tiers = platforms.map { supportTier(for: $0, resolveProfile: resolve) }
    let touch = zip(platforms, tiers).filter { $0.1 == .touchSupported }.map(\.0)
    let controller = zip(platforms, tiers).filter { $0.1 == .controllerSupported }.map(\.0)
    let unsupported = zip(platforms, tiers).filter { $0.1 == .unsupported }.map(\.0)

    return [
        PlatformSupportSection(
            supportTier: .touchSupported,
            title: "Touch-ready in app",
            supportingText: "These platform families have an embedded runtime and a validated mobile control layout.",
            platforms: touch
        ),
        PlatformSupportSection(
            supportTier: .controllerSupported,
            title: "Controller play in app",
            supportingText: "These platform families have an embedded runtime, but Rommio treats them as controller-first.",
            platforms: controller
        ),
        PlatformSupportSection(
            supportTier: .unsupported,
            title: "Not supported in app yet",
            supportingText: "These platform families stay browsable through RomM metadata, but the embedded player is not enabled for them.",
            platforms: unsupported
        ),
    ].filter { !$0.platforms.isEmpty }
}

func groupedRomSections(
    roms: [RomDTO],
    context: RomSupportSection.Context,
    resolveProfile: (String) -> PlatformControlProfile
) -> [RomSupportSection] {
    // One profile per platform slug, not three per game: resolving inspects cores on disk.
    var profiles: [String: PlatformControlProfile] = [:]
    func resolve(_ slug: String) -> PlatformControlProfile {
        if let hit = profiles[slug] { return hit }
        let profile = resolveProfile(slug)
        profiles[slug] = profile
        return profile
    }
    let tiers = roms.map { resolve($0.platformSlug).supportTier }
    let touch = zip(roms, tiers).filter { $0.1 == .touchSupported }.map(\.0)
    let controller = zip(roms, tiers).filter { $0.1 == .controllerSupported }.map(\.0)
    let unsupported = zip(roms, tiers).filter { $0.1 == .unsupported }.map(\.0)

    let touchText: String
    let controllerText: String
    let unsupportedText: String

    switch context {
    case .platformDetail:
        touchText = "These games already have embedded runtime support and a dedicated mobile control layout."
        controllerText = "These games are playable in the embedded player, but Rommio treats them as controller-first."
        unsupportedText = "These games stay browsable and downloadable, but no playable in-app path is presented."
    case .collectionDetail:
        touchText = "These entries have embedded runtime support and a validated mobile control layout."
        controllerText = "These entries are playable in the embedded player, but they remain controller-first."
        unsupportedText = "These collection entries stay visible for browsing and download, but they are not exposed as playable in-app."
    }

    return [
        RomSupportSection(
            supportTier: .touchSupported,
            title: "Touch-ready in app",
            supportingText: touchText,
            roms: touch
        ),
        RomSupportSection(
            supportTier: .controllerSupported,
            title: "Controller play in app",
            supportingText: controllerText,
            roms: controller
        ),
        RomSupportSection(
            supportTier: .unsupported,
            title: "Not supported in app yet",
            supportingText: unsupportedText,
            roms: unsupported
        ),
    ].filter { !$0.roms.isEmpty }
}

func groupedCollectionSections(collections: [RommCollectionDTO]) -> [CollectionTypeSection] {
    let regular = collections.filter { $0.kind == .regular }
    let smart = collections.filter { $0.kind == .smart }
    let virtual = collections.filter { $0.kind == .virtual }

    return [
        CollectionTypeSection(
            kind: .regular,
            title: "My collections",
            supportingText: "Manual collections created and managed in RomM.",
            collections: regular
        ),
        CollectionTypeSection(
            kind: .smart,
            title: "Smart collections",
            supportingText: "Rule-based groups maintained by RomM filters.",
            collections: smart
        ),
        CollectionTypeSection(
            kind: .virtual,
            title: "Autogenerated",
            supportingText: "Virtual collections generated by the server.",
            collections: virtual
        ),
    ].filter { !$0.collections.isEmpty }
}

private func supportTier(
    for platform: PlatformDTO,
    resolveProfile: (String) -> PlatformControlProfile
) -> EmbeddedSupportTier {
    let primary = resolveProfile(platform.slug)
    if primary.supportTier != .unsupported {
        return primary.supportTier
    }
    return resolveProfile(platform.fsSlug).supportTier
}
