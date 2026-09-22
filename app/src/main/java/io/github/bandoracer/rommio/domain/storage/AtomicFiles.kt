package io.github.bandoracer.rommio.domain.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stage beside the destination so replacement is atomic and never destroys the old file first. */
internal object AtomicFiles {
    fun stagingFile(target: File): File {
        val directory = requireNotNull(target.absoluteFile.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create storage directory." }
        return File.createTempFile(".rommio-", ".partial", directory)
    }

    fun install(staged: File, target: File) {
        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun write(target: File, writer: (FileOutputStream) -> Unit) {
        val staged = stagingFile(target)
        try {
            FileOutputStream(staged).use { output ->
                writer(output)
                output.flush()
                output.fd.sync()
            }
            install(staged, target)
        } finally {
            staged.delete()
        }
    }

    fun writeBytes(target: File, bytes: ByteArray) = write(target) { it.write(bytes) }

    @Synchronized fun writeResume(target: File, bytes: ByteArray) {
        val previous = File(target.parentFile, target.name + ".before-resume-fix")
        if (target.isFile && !previous.exists()) {
            write(previous) { output -> target.inputStream().use { it.copyTo(output) } }
        }
        writeBytes(target, bytes)
    }
}
