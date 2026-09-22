package io.github.bandoracer.rommio.domain.progress

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Readback of the native file container, used only to prove workspace cleanup
 * cannot discard any file created or changed after the acknowledged checkpoint. */
internal object PSPMemoryStickBundle {
    data class Entry(val directory: Boolean, val readOnly: Boolean, val bytes: ByteArray, val seconds: Long, val nanos: Int)
    fun files(bytes: ByteArray): Map<String, Entry> {
        require(bytes.size in 12..64*1024*1024)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8).also(input::get)
        require(magic.contentEquals("RMMF0001".toByteArray()))
        val count = input.int; require(count in 0..1024)
        val entries = linkedMapOf<String, Entry>(); val folded = mutableSetOf<String>()
        repeat(count) {
            val length = input.short.toInt() and 65535
            val kind = input.get().toInt(); require(input.get().toInt() == 0)
            val size = input.long; val seconds = input.long; val nanos = input.int
            require(length in 1..512 && size in 0..64L*1024*1024 && kind in 1..3 && nanos in 0..999999999)
            val pathBytes = ByteArray(length).also(input::get)
            val path = pathBytes.toString(Charsets.UTF_8); require(path.toByteArray().contentEquals(pathBytes))
            ProgressProtocol.validatePath(path)
            require(folded.add(path.lowercase(java.util.Locale.ROOT)))
            require(entries.keys.lastOrNull()?.let { it < path } != false)
            val parent = path.substringBeforeLast('/', ""); require(parent.isEmpty() || entries[parent]?.directory == true)
            val data = ByteArray(size.toInt()).also(input::get)
            require(kind != 1 || (size == 0L && seconds == 0L && nanos == 0))
            entries[path] = Entry(kind == 1, kind == 3, data, seconds, nanos)
        }
        require(!input.hasRemaining()); return entries
    }
    fun matches(directory: File, bytes: ByteArray,
        modifiedNanos: (java.nio.file.Path) -> Long = { NativeFileMetadata.modifiedNanoseconds(it) },
    ): Boolean = runCatching {
        val expected = files(bytes); val observed = mutableSetOf<String>()
        Files.walk(directory.toPath()).use { paths -> paths.filter { it != directory.toPath() }.forEach { path ->
            require(!Files.isSymbolicLink(path))
            val name = directory.toPath().relativize(path).toString()
            val entry = requireNotNull(expected[name]); observed += name
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (entry.directory) require(attrs.isDirectory) else {
                require(attrs.isRegularFile && attrs.size() == entry.bytes.size.toLong())
                require(Files.getPosixFilePermissions(path).contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE) != entry.readOnly)
                require(path.toFile().readBytes().contentEquals(entry.bytes))
                require(modifiedNanos(path) == Math.addExact(Math.multiplyExact(entry.seconds, 1_000_000_000), entry.nanos.toLong()))
            }
        } }
        observed == expected.keys
    }.getOrDefault(false)
}
