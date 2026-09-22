package io.github.bandoracer.rommio.domain.player

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Structural/ABI validation, not proof of upstream authenticity or successful native loading. */
internal object CoreBinary {
    const val MAX_BYTES = 512L * 1024 * 1024
    val exports = setOf("retro_init", "retro_deinit", "retro_api_version", "retro_get_system_info", "retro_get_system_av_info",
        "retro_set_environment", "retro_set_video_refresh", "retro_set_audio_sample", "retro_set_audio_sample_batch",
        "retro_set_input_poll", "retro_set_input_state", "retro_set_controller_port_device", "retro_reset", "retro_run",
        "retro_serialize_size", "retro_serialize", "retro_unserialize", "retro_cheat_reset", "retro_cheat_set", "retro_load_game",
        "retro_load_game_special", "retro_unload_game", "retro_get_region", "retro_get_memory_data", "retro_get_memory_size")

    fun validate(file: File, abi: AndroidCoreAbi): String {
        check(file.isFile && file.length() in 64..MAX_BYTES) { "Core library is missing, empty, or too large." }
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            fun read(offset: Long, size: Long): ByteBuffer {
                check(offset >= 0 && size in 0..(64L * 1024 * 1024) && offset <= length && size <= length - offset) { "Core ELF metadata is truncated or outside the file." }
                return ByteBuffer.wrap(ByteArray(size.toInt()).also { input.seek(offset); input.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
            }
            fun ByteBuffer.u16(offset: Int) = getShort(offset).toInt() and 0xffff
            fun ByteBuffer.u32(offset: Int) = getInt(offset).toLong() and 0xffffffffL
            val header = read(0, 64)
            check(header.get(0) == 0x7f.toByte() && header.get(1) == 'E'.code.toByte() && header.get(2) == 'L'.code.toByte() && header.get(3) == 'F'.code.toByte()) { "Downloaded core is not an ELF library." }
            val wide = abi in setOf(AndroidCoreAbi.ARM64_V8A, AndroidCoreAbi.X86_64)
            val machine = when (abi) { AndroidCoreAbi.ARM64_V8A -> 183; AndroidCoreAbi.ARMEABI_V7A -> 40; AndroidCoreAbi.X86_64 -> 62; AndroidCoreAbi.X86 -> 3 }
            check(header.get(4).toInt() == if (wide) 2 else 1) { "Core bitness does not match this app process." }
            check(header.get(5).toInt() == 1 && header.get(6).toInt() == 1 && header.u32(20) == 1L) { "Unsupported ELF encoding/version." }
            check(header.u16(16) == 3 && header.u16(18) == machine) { "Core is not a shared library for ${abi.folderName}." }
            check(header.u16(if (wide) 52 else 40) == if (wide) 64 else 52) { "Invalid ELF header size." }
            val phOffset = if (wide) header.getLong(32) else header.u32(28)
            val phSize = header.u16(if (wide) 54 else 42)
            val phCount = header.u16(if (wide) 56 else 44)
            check(phSize == if (wide) 56 else 32) { "Invalid ELF program table." }
            check(phCount in 1..4096)
            val programs = read(phOffset, phSize.toLong() * phCount)
            var loadable = false
            repeat(phCount) { index ->
                val start = index * phSize
                if (programs.u32(start) == 1L) {
                    loadable = true
                    val offset = if (wide) programs.getLong(start + 8) else programs.u32(start + 4)
                    val size = if (wide) programs.getLong(start + 32) else programs.u32(start + 16)
                    val memorySize = if (wide) programs.getLong(start + 40) else programs.u32(start + 20)
                    check(offset >= 0 && size >= 0 && size <= memorySize && offset <= length && size <= length - offset) { "Core load segment is truncated." }
                }
            }
            check(loadable) { "Core has no loadable segments." }
            val shOffset = if (wide) header.getLong(40) else header.u32(32)
            val shSize = header.u16(if (wide) 58 else 46)
            val shCount = header.u16(if (wide) 60 else 48)
            check(shSize == if (wide) 64 else 40) { "Invalid ELF section table." }
            check(shCount in 1..8192) { "Core has no supported dynamic-symbol metadata." }
            val sections = read(shOffset, shSize.toLong() * shCount)
            fun sectionOffset(index: Int) = if (wide) sections.getLong(index * shSize + 24) else sections.u32(index * shSize + 16)
            fun sectionSize(index: Int) = if (wide) sections.getLong(index * shSize + 32) else sections.u32(index * shSize + 20)
            val found = mutableSetOf<String>()
            check((0 until shCount).count { sections.u32(it * shSize + 4) == 11L } == 1) { "Core must have one supported dynamic symbol table." }
            repeat(shCount) { index ->
                val start = index * shSize
                if (sections.u32(start + 4) == 11L) { // SHT_DYNSYM
                    val link = sections.u32(start + if (wide) 40 else 24).toInt()
                    check(link in 0 until shCount && sections.u32(link * shSize + 4) == 3L) { "Invalid dynamic string table." }
                    val entrySize = if (wide) sections.getLong(start + 56) else sections.u32(start + 36)
                    check(entrySize == if (wide) 24L else 16L)
                    val size = sectionSize(index)
                    check(size % entrySize == 0L)
                    val symbols = read(sectionOffset(index), size)
                    val strings = read(sectionOffset(link), sectionSize(link)).array()
                    repeat((size / entrySize).toInt()) { symbol ->
                        val position = symbol * entrySize.toInt()
                        val name = symbols.u32(position)
                        val info = symbols.get(position + if (wide) 4 else 12).toInt() and 0xff
                        val visibility = symbols.get(position + if (wide) 5 else 13).toInt() and 3
                        val defined = symbols.u16(position + if (wide) 6 else 14) != 0
                        if (defined && (info shr 4) in 1..2 && (info and 15) in setOf(0, 2) && visibility in setOf(0, 3)) {
                            check(name < strings.size) { "Invalid exported symbol name." }
                            // Bound work per symbol even when malicious tables reuse a huge unterminated string.
                            val begin = name.toInt()
                            if (strings.size - begin >= 6 && String(strings, begin, 6, Charsets.US_ASCII) == "retro_") {
                                var end = begin
                                while (end < minOf(strings.size, begin + 128) && strings[end] != 0.toByte()) end++
                                if (end < strings.size && strings[end] == 0.toByte()) found += String(strings, begin, end - begin, Charsets.US_ASCII)
                            }
                        }
                    }
                }
            }
            check(found.containsAll(exports)) { "Core is missing required libretro exports: ${(exports - found).sorted().joinToString()}." }
        }
        return sha256(file)
    }

    fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
