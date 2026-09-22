package io.github.bandoracer.rommio.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.model.PendingContinuitySyncPayload
import io.github.bandoracer.rommio.model.PendingCoreDownloadPayload
import io.github.bandoracer.rommio.model.PendingManualSlotPayload

/** One codec for persisted jobs, including the legacy GAME_SYNC romId/fileId shape. */
internal class PendingActionJsonCodec {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val continuity = moshi.adapter(PendingContinuitySyncPayload::class.java).nonNull()
    private val manualSlot = moshi.adapter(PendingManualSlotPayload::class.java).nonNull()
    private val core = moshi.adapter(PendingCoreDownloadPayload::class.java).nonNull()

    fun encode(value: PendingContinuitySyncPayload): String = continuity.toJson(value)
    fun encode(value: PendingManualSlotPayload): String = manualSlot.toJson(value)
    fun encode(value: PendingCoreDownloadPayload): String = core.toJson(value)

    fun decodeContinuity(raw: String): PendingContinuitySyncPayload =
        requireNotNull(continuity.fromJson(raw))

    fun decodeManualSlot(raw: String): PendingManualSlotPayload =
        requireNotNull(manualSlot.fromJson(raw))

    fun decodeCore(raw: String): PendingCoreDownloadPayload = requireNotNull(core.fromJson(raw))
}
