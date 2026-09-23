import Foundation

public struct PlatformControlProfileResolver: ControlProfileResolving {
    private let profiles: [PlatformControlProfile]

    public init(profiles: [PlatformControlProfile]? = nil) {
        self.profiles = profiles ?? Self.defaultProfiles
    }

    public func resolve(platformSlug: String) -> PlatformControlProfile {
        let normalized = platformSlug.lowercased()
        return profiles.first(where: { $0.platformSlugs.contains(normalized) }) ?? unsupportedProfile(
            familyID: normalized,
            displayName: normalized.replacingOccurrences(of: "-", with: " ").capitalized,
            platformSlugs: [normalized],
            message: "This platform is not enabled in the embedded player yet."
        )
    }

    public func supportedProfiles() -> [PlatformControlProfile] {
        profiles
    }

    public static let defaultProfiles: [PlatformControlProfile] = ExpansionControls.profiles + [
        touchProfile(
            familyID: "nes",
            displayName: "NES / Famicom",
            platformSlugs: ["nes", "famicom"],
            presets: [
                TouchLayoutPreset(
                    presetID: "standard",
                    displayName: "Standard",
                    elements: [
                        standardDpad(),
                        faceTwo(
                            primaryLabel: "A",
                            secondaryLabel: "B",
                            primaryKey: LibretroJoypadID.a,
                            secondaryKey: LibretroJoypadID.b
                        ),
                        standardStartSelect(),
                    ]
                ),
                TouchLayoutPreset(
                    presetID: "compact",
                    displayName: "Compact",
                    elements: [
                        standardDpad().copy(centerX: 0.16, centerY: 0.76, baseScale: 0.9),
                        faceTwo(
                            primaryLabel: "A",
                            secondaryLabel: "B",
                            primaryKey: LibretroJoypadID.a,
                            secondaryKey: LibretroJoypadID.b
                        ).copy(centerX: 0.84, centerY: 0.75, baseScale: 0.92),
                        standardStartSelect().copy(centerY: 0.86, baseScale: 0.82),
                    ]
                ),
            ]
        ),
        touchProfile(
            familyID: "snes",
            displayName: "SNES / Super Famicom",
            platformSlugs: ["snes", "sfam"],
            orientationPolicy: .landscapeOnly,
            presets: [
                TouchLayoutPreset(
                    presetID: "standard",
                    displayName: "Standard",
                    elements: [
                        standardDpad(),
                        faceFour(
                            left: ("Y", LibretroJoypadID.y),
                            bottom: ("B", LibretroJoypadID.b),
                            right: ("A", LibretroJoypadID.a),
                            top: ("X", LibretroJoypadID.x)
                        ),
                        standardShoulders(labels: ("L", "R")),
                        standardStartSelect(),
                    ]
                ),
            ]
        ),
        touchProfile(
            familyID: "gb",
            displayName: "Game Boy / Color",
            platformSlugs: ["gb", "gbc"],
            presets: [
                TouchLayoutPreset(
                    presetID: "standard",
                    displayName: "Standard",
                    elements: [
                        standardDpad(),
                        faceTwo(
                            primaryLabel: "A",
                            secondaryLabel: "B",
                            primaryKey: LibretroJoypadID.a,
                            secondaryKey: LibretroJoypadID.b
                        ),
                        standardStartSelect(),
                    ]
                ),
            ]
        ),
        touchProfile(
            familyID: "gba",
            displayName: "Game Boy Advance",
            platformSlugs: ["gba"],
            orientationPolicy: .landscapeOnly,
            presets: [
                TouchLayoutPreset(
                    presetID: "standard",
                    displayName: "Standard",
                    elements: [
                        standardDpad(),
                        faceTwo(
                            primaryLabel: "A",
                            secondaryLabel: "B",
                            primaryKey: LibretroJoypadID.a,
                            secondaryKey: LibretroJoypadID.b
                        ).copy(centerY: 0.70),
                        standardShoulders(labels: ("L", "R")),
                        standardStartSelect(),
                    ]
                ),
            ]
        ),

        controllerFirstProfile(
            familyID: "sega32x",
            displayName: "Sega 32X",
            platformSlugs: ["32x", "sega32x", "sega-32x"]
        ),

        touchProfile(
            familyID: "atari2600",
            displayName: "Atari 2600",
            platformSlugs: ["atari2600"],
            presets: [
                TouchLayoutPreset(
                    presetID: "standard",
                    displayName: "Standard",
                    elements: [
                        standardDpad(),
                        faceTwo(
                            primaryLabel: "Fire",
                            secondaryLabel: "Trigger",
                            primaryKey: LibretroJoypadID.b,
                            secondaryKey: LibretroJoypadID.a
                        ),
                        standardStartSelect(selectLabel: "Select", startLabel: "Reset"),
                        standardShoulders(labels: ("Difficulty A", "Difficulty B"), keyCodes: (LibretroJoypadID.l1, LibretroJoypadID.l2)),
                    ]
                ),
            ]
        ),
        touchProfile(
            familyID: "atari7800",
            displayName: "Atari 7800",
            platformSlugs: ["atari7800"],
            presets: [
                TouchLayoutPreset(
                    presetID: "standard",
                    displayName: "Standard",
                    elements: [
                        standardDpad(),
                        faceTwo(primaryLabel: "1", secondaryLabel: "2", primaryKey: LibretroJoypadID.b, secondaryKey: LibretroJoypadID.a),
                        standardStartSelect(selectLabel: "Select", startLabel: "Pause"),
                        standardShoulders(labels: ("Reset", "Difficulty"), keyCodes: (LibretroJoypadID.x, LibretroJoypadID.l1)),
                    ]
                ),
            ]
        ),
        touchProfile(
            familyID: "lynx",
            displayName: "Atari Lynx",
            platformSlugs: ["lynx"],
            presets: [
                TouchLayoutPreset(
                    presetID: "standard",
                    displayName: "Standard",
                    elements: [
                        standardDpad(),
                        faceTwo(primaryLabel: "A", secondaryLabel: "B", primaryKey: LibretroJoypadID.a, secondaryKey: LibretroJoypadID.b),
                        standardStartSelect(selectLabel: "Rotate", startLabel: "Pause"),
                        standardShoulders(labels: ("Option 1", "Option 2")),
                    ]
                ),
            ]
        ),
        touchProfile(
            familyID: "tg16",
            displayName: "TurboGrafx / Neo Geo Pocket / WonderSwan",
            platformSlugs: ["tg16", "neo-geo-pocket", "neo-geo-pocket-color", "wonderswan", "wonderswan-color"],
            presets: [
                TouchLayoutPreset(
                    presetID: "standard",
                    displayName: "Standard",
                    elements: [
                        standardDpad(),
                        faceTwo(
                            primaryLabel: "II",
                            secondaryLabel: "I",
                            primaryKey: LibretroJoypadID.a,
                            secondaryKey: LibretroJoypadID.b
                        ),
                        standardStartSelect(),
                    ]
                ),
            ]
        ),
        touchProfile(
            familyID: "arcade",
            displayName: "Arcade",
            platformSlugs: ["arcade"],
            presets: [
                TouchLayoutPreset(
                    presetID: "standard",
                    displayName: "Standard",
                    elements: [
                        standardDpad(),
                        faceFour(
                            left: ("1", LibretroJoypadID.y),
                            bottom: ("2", LibretroJoypadID.b),
                            right: ("3", LibretroJoypadID.a),
                            top: ("4", LibretroJoypadID.x)
                        ),
                        standardStartSelect(selectLabel: "Coin", startLabel: "Start"),
                    ]
                ),
            ]
        ),


        touchProfile(
            familyID: "nds",
            displayName: "Nintendo DS / DSi-enhanced",
            platformSlugs: ["nds", "dsi", "nintendo-dsi"],
            orientationPolicy: .portraitOnly,
            presets: [
                TouchLayoutPreset(
                    presetID: "portrait-handheld",
                    displayName: "Portrait handheld",
                    elements: [
                        standardDpad().copy(centerX: 0.19, centerY: 0.80),
                        faceFour(
                            left: ("Y", LibretroJoypadID.y),
                            bottom: ("B", LibretroJoypadID.b),
                            right: ("A", LibretroJoypadID.a),
                            top: ("X", LibretroJoypadID.x)
                        ).copy(centerX: 0.81, centerY: 0.79, baseScale: 0.94),
                        standardShoulders(labels: ("L", "R")).copy(centerY: 0.11, baseScale: 0.86),
                        standardStartSelect().copy(centerY: 0.72, baseScale: 0.82),
                    ]
                ),
            ]
        ),
        controllerFirstProfile(
            familyID: "dreamcast",
            displayName: "Dreamcast / NAOMI",
            platformSlugs: ["dreamcast", "naomi"]
        ),
        controllerFirstProfile(
            familyID: "3do",
            displayName: "3DO",
            platformSlugs: ["3do"]
        ),
        controllerFirstProfile(
            familyID: "virtualboy",
            displayName: "Virtual Boy",
            platformSlugs: ["virtualboy", "virtual-boy"]
        ),
        controllerFirstProfile(
            familyID: "dolphin",
            displayName: "GameCube / Wii",
            platformSlugs: ["gamecube", "ngc", "wii"]
        ),
        touchProfile(
            familyID: "3ds",
            displayName: "Nintendo 3DS",
            platformSlugs: ["3ds", "new-nintendo-3ds"],
            orientationPolicy: .portraitOnly,
            presets: [
                TouchLayoutPreset(
                    presetID: "portrait-handheld",
                    displayName: "Portrait handheld",
                    elements: [
                        standardDpad().copy(centerX: 0.19, centerY: 0.80),
                        faceFour(
                            left: ("Y", LibretroJoypadID.y),
                            bottom: ("B", LibretroJoypadID.b),
                            right: ("A", LibretroJoypadID.a),
                            top: ("X", LibretroJoypadID.x)
                        ).copy(centerX: 0.81, centerY: 0.79, baseScale: 0.94),
                        standardShoulders(labels: ("L", "R")).copy(centerY: 0.11, baseScale: 0.86),
                        standardStartSelect().copy(centerY: 0.72, baseScale: 0.82),
                    ]
                ),
            ]
        ),
        controllerFirstProfile(
            familyID: "dos",
            displayName: "DOS",
            platformSlugs: ["dos"],
            message: "DOS stays controller-first until a validated pointer and keyboard helper overlay exists."
        ),
    ]
}

private func touchProfile(
    familyID: String,
    displayName: String,
    platformSlugs: Set<String>,
    orientationPolicy: PlayerOrientationPolicy = .auto,
    presets: [TouchLayoutPreset]
) -> PlatformControlProfile {
    let preferredViewportAspectRatio: Double?
    switch familyID {
    case "nes", "snes", "sega16", "psx", "atari", "tg16", "arcade":
        preferredViewportAspectRatio = 4.0 / 3.0
    case "gb":
        preferredViewportAspectRatio = 10.0 / 9.0
    case "gba":
        preferredViewportAspectRatio = 3.0 / 2.0
    case "nds":
        preferredViewportAspectRatio = 2.0 / 3.0
    case "3ds":
        preferredViewportAspectRatio = 5.0 / 6.0
    default:
        preferredViewportAspectRatio = nil
    }

    return PlatformControlProfile(
        familyID: familyID,
        displayName: displayName,
        platformSlugs: platformSlugs,
        supportTier: .touchSupported,
        touchSupportMode: .full,
        playerOrientationPolicy: orientationPolicy,
        preferredViewportAspectRatio: preferredViewportAspectRatio,
        defaultPresetID: presets.first?.presetID,
        presets: presets
    )
}

private func controllerFirstProfile(
    familyID: String,
    displayName: String,
    platformSlugs: Set<String>,
    message: String = "Connect a controller for this platform. Touch support comes later after a platform-specific layout is validated."
) -> PlatformControlProfile {
    PlatformControlProfile(
        familyID: familyID,
        displayName: displayName,
        platformSlugs: platformSlugs,
        supportTier: .controllerSupported,
        touchSupportMode: .controllerFirst,
        controllerFallbackMessage: message
    )
}

private func unsupportedProfile(
    familyID: String,
    displayName: String,
    platformSlugs: Set<String>,
    message: String
) -> PlatformControlProfile {
    PlatformControlProfile(
        familyID: familyID,
        displayName: displayName,
        platformSlugs: platformSlugs,
        supportTier: .unsupported,
        touchSupportMode: .controllerFirst,
        controllerFallbackMessage: message
    )
}

private extension TouchElementSpec {
    func copy(
        centerX: Double? = nil,
        centerY: Double? = nil,
        baseScale: Double? = nil
    ) -> TouchElementSpec {
        TouchElementSpec(
            id: id,
            label: label,
            layoutKind: layoutKind,
            buttons: buttons,
            centerX: centerX ?? self.centerX,
            centerY: centerY ?? self.centerY,
            baseScale: baseScale ?? self.baseScale
        )
    }
}
