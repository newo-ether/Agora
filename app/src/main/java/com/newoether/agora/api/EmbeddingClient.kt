package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.StreamScope
import com.newoether.agora.viewmodel.GenerationCancelHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class EmbeddingRequest(val input: String, val model: String)

@Serializable
private data class BatchEmbeddingRequest(val input: List<String>, val model: String)

object EmbeddingClient {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun computeEmbedding(
        text: String,
        apiKey: String,
        model: String = "text-embedding-3-small",
        baseUrl: String = "https://api.openai.com/v1",
    ): FloatArray? = requestEmbeddings(
        json.encodeToString(EmbeddingRequest(text, model)), 1, apiKey, baseUrl,
    ).single()

    suspend fun computeEmbeddings(
        texts: List<String>,
        apiKey: String,
        model: String = "text-embedding-3-small",
        baseUrl: String = "https://api.openai.com/v1",
    ): List<FloatArray?> {
        if (texts.isEmpty()) return emptyList()
        return requestEmbeddings(
            json.encodeToString(BatchEmbeddingRequest(texts, model)), texts.size, apiKey, baseUrl,
        )
    }

    private suspend fun requestEmbeddings(
        body: String,
        expectedCount: Int,
        apiKey: String,
        baseUrl: String,
    ): List<FloatArray?> = withContext(Dispatchers.IO) {
        try {
            val response = coroutineScope {
                val requestScope = StreamScope()
                val parentScope = HttpClient.boundStreamScope()
                val requestJob = currentCoroutineContext().job
                val parentCancellation = GenerationCancelHandle {
                    requestJob.cancel()
                    requestScope.cancelAll()
                }
                parentScope?.register(parentCancellation)
                // The existing buffered HTTP owner cancels its active Call through this scope.
                // A sibling can close the socket even while the IO caller is blocked in execute().
                val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        requestScope.cancelAll()
                    }
                }
                try {
                    HttpClient.withStreamScope(requestScope, HttpClient.boundRequestTrace()) {
                        HttpClient.post("${baseUrl.trimEnd('/')}/embeddings", body, buildMap {
                            put("Content-Type", "application/json")
                            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
                        })
                    }
                } finally {
                    cancellation.cancel()
                    parentScope?.unregister(parentCancellation)
                }
            }
            currentCoroutineContext().ensureActive()
            if (response == null) List(expectedCount) { null }
            else parseEmbeddings(response, expectedCount)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            // Provider parse failures can contain payloads; keep source/vector data out of logs.
            DebugLog.e("EmbeddingClient", "Embedding request failed: ${error.javaClass.simpleName}")
            List(expectedCount) { null }
        }
    }

    private fun parseEmbeddings(response: String, expectedCount: Int): List<FloatArray> {
        val data = json.parseToJsonElement(response).jsonObject.getValue("data").jsonArray
        require(data.size == expectedCount) { "Embedding response count mismatch" }
        val rows = data.map { it.jsonObject }
        val indexed = rows.any { "index" in it }
        val result = arrayOfNulls<FloatArray>(expectedCount)
        var dimension: Int? = null
        rows.forEachIndexed { position, row ->
            // Preserve ordered responses from compatible providers that omit every index.
            // Mixed, duplicate or out-of-range indices are ambiguous and fail the whole batch.
            val index = if (indexed) row.getValue("index").jsonPrimitive.int else position
            require(index in result.indices && result[index] == null) { "Invalid embedding index" }
            val values = row.getValue("embedding").jsonArray
            require(values.isNotEmpty()) { "Empty embedding vector" }
            val vector = FloatArray(values.size) { values[it].jsonPrimitive.float }
            require(vector.all(Float::isFinite)) { "Non-finite embedding vector" }
            require(dimension == null || dimension == vector.size) { "Embedding dimension mismatch" }
            dimension = vector.size
            result[index] = vector
        }
        return result.map { requireNotNull(it) }
    }
}
