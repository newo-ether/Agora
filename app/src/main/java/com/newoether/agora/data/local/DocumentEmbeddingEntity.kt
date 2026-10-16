package com.newoether.agora.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A vector for one chunk of a corpus document (e.g. a bundled manual page).
 *
 * This table is deliberately SEPARATE from [EmbeddingEntity] (the conversation-message corpus) so
 * the two never cross: conversation `rag_search` can never return a manual page, and `introspect`
 * can never return a user's conversation. The `corpus` column namespaces independent document sets
 * (currently only "manual") within the one table.
 */
@Entity(
    tableName = "document_embeddings",
    indices = [
        Index(value = ["corpus", "docId", "chunkIndex", "modelId"], unique = true),
        Index(value = ["corpus", "modelId"]),
    ],
)
data class DocumentEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Namespaces a document set, e.g. "manual". */
    val corpus: String,
    /** Stable document identifier within the corpus, e.g. the page slug "scanner". */
    val docId: String,
    /** Human-facing document title, returned to the caller. */
    val title: String,
    /** 0-based chunk ordinal within the document. */
    val chunkIndex: Int,
    /** Embedding model id these bytes were produced with. */
    val modelId: String,
    /** Cosine vector as BIG_ENDIAN float bytes (same encoding as the message corpus). */
    val embedding: ByteArray,
    val dimension: Int,
    /** The chunk's source text (used for ranking context; the full page is returned separately). */
    val chunkText: String,
    /** Fingerprint of the whole document, so a version change can detect staleness. */
    val sourceFingerprint: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DocumentEmbeddingEntity) return false
        return id == other.id && corpus == other.corpus && docId == other.docId &&
            chunkIndex == other.chunkIndex && modelId == other.modelId &&
            embedding.contentEquals(other.embedding) && dimension == other.dimension &&
            title == other.title && chunkText == other.chunkText &&
            sourceFingerprint == other.sourceFingerprint
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + corpus.hashCode()
        result = 31 * result + docId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + modelId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + dimension
        result = 31 * result + title.hashCode()
        result = 31 * result + chunkText.hashCode()
        result = 31 * result + sourceFingerprint.hashCode()
        return result
    }
}

/** One keyset page row for bounded semantic scoring over a corpus. */
data class DocumentEmbeddingSearchRow(
    val id: Long,
    val docId: String,
    val embedding: ByteArray,
    val dimension: Int,
)

/** A distinct document in a corpus for management listing. */
data class DocumentCorpusDocRow(
    val docId: String,
    val title: String,
    val chunks: Int,
)

/** A single matched chunk (fetched by row id after semantic scoring). */
data class DocumentChunkRow(
    val id: Long,
    val docId: String,
    val title: String,
    val chunkIndex: Int,
    val chunkText: String,
)

/**
 * Storage + bounded keyset reads for corpus document embeddings. Reads mirror the message-corpus
 * contract: a minimal projection, strictly ordered by id, bounded by LIMIT — never a full-corpus
 * materialization.
 */
@Dao
interface DocumentEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<DocumentEmbeddingEntity>)

    @Query("DELETE FROM document_embeddings WHERE corpus = :corpus")
    suspend fun clearCorpus(corpus: String)

    @Query("DELETE FROM document_embeddings WHERE corpus = :corpus AND docId = :docId")
    suspend fun deleteDoc(corpus: String, docId: String)

    /** Distinct documents in a corpus with their title and chunk count, for the management UI. */
    @Query(
        "SELECT docId, title, COUNT(*) AS chunks FROM document_embeddings " +
            "WHERE corpus = :corpus GROUP BY docId, title ORDER BY title",
    )
    suspend fun listDocs(corpus: String): List<DocumentCorpusDocRow>

    @Query("SELECT COUNT(DISTINCT docId) FROM document_embeddings WHERE corpus = :corpus")
    suspend fun docCount(corpus: String): Int

    @Query(
        "SELECT chunkText FROM document_embeddings WHERE corpus = :corpus AND docId = :docId " +
            "ORDER BY chunkIndex LIMIT :limit",
    )
    suspend fun chunksForDoc(corpus: String, docId: String, limit: Int): List<String>

    @Query(
        "SELECT id, docId, title, chunkIndex, chunkText FROM document_embeddings " +
            "WHERE corpus = :corpus AND id IN (:ids)",
    )
    suspend fun chunksByIds(corpus: String, ids: List<Long>): List<DocumentChunkRow>

    @Query("SELECT COUNT(*) FROM document_embeddings WHERE corpus = :corpus AND modelId = :modelId")
    suspend fun countFor(corpus: String, modelId: String): Int

    @Query(
        "SELECT DISTINCT sourceFingerprint FROM document_embeddings " +
            "WHERE corpus = :corpus AND modelId = :modelId",
    )
    suspend fun fingerprintsFor(corpus: String, modelId: String): List<String>

    /** Strictly-ordered, bounded keyset page for the bounded selector. */
    @Query(
        "SELECT id, docId, embedding, dimension FROM document_embeddings " +
            "WHERE corpus = :corpus AND modelId = :modelId AND id > :afterId " +
            "ORDER BY id LIMIT :limit",
    )
    suspend fun pageFor(
        corpus: String,
        modelId: String,
        afterId: Long,
        limit: Int,
    ): List<DocumentEmbeddingSearchRow>
}
