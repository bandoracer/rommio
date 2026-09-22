package io.github.bandoracer.rommio.domain.player

import java.io.File

/** Offline structural qualification of externally staged artifacts. Does not execute native code. */
object CoreArtifactProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Supply a TSV with ABI, absolute file path, and optional expected SHA-256." }
        val records = File(args[0]).readLines().filter { it.isNotBlank() && !it.startsWith('#') }
        require(records.isNotEmpty()) { "No core artifacts were supplied." }
        records.forEach { line ->
            val fields = line.split('\t'); require(fields.size in 2..3)
            val abi = AndroidCoreAbi.entries.first { it.folderName == fields[0] }
            val file = File(fields[1]); require(file.isAbsolute)
            val digest = CoreBinary.validate(file, abi)
            if (fields.size == 3) check(digest == fields[2]) { "Artifact digest differs from the supplied pin." }
            println("${abi.folderName}\t${file.length()}\t$digest\t${file.absolutePath}")
        }
    }
}
