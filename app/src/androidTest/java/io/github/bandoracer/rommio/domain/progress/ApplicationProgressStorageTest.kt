package io.github.bandoracer.rommio.domain.progress

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bandoracer.rommio.data.local.AppDatabase
import io.github.bandoracer.rommio.domain.storage.StorageAccess
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApplicationProgressStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "progress-owner-${UUID.randomUUID()}"
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var owner: ApplicationProgressStorage
    private lateinit var operation: ProgressOperation

    @Before fun prepare() {
        root = File(context.filesDir, name)
        db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        owner = ApplicationProgressStorage(db, root)
        operation = ProgressProtocol.decode(InstrumentationRegistry.getInstrumentation().context.assets.open("coherent.json").use { it.readBytes() })
    }

    @After fun cleanup() = runBlocking<Unit> {
        StorageAccess.requireRecovery(false)
        StorageAccess.exclusive { owner.closeForReplacement() }
        db.close(); context.deleteDatabase(name); root.deleteRecursively()
        File(root.parentFile, ".$name.owner.lock").delete()
    }

    @Test fun lazyOwnerRequiresExclusiveCloseAndReopensWithoutStaleStoreState() = runBlocking {
        assertFalse(root.exists())
        val store = owner.get()
        val identity = store.resolveIdentity(operation.binding)
        assertSame(store, owner.get())
        try { owner.closeForReplacement(); fail("Unowned closure") } catch (_: IllegalStateException) { }
        StorageAccess.normal {
            try { owner.closeForReplacement(); fail("Reader closed storage") } catch (_: IllegalStateException) { }
        }
        assertSame(store, owner.get())
        StorageAccess.exclusive { owner.closeForReplacement(); owner.closeForReplacement() }
        val reopened = owner.get()
        assertNotSame(store, reopened)
        assertEquals(identity, reopened.resolveIdentity(operation.binding))
        try { store.resolveIdentity(operation.binding); fail("Stale handle remained usable") } catch (_: IllegalStateException) { }
    }

    @Test fun replacementDrainsAcceptedCommitAndInvalidatesWaitingOldStoreWork() = runBlocking {
        withTimeout(20_000) {
            val store = owner.get(); val identity = store.resolveIdentity(operation.binding)
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            val input = File(context.cacheDir, "$name-input").apply { mkdirs() }
            val sources = operation.checkpoints.single().artifacts.associate { artifact ->
                artifact.sha256 to File(input, artifact.relativePath).also { file ->
                    file.parentFile!!.mkdirs()
                    assets.open(artifact.relativePath).use { source -> file.outputStream().use { source.copyTo(it) } }
                }
            }
            val capturing = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            try {
                val writer = async(Dispatchers.Default) {
                    store.commit(identity, ProgressProtocol.encode(operation), sources) { boundary ->
                        if (boundary == ProgressCommitBoundary.ARTIFACTS_INSTALLED) {
                            capturing.complete(Unit); runBlocking { finish.await() }
                        }
                    }
                }
                capturing.await()
                var maintenanceEntered = false
                val replacement = async {
                    StorageAccess.exclusive {
                        maintenanceEntered = true
                        assertEquals(listOf(operation.operationId), store.heads(identity, "save/current"))
                        owner.closeForReplacement()
                        StorageAccess.didReplaceLibrary()
                    }
                }
                StorageAccess.busy.first { it }
                assertFalse(maintenanceEntered)
                val stale = async(start = CoroutineStart.UNDISPATCHED) { store.resolveIdentity(operation.binding) }
                assertFalse(stale.isCompleted)
                finish.complete(Unit); writer.await(); replacement.await()
                try { stale.await(); fail("Old request crossed replacement") } catch (_: CancellationException) { }
                assertEquals(listOf(operation.operationId), owner.get().heads(identity, "save/current"))
            } finally { finish.complete(Unit); input.deleteRecursively() }
        }
    }

    @Test fun nativeSessionPreventsReplacementAndRetainsTheSameOwner() = runBlocking {
        val store = owner.get(); val identity = store.resolveIdentity(operation.binding)
        val session = store.acquireSession(identity, null, emptyMap())
        try {
            try { StorageAccess.exclusive { owner.closeForReplacement() }; fail("Closed session storage") }
            catch (_: IllegalStateException) { }
            assertSame(store, owner.get())
        } finally { store.releaseSession(session) }
        StorageAccess.exclusive { owner.closeForReplacement() }
        assertNotSame(store, owner.get())
    }

    @Test fun unresolvedImportBlocksLazyStoreCreationAndDirectStoreOperations() = runBlocking {
        StorageAccess.requireRecovery(true)
        try { owner.get(); fail("Opened over mixed import") } catch (_: IllegalStateException) { }
        assertFalse(root.exists())
        StorageAccess.requireRecovery(false)
        val store = owner.get(); store.resolveIdentity(operation.binding)
        StorageAccess.requireRecovery(true)
        try { store.resolveIdentity(operation.binding); fail("Store bypassed recovery") } catch (_: IllegalStateException) { }
        StorageAccess.exclusive { owner.closeForReplacement(); StorageAccess.requireRecovery(false) }
        assertNotSame(store, owner.get())
    }

    @Test fun stableLockPreventsAnotherStoreFromRecreatingRootBetweenRenames() = runBlocking {
        val store = owner.get(); val identity = store.resolveIdentity(operation.binding)
        StorageAccess.exclusive {
            owner.closeForReplacement()
            ProgressStorageLock.acquire(root).use {
                val backup = File(root.parentFile, "$name-retained")
                assertTrue(root.renameTo(backup))
                try {
                    try { ProgressStore(db, root).close(); fail("Another owner bypassed import lock") }
                    catch (_: java.nio.channels.OverlappingFileLockException) { }
                    assertFalse(root.exists())
                    assertTrue(backup.renameTo(root))
                } finally {
                    if (!root.exists()) backup.renameTo(root)
                }
            }
        }
        assertEquals(identity, owner.get().resolveIdentity(operation.binding))
    }
}
