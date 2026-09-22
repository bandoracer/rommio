package io.github.bandoracer.rommio.domain.progress

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files

/** A sibling lock survives replacement of the entire progress directory. Never transfer/delete it. */
internal class ProgressStorageLock private constructor(private val file: RandomAccessFile, private val lock: FileLock) : Closeable {
    private var closed = false
    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            try { lock.release() } finally { file.close() }
        }
    }
    companion object {
        fun acquire(root: File): ProgressStorageLock {
            require(!Files.isSymbolicLink(root.toPath())) { "Progress directory must not be a symbolic link." }
            val parent = requireNotNull(root.canonicalFile.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Unable to open progress storage ownership." }
            val path = File(parent, ".${root.name}.owner.lock")
            require(!Files.isSymbolicLink(path.toPath())) { "Progress ownership must not be a symbolic link." }
            val file = RandomAccessFile(path, "rw")
            try {
                return ProgressStorageLock(file, requireNotNull(file.channel.tryLock()) { "Another process owns progress storage." })
            } catch (error: Exception) { file.close(); throw error }
        }
    }
}
