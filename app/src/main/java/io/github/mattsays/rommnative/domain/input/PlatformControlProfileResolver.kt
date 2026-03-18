package io.github.mattsays.rommnative.domain.input

import android.view.KeyEvent

class PlatformControlProfileResolver : ControlProfileResolver {
    private val profiles = listOf(
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
        touchProfile(
            familyId = "sega16",
            displayName = "Sega 8/16-bit",
            platformSlugs = setOf("genesis", "sms", "gamegear", "segacd"),
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "standard",
                    displayName = "Standard",
                    elements = listOf(
                        standardDpad(),
                        faceFour(
                            left = "B" to KeyEvent.KEYCODE_BUTTON_Y,
                            bottom = "A" to KeyEvent.KEYCODE_BUTTON_B,
                            right = "C" to KeyEvent.KEYCODE_BUTTON_A,
                            top = "X" to KeyEvent.KEYCODE_BUTTON_X,
                        ).copy(centerY = 0.69f),
                        standardStartSelect(selectLabel = "Mode", startLabel = "Start"),
                    ),
                ),
            ),
        ),
        touchProfile(
            familyId = "psx",
            displayName = "PlayStation",
            platformSlugs = setOf("psx", "ps1", "playstation"),
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "digital",
                    displayName = "Digital",
                    elements = listOf(
                        standardDpad(),
                        faceFour(
                            left = "Square" to KeyEvent.KEYCODE_BUTTON_Y,
                            bottom = "Cross" to KeyEvent.KEYCODE_BUTTON_B,
                            right = "Circle" to KeyEvent.KEYCODE_BUTTON_A,
                            top = "Triangle" to KeyEvent.KEYCODE_BUTTON_X,
                        ),
                        standardShoulders(
                            id = "shoulders_top",
                            labels = "L1" to "R1",
                            keyCodes = KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_R1,
                        ),
                        standardShoulders(
                            id = "shoulders_bottom",
                            labels = "L2" to "R2",
                            keyCodes = KeyEvent.KEYCODE_BUTTON_L2 to KeyEvent.KEYCODE_BUTTON_R2,
                        ).copy(centerY = 0.25f, baseScale = 0.84f),
                        standardStartSelect(),
                    ),
                ),
            ),
        ),
        touchProfile(
            familyId = "atari",
            displayName = "Atari / Handheld Classics",
            platformSlugs = setOf("atari2600", "atari7800", "lynx"),
            presets = listOf(
                TouchLayoutPreset(
                    presetId = "standard",
                    displayName = "Standard",
                    elements = listOf(
                        standardDpad(),
                        faceTwo(
                            primaryLabel = "1",
                            secondaryLabel = "2",
                            primaryKey = KeyEvent.KEYCODE_BUTTON_A,
                            secondaryKey = KeyEvent.KEYCODE_BUTTON_B,
                        ),
                        standardStartSelect(selectLabel = "Option", startLabel = "Start"),
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
        controllerFirstProfile(
            familyId = "n64",
            displayName = "Nintendo 64",
            platformSlugs = setOf("n64"),
        ),
        controllerFirstProfile(
            familyId = "psp",
            displayName = "PlayStation Portable",
            platformSlugs = setOf("psp"),
        ),
        controllerFirstProfile(
            familyId = "nds",
            displayName = "Nintendo DS",
            platformSlugs = setOf("nds"),
            message = "Connect a controller for DS for now. Touch and dual-screen layouts ship after the baseline controls pass validation.",
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
        } ?: controllerFirstProfile(
            familyId = platformSlug.lowercase(),
            displayName = platformSlug.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
            platformSlugs = setOf(platformSlug.lowercase()),
            message = "This platform still needs a validated touch layout. Use a controller for now.",
        )
    }

    override fun supportedProfiles(): List<PlatformControlProfile> = profiles

    private fun touchProfile(
        familyId: String,
        displayName: String,
        platformSlugs: Set<String>,
        presets: List<TouchLayoutPreset>,
    ): PlatformControlProfile {
        return PlatformControlProfile(
            familyId = familyId,
            displayName = displayName,
            platformSlugs = platformSlugs,
            touchSupportMode = TouchSupportMode.FULL,
            preferredViewportAspectRatio = when (familyId) {
                "nes", "snes", "sega16", "psx", "atari", "tg16", "arcade" -> 4f / 3f
                "gb" -> 10f / 9f
                "gba" -> 3f / 2f
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
            touchSupportMode = TouchSupportMode.CONTROLLER_FIRST,
            controllerFallbackMessage = message,
        )
    }
}
