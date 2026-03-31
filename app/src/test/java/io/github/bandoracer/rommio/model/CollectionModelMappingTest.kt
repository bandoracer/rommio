package io.github.bandoracer.rommio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionModelMappingTest {
    @Test
    fun regularCollectionMapsToDomainModel() {
        val dto = CollectionResponseDto(
            id = 7,
            name = "Favorites",
            description = "Top picks",
            romIds = setOf(1, 2),
            romCount = 2,
            isPublic = true,
            isFavorite = true,
            ownerUsername = "ryan",
        )

        val mapped = dto.toDomain()

        assertEquals(CollectionKind.REGULAR, mapped.kind)
        assertEquals("7", mapped.id)
        assertTrue(mapped.isFavorite)
        assertEquals("ryan", mapped.ownerUsername)
    }

    @Test
    fun virtualCollectionMapsToVirtualKind() {
        val dto = VirtualCollectionResponseDto(
            id = "encoded-id",
            type = "all",
            name = "Recently added",
            description = "Auto generated",
            romIds = setOf(5),
            romCount = 1,
        )

        val mapped = dto.toDomain()

        assertEquals(CollectionKind.VIRTUAL, mapped.kind)
        assertTrue(mapped.isVirtual)
        assertEquals("encoded-id", mapped.id)
    }
}
