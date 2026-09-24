package io.github.bandoracer.rommio.data.input

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.bandoracer.rommio.model.AppearancePreferences
import io.github.bandoracer.rommio.model.AppearanceScheme
import io.github.bandoracer.rommio.model.AppearanceTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Theme and light/dark choice for this device. Stored in its own DataStore file and deliberately
 * not exported by `MigrationBundleManager`: appearance is device-local, not part of backups.
 */
class AppearancePreferencesStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(
        context: Context,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { context.preferencesDataStoreFile(FILE_NAME) },
        ),
    )

    /** For tests and tools that manage the file themselves. */
    constructor(file: File, scope: CoroutineScope) : this(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
    )

    val preferencesFlow: Flow<AppearancePreferences> = dataStore.data
        .map { it.toModel() }
        .distinctUntilChanged()

    suspend fun snapshot(): AppearancePreferences = dataStore.data.first().toModel()

    suspend fun setTheme(theme: AppearanceTheme) {
        dataStore.edit { it[THEME] = theme.key }
    }

    suspend fun setScheme(scheme: AppearanceScheme) {
        dataStore.edit { it[SCHEME] = scheme.key }
    }

    private fun Preferences.toModel(): AppearancePreferences = AppearancePreferences(
        theme = AppearanceTheme.fromKey(this[THEME]),
        scheme = AppearanceScheme.fromKey(this[SCHEME]),
    )

    companion object {
        const val FILE_NAME = "appearance.preferences_pb"
        private val THEME = stringPreferencesKey("theme")
        private val SCHEME = stringPreferencesKey("scheme")
    }
}
