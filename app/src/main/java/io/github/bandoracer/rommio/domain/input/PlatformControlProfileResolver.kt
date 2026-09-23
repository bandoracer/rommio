package io.github.bandoracer.rommio.domain.input

import android.view.KeyEvent
import io.github.bandoracer.rommio.domain.player.EmbeddedSupportTier

class PlatformControlProfileResolver : ControlProfileResolver {
    private val profiles = ExpansionControls.profiles + listOf(
        touchProfile(
            familyId = "nes",
            displayName = "NES / Famicom",
            platformSlugs = setOf("nes", "famicom"),
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "standard",
                    displayName = "Standard",
                    elements = listOf(
                        standardDpad(),
                        faceTwo(
                            primaryLabel = "A",
                            secondaryLabel = "B",
                            primaryKey = KeyEvent.KEYCODE_BUTTON_A,
                            secondaryKey = KeyEvent.KEYCODE_BUTTON_B,
                        ),
                        standardStartSelect(),
                    ),
                ),
                TouchLayoutPreset(
                    presetId = "compact",
                    displayName = "Compact",
                    elements = listOf(
                        standardDpad().copy(centerX = 0.16f, centerY = 0.76f, baseScale = 0.9f),
                        faceTwo(
                            primaryLabel = "A",
                            secondaryLabel = "B",
                            primaryKey = KeyEvent.KEYCODE_BUTTON_A,
                            secondaryKey = KeyEvent.KEYCODE_BUTTON_B,
                        ).copy(centerX = 0.84f, centerY = 0.75f, baseScale = 0.92f),
                        standardStartSelect().copy(centerY = 0.86f, baseScale = 0.82f),
                    ),
                ),
            ),
        ),
        touchProfile(
            familyId = "snes",
            displayName = "SNES / Super Famicom",
            platformSlugs = setOf("snes", "sfam"),
            orientationPolicy = PlayerOrientationPolicy.LANDSCAPE_ONLY,
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "standard",
                    displayName = "Standard",
                    elements = listOf(
                        standardDpad(),
                        faceFour(
                            left = "Y" to KeyEvent.KEYCODE_BUTTON_Y,
                            bottom = "B" to KeyEvent.KEYCODE_BUTTON_B,
                            right = "A" to KeyEvent.KEYCODE_BUTTON_A,
                            top = "X" to KeyEvent.KEYCODE_BUTTON_X,
                        ),
                        standardShoulders(labels = "L" to "R"),
                        standardStartSelect(),
                    ),
                ),
            ),
        ),
        touchProfile(
            familyId = "gb",
            displayName = "Game Boy / Color",
            platformSlugs = setOf("gb", "gbc"),
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "standard",
                    displayName = "Standard",
                    elements = listOf(
                        standardDpad(),
                        faceTwo(
                            primaryLabel = "A",
                            secondaryLabel = "B",
                            primaryKey = KeyEvent.KEYCODE_BUTTON_A,
                            secondaryKey = KeyEvent.KEYCODE_BUTTON_B,
                        ),
                        standardStartSelect(),
                    ),
                ),
            ),
        ),
        touchProfile(
            familyId = "gba",
            displayName = "Game Boy Advance",
            platformSlugs = setOf("gba"),
            orientationPolicy = PlayerOrientationPolicy.LANDSCAPE_ONLY,
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "standard",
                    displayName = "Standard",
                    elements = listOf(
                        standardDpad(),
                        faceTwo(
                            primaryLabel = "A",
                            secondaryLabel = "B",
                            primaryKey = KeyEvent.KEYCODE_BUTTON_A,
                            secondaryKey = KeyEvent.KEYCODE_BUTTON_B,
                        ).copy(centerY = 0.70f),
                        standardShoulders(labels = "L" to "R"),
                        standardStartSelect(),
                    ),
                ),
            ),
        ),

        controllerFirstProfile(
            familyId = "sega32x",
            displayName = "Sega 32X",
            platformSlugs = setOf("32x", "sega32x", "sega-32x"),
        ),

        touchProfile(
            familyId = "atari2600",
            displayName = "Atari 2600",
            platformSlugs = setOf("atari2600"),
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "standard",
                    displayName = "Standard",
                    elements = listOf(
                        standardDpad(),
                        faceTwo(
                            primaryLabel = "Fire",
                            secondaryLabel = "Trigger",
                            primaryKey = KeyEvent.KEYCODE_BUTTON_B,
                            secondaryKey = KeyEvent.KEYCODE_BUTTON_A,
                        ),
                        standardStartSelect(selectLabel = "Select", startLabel = "Reset"),
                        standardShoulders(labels = "Difficulty A" to "Difficulty B", keyCodes = KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_L2),
                    ),
                ),
            ),
        ),
        touchProfile(
            familyId = "atari7800",
            displayName = "Atari 7800",
            platformSlugs = setOf("atari7800"),
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "standard",
                    displayName = "Standard",
                    elements = listOf(
                        standardDpad(),
                        faceTwo(primaryLabel = "1", secondaryLabel = "2", primaryKey = KeyEvent.KEYCODE_BUTTON_B, secondaryKey = KeyEvent.KEYCODE_BUTTON_A),
                        standardStartSelect(selectLabel = "Select", startLabel = "Pause"),
                        standardShoulders(labels = "Reset" to "Difficulty", keyCodes = KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_BUTTON_L1),
                    ),
                ),
            ),
        ),
        touchProfile(
            familyId = "lynx",
            displayName = "Atari Lynx",
            platformSlugs = setOf("lynx"),
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "standard",
                    displayName = "Standard",
                    elements = listOf(
                        standardDpad(),
                        faceTwo(primaryLabel = "A", secondaryLabel = "B", primaryKey = KeyEvent.KEYCODE_BUTTON_A, secondaryKey = KeyEvent.KEYCODE_BUTTON_B),
                        standardStartSelect(selectLabel = "Rotate", startLabel = "Pause"),
                        standardShoulders(labels = "Option 1" to "Option 2"),
                    ),
                ),
            ),
        ),
        touchProfile(
            familyId = "tg16",
            displayName = "TurboGrafx / Neo Geo Pocket / WonderSwan",
            platformSlugs = setOf(
                "tg16",
                "neo-geo-pocket",
                "neo-geo-pocket-color",
                "wonderswan",
                "wonderswan-color",
            ),
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "standard",
                    displayName = "Standard",
                    elements = listOf(
                        standardDpad(),
                        faceTwo(
                            primaryLabel = "II",
                            secondaryLabel = "I",
                            primaryKey = KeyEvent.KEYCODE_BUTTON_A,
                            secondaryKey = KeyEvent.KEYCODE_BUTTON_B,
                        ),
                        standardStartSelect(),
                    ),
                ),
            ),
        ),
        touchProfile(
            familyId = "arcade",
            displayName = "Arcade",
            platformSlugs = setOf("arcade"),
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "standard",
                    displayName = "Standard",
                    elements = listOf(
                        standardDpad(),
                        faceFour(
                            left = "1" to KeyEvent.KEYCODE_BUTTON_Y,
                            bottom = "2" to KeyEvent.KEYCODE_BUTTON_B,
                            right = "3" to KeyEvent.KEYCODE_BUTTON_A,
                            top = "4" to KeyEvent.KEYCODE_BUTTON_X,
                        ),
                        standardStartSelect(selectLabel = "Coin", startLabel = "Start"),
                    ),
                ),
            ),
        ),


        touchProfile(
            familyId = "nds",
            displayName = "Nintendo DS / DSi-enhanced",
            platformSlugs = setOf("nds", "dsi", "nintendo-dsi"),
            orientationPolicy = PlayerOrientationPolicy.PORTRAIT_ONLY,
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "portrait-handheld",
                    displayName = "Portrait handheld",
                    elements = listOf(
                        standardDpad().copy(centerX = 0.19f, centerY = 0.80f),
                        faceFour(
                            left = "Y" to KeyEvent.KEYCODE_BUTTON_Y,
                            bottom = "B" to KeyEvent.KEYCODE_BUTTON_B,
                            right = "A" to KeyEvent.KEYCODE_BUTTON_A,
                            top = "X" to KeyEvent.KEYCODE_BUTTON_X,
                        ).copy(centerX = 0.81f, centerY = 0.79f, baseScale = 0.94f),
                        standardShoulders(labels = "L" to "R").copy(centerY = 0.11f, baseScale = 0.86f),
                        standardStartSelect().copy(centerY = 0.72f, baseScale = 0.82f),
                    ),
                ),
            ),
        ),
        controllerFirstProfile(
            familyId = "dreamcast",
            displayName = "Dreamcast / NAOMI",
            platformSlugs = setOf("dreamcast", "naomi"),
        ),
        controllerFirstProfile(
            familyId = "3do",
            displayName = "3DO",
            platformSlugs = setOf("3do"),
        ),
        controllerFirstProfile(
            familyId = "virtualboy",
            displayName = "Virtual Boy",
            platformSlugs = setOf("virtualboy", "virtual-boy"),
        ),
        controllerFirstProfile(
            familyId = "dolphin",
            displayName = "GameCube / Wii",
            platformSlugs = setOf("gamecube", "ngc", "wii"),
        ),
        controllerFirstProfile(
            familyId = "3ds",
            displayName = "Nintendo 3DS",
            platformSlugs = setOf("3ds", "new-nintendo-3ds"),
        ),
        controllerFirstProfile(
            familyId = "dos",
            displayName = "DOS",
            platformSlugs = setOf("dos"),
            message = "DOS stays controller-first until a validated pointer and keyboard helper overlay exists.",
        ),
    )

    override fun resolve(platformSlug: String): PlatformControlProfile {
        return profiles.firstOrNull { profile ->
            platformSlug.lowercase() in profile.platformSlugs
        } ?: unsupportedProfile(
            familyId = platformSlug.lowercase(),
            displayName = platformSlug.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
            platformSlugs = setOf(platformSlug.lowercase()),
            message = "This platform is not enabled in the embedded player yet.",
        )
    }

    override fun supportedProfiles(): List<PlatformControlProfile> = profiles

    private fun touchProfile(
        familyId: String,
        displayName: String,
        platformSlugs: Set<String>,
        orientationPolicy: PlayerOrientationPolicy = PlayerOrientationPolicy.AUTO,
        presets: List<TouchLayoutPreset>,
    ): PlatformControlProfile {
        return PlatformControlProfile(
            familyId = familyId,
            displayName = displayName,
            platformSlugs = platformSlugs,
            supportTier = EmbeddedSupportTier.TOUCH_SUPPORTED,
            touchSupportMode = TouchSupportMode.FULL,
            playerOrientationPolicy = orientationPolicy,
            preferredViewportAspectRatio = when (familyId) {
                "nes", "snes", "sega16", "psx", "atari2600", "atari7800", "tg16", "arcade" -> 4f / 3f
                "lynx" -> 160f / 102f
                "gb" -> 10f / 9f
                "gba" -> 3f / 2f
                "nds" -> 2f / 3f
                else -> null
            },
            defaultPresetId = presets.firstOrNull()?.presetId,
            presets = presets,
        )
    }

    private fun controllerFirstProfile(
        familyId: String,
        displayName: String,
        platformSlugs: Set<String>,
        message: String = "Connect a controller for this platform. Touch support comes later after a platform-specific layout is validated.",
    ): PlatformControlProfile {
        return PlatformControlProfile(
            familyId = familyId,
            displayName = displayName,
            platformSlugs = platformSlugs,
            supportTier = EmbeddedSupportTier.CONTROLLER_SUPPORTED,
            touchSupportMode = TouchSupportMode.CONTROLLER_FIRST,
            playerOrientationPolicy = PlayerOrientationPolicy.AUTO,
            controllerFallbackMessage = message,
        )
    }

    private fun unsupportedProfile(
        familyId: String,
        displayName: String,
        platformSlugs: Set<String>,
        message: String,
    ): PlatformControlProfile {
        return PlatformControlProfile(
            familyId = familyId,
            displayName = displayName,
            platformSlugs = platformSlugs,
            supportTier = EmbeddedSupportTier.UNSUPPORTED,
            touchSupportMode = TouchSupportMode.CONTROLLER_FIRST,
            playerOrientationPolicy = PlayerOrientationPolicy.AUTO,
            controllerFallbackMessage = message,
        )
    }
}
