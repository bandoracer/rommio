package io.github.bandoracer.rommio.domain.player

import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import io.github.bandoracer.rommio.domain.storage.StorageAccess
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.InstallIdentity
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Known immutable firmware only. Mutable files produced in a core's system directory are progress. */
data class FirmwareDefinition(val path: String, val label: String, val md5: String)
data class FirmwareStatus(val definition: FirmwareDefinition, val installed: Boolean, val problem: String?, val legacyCopies: List<String>)
data class RuntimeFirmware(val systemDirectory: File, val sha256: Map<String, String>)

class FirmwareStore(
    private val library: LibraryStore,
    val catalog: List<FirmwareDefinition> = DEFAULT_FIRMWARE,
    private val openAsset: ((String) -> InputStream)? = null,
) {
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    init {
        require(catalog.distinctBy { it.path }.size == catalog.size)
        catalog.forEach { definition ->
            require(definition.path.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") && it.matches(Regex("[A-Za-z0-9_.() -]+")) })
            require(definition.md5.matches(Regex("[0-9a-f]{32}")))
        }
    }

    fun requiredPaths(profile: RuntimeProfile, platformSlug: String): List<String> =
        (profile.requiredBiosFiles + if (platformSlug.lowercase() == "segacd" && profile.runtimeId in setOf("genesis_plus_gx", "picodrive"))
            listOf("bios_CD_E.bin", "bios_CD_U.bin", "bios_CD_J.bin") else emptyList()).distinct()

    fun missing(profile: RuntimeProfile, platformSlug: String): List<String> = requiredPaths(profile, platformSlug).filter { path ->
        val definition = catalog.firstOrNull { it.path == path }
        if (profile.runtimeId == "opera" && path == "panafz10.bin" && catalog.any { it.path in OPERA_BOOT_FILES && valid(it, systemFile(it.path)) }) false
        else definition == null || !valid(definition, systemFile(path))
    }

    suspend fun status(): List<FirmwareStatus> = StorageAccess.normal { withContext(Dispatchers.IO) {
        catalog.map { definition ->
            val target = systemFile(definition.path)
            val ready = valid(definition, target)
            val legacy = listOf(File(library.biosDirectory(), definition.path), File(library.biosDirectory(), File(definition.path).name),
                File(library.systemDirectory(), File(definition.path).name)).distinctBy { it.absolutePath }
                .filter { it.absolutePath != target.absolutePath && valid(definition, it) }
            FirmwareStatus(definition, ready, if (target.exists() && !ready) "Existing file has the wrong content or is linked; it has been preserved." else null,
                legacy.map { it.absolutePath })
        }
    } }

    /** The caller provides a user-selected document, never a remote BIOS download. */
    suspend fun import(path: String, open: () -> InputStream) = StorageAccess.exclusive { withContext(Dispatchers.IO) {
        StorageAccess.checkRecovered()
        install(requireNotNull(catalog.find { it.path == path }) { "Unknown firmware selection." }, open)
        revision.value++
    } }

    suspend fun useLegacyCopy(path: String, sourcePath: String) = StorageAccess.exclusive { withContext(Dispatchers.IO) {
        StorageAccess.checkRecovered()
        val entry = status().first { it.definition.path == path }
        check(sourcePath in entry.legacyCopies) { "The older firmware changed. Inspect it again." }
        install(entry.definition) { File(sourcePath).inputStream() }
        revision.value++
    } }

    private fun install(definition: FirmwareDefinition, open: () -> InputStream) {
        val target = systemFile(definition.path)
        checkSafe(target)
        val staged = AtomicFiles.stagingFile(target)
        try {
            java.io.FileOutputStream(staged).use { output ->
                open().use { input ->
                    val buffer = ByteArray(65536); var size = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        size += count; check(size <= MAX_BIOS_BYTES) { "The selected file is too large for this BIOS." }
                        output.write(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
            check(valid(definition, staged)) { "This file does not match ${definition.label}. The existing BIOS was not changed." }
            if (target.exists()) {
                check(target.isFile) { "A directory occupies the firmware path. It has not been changed." }
                if (digest(target, "SHA-256") == digest(staged, "SHA-256")) return
                val backup = File(library.rootDirectory(), "firmware-backups/${digest(target, "SHA-256")}/${definition.path}")
                checkSafe(backup)
                if (!backup.exists()) AtomicFiles.write(backup) { output -> target.inputStream().use { it.copyTo(output) } }
                check(digest(backup, "SHA-256") == digest(target, "SHA-256")) { "Unable to preserve the previous firmware." }
            }
            AtomicFiles.install(staged, target)
        } finally { staged.delete() }
    }

    suspend fun prepare(installation: DownloadedRomEntity, profile: RuntimeProfile): RuntimeFirmware = StorageAccess.normal { withContext(Dispatchers.IO) {
        mutex.withLock {
            if (profile.runtimeId == "ppsspp") return@withLock preparePSPAssets()
            val missing = missing(profile, installation.platformSlug)
            check(missing.isEmpty()) { "Import valid BIOS files in Settings before playing: ${missing.joinToString()}." }
            // Only these runtime layouts are qualified by this contract. Other runtime assets keep their existing path until R07/R08 describe them.
            val paths = if (installation.platformSlug.lowercase() == "naomi") null else pathsForRuntime(profile.runtimeId)
            if (paths == null) return@withLock RuntimeFirmware(library.systemDirectory(), emptyMap())
            require(profile.runtimeId.matches(Regex("[a-zA-Z0-9_-]+")))
            val binding = InstallIdentity.progress(installation.ownerBaseUrl, installation.ownerAccountId, installation.romId, installation.fileId, installation.contentSha256)
            val identity = if (binding == InstallIdentity.LEGACY) "legacy-${installation.romId}-${installation.fileId}" else binding
            val directory = File(library.rootDirectory(), "runtime-system/$identity/${profile.runtimeId}")
            checkSafe(directory)
            check(directory.isDirectory || directory.mkdirs()) { "Unable to create runtime system directory." }
            val manifest = File(directory, ".rommio-firmware")
            checkSafe(manifest)
            if (manifest.exists()) {
                val lines = manifest.readLines()
                check(lines.firstOrNull() == "rommio-firmware-v1") { "Unknown runtime firmware manifest. Existing files are retained." }
                val recorded = linkedMapOf<String, String>()
                lines.drop(1).forEach { line ->
                    val sha = line.substringBefore(' ')
                    val path = line.substringAfter(' ', "")
                    val definition = catalog.firstOrNull { it.path == path }
                    check(sha.matches(Regex("[0-9a-f]{64}")) && path in paths && definition != null && path !in recorded) { "Invalid runtime firmware manifest." }
                    val destination = File(directory, path)
                    check(valid(requireNotNull(definition), destination) && digest(destination, "SHA-256") == sha) { "The game's firmware copy changed. Existing files are retained for recovery." }
                    recorded[path] = sha
                }
                check(paths.filter { File(directory, it).exists() }.all { it in recorded }) { "Unrecorded firmware appeared in the game's system directory. Existing files are retained." }
                val required = requiredPaths(profile, installation.platformSlug)
                check(required.all { it in recorded || (profile.runtimeId == "opera" && recorded.keys.any { path -> path in OPERA_BOOT_FILES }) }) { "The game's firmware selection does not satisfy this runtime. Explicit migration is required." }
                return@withLock RuntimeFirmware(directory, recorded)
            }
            val hashes = linkedMapOf<String, String>()
            catalog.filter { it.path in paths }.forEach { definition ->
                val source = systemFile(definition.path)
                val destination = File(directory, definition.path)
                checkSafe(destination)
                if (valid(definition, source)) {
                    val sha = digest(source, "SHA-256")
                    if (destination.exists()) check(destination.isFile && digest(destination, "SHA-256") == sha) {
                        "The game's firmware copy changed. Its system directory has been preserved for recovery."
                    } else AtomicFiles.write(destination) { output -> source.inputStream().use { it.copyTo(output) } }
                    check(digest(destination, "SHA-256") == sha) { "Firmware changed during runtime preparation." }
                }
                if (destination.exists()) {
                    check(valid(definition, destination)) { "The game's firmware copy is invalid. Its system directory has been preserved." }
                    hashes[definition.path] = digest(destination, "SHA-256")
                }
            }
            AtomicFiles.writeBytes(manifest, ("rommio-firmware-v1\n" + hashes.toSortedMap().entries.joinToString("") { "${it.value} ${it.key}\n" }).toByteArray())
            RuntimeFirmware(directory, hashes)
        }
    } }

    private fun preparePSPAssets(): RuntimeFirmware {
        val open = requireNotNull(openAsset) { "The bundled PSP assets are unavailable." }
        val manifest = open("cores/ppsspp/system-manifest.json").use { org.json.JSONObject(it.bufferedReader().readText()) }
        require(manifest.length() in 1..128)
        val root = File(library.rootDirectory(), "runtime-assets/ppsspp")
        checkSafe(root); check(root.isDirectory || root.mkdirs())
        val hashes = mutableMapOf<String, String>()
        manifest.keys().forEach { path ->
            io.github.bandoracer.rommio.domain.progress.ProgressProtocol.validatePath(path)
            require(path.startsWith("PPSSPP/"))
            val entry = manifest.getJSONObject(path); val size = entry.getLong("sizeBytes"); val hash = entry.getString("sha256")
            require(size in 1..MAX_BIOS_BYTES && hash.matches(Regex("[a-f0-9]{64}")))
            val destination = File(root, path); checkSafe(destination)
            if (!destination.isFile || destination.length() != size || digest(destination, "SHA-256") != hash) {
                AtomicFiles.write(destination) { output -> open("cores/ppsspp/system/$path").use { it.copyTo(output) } }
                check(destination.length() == size && digest(destination, "SHA-256") == hash) { "Bundled PSP assets changed." }
            }
            hashes[path] = hash
        }
        return RuntimeFirmware(root, hashes)
    }

    private fun systemFile(path: String) = File(library.systemDirectory(), path)
    private fun valid(definition: FirmwareDefinition, file: File): Boolean = runCatching {
        checkSafe(file)
        file.isFile && file.length() in 1..MAX_BIOS_BYTES && digest(file, "MD5") == definition.md5
    }.getOrDefault(false)
    private fun checkSafe(file: File) {
        val root = library.rootDirectory().absoluteFile
        val relative = file.absoluteFile.relativeToOrNull(root)?.invariantSeparatorsPath ?: error("Firmware path is outside the library.")
        require(relative.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") }) { "Invalid firmware path." }
        check(file.canonicalFile == File(root.canonicalFile, relative)) { "Linked firmware paths are not supported." }
    }
    private fun digest(file: File, algorithm: String): String {
        val hash = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun pathsForRuntime(runtimeId: String): Set<String>? = when (runtimeId) {
        "pcsx_rearmed", "swanstation" -> setOf("scph5500.bin", "scph5501.bin", "scph5502.bin")
        "flycast" -> setOf("dc/dc_boot.bin")
        "opera" -> OPERA_BOOT_FILES + setOf("panafz1-kanji.bin", "panafz10ja-anvil-kanji.bin", "panafz1j-kanji.bin")
        "handy" -> setOf("lynxboot.img")
        "prosystem" -> setOf("7800 BIOS (U).rom")
        "genesis_plus_gx", "picodrive" -> setOf("bios_CD_E.bin", "bios_CD_U.bin", "bios_CD_J.bin")
        else -> null
    }
    companion object {
        private const val MAX_BIOS_BYTES = 16L * 1024 * 1024
        private val OPERA_BOOT_FILES = setOf("panafz1.bin", "panafz10.bin", "panafz10-norsa.bin", "panafz10e-anvil.bin", "panafz10e-anvil-norsa.bin",
            "panafz1j.bin", "panafz1j-norsa.bin", "goldstar.bin", "sanyotry.bin", "3do_arcade_saot.bin")
        // Identification hashes from libretro-core-info 5a74858ab2f7a50cebb5a6330895bc38899531c0 and Libretro Genesis Plus GX docs.
        // MD5 recognizes known dumps; SHA-256 records the exact provisioned bytes for compatibility descriptors.
        val DEFAULT_FIRMWARE = listOf(
            FirmwareDefinition("scph5500.bin", "PlayStation Japan BIOS", "8dd7d5296a650fac7319bce665a6a53c"),
            FirmwareDefinition("scph5501.bin", "PlayStation US BIOS", "490f666e1afb15b7362b406ed1cea246"),
            FirmwareDefinition("scph5502.bin", "PlayStation Europe BIOS", "32736f17079d0b2b7024407c39bd3050"),
            FirmwareDefinition("dc/dc_boot.bin", "Dreamcast boot BIOS", "e10c53c2f8b90bab96ead2d368858623"),
            FirmwareDefinition("panafz10.bin", "3DO Panasonic FZ-10 BIOS", "51f2f43ae2f3508a14d9f56597e2d3ce"),
            FirmwareDefinition("panafz1.bin", "3DO Panasonic FZ-1 BIOS", "f47264dd47fe30f73ab3c010015c155b"),
            FirmwareDefinition("panafz10-norsa.bin", "3DO FZ-10 RSA-patched BIOS", "1477bda80dc33731a65468c1f5bcbee9"),
            FirmwareDefinition("panafz10e-anvil.bin", "3DO FZ-10E Anvil BIOS", "a48e6746bd7edec0f40cff078f0bb19f"),
            FirmwareDefinition("panafz10e-anvil-norsa.bin", "3DO FZ-10E Anvil RSA-patched BIOS", "cf11bbb5a16d7af9875cca9de9a15e09"),
            FirmwareDefinition("panafz1j.bin", "3DO Panasonic FZ-1J BIOS", "a496cfdded3da562759be3561317b605"),
            FirmwareDefinition("panafz1j-norsa.bin", "3DO FZ-1J RSA-patched BIOS", "f6c71de7470d16abe4f71b1444883dc8"),
            FirmwareDefinition("goldstar.bin", "3DO GoldStar BIOS", "8639fd5e549bd6238cfee79e3e749114"),
            FirmwareDefinition("sanyotry.bin", "3DO Sanyo BIOS", "35fa1a1ebaaeea286dc5cd15487c13ea"),
            FirmwareDefinition("3do_arcade_saot.bin", "3DO Shootout at Old Tucson BIOS", "8970fc987ab89a7f64da9f8a8c4333ff"),
            FirmwareDefinition("panafz1-kanji.bin", "3DO FZ-1 Kanji font", "b8dc97f778a6245c58e064b0312e8281"),
            FirmwareDefinition("panafz10ja-anvil-kanji.bin", "3DO FZ-10JA Anvil Kanji font", "428577250f43edc902ea239c50d2240d"),
            FirmwareDefinition("panafz1j-kanji.bin", "3DO FZ-1J Kanji font", "c23fb5d5e6bb1c240d02cf968972be37"),
            FirmwareDefinition("lynxboot.img", "Atari Lynx boot image", "fcd403db69f54290b51035d82f835e7b"),
            FirmwareDefinition("7800 BIOS (U).rom", "Atari 7800 US boot image (optional)", "0763f1ffb006ddbe32e52d497ee848ae"),
            FirmwareDefinition("bios_CD_E.bin", "Mega CD Europe BIOS", "e66fa1dc5820d254611fdcdba0662372"),
            FirmwareDefinition("bios_CD_U.bin", "Sega CD US BIOS", "854b9150240a198070150e4566ae1290"),
            FirmwareDefinition("bios_CD_J.bin", "Mega CD Japan BIOS", "278a9397d192149e84e820ac621a8edd"),
        )
    }
}
