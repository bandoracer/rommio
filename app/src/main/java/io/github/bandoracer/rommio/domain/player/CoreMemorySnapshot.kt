package io.github.bandoracer.rommio.domain.player

/** Raw memory inventory, not a declaration that file-backed persistence is complete. */
enum class PersistentMemory(val id: Int, val role: String, val path: String) {
    SRAM(0, "sram", "save.srm"),
    RTC(1, "rtc", "save.rtc"),
    BATTERY_1(0x100, "sram", "save-1.srm"),
    BATTERY_2(0x200, "sram", "save-2.srm"),
    BATTERY_3(0x300, "sram", "save-3.srm");

    companion object {
        val STANDARD = setOf(SRAM, RTC)
        val FCEUMM_SEGMENTS_V2 = setOf(SRAM, RTC, BATTERY_1, BATTERY_2, BATTERY_3)
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    }
}

data class CoreMemorySnapshot(
    val memory: Map<PersistentMemory, ByteArray>,
    val state: ByteArray?,
    val stateRejected: Boolean,
    val effectiveOptions: Map<String, String>,
    val coreIdentity: CoreArtifactIdentity?,
    val firmwareSha256: Map<String, String>,
    val binding: io.github.bandoracer.rommio.domain.progress.ProgressBinding?,
    val persistenceDescriptor: String? = null,
)
