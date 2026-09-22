package io.github.bandoracer.rommio.domain.progress

import java.nio.file.Path

/** Exact native timestamps, including on API 26 where StructStat has no nanos. */
internal object NativeFileMetadata {
    init { System.loadLibrary("rommio_files") }
    private external fun modifiedTime(path: ByteArray): LongArray?
    fun modifiedNanoseconds(path: Path): Long {
        val value = requireNotNull(modifiedTime(path.toString().toByteArray(Charsets.UTF_8)))
        require(value.size == 2 && value[1] in 0..999_999_999)
        return Math.addExact(Math.multiplyExact(value[0], 1_000_000_000), value[1])
    }
}
