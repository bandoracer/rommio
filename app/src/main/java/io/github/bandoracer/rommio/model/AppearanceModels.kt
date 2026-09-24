package io.github.bandoracer.rommio.model

/** Visual theme. Presentation only: navigation, copy and test selectors never depend on it. */
enum class AppearanceTheme(val key: String, val label: String) {
    AURORA("aurora", "Aurora"),
    RISO("riso", "Riso"),
    ;

    companion object {
        val Default = AURORA
        fun fromKey(key: String?): AppearanceTheme = entries.firstOrNull { it.key == key } ?: Default
    }
}

/** Light/dark choice. System follows the device setting. */
enum class AppearanceScheme(val key: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    companion object {
        val Default = SYSTEM
        fun fromKey(key: String?): AppearanceScheme = entries.firstOrNull { it.key == key } ?: Default
    }
}

/** Device-local appearance preferences. Never part of backup or migration bundles. */
data class AppearancePreferences(
    val theme: AppearanceTheme = AppearanceTheme.Default,
    val scheme: AppearanceScheme = AppearanceScheme.Default,
)
