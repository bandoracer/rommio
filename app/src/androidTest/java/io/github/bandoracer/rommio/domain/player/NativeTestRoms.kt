package io.github.bandoracer.rommio.domain.player

internal fun counterRom(): ByteArray {
    val bytes = ByteArray(16 + 16384)
    byteArrayOf(0x4e, 0x45, 0x53, 0x1a, 1, 0, 2).copyInto(bytes)
    // Original 6502 program: disable IRQ, increment a 24-bit battery SRAM counter forever.
    byteArrayOf(0x78, 0xee.toByte(), 0, 0x60, 0xd0.toByte(), 8,
        0xee.toByte(), 1, 0x60, 0xd0.toByte(), 3, 0xee.toByte(), 2, 0x60,
        0x4c, 1, 0x80.toByte()).copyInto(bytes, 16)
    for (offset in listOf(0x3ffa, 0x3ffc, 0x3ffe)) { bytes[16 + offset] = 0; bytes[16 + offset + 1] = 0x80.toByte() }
    return bytes
}
