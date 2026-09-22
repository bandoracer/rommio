package io.github.bandoracer.rommio.domain.storage

import android.content.Context
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import java.io.File
import io.github.bandoracer.rommio.model.InstallIdentity

interface LibraryStore {
    fun ensureRootLayout()
    fun rootDirectory(): File
    fun romDirectory(platformSlug: String): File
    fun romTarget(platformSlug: String, fileName: String): File
    fun saveRamFile(installation: DownloadedRomEntity): File
    fun saveStatesDirectory(installation: DownloadedRomEntity): File
    fun continuityResumeStateFile(installation: DownloadedRomEntity): File
    fun saveStateFile(installation: DownloadedRomEntity, slot: Int): File
    fun screenshotsDirectory(installation: DownloadedRomEntity): File
    fun systemDirectory(): File
    fun biosDirectory(): File
    fun coresDirectory(): File
    fun resolveCoreLibrary(context: Context, libraryFileName: String): File?
}

class AppManagedLibraryStore(
    private val context: Context,
) : LibraryStore {
    private val root: File
        get() = File(context.filesDir, "library")

    override fun ensureRootLayout() {
        listOf(
            root,
            File(root, "roms"),
            File(root, "saves"),
            File(root, "states"),
            File(root, "screenshots"),
            File(root, "bios"),
            File(root, "cores"),
            File(root, "system"),
        ).forEach { it.mkdirs() }
    }

    override fun rootDirectory(): File = root

    override fun romDirectory(platformSlug: String): File {
        return File(File(root, "roms"), platformSlug)
    }

    override fun romTarget(platformSlug: String, fileName: String): File {
        return File(romDirectory(platformSlug), fileName)
    }

    override fun saveRamFile(installation: DownloadedRomEntity): File {
        return if (installation.progressScope == InstallIdentity.LEGACY) File(File(root, "saves"), "${installation.romId}_${installation.fileId}.srm")
        else File(progressDirectory(installation), "saves/save.srm")
    }

    override fun saveStatesDirectory(installation: DownloadedRomEntity): File {
        return if (installation.progressScope == InstallIdentity.LEGACY) File(File(root, "states"), installation.romId.toString())
        else File(progressDirectory(installation), "states")
    }

    override fun continuityResumeStateFile(installation: DownloadedRomEntity): File {
        return File(saveStatesDirectory(installation), "__rommio_resume_${installation.romId}_${installation.fileId}.state")
    }

    override fun saveStateFile(installation: DownloadedRomEntity, slot: Int): File {
        return File(saveStatesDirectory(installation), "${installation.romId}_slot$slot.state")
    }

    override fun screenshotsDirectory(installation: DownloadedRomEntity): File {
        return if (installation.progressScope == InstallIdentity.LEGACY) File(File(root, "screenshots"), installation.romId.toString())
        else File(progressDirectory(installation), "screenshots")
    }

    private fun progressDirectory(installation: DownloadedRomEntity): File {
        require(installation.progressScope.matches(Regex("[0-9a-f]{64}"))) { "Invalid installed progress scope." }
        return File(root, "progress-local/${installation.progressScope}")
    }

    override fun systemDirectory(): File {
        return File(root, "system")
    }

    override fun biosDirectory(): File {
        return File(root, "bios")
    }

    override fun coresDirectory(): File {
        return File(root, "cores")
    }

    override fun resolveCoreLibrary(context: Context, libraryFileName: String): File? {
        val packaged = File(context.applicationInfo.nativeLibraryDir, libraryFileName)
        if (packaged.exists()) {
            return packaged
        }

        val managed = File(coresDirectory(), libraryFileName)
        return managed.takeIf { it.exists() }
    }
}
