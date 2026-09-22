package io.github.bandoracer.rommio.data.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.github.bandoracer.rommio.data.network.RommService
import io.github.bandoracer.rommio.model.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class CatalogReaderTest {
    private val server = MockWebServer().apply { start() }
    private val service = Retrofit.Builder().baseUrl(server.url("/"))
        .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
        .build().create(RommService::class.java)
    @After fun cleanup() = server.shutdown()
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private suspend fun reject(block: suspend () -> Unit) {
        var error: Exception? = null
        try { block() } catch (caught: Exception) { error = caught }
        assertNotNull("Incomplete traversal must fail", error)
    }
    @Test fun missingTotalAndServerPageCapContinueUntilEmpty() = runBlocking {
        val all = (1..153).map { RomDto(it) }; val offsets = mutableListOf<Int>()
        val actual = readRomPages { offset -> offsets.add(offset); ItemsResponse(all.drop(offset).take(37)) }
        assertEquals(all, actual); assertEquals(listOf(0, 37, 74, 111, 148, 153), offsets)
    }
    @Test fun shortPagesWithKnownTotalAreNotTreatedAsEnd() = runBlocking {
        val all = (1..121).map { RomDto(it) }
        assertEquals(all, readRomPages { ItemsResponse(all.drop(it).take(20), total = all.size) })
        assertTrue(readRomPages { ItemsResponse(emptyList(), total = 0) }.isEmpty())
    }
    @Test fun missingMiddlePageChangingTotalsAndRepeatedIdsAreRejected() = runBlocking {
        reject { readRomPages { if (it == 0) ItemsResponse(listOf(RomDto(1)), total = 3) else ItemsResponse(emptyList(), total = 3) } }
        reject { readRomPages { ItemsResponse(listOf(RomDto(it + 1)), total = if (it == 0) 3 else 4) } }
        reject { readRomPages { ItemsResponse(listOf(RomDto(1)), total = 3) } }
        reject { readRomPages { ItemsResponse(listOf(RomDto(2), RomDto(1)), total = 2) } }
    }
    @Test fun indexDetectsSameCountMembershipChangesAndWrongPages() = runBlocking {
        reject { readRomPages { offset -> ItemsResponse(listOf(RomDto(offset + 1)), total = 2,
            romIdIndex = if (offset == 0) listOf(1, 2) else listOf(1, 3)) } }
        reject { readRomPages { ItemsResponse(listOf(RomDto(2)), total = 2, romIdIndex = listOf(1, 2)) } }
    }
    @Test fun failedPageAndCancellationNeverReturnPartialResults() = runBlocking {
        reject { readRomPages { if (it > 0) throw java.io.IOException("connection lost") else ItemsResponse(listOf(RomDto(1)), total = 2) } }
        var returned = false
        val started = CompletableDeferred<Unit>()
        val job = launch { readRomPages { started.complete(Unit); awaitCancellation() }; returned = true }
        started.await(); job.cancelAndJoin(); assertFalse(returned)
    }
    @Test fun platformAndEachCollectionKindKeepScopeAcrossPagesAndDisableGrouping() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val offset = request.requestUrl!!.queryParameter("offset")!!.toInt()
                val items = (offset + 1..minOf(offset + 40, 111)).joinToString { "{\"id\":$it}" }
                return json("{\"items\":[$items],\"total\":111}")
            }
        }
        assertEquals(111, CatalogReader(service).roms(platformId = 7).size)
        for (kind in CollectionKind.entries) assertEquals(111, CatalogReader(service).roms(collection = RommCollectionDto(kind, "8", "Collection")).size)
        assertEquals(12, requests.size)
        requests.forEach {
            assertEquals("0", it.requestUrl!!.queryParameter("group_by_meta_id"))
            assertEquals("id", it.requestUrl!!.queryParameter("order_by"))
            assertEquals("asc", it.requestUrl!!.queryParameter("order_dir"))
            assertEquals("true", it.requestUrl!!.queryParameter("with_files"))
        }
        for ((i, key) in listOf("platform_ids", "collection_id", "smart_collection_id", "virtual_collection_id").withIndex()) {
            requests.subList(i * 3, i * 3 + 3).forEach { assertEquals(if (i == 0) "7" else "8", it.requestUrl!!.queryParameter(key)) }
        }
    }
    @Test fun fullLibraryChecksUngroupedInventoryBeforeAndAfterTraversal() = runBlocking {
        server.enqueue(json("[1,2]")); server.enqueue(json("""{"items":[{"id":1},{"id":2}],"total":2}""")); server.enqueue(json("[1,2]"))
        assertEquals(listOf(1, 2), CatalogReader(service).roms().map { it.id })
        server.enqueue(json("[1,2]")); server.enqueue(json("""{"items":[{"id":1},{"id":2}],"total":2}""")); server.enqueue(json("[1,3]"))
        reject { CatalogReader(service).roms() }
    }
    @Test fun collectionListsAreUncappedAndKindsDoNotCollide() = runBlocking {
        server.enqueue(json("""[{"id":1,"name":"Regular","rom_ids":[1],"rom_count":1}]"""))
        server.enqueue(json("""[{"id":1,"name":"Smart","rom_ids":[2],"rom_count":1}]"""))
        server.enqueue(json((1..63).joinToString(prefix="[",postfix="]") { """{"id":"$it","type":"genre","name":"Virtual $it","rom_ids":[],"rom_count":0}""" }))
        val result = CatalogReader(service).collections()
        assertEquals(65, result.size); assertEquals(3, result.count { it.id == "1" })
        server.takeRequest(); server.takeRequest(); assertNull(server.takeRequest().requestUrl!!.queryParameter("limit"))
        server.enqueue(json("[]")); server.enqueue(json("[]")); server.enqueue(json("""[{"id":"x","type":"genre","name":"Incomplete","rom_ids":[],"rom_count":1}]"""))
        reject { CatalogReader(service).collections() }
    }
}
