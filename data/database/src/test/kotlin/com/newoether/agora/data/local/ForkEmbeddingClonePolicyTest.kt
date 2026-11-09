package com.newoether.agora.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ForkEmbeddingClonePolicyTest {
    @Test
    fun everySourceEmbeddingBecomesAnIndependentRowWithAllPayloadFields() {
        val firstVector = byteArrayOf(1, 2, 3, 4)
        val sources = listOf(
            embedding(
                id = 11L,
                messageId = "source-a",
                modelId = "model-one",
                vector = firstVector,
                chunkText = "first chunk",
                dimension = 4,
            ),
            embedding(
                id = 12L,
                messageId = "source-a",
                modelId = "model-two",
                vector = byteArrayOf(5, 6),
                chunkText = "second model",
                dimension = 2,
            ),
            embedding(
                id = 13L,
                messageId = "source-b",
                modelId = "model-one",
                vector = byteArrayOf(7, 8, 9),
                chunkText = "other message",
                dimension = 3,
            ),
        )

        val clones = ForkEmbeddingClonePolicy.cloneAll(
            sourceEmbeddings = sources,
            sourceToForkMessageIds = mapOf(
                "source-a" to "fork-a",
                "source-b" to "fork-b",
            ),
        )

        assertEquals(3, clones.size)
        sources.zip(clones).forEach { (source, clone) ->
            assertEquals(0L, clone.id)
            assertEquals(
                if (source.messageId == "source-a") "fork-a" else "fork-b",
                clone.messageId,
            )
            assertEquals(source.modelId, clone.modelId)
            assertEquals(source.chunkText, clone.chunkText)
            assertEquals(source.dimension, clone.dimension)
            assertArrayEquals(source.embedding, clone.embedding)
            assertNotSame(source.embedding, clone.embedding)
        }

        clones.first().embedding[0] = 99
        assertEquals(1, firstVector[0].toInt())
    }

    @Test
    fun missingMessageMappingRejectsTheWholeClonePlan() {
        val source = embedding(
            id = 21L,
            messageId = "unmapped",
            modelId = "model",
            vector = byteArrayOf(1),
            chunkText = "chunk",
            dimension = 1,
        )

        assertThrows(IllegalArgumentException::class.java) {
            ForkEmbeddingClonePolicy.cloneAll(listOf(source), emptyMap())
        }
    }

    private fun embedding(
        id: Long,
        messageId: String,
        modelId: String,
        vector: ByteArray,
        chunkText: String,
        dimension: Int,
    ) = EmbeddingEntity(
        id = id,
        messageId = messageId,
        modelId = modelId,
        embedding = vector,
        chunkText = chunkText,
        dimension = dimension,
    )
}
