package io.github.bandoracer.rommio.domain.progress

import io.github.bandoracer.rommio.data.network.RommService
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

/** Only the probed complete-inventory API is enabled; no server-supplied download URLs are followed. */
class RommProgressTransport(
    private val service: RommService,
    private val binding: ProgressBinding,
    private val verifyOwner: suspend () -> Unit = {},
) : ProgressTransport {
    suspend fun qualify() {
        verifyOwner()
        check(service.getHeartbeat().system.version == "5.2.0") { "Sync requires a qualified RomM version (5.2.0). Both kinds of progress remain saved locally." }
        check(service.getCurrentUser().id.toString() == binding.accountId) { "The signed-in account does not own this progress." }
    }
    override suspend fun inventory(): List<ProgressRemoteRecord> {
        verifyOwner()
        return service.listStates(binding.romId.toInt()).map {
            require(it.romId.toLong() == binding.romId)
            ProgressRemoteRecord(it.fileName, it.id, it.fileSizeBytes)
        }
    }
    override suspend fun read(record: ProgressRemoteRecord, destination: File, maxBytes: Long) = withContext(Dispatchers.IO) {
        verifyOwner()
        require(record.byteLength in 0..maxBytes)
        service.stateContent(record.id).use { body ->
            require(body.contentLength() < 0 || body.contentLength() == record.byteLength)
            coroutineScope {
                val transfer = async(Dispatchers.IO) { FileOutputStream(destination).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= maxBytes && total <= record.byteLength) { "Remote artifact exceeds declared size." }
                        output.write(buffer, 0, n)
                    }
                    require(total == record.byteLength) { "Incomplete remote artifact." }
                }
                output.fd.sync()
                } }
                try { transfer.await() } finally { body.close() } // Cancelling the launch budget closes a blocked body read.
            }
        }
    }
    override suspend fun upload(name: String, source: File): ProgressRemoteRecord {
        verifyOwner()
        require(name.startsWith("__rommio3_${ProgressProtocol.bindingKey(binding)}_") && name.matches(Regex("[A-Za-z0-9_.-]+")))
        val result = service.uploadState(binding.romId.toInt(), "rommio-progress-v3",
            MultipartBody.Part.createFormData("stateFile", name, source.asRequestBody("application/octet-stream".toMediaType())))
        require(result.romId.toLong() == binding.romId)
        return ProgressRemoteRecord(result.fileName, result.id, result.fileSizeBytes)
    }
}
