package com.newoether.agora.data.local

/**
 * Builds independent embedding rows for a forked message graph.
 *
 * Database identity and message ownership must be new. All semantic payload fields are preserved,
 * and the vector BLOB is deep-copied so the clone never shares the source [ByteArray] instance.
 */
internal object ForkEmbeddingClonePolicy {
    fun cloneAll(
        sourceEmbeddings: List<EmbeddingEntity>,
        sourceToForkMessageIds: Map<String, String>,
    ): List<EmbeddingEntity> = sourceEmbeddings.map { source ->
        val forkMessageId = requireNotNull(sourceToForkMessageIds[source.messageId]) {
            "Missing fork message mapping for embedding ${source.id} (${source.messageId})"
        }
        source.copy(
            id = 0L,
            messageId = forkMessageId,
            embedding = source.embedding.copyOf(),
        )
    }
}
