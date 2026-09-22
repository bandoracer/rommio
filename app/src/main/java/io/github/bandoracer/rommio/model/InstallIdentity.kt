package io.github.bandoracer.rommio.model

import io.github.bandoracer.rommio.domain.progress.ProgressBinding
import io.github.bandoracer.rommio.domain.progress.ProgressContentFile
import io.github.bandoracer.rommio.domain.progress.ProgressProtocol
import java.security.MessageDigest

/** Legacy is an explicit unassigned bucket, never an alias for the active account. */
object InstallIdentity {
    const val LEGACY = "legacy"
    fun namespace(baseUrl: String?, accountId: Int?): String {
        if (baseUrl == null && accountId == null) return LEGACY
        require(baseUrl != null && accountId != null) { "Incomplete installed-account identity." }
        val bytes = "rommio-install-account-v1\n${baseUrl.toByteArray().size}:$baseUrl\n$accountId".toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    fun progress(baseUrl: String?, accountId: Int?, romId: Int, fileId: Int, digest: String?): String {
        if (baseUrl == null || accountId == null || digest == null) return LEGACY
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid installed-content digest." }
        return ProgressProtocol.bindingKey(ProgressBinding(baseUrl, accountId.toString(), romId.toLong(),
            listOf(ProgressContentFile(fileId.toLong(), 0, digest, null, null))))
    }
}
