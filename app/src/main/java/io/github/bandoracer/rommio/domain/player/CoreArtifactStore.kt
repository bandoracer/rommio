package io.github.bandoracer.rommio.domain.player

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.domain.storage.AtomicFiles
import java.io.File
import java.util.UUID

/** Exact installed identity. An observed download hash is deliberately not labelled an approved pin. */
data class CoreArtifactIdentity(
    val runtimeId: String,
    val abi: String,
    val sha256: String,
    val sizeBytes: Long,
    val source: String,
    val sourceUrl: String? = null,
    val archiveSha256: String? = null,
    val approvedPin: Boolean = false,
)
data class InstalledCore(val file: File, val identity: CoreArtifactIdentity)
internal data class CoreSelection(val version: Int = 1, val installId: String, val libraryFileName: String, val identity: CoreArtifactIdentity)

class CoreArtifactStore(private val directory: File, private val checkpoint: (String) -> Unit = {}) {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(CoreSelection::class.java)
    private val cache = mutableMapOf<String, Pair<String, InstalledCore>>()

    @Synchronized fun selected(profile: RuntimeProfile, abi: AndroidCoreAbi, fresh: Boolean = false): InstalledCore? {
        val pointer = pointer(profile, abi)
        if (!pointer.exists()) return null
        safe(pointer)
        check(pointer.isFile && pointer.length() in 1..65536) { "Invalid core selection metadata size." }
        val encoded = pointer.readText()
        val selection = requireNotNull(adapter.fromJson(encoded)) { "Core selection is invalid." }
        val identity = selection.identity
        check(selection.version == 1 && identity.runtimeId == profile.runtimeId && identity.abi == abi.folderName &&
            selection.libraryFileName == profile.libraryFileName && identity.sha256.matches(Regex("[0-9a-f]{64}")) &&
            UUID.fromString(selection.installId).toString() == selection.installId) { "Core selection does not match this runtime." }
        val file = File(directory, "artifacts/${identity.sha256}/${selection.installId}/${selection.libraryFileName}")
        safe(file)
        check(file.isFile && file.length() == identity.sizeBytes) { "Installed core is missing or truncated. Download it again." }
        val stamp = "$encoded|${file.length()}|${file.lastModified()}"
        if (!fresh) cache[pointer.path]?.takeIf { it.first == stamp }?.let { return it.second }
        check(CoreBinary.validate(file, abi) == identity.sha256) { "Installed core content changed. Download it again; prior versions are retained." }
        return InstalledCore(file, identity).also { cache[pointer.path] = stamp to it }
    }

    /** Each publication uses a new immutable destination, even when repairing a corrupt same-hash path. */
    @Synchronized fun install(profile: RuntimeProfile, abi: AndroidCoreAbi, source: File, provenance: String,
        sourceUrl: String? = null, archiveSha256: String? = null, expectedSha256: String? = null, approvedPin: Boolean = expectedSha256 != null): InstalledCore {
        validateProfile(profile)
        require(!approvedPin || expectedSha256 != null) { "An approved artifact requires an expected digest." }
        val sha = CoreBinary.validate(source, abi)
        if (expectedSha256 != null) check(sha == expectedSha256) { "Core does not match the approved artifact digest." }
        val id = UUID.randomUUID().toString()
        val target = File(directory, "artifacts/$sha/$id/${profile.libraryFileName}")
        safe(target)
        AtomicFiles.write(target) { output -> source.inputStream().use { it.copyTo(output) } }
        check(CoreBinary.validate(target, abi) == sha) { "Core changed during installation." }
        checkpoint("ARTIFACT_INSTALLED")
        val identity = CoreArtifactIdentity(profile.runtimeId, abi.folderName, sha, target.length(), provenance, sourceUrl, archiveSha256, approvedPin)
        val selection = CoreSelection(installId = id, libraryFileName = profile.libraryFileName, identity = identity)
        AtomicFiles.writeBytes(pointer(profile, abi), adapter.toJson(selection).toByteArray())
        checkpoint("SELECTION_INSTALLED")
        cache.clear()
        return InstalledCore(target, identity)
    }

    @Synchronized fun inspectLegacy(profile: RuntimeProfile, abi: AndroidCoreAbi, source: File, provenance: String, fresh: Boolean = false): InstalledCore {
        validateProfile(profile)
        check(source.isFile && source.canonicalFile == File(source.absoluteFile.parentFile!!.canonicalFile, source.name)) { "Linked core libraries are not accepted." }
        val key = "legacy:${source.absolutePath}:${profile.runtimeId}:${abi.folderName}"
        val stamp = "${source.length()}|${source.lastModified()}|$provenance"
        if (!fresh) cache[key]?.takeIf { it.first == stamp }?.let { return it.second }
        val sha = CoreBinary.validate(source, abi)
        return InstalledCore(source, CoreArtifactIdentity(profile.runtimeId, abi.folderName, sha, source.length(), provenance)).also { cache[key] = stamp to it }
    }

    private fun pointer(profile: RuntimeProfile, abi: AndroidCoreAbi): File {
        validateProfile(profile)
        return File(directory, "selected/${profile.runtimeId}-${abi.folderName}.json").also(::safe)
    }
    private fun validateProfile(profile: RuntimeProfile) {
        require(profile.runtimeId.matches(Regex("[A-Za-z0-9_-]+")) && profile.libraryFileName.matches(Regex("[A-Za-z0-9_-]+\\.so"))) { "Invalid core identity or filename." }
    }
    private fun safe(file: File) {
        check(directory.canonicalFile == File(directory.absoluteFile.parentFile!!.canonicalFile, directory.name)) { "Linked core storage root is not accepted." }
        val relative = file.absoluteFile.relativeTo(directory.absoluteFile).invariantSeparatorsPath
        check(relative.split('/').none { it in setOf(".", "..", "") } && file.canonicalFile == File(directory.canonicalFile, relative)) { "Invalid or linked core storage path." }
    }
}
