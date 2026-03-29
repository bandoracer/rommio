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
    let touch = platforms.filter { supportTier(for: $0, resolveProfile: resolveProfile) == .touchSupported }
    let controller = platforms.filter { supportTier(for: $0, resolveProfile: resolveProfile) == .controllerSupported }
    let unsupported = platforms.filter { supportTier(for: $0, resolveProfile: resolveProfile) == .unsupported }

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
    let touch = roms.filter { resolveProfile($0.platformSlug).supportTier == .touchSupported }
    let controller = roms.filter { resolveProfile($0.platformSlug).supportTier == .controllerSupported }
    let unsupported = roms.filter { resolveProfile($0.platformSlug).supportTier == .unsupported }

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
