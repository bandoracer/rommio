package io.github.bandoracer.rommio.data.input

import io.github.bandoracer.rommio.model.AppearancePreferences
import io.github.bandoracer.rommio.model.AppearanceScheme
import io.github.bandoracer.rommio.model.AppearanceTheme
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AppearancePreferencesStoreTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("appearance-store").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun defaultsAreAuroraAndSystem() = runBlocking {
        withStore { store ->
            assertEquals(AppearancePreferences(AppearanceTheme.AURORA, AppearanceScheme.SYSTEM), store.snapshot())
            assertEquals(AppearancePreferences(), store.preferencesFlow.first())
        }
    }

    @Test
    fun choicesRoundTripAcrossInstances() = runBlocking {
        withStore { store ->
            store.setTheme(AppearanceTheme.RISO)
            store.setScheme(AppearanceScheme.DARK)
            assertEquals(AppearancePreferences(AppearanceTheme.RISO, AppearanceScheme.DARK), store.snapshot())
        }
        withStore { reopened ->
            assertEquals(AppearancePreferences(AppearanceTheme.RISO, AppearanceScheme.DARK), reopened.preferencesFlow.first())
            reopened.setScheme(AppearanceScheme.LIGHT)
            assertEquals(AppearanceScheme.LIGHT, reopened.snapshot().scheme)
            assertEquals(AppearanceTheme.RISO, reopened.snapshot().theme)
        }
    }

    @Test
    fun unknownStoredKeysFallBackToDefaults() {
        assertEquals(AppearanceTheme.AURORA, AppearanceTheme.fromKey("classic"))
        assertEquals(AppearanceTheme.AURORA, AppearanceTheme.fromKey(null))
        assertEquals(AppearanceScheme.SYSTEM, AppearanceScheme.fromKey("sepia"))
        assertEquals(AppearanceTheme.RISO, AppearanceTheme.fromKey("riso"))
        assertEquals(AppearanceScheme.DARK, AppearanceScheme.fromKey("dark"))
    }

    private suspend fun withStore(block: suspend (AppearancePreferencesStore) -> Unit) {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        try {
            block(AppearancePreferencesStore(File(directory, AppearancePreferencesStore.FILE_NAME), scope))
        } finally {
            job.cancelAndJoin()
        }
    }
}
