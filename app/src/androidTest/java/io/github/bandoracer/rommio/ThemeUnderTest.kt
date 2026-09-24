package io.github.bandoracer.rommio

import androidx.test.platform.app.InstrumentationRegistry
import io.github.bandoracer.rommio.model.AppearancePreferences
import io.github.bandoracer.rommio.model.AppearanceScheme
import io.github.bandoracer.rommio.model.AppearanceTheme

/**
 * The theme UI tests render under. Themes are presentation only, so the same tests and selectors
 * must pass under each; pick one per run with instrumentation arguments, e.g.
 * `-Pandroid.testInstrumentationRunnerArguments.rommioTheme=riso` and `...rommioScheme=dark`.
 * Defaults to Aurora light.
 */
object ThemeUnderTest {
    private val arguments get() = InstrumentationRegistry.getArguments()

    val preferences: AppearancePreferences
        get() = AppearancePreferences(
            theme = AppearanceTheme.fromKey(arguments.getString("rommioTheme")),
            scheme = AppearanceScheme.fromKey(arguments.getString("rommioScheme") ?: AppearanceScheme.LIGHT.key)
                .takeIf { it != AppearanceScheme.SYSTEM } ?: AppearanceScheme.LIGHT,
        )

    /** For file names: "aurora-light". */
    val label: String get() = preferences.let { "${it.theme.key}-${it.scheme.key}" }
}
