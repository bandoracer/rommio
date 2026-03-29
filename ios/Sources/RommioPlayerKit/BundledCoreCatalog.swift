import Foundation
import RommioContract
import RommioFoundation

public protocol CoreCatalog: Sendable {
    func allFamilies() -> [IOSRuntimeFamily]
    func family(for platformSlug: String) -> IOSRuntimeFamily?
    func resolve(rom: RomDTO, file: RomFileDTO, libraryStore: LibraryStore, bundle: Bundle) -> CoreResolution
}

public struct BundledCoreCatalog: CoreCatalog, Sendable {
    public let families: [IOSRuntimeFamily]
    public let installer: any CoreInstalling

    public init(
        families: [IOSRuntimeFamily] = IOSRuntimeMatrix.fullParityFamilies,
        installer: any CoreInstalling = BundledCoreInstaller()
    ) {
        self.families = families
        self.installer = installer
    }

    public func allFamilies() -> [IOSRuntimeFamily] {
        families
    }

    public func family(for platformSlug: String) -> IOSRuntimeFamily? {
        families.first { $0.platformSlugs.contains(platformSlug.lowercased()) }
    }

    public func resolve(
        rom: RomDTO,
        file: RomFileDTO,
        libraryStore: LibraryStore,
        bundle: Bundle = .main
    ) -> CoreResolution {
        guard let family = family(for: rom.platformSlug) else {
            return CoreResolution(
                capability: .unsupported,
                provisioningStatus: .unsupported,
                availabilityStatus: .unsupportedByCurrentBuild,
                message: "No iOS runtime family is defined for \(rom.platformName)."
            )
        }

        let fileExtension = file.effectiveFileExtension.lowercased()
        let runtime = family.runtimeOptions.first {
            $0.platformSlugs.contains(rom.platformSlug.lowercased()) &&
                ($0.supportedExtensions.isEmpty || $0.supportedExtensions.contains(fileExtension))
        } ?? family.runtimeOptions.first(where: { $0.runtimeID == family.defaultRuntimeID })

        guard let runtime else {
            return CoreResolution(
                capability: .unsupported,
                provisioningStatus: .unsupported,
                availabilityStatus: .unsupportedByCurrentBuild,
                message: "No bundled runtime can open \(file.fileName) for \(rom.platformName)."
            )
        }

        let inspection = installer.inspect(runtime: runtime, libraryStore: libraryStore, bundle: bundle)
        if runtime.validationState == .blockedByValidation {
            return CoreResolution(
                capability: .unsupported,
                provisioningStatus: inspection.provisioningStatus,
                availabilityStatus: .blockedByValidation,
                runtimeProfile: runtime,
                canAutoProvision: false,
                canRetryProvisioning: false,
                message: runtime.validationBlockReason ?? "\(runtime.displayName) is tracked in the catalog but blocked by iOS validation."
            )
        }

        if inspection.provisioningStatus == .missingCoreNotShipped {
            return CoreResolution(
                capability: .missingCore,
                provisioningStatus: .missingCoreNotShipped,
                availabilityStatus: .missingBundledCore,
                runtimeProfile: runtime,
                canAutoProvision: false,
                canRetryProvisioning: false,
                message: inspection.message
            )
        }

        if inspection.provisioningStatus == .missingCoreInstallable {
            return CoreResolution(
                capability: .missingCore,
                provisioningStatus: .missingCoreInstallable,
                availabilityStatus: .playable,
                runtimeProfile: runtime,
                canAutoProvision: true,
                canRetryProvisioning: false,
                message: inspection.message
            )
        }

        if inspection.provisioningStatus == .failedCoreInstall {
            return CoreResolution(
                capability: .missingCore,
                provisioningStatus: .failedCoreInstall,
                availabilityStatus: .playable,
                runtimeProfile: runtime,
                canAutoProvision: false,
                canRetryProvisioning: true,
                message: inspection.message
            )
        }

        let missingBIOS = runtime.requiredBIOSFiles.filter { biosName in
            let biosURL = libraryStore.biosDirectory().appending(path: biosName)
            let systemURL = libraryStore.systemDirectory().appending(path: biosName)
            return !FileManager.default.fileExists(atPath: biosURL.path) &&
                !FileManager.default.fileExists(atPath: systemURL.path)
        }

        guard missingBIOS.isEmpty else {
            return CoreResolution(
                capability: .missingBIOS,
                provisioningStatus: .missingBIOS,
                availabilityStatus: .missingBIOS,
                runtimeProfile: runtime,
                missingBIOS: missingBIOS,
                canAutoProvision: false,
                canRetryProvisioning: false,
                message: "Missing BIOS: \(missingBIOS.joined(separator: ", "))"
            )
        }

        return CoreResolution(
            capability: .ready,
            provisioningStatus: .ready,
            availabilityStatus: .playable,
            runtimeProfile: runtime,
            coreURL: libraryStore.bundledCoreURL(bundleRelativePath: runtime.bundleRelativePath, bundle: bundle),
            canAutoProvision: false,
            canRetryProvisioning: false,
            message: "\(runtime.displayName) is ready for \(file.fileName)."
        )
    }
}

public enum IOSRuntimeMatrix {
    public static let fullParityFamilies: [IOSRuntimeFamily] = [
        family(
            familyID: "nes",
            displayName: "NES / Famicom",
            platformSlugs: ["nes", "famicom"],
            runtime: runtime(
                runtimeID: "fceumm",
                displayName: "FCEUmm",
                platformSlugs: ["nes", "famicom"],
                bundleRelativePath: "Cores/fceumm.dylib",
                supportedExtensions: ["nes"],
                shader: .crt
            )
        ),
        family(
            familyID: "snes",
            displayName: "SNES / Super Famicom",
            platformSlugs: ["snes", "sfam"],
            runtime: runtime(
                runtimeID: "snes9x",
                displayName: "Snes9x",
                platformSlugs: ["snes", "sfam"],
                bundleRelativePath: "Cores/snes9x.dylib",
                supportedExtensions: ["smc", "sfc"],
                shader: .crt
            )
        ),
        family(
            familyID: "gb",
            displayName: "Game Boy / Game Boy Color",
            platformSlugs: ["gb", "gbc"],
            runtime: runtime(
                runtimeID: "gambatte",
                displayName: "Gambatte",
                platformSlugs: ["gb", "gbc"],
                bundleRelativePath: "Cores/gambatte.dylib",
                supportedExtensions: ["gb", "gbc"],
                shader: .lcd
            )
        ),
        family(
            familyID: "gba",
            displayName: "Game Boy Advance",
            platformSlugs: ["gba"],
            runtime: runtime(
                runtimeID: "mgba",
                displayName: "mGBA",
                platformSlugs: ["gba"],
                bundleRelativePath: "Cores/mgba.dylib",
                supportedExtensions: ["gba"],
                shader: .lcd
            )
        ),
        family(
            familyID: "sega16",
            displayName: "Sega 8/16-bit",
            platformSlugs: ["genesis", "sms", "gamegear", "segacd"],
            runtime: runtime(
                runtimeID: "genesis_plus_gx",
                displayName: "Genesis Plus GX",
                platformSlugs: ["genesis", "sms", "gamegear", "segacd"],
                bundleRelativePath: "Cores/genesis_plus_gx.dylib",
                shader: .crt
            )
        ),
        family(
            familyID: "sega32x",
            displayName: "Sega 32X",
            platformSlugs: ["32x", "sega32x", "sega-32x"],
            runtime: runtime(
                runtimeID: "picodrive",
                displayName: "PicoDrive",
                platformSlugs: ["32x", "sega32x", "sega-32x"],
                bundleRelativePath: "Cores/picodrive.dylib",
                shader: .crt,
                interactionProfile: .controller,
                validationState: .blockedByValidation,
                validationBlockReason: "Controller-first iOS runtime validation is still pending for Sega 32X."
            )
        ),
        family(
            familyID: "n64",
            displayName: "Nintendo 64",
            platformSlugs: ["n64"],
            runtime: runtime(
                runtimeID: "mupen64plus_next_gles3",
                displayName: "Mupen64Plus-Next (GLES3)",
                platformSlugs: ["n64"],
                bundleRelativePath: "Cores/mupen64plus_next_gles3.dylib",
                defaultVariables: [
                    "mupen64plus-43screensize": "320x240",
                    "mupen64plus-FrameDuping": "True",
                ],
                renderBackend: .hardwareRender,
                interactionProfile: .controller,
                validationState: .blockedByValidation,
                validationBlockReason: "The iOS hardware-render host is not implemented for Nintendo 64 yet."
            )
        ),
        family(
            familyID: "psx",
            displayName: "PlayStation",
            platformSlugs: ["psx", "ps1", "playstation"],
            runtime: runtime(
                runtimeID: "pcsx_rearmed",
                displayName: "PCSX ReARMed",
                platformSlugs: ["psx", "ps1", "playstation"],
                bundleRelativePath: "Cores/pcsx_rearmed.dylib",
                defaultVariables: ["pcsx_rearmed_drc": "disabled"],
                supportedExtensions: ["cue", "bin", "img", "iso", "pbp"],
                requiredBIOSFiles: ["scph5501.bin"],
                shader: .crt
            )
        ),
        family(
            familyID: "psp",
            displayName: "PlayStation Portable",
            platformSlugs: ["psp"],
            runtime: runtime(
                runtimeID: "ppsspp",
                displayName: "PPSSPP",
                platformSlugs: ["psp"],
                bundleRelativePath: "Cores/ppsspp.dylib",
                defaultVariables: ["ppsspp_frame_duplication": "enabled"],
                shader: .lcd,
                renderBackend: .hardwareRender,
                interactionProfile: .controller,
                validationState: .blockedByValidation,
                validationBlockReason: "The iOS hardware-render host is not implemented for PSP yet."
            )
        ),
        family(
            familyID: "nds",
            displayName: "Nintendo DS / DSi-enhanced",
            platformSlugs: ["nds", "dsi", "nintendo-dsi"],
            runtime: runtime(
                runtimeID: "melonds_ds",
                displayName: "melonDS DS",
                platformSlugs: ["nds", "dsi", "nintendo-dsi"],
                bundleRelativePath: "Cores/melonds_ds.dylib",
                defaultVariables: [
                    "melonds_number_of_screen_layouts": "1",
                    "melonds_touch_mode": "Touch",
                    "melonds_threaded_renderer": "enabled",
                ],
                supportedExtensions: ["nds"],
                shader: .lcd,
                interactionProfile: .dualScreenTouch
            )
        ),
        family(
            familyID: "atari2600",
            displayName: "Atari 2600",
            platformSlugs: ["atari2600"],
            runtime: runtime(
                runtimeID: "stella",
                displayName: "Stella",
                platformSlugs: ["atari2600"],
                bundleRelativePath: "Cores/stella.dylib",
                shader: .crt
            )
        ),
        family(
            familyID: "atari7800",
            displayName: "Atari 7800",
            platformSlugs: ["atari7800"],
            runtime: runtime(
                runtimeID: "prosystem",
                displayName: "ProSystem",
                platformSlugs: ["atari7800"],
                bundleRelativePath: "Cores/prosystem.dylib",
                shader: .crt
            )
        ),
        family(
            familyID: "lynx",
            displayName: "Atari Lynx",
            platformSlugs: ["lynx"],
            runtime: runtime(
                runtimeID: "handy",
                displayName: "Handy",
                platformSlugs: ["lynx"],
                bundleRelativePath: "Cores/handy.dylib",
                shader: .lcd
            )
        ),
        family(
            familyID: "arcade",
            displayName: "Arcade",
            platformSlugs: ["arcade"],
            runtime: runtime(
                runtimeID: "fbneo",
                displayName: "FinalBurn Neo",
                platformSlugs: ["arcade"],
                bundleRelativePath: "Cores/fbneo.dylib",
                shader: .crt
            )
        ),
        family(
            familyID: "dos",
            displayName: "DOS",
            platformSlugs: ["dos"],
            runtime: runtime(
                runtimeID: "dosbox_pure",
                displayName: "DOSBox Pure",
                platformSlugs: ["dos"],
                bundleRelativePath: "Cores/dosbox_pure.dylib",
                interactionProfile: .keyboardMouse,
                validationState: .blockedByValidation,
                validationBlockReason: "Keyboard and mouse overlay support has not been implemented for DOS yet."
            )
        ),
        family(
            familyID: "tg16",
            displayName: "PC Engine / TurboGrafx-16",
            platformSlugs: ["tg16"],
            runtime: runtime(
                runtimeID: "mednafen_pce_fast",
                displayName: "Beetle PCE Fast",
                platformSlugs: ["tg16"],
                bundleRelativePath: "Cores/mednafen_pce_fast.dylib",
                shader: .crt
            )
        ),
        family(
            familyID: "ngp",
            displayName: "Neo Geo Pocket",
            platformSlugs: ["neo-geo-pocket", "neo-geo-pocket-color"],
            runtime: runtime(
                runtimeID: "mednafen_ngp",
                displayName: "Beetle NeoPop",
                platformSlugs: ["neo-geo-pocket", "neo-geo-pocket-color"],
                bundleRelativePath: "Cores/mednafen_ngp.dylib",
                shader: .lcd
            )
        ),
        family(
            familyID: "wswan",
            displayName: "WonderSwan",
            platformSlugs: ["wonderswan", "wonderswan-color"],
            runtime: runtime(
                runtimeID: "mednafen_wswan",
                displayName: "Beetle Cygne",
                platformSlugs: ["wonderswan", "wonderswan-color"],
                bundleRelativePath: "Cores/mednafen_wswan.dylib",
                shader: .lcd
            )
        ),
        family(
            familyID: "dreamcast",
            displayName: "Dreamcast / NAOMI",
            platformSlugs: ["dreamcast", "naomi"],
            runtime: runtime(
                runtimeID: "flycast",
                displayName: "Flycast",
                platformSlugs: ["dreamcast", "naomi"],
                bundleRelativePath: "Cores/flycast.dylib",
                requiredBIOSFiles: ["dc_boot.bin", "dc_flash.bin"],
                renderBackend: .hardwareRender,
                interactionProfile: .controller,
                validationState: .blockedByValidation,
                validationBlockReason: "The iOS hardware-render host is not implemented for Dreamcast / NAOMI yet."
            )
        ),
        family(
            familyID: "3do",
            displayName: "3DO",
            platformSlugs: ["3do"],
            runtime: runtime(
                runtimeID: "opera",
                displayName: "Opera",
                platformSlugs: ["3do"],
                bundleRelativePath: "Cores/opera.dylib",
                interactionProfile: .controller,
                validationState: .blockedByValidation,
                validationBlockReason: "Controller-first iOS runtime validation is still pending for 3DO."
            )
        ),
        family(
            familyID: "virtualboy",
            displayName: "Virtual Boy",
            platformSlugs: ["virtualboy", "virtual-boy"],
            runtime: runtime(
                runtimeID: "beetle_vb",
                displayName: "Beetle VB",
                platformSlugs: ["virtualboy", "virtual-boy"],
                bundleRelativePath: "Cores/beetle_vb.dylib",
                interactionProfile: .controller,
                validationState: .blockedByValidation,
                validationBlockReason: "Controller-first iOS runtime validation is still pending for Virtual Boy."
            )
        ),
        family(
            familyID: "dolphin",
            displayName: "GameCube / Wii",
            platformSlugs: ["gamecube", "ngc", "wii"],
            runtime: runtime(
                runtimeID: "dolphin",
                displayName: "Dolphin",
                platformSlugs: ["gamecube", "ngc", "wii"],
                bundleRelativePath: "Cores/dolphin.dylib",
                renderBackend: .hardwareRender,
                interactionProfile: .controller,
                validationState: .blockedByValidation,
                validationBlockReason: "The iOS hardware-render host is not implemented for GameCube / Wii yet."
            )
        ),
        family(
            familyID: "3ds",
            displayName: "Nintendo 3DS",
            platformSlugs: ["3ds", "new-nintendo-3ds"],
            runtime: runtime(
                runtimeID: "citra",
                displayName: "Citra Canary/Experimental",
                platformSlugs: ["3ds", "new-nintendo-3ds"],
                bundleRelativePath: "Cores/citra.dylib",
                renderBackend: .hardwareRender,
                interactionProfile: .controller,
                validationState: .blockedByValidation,
                validationBlockReason: "The iOS hardware-render host is not implemented for Nintendo 3DS yet."
            )
        ),
    ]

    private static func family(
        familyID: String,
        displayName: String,
        platformSlugs: Set<String>,
        runtime: IOSRuntimeProfile
    ) -> IOSRuntimeFamily {
        IOSRuntimeFamily(
            familyID: familyID,
            displayName: displayName,
            platformSlugs: platformSlugs,
            defaultRuntimeID: runtime.runtimeID,
            runtimeOptions: [runtime]
        )
    }

    private static func runtime(
        runtimeID: String,
        displayName: String,
        platformSlugs: Set<String>,
        bundleRelativePath: String,
        defaultVariables: [String: String] = [:],
        supportedExtensions: Set<String> = [],
        requiredBIOSFiles: [String] = [],
        shader: PlayerShader = .default,
        renderBackend: IOSRenderBackend = .softwareFramebuffer,
        interactionProfile: PlayerInteractionProfile = .touch,
        validationState: IOSRuntimeValidationState = .playable,
        validationBlockReason: String? = nil
    ) -> IOSRuntimeProfile {
        IOSRuntimeProfile(
            runtimeID: runtimeID,
            displayName: displayName,
            platformSlugs: platformSlugs,
            bundleRelativePath: bundleRelativePath,
            defaultVariables: defaultVariables,
            supportedExtensions: supportedExtensions,
            requiredBIOSFiles: requiredBIOSFiles,
            shader: shader,
            renderBackend: renderBackend,
            interactionProfile: interactionProfile,
            validationState: validationState,
            validationBlockReason: validationBlockReason
        )
    }
}
