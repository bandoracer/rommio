package io.github.bandoracer.rommio.domain.player

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun syntheticCore(abi: AndroidCoreAbi = AndroidCoreAbi.ARM64_V8A, exports: Set<String> = CoreBinary.exports): ByteArray {
    val wide = abi in setOf(AndroidCoreAbi.ARM64_V8A, AndroidCoreAbi.X86_64)
    val b = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
    b.put(byteArrayOf(0x7f, 69, 76, 70, if (wide) 2 else 1, 1, 1))
    b.putShort(16, 3); b.putShort(18, when (abi) { AndroidCoreAbi.ARM64_V8A -> 183; AndroidCoreAbi.ARMEABI_V7A -> 40; AndroidCoreAbi.X86_64 -> 62; AndroidCoreAbi.X86 -> 3 }); b.putInt(20, 1)
    val phSize = if (wide) 56 else 32; val shSize = if (wide) 64 else 40; val symSize = if (wide) 24 else 16
    if (wide) { b.putLong(32, 64); b.putLong(40, 128) } else { b.putInt(28, 64); b.putInt(32, 128) }
    b.putShort(if (wide) 52 else 40, (if (wide) 64 else 52).toShort())
    b.putShort(if (wide) 54 else 42, phSize.toShort()); b.putShort(if (wide) 56 else 44, 1)
    b.putShort(if (wide) 58 else 46, shSize.toShort()); b.putShort(if (wide) 60 else 48, 3)
    b.putInt(64, 1)
    if (wide) { b.putLong(96, 8192); b.putLong(104, 8192) } else { b.putInt(80, 8192); b.putInt(84, 8192) }
    val strings = "\u0000" + exports.joinToString("\u0000") + "\u0000"
    b.position(4096); b.put(strings.toByteArray())
    val symbols = 128 + shSize; val names = 128 + shSize * 2
    b.putInt(symbols + 4, 11); b.putInt(names + 4, 3)
    if (wide) {
        b.putLong(symbols + 24, 512); b.putLong(symbols + 32, ((exports.size + 1) * symSize).toLong()); b.putInt(symbols + 40, 2); b.putLong(symbols + 56, symSize.toLong())
        b.putLong(names + 24, 4096); b.putLong(names + 32, strings.toByteArray().size.toLong())
    } else {
        b.putInt(symbols + 16, 512); b.putInt(symbols + 20, (exports.size + 1) * symSize); b.putInt(symbols + 24, 2); b.putInt(symbols + 36, symSize)
        b.putInt(names + 16, 4096); b.putInt(names + 20, strings.toByteArray().size)
    }
    var offset = 1
    exports.forEachIndexed { index, name ->
        val entry = 512 + (index + 1) * symSize
        b.putInt(entry, offset); b.put(entry + if (wide) 4 else 12, 0x12); b.putShort(entry + if (wide) 6 else 14, 1)
        offset += name.length + 1
    }
    return b.array()
}
