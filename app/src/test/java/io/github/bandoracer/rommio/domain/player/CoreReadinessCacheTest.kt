package io.github.bandoracer.rommio.domain.player

import android.content.Context
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * Readiness (Settings › Cores and BIOS, a game's page, lists) does no repeated disk work: a game's
 * tier needs no disk at all, each BIOS is identified once per file identity, and each bundled core
 * manifest is read once. Launch-time checks still read every byte.
 */
class CoreReadinessCacheTest {
    private val root = Files.createTempDirectory("core-readiness").toFile()
    private val library = object : LibraryStore {
        override fun rootDirectory() = root
        override fun systemDirectory() = File(root, "system")
        override fun biosDirectory() = File(root, "bios")
        override fun coresDirectory() = File(root, "cores")
        override fun ensureRootLayout() { listOf(systemDirectory(), biosDirectory(), coresDirectory()).forEach { it.mkdirs() } }
        override fun romDirectory(platformSlug: String): File = error("unused")
        override fun romTarget(platformSlug: String, fileName: String): File = error("unused")
        override fun saveRamFile(installation: DownloadedRomEntity): File = error("unused")
        override fun saveStatesDirectory(installation: DownloadedRomEntity): File = error("unused")
        override fun continuityResumeStateFile(installation: DownloadedRomEntity): File = error("unused")
        override fun saveStateFile(installation: DownloadedRomEntity, slot: Int): File = error("unused")
        override fun screenshotsDirectory(installation: DownloadedRomEntity): File = error("unused")
        override fun resolveCoreLibrary(context: Context, libraryFileName: String): File? = null
    }
    private val families = buildPlatformRuntimeFamilies()
    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    @After fun cleanup() {
        root.walkTopDown().forEach { it.setReadable(true) }
        root.deleteRecursively()
    }

    /** The catalogue only: resolving a game (the disk) is not allowed. */
    private val catalogueOnly = object : CoreResolver {
        val bySlug = families.flatMap { family -> family.platformSlugs.map { it to family } }.toMap()
        override fun resolve(platformSlug: String, fileExtension: String?): CoreResolution = error("A tier must not resolve the core")
        override fun prepareCore(profile: RuntimeProfile, expected: CoreArtifactIdentity): InstalledCore = error("unused")
        override fun platformSupport(platformSlug: String): PlatformRuntimeFamily? = bySlug[platformSlug.lowercase()]
        override fun supportedPlatforms(): List<PlatformRuntimeFamily> = families
    }

    @Test fun aGamesTierComesFromItsPlatformAndFileTypeWithoutTheDisk() {
        for (family in families) {
            val profile = requireNotNull(family.defaultProfile)
            for (slug in family.platformSlugs) {
                assertEquals(family.supportTier, catalogueOnly.supportTier(slug, null))
                assertEquals(family.supportTier, catalogueOnly.supportTier(slug.uppercase(), null))
                profile.supportedExtensions.forEach { assertEquals(family.supportTier, catalogueOnly.supportTier(slug, it)) }
                // A file type the core does not open is unsupported, exactly as resolve() reports it.
                val expected = if (profile.supportedExtensions.isEmpty()) family.supportTier else EmbeddedSupportTier.UNSUPPORTED
                assertEquals(expected, catalogueOnly.supportTier(slug, "not-a-rom-type"))
            }
        }
        assertEquals(EmbeddedSupportTier.UNSUPPORTED, catalogueOnly.supportTier("not-a-platform", null))
    }

    /**
     * The tier before this change was derived from the full resolution: Browse only only when the
     * resolution is UNSUPPORTED; a missing core, or one whose setup failed, resolves MISSING_CORE and
     * kept the family's tier. The disk-free tier keeps that (CoreSupportTierTest checks the real
     * resolver on a device).
     */
    @Test fun aMissingOrFailedCoreKeepsTheTierItHadBefore() {
        fun tierBefore(resolution: CoreResolution) =
            if (resolution.capability == PlayerCapability.UNSUPPORTED) EmbeddedSupportTier.UNSUPPORTED
            else resolution.platformFamily?.supportTier ?: EmbeddedSupportTier.UNSUPPORTED
        val snes = families.first { "snes" in it.platformSlugs }
        val missing = CoreResolution(PlayerCapability.MISSING_CORE, snes, snes.defaultProfile)
        val failed = CoreResolution(PlayerCapability.MISSING_CORE, snes, snes.defaultProfile, message = "Installed core content changed.")
        for (resolution in listOf(missing, failed)) {
            assertEquals(tierBefore(resolution), catalogueOnly.supportTier("snes", "sfc"))
        }
        assertEquals(EmbeddedSupportTier.TOUCH_SUPPORTED, catalogueOnly.supportTier("snes", "sfc"))
        assertEquals(tierBefore(CoreResolution(PlayerCapability.UNSUPPORTED)), catalogueOnly.supportTier("not-a-platform", "sfc"))
    }

    @Test fun readinessIdentifiesABiosOncePerFileIdentityAndLaunchReadsItAgain() {
        library.ensureRootLayout()
        val bytes = "synthetic firmware".toByteArray()
        val store = FirmwareStore(library, listOf(FirmwareDefinition("scph5501.bin", "PlayStation US BIOS", md5(bytes))))
        val psx = requireNotNull(families.first { "psx" in it.platformSlugs }.defaultProfile)
        assertEquals(listOf("scph5501.bin"), store.missing(psx, "psx"))
        val bios = File(library.systemDirectory(), "scph5501.bin").apply { writeBytes(bytes) }
        assertTrue(store.missing(psx, "psx").isEmpty())

        // Unreadable now: readiness does not read the file again while its identity is unchanged...
        bios.setReadable(false)
        assumeFalse("Running as a user who can read any file", bios.canRead())
        repeat(3) { assertTrue(store.missing(psx, "psx").isEmpty()) }
        // ...while a launch's check reads every byte (and here cannot).
        assertEquals(listOf("scph5501.bin"), store.missing(psx, "psx", fresh = true))
        bios.setReadable(true)

        // A changed file is identified afresh.
        bios.writeBytes("wrong firmware bytes, longer".toByteArray())
        assertEquals(listOf("scph5501.bin"), store.missing(psx, "psx"))
        bios.delete()
        assertEquals(listOf("scph5501.bin"), store.missing(psx, "psx"))
    }

    @Test fun eachBundledManifestIsReadOnce() {
        val nes = requireNotNull(families.first { it.familyId == "nes" }.defaultProfile)
        val snes = requireNotNull(families.first { "snes" in it.platformSlugs }.defaultProfile)
        val hash = "a".repeat(64)
        val artifacts = AndroidCoreAbi.entries.joinToString(",") {
            """{"abi":"${it.folderName}","assetPath":"cores/${nes.runtimeId}/${it.folderName}/${nes.libraryFileName}","sha256":"$hash","sizeBytes":1}"""
        }
        val manifest = """{"schemaVersion":1,"runtimeId":"${nes.runtimeId}","sourceRevision":"${"b".repeat(40)}",
            "sourceManifestSha256":"$hash","recipeSha256":"$hash","ndkVersion":"27.2.12479018","androidApi":26,
            "persistenceExtension":"rommio-fceumm-segments-v2","artifacts":[$artifacts]}"""
        val reads = mutableListOf<String>()
        val packaged = PackagedCoreArtifacts(library.coresDirectory(), CoreArtifactStore(library.coresDirectory())) { path ->
            reads += path
            if (path == "cores/${nes.runtimeId}/manifest.json") manifest.byteInputStream() as InputStream else throw FileNotFoundException(path)
        }
        repeat(3) {
            AndroidCoreAbi.entries.forEach { abi ->
                assertEquals(abi.folderName, packaged.find(nes, abi)?.file?.abi)
                // A runtime this APK ships no manifest for is remembered as absent too.
                assertEquals(null, packaged.find(snes, abi))
            }
        }
        assertEquals(listOf("cores/${nes.runtimeId}/manifest.json", "cores/${snes.runtimeId}/manifest.json"), reads)
    }
}
