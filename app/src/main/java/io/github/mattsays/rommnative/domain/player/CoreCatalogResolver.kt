package io.github.mattsays.rommnative.domain.player

import android.content.Context
import io.github.mattsays.rommnative.domain.storage.LibraryStore

class CoreCatalogResolver(
    private val context: Context,
    private val libraryStore: LibraryStore,
) : CoreResolver {
    private val families = listOf(
        family(
            familyId = "nes",
            displayName = "NES / Famicom",
            platformSlugs = setOf("nes", "famicom"),
            defaultRuntimeId = "fceumm",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "fceumm",
                    displayName = "FCEUmm",
                    platformSlugs = setOf("nes", "famicom"),
                    artifactBaseName = "fceumm",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/fceumm/",
                ),
                libretroProfile(
                    runtimeId = "nestopia",
                    displayName = "Nestopia UE",
                    platformSlugs = setOf("nes", "famicom"),
                    artifactBaseName = "nestopia",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/nestopia/",
                ),
            ),
        ),
        family(
            familyId = "snes",
            displayName = "SNES / Super Famicom",
            platformSlugs = setOf("snes", "sfam"),
            defaultRuntimeId = "snes9x",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "snes9x",
                    displayName = "Snes9x",
                    platformSlugs = setOf("snes", "sfam"),
                    artifactBaseName = "snes9x",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/snes9x/",
                ),
                libretroProfile(
                    runtimeId = "bsnes",
                    displayName = "bsnes",
                    platformSlugs = setOf("snes", "sfam"),
                    artifactBaseName = "bsnes",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/bsnes/",
                ),
            ),
        ),
        family(
            familyId = "gb",
            displayName = "Game Boy / Game Boy Color",
            platformSlugs = setOf("gb", "gbc"),
            defaultRuntimeId = "gambatte",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "gambatte",
                    displayName = "Gambatte",
                    platformSlugs = setOf("gb", "gbc"),
                    artifactBaseName = "gambatte",
                    shader = PlayerShader.LCD,
                    documentationUrl = "https://docs.libretro.com/library/gambatte/",
                ),
                libretroProfile(
                    runtimeId = "sameboy",
                    displayName = "SameBoy",
                    platformSlugs = setOf("gb", "gbc"),
                    artifactBaseName = "sameboy",
                    shader = PlayerShader.LCD,
                    documentationUrl = "https://docs.libretro.com/library/sameboy/",
                ),
            ),
        ),
        family(
            familyId = "gba",
            displayName = "Game Boy Advance",
            platformSlugs = setOf("gba"),
            defaultRuntimeId = "mgba",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "mgba",
                    displayName = "mGBA",
                    platformSlugs = setOf("gba"),
                    artifactBaseName = "mgba",
                    shader = PlayerShader.LCD,
                    documentationUrl = "https://docs.libretro.com/library/mgba/",
                ),
                libretroProfile(
                    runtimeId = "vba_next",
                    displayName = "VBA Next",
                    platformSlugs = setOf("gba"),
                    artifactBaseName = "vba_next",
                    shader = PlayerShader.LCD,
                    documentationUrl = "https://docs.libretro.com/library/vba_next/",
                ),
            ),
        ),
        family(
            familyId = "sega16",
            displayName = "Sega 8/16-bit",
            platformSlugs = setOf("genesis", "sms", "gamegear", "segacd"),
            defaultRuntimeId = "genesis_plus_gx",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "genesis_plus_gx",
                    displayName = "Genesis Plus GX",
                    platformSlugs = setOf("genesis", "sms", "gamegear", "segacd"),
                    artifactBaseName = "genesis_plus_gx",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/genesis_plus_gx/",
                ),
                libretroProfile(
                    runtimeId = "picodrive",
                    displayName = "PicoDrive",
                    platformSlugs = setOf("genesis", "sms", "gamegear", "segacd"),
                    artifactBaseName = "picodrive",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/picodrive/",
                ),
            ),
        ),
        family(
            familyId = "n64",
            displayName = "Nintendo 64",
            platformSlugs = setOf("n64"),
            defaultRuntimeId = "mupen64plus_next_gles3",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "mupen64plus_next_gles3",
                    displayName = "Mupen64Plus-Next (GLES3)",
                    platformSlugs = setOf("n64"),
                    artifactBaseName = "mupen64plus_next_gles3",
                    defaultVariables = mapOf(
                        "mupen64plus-43screensize" to "320x240",
                        "mupen64plus-FrameDuping" to "True",
                    ),
                    documentationUrl = "https://docs.libretro.com/library/mupen64plus/",
                ),
                libretroProfile(
                    runtimeId = "parallel_n64",
                    displayName = "Parallel N64",
                    platformSlugs = setOf("n64"),
                    artifactBaseName = "parallel_n64",
                    documentationUrl = "https://docs.libretro.com/library/parallel_n64/",
                ),
            ),
        ),
        family(
            familyId = "psx",
            displayName = "PlayStation",
            platformSlugs = setOf("psx", "ps1", "playstation"),
            defaultRuntimeId = "pcsx_rearmed",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "pcsx_rearmed",
                    displayName = "PCSX ReARMed",
                    platformSlugs = setOf("psx", "ps1", "playstation"),
                    artifactBaseName = "pcsx_rearmed",
                    requiredBiosFiles = listOf("scph5501.bin"),
                    defaultVariables = mapOf("pcsx_rearmed_drc" to "disabled"),
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/pcsx_rearmed/",
                ),
                libretroProfile(
                    runtimeId = "swanstation",
                    displayName = "SwanStation",
                    platformSlugs = setOf("psx", "ps1", "playstation"),
                    artifactBaseName = "swanstation",
                    requiredBiosFiles = listOf("scph5501.bin"),
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/swanstation/",
                ),
            ),
        ),
        family(
            familyId = "psp",
            displayName = "PlayStation Portable",
            platformSlugs = setOf("psp"),
            defaultRuntimeId = "ppsspp",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "ppsspp",
                    displayName = "PPSSPP",
                    platformSlugs = setOf("psp"),
                    artifactBaseName = "ppsspp",
                    defaultVariables = mapOf("ppsspp_frame_duplication" to "enabled"),
                    shader = PlayerShader.LCD,
                    documentationUrl = "https://docs.libretro.com/library/ppsspp/",
                ),
            ),
        ),
        family(
            familyId = "nds",
            displayName = "Nintendo DS",
            platformSlugs = setOf("nds"),
            defaultRuntimeId = "melonds",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "melonds",
                    displayName = "melonDS",
                    platformSlugs = setOf("nds"),
                    artifactBaseName = "melonds",
                    defaultVariables = mapOf(
                        "melonds_number_of_screen_layouts" to "1",
                        "melonds_touch_mode" to "Touch",
                        "melonds_threaded_renderer" to "enabled",
                    ),
                    shader = PlayerShader.LCD,
                    documentationUrl = "https://docs.libretro.com/library/melonds_ds/",
                ),
                libretroProfile(
                    runtimeId = "desmume",
                    displayName = "DeSmuME",
                    platformSlugs = setOf("nds"),
                    artifactBaseName = "desmume",
                    shader = PlayerShader.LCD,
                    documentationUrl = "https://docs.libretro.com/library/desmume/",
                ),
            ),
        ),
        family(
            familyId = "atari2600",
            displayName = "Atari 2600",
            platformSlugs = setOf("atari2600"),
            defaultRuntimeId = "stella",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "stella",
                    displayName = "Stella",
                    platformSlugs = setOf("atari2600"),
                    artifactBaseName = "stella",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/stella/",
                ),
                libretroProfile(
                    runtimeId = "stella2023",
                    displayName = "Stella 2023",
                    platformSlugs = setOf("atari2600"),
                    artifactBaseName = "stella2023",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/stella/",
                ),
            ),
        ),
        family(
            familyId = "atari7800",
            displayName = "Atari 7800",
            platformSlugs = setOf("atari7800"),
            defaultRuntimeId = "prosystem",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "prosystem",
                    displayName = "ProSystem",
                    platformSlugs = setOf("atari7800"),
                    artifactBaseName = "prosystem",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/prosystem/",
                ),
            ),
        ),
        family(
            familyId = "lynx",
            displayName = "Atari Lynx",
            platformSlugs = setOf("lynx"),
            defaultRuntimeId = "handy",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "handy",
                    displayName = "Handy",
                    platformSlugs = setOf("lynx"),
                    artifactBaseName = "handy",
                    shader = PlayerShader.LCD,
                    documentationUrl = "https://docs.libretro.com/library/handy/",
                ),
            ),
        ),
        family(
            familyId = "arcade",
            displayName = "Arcade",
            platformSlugs = setOf("arcade"),
            defaultRuntimeId = "fbneo",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "fbneo",
                    displayName = "FinalBurn Neo",
                    platformSlugs = setOf("arcade"),
                    artifactBaseName = "fbneo",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/fbneo/",
                ),
            ),
        ),
        family(
            familyId = "dos",
            displayName = "DOS",
            platformSlugs = setOf("dos"),
            defaultRuntimeId = "dosbox_pure",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "dosbox_pure",
                    displayName = "DOSBox Pure",
                    platformSlugs = setOf("dos"),
                    artifactBaseName = "dosbox_pure",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/dosbox_pure/",
                ),
                libretroProfile(
                    runtimeId = "dosbox_core",
                    displayName = "DOSBox Core",
                    platformSlugs = setOf("dos"),
                    artifactBaseName = "dosbox_core",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/dosbox/",
                ),
            ),
        ),
        family(
            familyId = "tg16",
            displayName = "PC Engine / TurboGrafx-16",
            platformSlugs = setOf("tg16"),
            defaultRuntimeId = "mednafen_pce_fast",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "mednafen_pce_fast",
                    displayName = "Beetle PCE Fast",
                    platformSlugs = setOf("tg16"),
                    artifactBaseName = "mednafen_pce_fast",
                    shader = PlayerShader.CRT,
                    documentationUrl = "https://docs.libretro.com/library/beetle_pce_fast/",
                ),
            ),
        ),
        family(
            familyId = "ngp",
            displayName = "Neo Geo Pocket",
            platformSlugs = setOf("neo-geo-pocket", "neo-geo-pocket-color"),
            defaultRuntimeId = "mednafen_ngp",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "mednafen_ngp",
                    displayName = "Beetle NeoPop",
                    platformSlugs = setOf("neo-geo-pocket", "neo-geo-pocket-color"),
                    artifactBaseName = "mednafen_ngp",
                    shader = PlayerShader.LCD,
                    documentationUrl = "https://docs.libretro.com/library/beetle_ngp/",
                ),
            ),
        ),
        family(
            familyId = "wswan",
            displayName = "WonderSwan",
            platformSlugs = setOf("wonderswan", "wonderswan-color"),
            defaultRuntimeId = "mednafen_wswan",
            runtimeOptions = listOf(
                libretroProfile(
                    runtimeId = "mednafen_wswan",
                    displayName = "Beetle Cygne",
                    platformSlugs = setOf("wonderswan", "wonderswan-color"),
                    artifactBaseName = "mednafen_wswan",
                    shader = PlayerShader.LCD,
                    documentationUrl = "https://docs.libretro.com/library/beetle_cygne/",
                ),
            ),
        ),
    )

    private val familyBySlug = families.flatMap { family ->
        family.platformSlugs.map { slug -> slug to family }
    }.toMap()

    override fun resolve(platformSlug: String, fileExtension: String?): CoreResolution {
        val family = platformSupport(platformSlug)
            ?: return CoreResolution(
                capability = PlayerCapability.UNSUPPORTED,
                message = "This platform is not enabled in the embedded player yet.",
            )
        val profile = family.runtimeOptions.firstOrNull { it.runtimeId == family.defaultRuntimeId }
            ?: family.runtimeOptions.firstOrNull()
            ?: return CoreResolution(
                capability = PlayerCapability.UNSUPPORTED,
                platformFamily = family,
                availableProfiles = family.runtimeOptions,
                message = "No embedded core has been configured for ${family.displayName}.",
            )

        if (profile.supportedExtensions.isNotEmpty() && fileExtension != null && fileExtension !in profile.supportedExtensions) {
            return CoreResolution(
                capability = PlayerCapability.UNSUPPORTED,
                platformFamily = family,
                runtimeProfile = profile,
                availableProfiles = family.runtimeOptions,
                message = "This file type is not enabled for ${profile.displayName} yet.",
            )
        }

        val missingBios = profile.requiredBiosFiles.filterNot { fileName ->
            java.io.File(libraryStore.biosDirectory(), fileName).exists()
        }
        if (missingBios.isNotEmpty()) {
            return CoreResolution(
                capability = PlayerCapability.MISSING_BIOS,
                platformFamily = family,
                runtimeProfile = profile,
                availableProfiles = family.runtimeOptions,
                missingBios = missingBios,
                message = "Install BIOS files in app-managed storage before launching this game.",
            )
        }

        val coreLibrary = libraryStore.resolveCoreLibrary(context, profile.libraryFileName)
            ?: return CoreResolution(
                capability = PlayerCapability.MISSING_CORE,
                platformFamily = family,
                runtimeProfile = profile,
                availableProfiles = family.runtimeOptions,
                message = "Download the recommended ${profile.displayName} core to launch this game inside the app.",
            )

        return CoreResolution(
            capability = PlayerCapability.READY,
            platformFamily = family,
            runtimeProfile = profile,
            availableProfiles = family.runtimeOptions,
            coreLibrary = coreLibrary,
            message = null,
        )
    }

    override fun platformSupport(platformSlug: String): PlatformRuntimeFamily? {
        return familyBySlug[platformSlug]
    }

    override fun supportedPlatforms(): List<PlatformRuntimeFamily> = families
}

private fun family(
    familyId: String,
    displayName: String,
    platformSlugs: Set<String>,
    defaultRuntimeId: String,
    runtimeOptions: List<RuntimeProfile>,
): PlatformRuntimeFamily {
    return PlatformRuntimeFamily(
        familyId = familyId,
        displayName = displayName,
        platformSlugs = platformSlugs,
        defaultRuntimeId = defaultRuntimeId,
        runtimeOptions = runtimeOptions,
    )
}

private fun libretroProfile(
    runtimeId: String,
    displayName: String,
    platformSlugs: Set<String>,
    artifactBaseName: String = runtimeId,
    defaultVariables: Map<String, String> = emptyMap(),
    supportedExtensions: Set<String> = emptySet(),
    requiredBiosFiles: List<String> = emptyList(),
    supportsSaveStates: Boolean = true,
    shader: PlayerShader = PlayerShader.DEFAULT,
    documentationUrl: String? = null,
): RuntimeProfile {
    return RuntimeProfile(
        runtimeId = runtimeId,
        displayName = displayName,
        platformSlugs = platformSlugs,
        libraryFileName = "lib${artifactBaseName}_libretro_android.so",
        defaultVariables = defaultVariables,
        supportedExtensions = supportedExtensions,
        requiredBiosFiles = requiredBiosFiles,
        supportsSaveStates = supportsSaveStates,
        shader = shader,
        download = CoreDownloadDescriptor(
            provider = CoreDistributionProvider.LIBRETRO_BUILDBOT,
            artifactBaseName = artifactBaseName,
            documentationUrl = documentationUrl,
        ),
    )
}
