package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.newoether.agora.data.local.DocumentEmbeddingDao
import com.newoether.agora.util.DebugLog
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A user-imported document shown in the management UI. */
data class UserDocument(val docId: String, val title: String, val chunks: Int)

/** A semantic hit over user documents, with a readable snippet for the agent. */
data class UserDocSearchHit(
    val docId: String,
    val title: String,
    val snippet: String,
    val score: Float,
)

/** Outcome of an import attempt, surfaced to the UI. */
sealed interface ImportResult {
    data class Success(val title: String, val chunks: Int) : ImportResult
    data class Unsupported(val fileName: String) : ImportResult
    data class NoText(val fileName: String) : ImportResult
    data class NoEmbeddingModel(val fileName: String) : ImportResult
    data class Failed(val fileName: String, val reason: String) : ImportResult
}

/**
 * Owns the user-uploaded document corpus for RAG. Isolated under its own corpus tag in the shared
 * document_embeddings store, so it never mixes with the bundled manual (introspect) or the
 * conversation message corpus. Reuses [DocumentRagEngine] for embed/store/search.
 */
class UserDocumentManager(
    private val context: Context,
    dao: DocumentEmbeddingDao,
) {
    private val engine = DocumentRagEngine(dao, CORPUS, context)

    suspend fun list(): List<UserDocument> =
        engine.listDocuments().map { UserDocument(it.docId, it.title, it.chunks) }

    suspend fun docCount(): Int = list().size

    suspend fun remove(docId: String) = engine.removeDocument(docId)

    /** Imports a picked file: resolve name, extract text, embed+store under user_docs. */
    suspend fun import(
        uri: Uri,
        config: EmbeddingModelConfig?,
        apiKey: String?,
    ): ImportResult = withContext(Dispatchers.IO) {
        val fileName = displayName(uri)
        val mimeType = context.contentResolver.getType(uri)
        if (!DocumentTextExtractor.isSupported(mimeType, fileName)) {
            return@withContext ImportResult.Unsupported(fileName)
        }
        if (config == null) return@withContext ImportResult.NoEmbeddingModel(fileName)

        val text = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                DocumentTextExtractor.extract(context, stream, mimeType, fileName)
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "import read failed: ${e.javaClass.simpleName}")
            return@withContext ImportResult.Failed(fileName, "could not read file")
        } ?: return@withContext ImportResult.NoText(fileName)

        val doc = CorpusDocument(
            docId = UUID.randomUUID().toString(),
            title = fileName,
            body = text,
        )
        val chunks = engine.addOrReplaceDocument(doc, config, apiKey)
        if (chunks <= 0) {
            // addOrReplaceDocument returns 0 when the first embed fails (auth/model) or no chunks.
            return@withContext ImportResult.Failed(fileName, "embedding failed (check embedding model/key)")
        }
        ImportResult.Success(fileName, chunks)
    }

    /** Semantic search over user documents; empty when no model, no docs, or no match. */
    suspend fun search(
        query: String,
        limit: Int,
        threshold: Float,
        config: EmbeddingModelConfig?,
        apiKey: String?,
    ): List<UserDocSearchHit> = withContext(Dispatchers.IO) {
        if (config == null || query.isBlank()) return@withContext emptyList()
        // Return the actual matched passages (chunk-level), not the document head — a large
        // document's relevant lines can be anywhere, so collapsing to the first chunks hides them.
        engine.searchChunks(query, limit, threshold, config, apiKey).map { hit ->
            UserDocSearchHit(
                docId = hit.docId,
                title = hit.title,
                snippet = hit.text.trim().take(SNIPPET_CHARS),
                score = hit.score,
            )
        }
    }

    private fun displayName(uri: Uri): String {
        val fromResolver = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
        return fromResolver ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    companion object {
        const val CORPUS = "user_docs"
        private const val TAG = "UserDocumentManager"
        private const val SNIPPET_CHARS = 2_000
    }
}
