package io.github.bandoracer.rommio.data.repository

import com.squareup.moshi.JsonDataException
import io.github.bandoracer.rommio.model.PendingContinuitySyncPayload
import io.github.bandoracer.rommio.model.PendingCoreDownloadPayload
import io.github.bandoracer.rommio.model.PendingManualSlotPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingActionJsonCodecTest {
    private val codec = PendingActionJsonCodec()

    @Test fun persistedJobsRoundTripThroughANewCodec() {
        val reader = PendingActionJsonCodec()
        val continuity = PendingContinuitySyncPayload(42, 17)
        val slot = PendingManualSlotPayload(42, 17, 0)
        val core = PendingCoreDownloadPayload("snes9x")
        assertEquals(continuity, reader.decodeContinuity(codec.encode(continuity)))
        assertEquals(slot, reader.decodeManualSlot(codec.encode(slot)))
        assertEquals(core, reader.decodeCore(codec.encode(core)))
    }

    @Test fun legacyGameSyncShapeAndAdditionalFieldsRemainReadable() {
        assertEquals(
            PendingContinuitySyncPayload(42, 17),
            codec.decodeContinuity("""{"romId":42,"fileId":17,"oldOption":true}"""),
        )
    }

    @Test fun incompleteOrNullJobsAreRejectedInsteadOfInventingDestinations() {
        assertThrows(JsonDataException::class.java) { codec.decodeContinuity("{}") }
        assertThrows(JsonDataException::class.java) { codec.decodeContinuity("null") }
        assertThrows(JsonDataException::class.java) { codec.decodeManualSlot("""{"romId":42,"fileId":17}""") }
        assertThrows(JsonDataException::class.java) { codec.decodeCore("""{"runtimeId":null}""") }
        assertThrows(java.io.IOException::class.java) { codec.decodeContinuity("{") }
    }
}
