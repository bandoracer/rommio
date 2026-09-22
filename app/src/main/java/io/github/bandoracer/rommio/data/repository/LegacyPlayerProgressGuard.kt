package io.github.bandoracer.rommio.data.repository

import io.github.bandoracer.rommio.data.local.ProgressDao
import io.github.bandoracer.rommio.model.DownloadedRomEntity
import io.github.bandoracer.rommio.model.InstallIdentity

/** Temporary cutover guard: the old player must never silently boot around imported v2 progress. */
internal suspend fun requireLegacyPlayerProgress(dao: ProgressDao, installation: DownloadedRomEntity) {
    val exists = if (installation.progressScope == InstallIdentity.LEGACY) {
        // An unowned install cannot prove it is separate from an imported content generation.
        dao.exportContents().isNotEmpty()
    } else {
        val namespace = dao.namespace(requireNotNull(installation.ownerBaseUrl), requireNotNull(installation.ownerAccountId).toString())
        namespace != null && dao.content(namespace.namespaceId, installation.progressScope) != null
    }
    check(!exists) { "Saved progress is preserved, but it requires the new player integration before this game can start." }
}
