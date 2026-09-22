package io.github.bandoracer.rommio.domain.progress

import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.domain.storage.StorageAccess
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Lazy process owner. Storage replacement drops the old file lock and all cached store state. */
internal class ApplicationProgressStorage(private val database: AppDatabase, private val root: File) {
    private val mutex = Mutex()
    private var current: ProgressStore? = null

    suspend fun get(): ProgressStore = StorageAccess.normal {
        withContext(Dispatchers.IO) {
            mutex.withLock { current ?: ProgressStore(database, root).also { current = it } }
        }
    }

    /** Call only after ordinary work drains; native lifetime leases prevent entry during gameplay. */
    suspend fun closeForReplacement() {
        StorageAccess.checkExclusive()
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                current?.close()
                // If native teardown has not completed, close throws and ownership stays retained.
                current = null
            }
        }
    }
}
