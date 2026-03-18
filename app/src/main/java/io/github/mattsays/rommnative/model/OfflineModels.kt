package io.github.mattsays.rommnative.model

enum class ConnectivityState {
    ONLINE,
    OFFLINE,
}

enum class OfflineHomeFeedType {
    CONTINUE_PLAYING,
    RECENTLY_ADDED,
    FEATURED_COLLECTION,
}

enum class PendingRemoteActionType {
    GAME_SYNC,
    CORE_DOWNLOAD,
}

enum class PendingRemoteActionStatus {
    PENDING,
    RUNNING,
    FAILED,
}

enum class MediaCacheCategory {
    PLATFORM_LOGO,
    ROM_COVER,
    COLLECTION_COVER,
}

data class OfflineState(
    val connectivity: ConnectivityState = ConnectivityState.ONLINE,
    val activeProfileId: String? = null,
    val catalogReady: Boolean = false,
    val mediaReady: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastFullSyncAtEpochMs: Long? = null,
    val lastMediaSyncAtEpochMs: Long? = null,
    val lastError: String? = null,
    val cacheBytes: Long = 0L,
) {
    val isOffline: Boolean
        get() = connectivity == ConnectivityState.OFFLINE

    val isOfflineReady: Boolean
        get() = catalogReady && mediaReady
}

data class PendingRemoteAction(
    val profileId: String,
    val actionType: PendingRemoteActionType,
    val dedupeKey: String,
    val payloadJson: String,
    val status: PendingRemoteActionStatus,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastError: String? = null,
)

data class PendingGameSyncPayload(
    val romId: Int,
    val fileId: Int,
)

data class PendingCoreDownloadPayload(
    val runtimeId: String,
)
