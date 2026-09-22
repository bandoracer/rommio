package io.github.bandoracer.rommio.domain.progress

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class ProgressProtocolTest {
    data class Entry(val file: String, val bindingKey: String? = null, val payloadSha256: String? = null, val stateKey: String? = null)
    data class Index(val valid: List<Entry>, val invalid: List<Entry>)
    private fun bytes(name: String) = requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)).use { it.readBytes() }
    private val index = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(Index::class.java).fromJson(bytes("index.json").toString(Charsets.UTF_8))!!

    @Test fun sharedValidFixturesPreserveMeaningAndKeys() {
        index.valid.forEach { entry ->
            val raw = bytes(entry.file)
            val op = ProgressProtocol.decode(raw)
            assertEquals(entry.file, entry.payloadSha256, ProgressProtocol.sha256(raw))
            assertEquals(entry.file, entry.bindingKey, ProgressProtocol.bindingKey(op.binding))
            entry.stateKey?.let { assertEquals(entry.file, it, ProgressProtocol.stateCompatibilityKey(op.checkpoints.first())) }
            assertEquals(entry.file, op, ProgressProtocol.decode(ProgressProtocol.encode(op)))
        }
    }

    @Test fun sharedInvalidFixturesAreRejected() {
        index.invalid.forEach { entry ->
            try { ProgressProtocol.decode(bytes(entry.file)); fail("Accepted ${entry.file}") } catch (expected: Exception) { }
        }
    }

    @Test fun parentsMustMatchReferenceAndContent() {
        val original = ProgressProtocol.decode(bytes("coherent.json"))
        val child = original.copy(operationId = "00000000-0000-4000-8000-000000000005", changes = original.changes.map { it.copy(parents = listOf(original.operationId)) })
        ProgressProtocol.validateParents(child, mapOf(original.operationId to original))
        assertThrows(IllegalStateException::class.java) { ProgressProtocol.validateParents(child, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { ProgressProtocol.validateParents(child, mapOf(original.operationId to original.copy(binding = original.binding.copy(accountId = "other")))) }
        assertThrows(IllegalArgumentException::class.java) { ProgressProtocol.validateParents(child, mapOf(original.operationId to original.copy(changes = original.changes.take(1)))) }
    }

    @Test fun unsupportedSchemaIsDistinctFromCorruption() {
        assertThrows(UnsupportedProgressSchema::class.java) { ProgressProtocol.decode(bytes("unknown-version.json").toString(Charsets.UTF_8).replace("\"schemaVersion\":3", "\"schemaVersion\":4").replace("\"schemaVersion\": 3", "\"schemaVersion\": 4").toByteArray()) }
    }
    @Test fun independentSchemaRequiresPureGameOperationsAndExactStatePairing() {
        val original = ProgressProtocol.decode(bytes("coherent.json"))
        val cp = original.checkpoints.single()
        val game = cp.copy(schemaVersion = 3, stateCompatibility = null, artifacts = ProgressProtocol.gameArtifacts(cp))
        val gameOp = original.copy(schemaVersion = 3, changes = listOf(ProgressRefChange("game/current", emptyList(), game.checkpointId, false)), checkpoints = listOf(game))
        val state = cp.copy(schemaVersion = 3, gameSaveOperationId = gameOp.operationId)
        val stateOp = original.copy(schemaVersion = 3, operationId = "00000000-0000-4000-8000-000000000099",
            changes = listOf(ProgressRefChange("state/resume/${ProgressProtocol.stateCompatibilityKey(state)}", emptyList(), state.checkpointId, false)), checkpoints = listOf(state))
        assertEquals(gameOp, ProgressProtocol.decode(ProgressProtocol.encode(gameOp)))
        assertEquals(stateOp, ProgressProtocol.decode(ProgressProtocol.encode(stateOp)))
        ProgressProtocol.validateParents(stateOp, mapOf(gameOp.operationId to gameOp))
        assertEquals(listOf(gameOp.operationId), ProgressProtocol.dependencies(stateOp))
        assertTrue(ProgressProtocol.dependencies(gameOp).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { ProgressProtocol.encode(gameOp.copy(changes = gameOp.changes + stateOp.changes)) }
        assertThrows(IllegalArgumentException::class.java) { ProgressProtocol.encode(stateOp.copy(checkpoints = listOf(state.copy(gameSaveOperationId = null)))) }
        val different = game.copy(artifacts = game.artifacts.map { it.copy(sha256 = "f".repeat(64)) })
        assertThrows(IllegalArgumentException::class.java) { ProgressProtocol.validateParents(stateOp, mapOf(gameOp.operationId to gameOp.copy(checkpoints = listOf(different)))) }
    }
    @Test fun rommUserWithoutEmailRemainsAValidAccount() {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(io.github.bandoracer.rommio.model.UserDto::class.java)
        val user = adapter.fromJson("""{"id":1,"username":"fixture","email":null,"role":"admin"}""")!!
        assertEquals(1, user.id); assertNull(user.email)
    }

}
