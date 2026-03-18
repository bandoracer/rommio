package io.github.mattsays.rommnative.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.mattsays.rommnative.AppContainer
import io.github.mattsays.rommnative.BuildConfig
import io.github.mattsays.rommnative.RommNativeApplication
import io.github.mattsays.rommnative.domain.player.PlayerCapability
import io.github.mattsays.rommnative.model.AuthStatus
import io.github.mattsays.rommnative.model.CloudflareServiceCredentials
import io.github.mattsays.rommnative.model.DirectLoginCredentials
import io.github.mattsays.rommnative.model.DownloadedRomEntity
import io.github.mattsays.rommnative.model.EdgeAuthMode
import io.github.mattsays.rommnative.model.OriginAuthMode
import io.github.mattsays.rommnative.model.ServerAccessStatus
import io.github.mattsays.rommnative.util.buildRomContentPath
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalagaLiveSmokeTest {
    @Test
    fun galaga_downloads_through_native_auth_and_installs_recommended_nes_core() = runBlocking {
        assumeConfigured()
        val application = ApplicationProvider.getApplicationContext<RommNativeApplication>()
        val container = AppContainer(application.applicationContext)
        val repository = container.repository

        val profile = connect(repository = repository)
        assertEquals(AuthStatus.CONNECTED, repository.validateProfile(profile.id))

        val platforms = repository.getPlatforms()
        val nesPlatform = platforms.firstOrNull { platform ->
            platform.slug.equals("nes", ignoreCase = true) || platform.fsSlug.equals("nes", ignoreCase = true)
        }
        requireNotNull(nesPlatform) { "Expected NES platform in RomM library." }

        val nesRoms = repository.getRomsByPlatform(nesPlatform.id)
        val galaga = nesRoms.firstOrNull { rom ->
            rom.displayName.lowercase(Locale.US).contains("galaga")
        } ?: repository.getRomById(3)

        assertTrue("Expected to find Galaga in the NES library.", galaga.displayName.contains("Galaga", ignoreCase = true))
        assertEquals("nes", galaga.platformSlug)

        val romFile = galaga.files.firstOrNull { file -> file.effectiveFileExtension.equals("nes", ignoreCase = true) }
        requireNotNull(romFile) { "Expected a downloadable NES ROM file for Galaga." }

        val resolution = repository.resolveCoreSupport(galaga, romFile)
        assertEquals(PlayerCapability.MISSING_CORE, resolution.capability)
        val expectedCore = requireNotNull(resolution.runtimeProfile) { "Expected a recommended runtime profile for NES." }
        container.libraryStore.coresDirectory()
            .resolve(expectedCore.libraryFileName)
            .delete()

        val target = container.libraryStore.romTarget(galaga.platformSlug, romFile.fileName)
        target.delete()
        container.downloadClient.downloadToFile(
            profileId = profile.id,
            absoluteUrl = profile.baseUrl.removeSuffix("/") + buildRomContentPath(galaga.id, romFile.fileName),
            target = target,
        )

        assertTrue("Expected Galaga ROM to download to app-managed storage.", target.exists())
        assertTrue("Expected Galaga ROM to contain data.", target.length() > 1024L)

        container.downloadedRomDao.upsert(
            DownloadedRomEntity(
                romId = galaga.id,
                fileId = romFile.id,
                platformSlug = galaga.platformSlug,
                romName = galaga.displayName,
                fileName = romFile.fileName,
                localPath = target.absolutePath,
                fileSizeBytes = romFile.fileSizeBytes,
                downloadedAtEpochMs = System.currentTimeMillis(),
            ),
        )

        val installed = repository.observeInstalledFiles(galaga.id).first()
        assertTrue("Expected Galaga install record after native download.", installed.any { it.fileId == romFile.id })

        val updatedResolution = repository.installRecommendedCore(galaga, romFile)
        assertEquals(PlayerCapability.READY, updatedResolution.capability)
        assertTrue(
            "Expected the recommended NES core to be written into app-managed storage.",
            container.libraryStore.coresDirectory().resolve(expectedCore.libraryFileName).exists(),
        )

        val session = repository.buildPlayerSession(installed.first { it.fileId == romFile.id }, galaga)
        assertEquals(expectedCore.runtimeId, session.runtimeProfile.runtimeId)
        assertTrue("Expected player session to reference an installed core library.", session.coreLibrary.exists())
    }

    private suspend fun connect(repository: io.github.mattsays.rommnative.data.repository.RommRepository): io.github.mattsays.rommnative.model.ServerProfile {
        val discovery = repository.discoverServer(BuildConfig.DEBUG_TEST_BASE_URL)
        assertEquals(EdgeAuthMode.CLOUDFLARE_ACCESS_SESSION, discovery.recommendedEdgeAuthMode)
        assertEquals(OriginAuthMode.ROMM_BEARER_PASSWORD, discovery.recommendedOriginAuthMode)

        repository.logout(clearServerAccess = true)

        val profile = repository.configureServerProfile(
            baseUrl = BuildConfig.DEBUG_TEST_BASE_URL,
            edgeAuthMode = EdgeAuthMode.CLOUDFLARE_ACCESS_SERVICE,
            originAuthMode = OriginAuthMode.ROMM_BEARER_PASSWORD,
            discoveryResult = discovery,
            makeActive = true,
        )
        repository.setCloudflareServiceCredentials(
            profile.id,
            CloudflareServiceCredentials(
                clientId = BuildConfig.DEBUG_TEST_CLIENT_ID,
                clientSecret = BuildConfig.DEBUG_TEST_CLIENT_SECRET,
            ),
        )
        assertEquals(ServerAccessStatus.READY, repository.testServerAccess(profile.id).status)
        repository.loginWithDirectCredentials(
            profile.id,
            DirectLoginCredentials(
                username = BuildConfig.DEBUG_TEST_USERNAME,
                password = BuildConfig.DEBUG_TEST_PASSWORD,
            ),
        )
        return requireNotNull(repository.currentProfile())
    }

    private fun assumeConfigured() {
        assumeTrue("ROMM_TEST_BASE_URL is required for live Galaga tests.", BuildConfig.DEBUG_TEST_BASE_URL.isNotBlank())
        assumeTrue("ROMM_TEST_CLIENT_ID is required for live Galaga tests.", BuildConfig.DEBUG_TEST_CLIENT_ID.isNotBlank())
        assumeTrue("ROMM_TEST_CLIENT_SECRET is required for live Galaga tests.", BuildConfig.DEBUG_TEST_CLIENT_SECRET.isNotBlank())
        assumeTrue("ROMM_TEST_USERNAME is required for live Galaga tests.", BuildConfig.DEBUG_TEST_USERNAME.isNotBlank())
        assumeTrue("ROMM_TEST_PASSWORD is required for live Galaga tests.", BuildConfig.DEBUG_TEST_PASSWORD.isNotBlank())
    }
}
