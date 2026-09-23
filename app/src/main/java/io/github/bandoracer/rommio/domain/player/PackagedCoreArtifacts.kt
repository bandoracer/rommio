package io.github.bandoracer.rommio.domain.player

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import io.github.bandoracer.rommio.domain.storage.LibraryStore
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

data class PackagedCoreFile(val abi: String, val assetPath: String, val sha256: String, val sizeBytes: Long)
data class PackagedCoreManifest(val schemaVersion: Int, val runtimeId: String, val sourceRevision: String,
    val sourceManifestSha256: String, val recipeSha256: String, val ndkVersion: String, val androidApi: Int,
    val persistenceExtension: String, val artifacts: List<PackagedCoreFile>)
data class PackagedCoreArtifact(val manifest: PackagedCoreManifest, val file: PackagedCoreFile) {
    val identity get() = CoreArtifactIdentity(manifest.runtimeId, file.abi, file.sha256, file.sizeBytes,
        "packaged-source", "https://github.com/${when (manifest.runtimeId) { "fceumm" -> "libretro/libretro-fceumm"; "gambatte" -> "libretro/gambatte-libretro"; "melonds_ds" -> "JesseTG/melonds-ds"; "genesis_plus_gx" -> "libretro/Genesis-Plus-GX"; "mupen64plus_next_gles3" -> "libretro/mupen64plus-libretro-nx"; "ppsspp" -> "hrydgard/ppsspp"; "stella" -> "libretro/stella"; "prosystem" -> "libretro/prosystem-libretro"; "handy" -> "libretro/libretro-handy"; else -> "libretro/${manifest.runtimeId}" }}/tree/${manifest.sourceRevision}", approvedPin = true)
}

/** Reads the manifest shipped in this APK, never imported app-data provenance flags. */
class PackagedCoreArtifacts(
    private val directory: File,
    private val artifacts: CoreArtifactStore,
    private val openAsset: (String) -> InputStream,
) {
    constructor(context: Context, library: LibraryStore, artifacts: CoreArtifactStore) :
        this(library.coresDirectory(), artifacts, { context.assets.open(it) })

    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(PackagedCoreManifest::class.java)
    @Synchronized fun find(profile: RuntimeProfile, abi: AndroidCoreAbi): PackagedCoreArtifact? {
        if (profile.runtimeId !in setOf("fceumm", "gambatte", "mgba", "snes9x", "melonds_ds", "genesis_plus_gx", "pcsx_rearmed", "mupen64plus_next_gles3", "ppsspp", "stella", "prosystem", "handy")) return null
        val bytes = try {
            openAsset("cores/${profile.runtimeId}/manifest.json").use { input ->
                val buffer = ByteArray(65_537)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                }
                check(count in 1..65_536) { "Invalid packaged core manifest size." }
                buffer.copyOf(count)
            }
        } catch (_: FileNotFoundException) { return null }
        val manifest = requireNotNull(adapter.fromJson(bytes.toString(Charsets.UTF_8)))
        val hash = Regex("[0-9a-f]{64}")
        check(manifest.schemaVersion == 1 && manifest.runtimeId == profile.runtimeId &&
            manifest.sourceRevision.matches(Regex("[0-9a-f]{40}")) && manifest.sourceManifestSha256.matches(hash) &&
            manifest.recipeSha256.matches(hash) && manifest.ndkVersion == "27.2.12479018" && manifest.androidApi == 26 &&
            manifest.persistenceExtension == (if (profile.runtimeId == "fceumm") "rommio-fceumm-segments-v2" else if (profile.runtimeId == "ppsspp") "rommio-files-v1" else "rommio-cart-v1")) { "Invalid packaged core provenance." }
        check(manifest.artifacts.map { it.abi }.toSet() == AndroidCoreAbi.entries.map { it.folderName }.toSet() &&
            manifest.artifacts.size == AndroidCoreAbi.entries.size) { "Incomplete packaged core ABI inventory." }
        manifest.artifacts.forEach {
            check(it.sha256.matches(hash) && it.sizeBytes in 1..CoreBinary.MAX_BYTES &&
                it.assetPath == "cores/${profile.runtimeId}/${it.abi}/${profile.libraryFileName}") { "Invalid packaged core artifact." }
        }
        return PackagedCoreArtifact(manifest, manifest.artifacts.single { it.abi == abi.folderName })
    }

    /** Private memory IDs are granted only to exact bytes declared by the trusted APK. */
    @Synchronized fun verifiedMemoryInventory(profile: RuntimeProfile, installed: InstalledCore): Set<PersistentMemory> {
        val identity = installed.identity
        val abi = AndroidCoreAbi.entries.singleOrNull { it.folderName == identity.abi }
            ?: return PersistentMemory.STANDARD
        val bundled = find(profile, abi) ?: return PersistentMemory.STANDARD
        if (identity.runtimeId != bundled.identity.runtimeId || identity.sha256 != bundled.file.sha256 ||
            identity.sizeBytes != bundled.file.sizeBytes) return PersistentMemory.STANDARD
        check(CoreBinary.validate(installed.file, abi) == bundled.file.sha256) { "Core changed before persistence qualification." }
        return if (profile.runtimeId == "fceumm") PersistentMemory.FCEUMM_SEGMENTS_V2 else PersistentMemory.STANDARD
    }

    @Synchronized fun install(profile: RuntimeProfile, abi: AndroidCoreAbi, expected: CoreArtifactIdentity? = null): InstalledCore {
        val packaged = requireNotNull(find(profile, abi)) { "No bundled core for this runtime." }
        expected?.let { check(it == packaged.identity) { "Bundled core selection changed during preparation." } }
        val staged = AtomicFiles.stagingFile(File(directory, "staging/packaged.so"))
        try {
            staged.outputStream().use { output ->
                openAsset(packaged.file.assetPath).use { input ->
                    val buffer = ByteArray(65_536)
                    var count = 0L
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        count += size
                        check(count <= packaged.file.sizeBytes) { "Packaged core exceeds its declared size." }
                        output.write(buffer, 0, size)
                    }
                    check(count == packaged.file.sizeBytes) { "Packaged core is truncated." }
                }
                output.fd.sync()
            }
            return artifacts.install(profile, abi, staged, packaged.identity.source, packaged.identity.sourceUrl,
                expectedSha256 = packaged.file.sha256, approvedPin = true).also {
                check(it.identity == packaged.identity)
            }
        } finally { staged.delete() }
    }
}
