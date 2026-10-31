package com.newoether.agora.mcp

import com.newoether.agora.data.McpTransportType
import com.newoether.agora.util.NetworkRetryPolicy
import com.newoether.agora.util.NetworkHttpException
import com.newoether.agora.util.Constants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

internal fun isUnsupportedMcpProtocolVersion(error: Throwable): Boolean {
    val text = generateSequence(error) { it.cause }
        .joinToString(" ") { it.message.orEmpty() }
        .lowercase()
    return "unsupported protocol version" in text ||
        "unsupported mcp protocol version" in text ||
        "protocol version is not supported" in text
}

/**
 * Shared MCP JSON-RPC lifecycle.
 *
 * Transport-specific connection/session behavior lives behind [McpClientTransport]. Requests are
 * serialized because initialization and session replacement must be observed atomically.
 */
internal class McpProtocolClient(
    endpoint: String,
    customHeaders: Map<String, String>,
    transportType: McpTransportType,
) : AutoCloseable {
    companion object {
        private const val STREAMABLE_HTTP_PROTOCOL_VERSION = "2025-11-25"
        private const val LEGACY_SSE_PROTOCOL_VERSION = "2024-11-05"
        private const val MAX_TOOL_PAGES = 100
        internal val STREAMABLE_HTTP_PROTOCOL_VERSIONS = listOf(
            STREAMABLE_HTTP_PROTOCOL_VERSION,
            "2025-06-18",
            "2025-03-26",
            LEGACY_SSE_PROTOCOL_VERSION,
        )
        private val SUPPORTED_PROTOCOL_VERSIONS = STREAMABLE_HTTP_PROTOCOL_VERSIONS.toSet()
    }

    private val transportType = transportType
    private var protocolVersion = when (transportType) {
        McpTransportType.STREAMABLE_HTTP -> STREAMABLE_HTTP_PROTOCOL_VERSION
        McpTransportType.SSE -> LEGACY_SSE_PROTOCOL_VERSION
    }
    private val transport = createMcpClientTransport(
        type = transportType,
        endpoint = endpoint,
        customHeaders = customHeaders,
        protocolVersion = protocolVersion,
    )
    private val ids = AtomicLong(0)
    private val mutex = Mutex()

    private var initialized = false
    private var initializedGeneration: Long? = null

    suspend fun listTools(): List<McpRemoteTool> = mutex.withLock {
        retryAfterSessionExpiry {
            ensureInitializedLocked()
            val tools = mutableListOf<McpRemoteTool>()
            var cursor: String? = null
            repeat(MAX_TOOL_PAGES) {
                val params = if (cursor == null) {
                    buildJsonObject {}
                } else {
                    buildJsonObject { put("cursor", cursor) }
                }
                val result = requestLocked("tools/list", params)
                val page = result["tools"] as? JsonArray
                    ?: throw IOException("MCP tools/list returned no tools array")
                page.forEach { element ->
                    val obj = element.asObjectOrNull() ?: return@forEach
                    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: return@forEach
                    tools += McpRemoteTool(
                        name = name,
                        description = (obj["description"] as? JsonPrimitive)
                            ?.contentOrNull
                            .orEmpty(),
                        inputSchema = obj["inputSchema"]?.asObjectOrNull()
                            ?: buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {})
                            },
                    )
                }
                cursor = (result["nextCursor"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                if (cursor == null) return@retryAfterSessionExpiry tools
            }
            throw IOException("MCP tools/list exceeded $MAX_TOOL_PAGES pages")
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallPayload = mutex.withLock {
        retryAfterSessionExpiry {
            ensureInitializedLocked()
        }
        try {
            val result = requestLocked(
                method = "tools/call",
                params = buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
            )
            parseCallPayload(result)
        } catch (error: McpSessionExpiredException) {
            // Recover for the next independent invocation without replaying this tool request.
            initialized = false
            initializedGeneration = null
            transport.resetSession()
            throw error
        }
    }

    override fun close() {
        initialized = false
        initializedGeneration = null
        transport.close()
    }

    private suspend fun ensureInitializedLocked() {
        val generation = ensureReadyWithRetry()
        if (initialized && initializedGeneration == generation) return
        initialized = false
        initializedGeneration = null
        val candidates = if (transportType == McpTransportType.STREAMABLE_HTTP) {
            STREAMABLE_HTTP_PROTOCOL_VERSIONS.dropWhile { it != protocolVersion }
                .ifEmpty { STREAMABLE_HTTP_PROTOCOL_VERSIONS }
        } else {
            listOf(LEGACY_SSE_PROTOCOL_VERSION)
        }
        var unsupported: IOException? = null
        candidates.forEachIndexed { index, candidate ->
            if (candidate != protocolVersion) {
                protocolVersion = candidate
                transport.updateProtocolVersion(candidate)
                transport.resetSession()
            }
            try {
                initializeLocked(ensureReadyWithRetry())
                return
            } catch (error: IOException) {
                if (!isUnsupportedMcpProtocolVersion(error) || index == candidates.lastIndex) {
                    throw error
                }
                unsupported = error
                initialized = false
                initializedGeneration = null
                transport.resetSession()
            }
        }
        throw unsupported ?: IOException("MCP protocol negotiation failed")
    }

    private suspend fun ensureReadyWithRetry(): Long {
        var retryAfter: String? = null
        return NetworkRetryPolicy.retry(
            canRetry = { failure ->
                retryAfter = (failure as? NetworkHttpException)?.retryAfter
                failure is McpSessionExpiredException ||
                    (failure is NetworkHttpException && failure.statusCode in NetworkRetryPolicy.HTTP_CODES) ||
                    NetworkRetryPolicy.isTransient(failure)
            },
            retryAfter = { retryAfter },
        ) { transport.ensureReady() }
    }

    private suspend fun initializeLocked(generation: Long) {
        val result = requestLocked(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", protocolVersion)
                put("capabilities", buildJsonObject {})
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "Agora")
                        put("version", "1.3.7")
                    },
                )
            },
        )
        val negotiated = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull
            ?: throw IOException("MCP initialize returned no protocolVersion")
        if (negotiated !in SUPPORTED_PROTOCOL_VERSIONS) {
            throw IOException("Unsupported MCP protocol version: $negotiated")
        }
        if (negotiated != protocolVersion) {
            protocolVersion = negotiated
            transport.updateProtocolVersion(negotiated)
        }
        notificationLocked("notifications/initialized", buildJsonObject {})
        if (transport.ensureReady() != generation) {
            throw McpSessionExpiredException("MCP session changed during initialization")
        }
        initializedGeneration = generation
        initialized = true
    }

    private suspend fun <T> retryAfterSessionExpiry(block: suspend () -> T): T {
        var expiry: McpSessionExpiredException? = null
        repeat(2) { attempt ->
            try {
                return block()
            } catch (error: McpSessionExpiredException) {
                expiry = error
                initialized = false
                initializedGeneration = null
                transport.resetSession()
                if (attempt == 1) throw error
            }
        }
        throw checkNotNull(expiry)
    }

    private suspend fun requestLocked(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val envelope = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val progress = com.newoether.agora.util.NetworkRequestProgress()
        var retryAfter: String? = null
        val response = NetworkRetryPolicy.retry(
            canRetry = { failure ->
                retryAfter = (failure as? NetworkHttpException)?.retryAfter
                if (method == "tools/call") !progress.started && NetworkRetryPolicy.isBeforeSendFailure(failure)
                else if (failure is NetworkHttpException) failure.statusCode in NetworkRetryPolicy.HTTP_CODES
                else if (failure is McpSessionExpiredException) false
                else NetworkRetryPolicy.isTransient(failure)
            },
            retryAfter = { retryAfter },
        ) {
            progress.started = false
            transport.send(
                envelope = envelope,
                expectResponse = true,
                progress = progress,
                callTimeoutMillis = if (method == "tools/call") {
                    Constants.TOOL_EXECUTION_TIMEOUT_MS
                } else {
                    Constants.NETWORK_TOOL_TIMEOUT_MS
                },
            )
        } ?: throw IOException("MCP $method returned an empty response")
        val responseId = (response["id"] as? JsonPrimitive)?.contentOrNull
        if (responseId != id.toString()) {
            throw IOException("MCP $method returned mismatched response id")
        }
        response["error"]?.takeUnless { it is JsonNull }?.let { error ->
            val errorObj = error.asObjectOrNull()
            val message = (errorObj?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: error.toString()
            throw IOException("MCP $method failed: $message")
        }
        return response["result"]?.asObjectOrNull()
            ?: throw IOException("MCP $method returned no result")
    }

    private suspend fun notificationLocked(method: String, params: JsonObject) {
        var retryAfter: String? = null
        NetworkRetryPolicy.retry(
            canRetry = { failure ->
                retryAfter = (failure as? NetworkHttpException)?.retryAfter
                if (failure is NetworkHttpException) failure.statusCode in NetworkRetryPolicy.HTTP_CODES
                else if (failure is McpSessionExpiredException) false
                else NetworkRetryPolicy.isTransient(failure)
            },
            retryAfter = { retryAfter },
        ) {
            transport.send(
                envelope = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", method)
                    put("params", params)
                },
                expectResponse = false,
                callTimeoutMillis = Constants.NETWORK_TOOL_TIMEOUT_MS,
            )
        }
    }

    private fun parseCallPayload(result: JsonObject): McpCallPayload {
        val texts = mutableListOf<String>()
        val images = mutableListOf<McpImagePayload>()
        (result["content"] as? JsonArray).orEmpty().forEach { element ->
            val item = element.asObjectOrNull() ?: return@forEach
            when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (item["text"] as? JsonPrimitive)?.contentOrNull?.let(texts::add)
                "image" -> {
                    val data = (item["data"] as? JsonPrimitive)?.contentOrNull
                    val mimeType = (item["mimeType"] as? JsonPrimitive)?.contentOrNull
                    if (!data.isNullOrBlank() && !mimeType.isNullOrBlank()) {
                        images += McpImagePayload(data, mimeType)
                    }
                }
                "resource" -> {
                    val resource = item["resource"]?.asObjectOrNull()
                    val mimeType = (resource?.get("mimeType") as? JsonPrimitive)?.contentOrNull
                    val blob = (resource?.get("blob") as? JsonPrimitive)?.contentOrNull
                    val text = (resource?.get("text") as? JsonPrimitive)?.contentOrNull
                    if (!blob.isNullOrBlank() && mimeType?.startsWith("image/") == true) {
                        images += McpImagePayload(blob, mimeType)
                    } else if (!text.isNullOrBlank()) {
                        texts += text
                    }
                }
            }
        }
        return McpCallPayload(
            textParts = texts,
            images = images,
            structuredContent = result["structuredContent"]?.takeUnless { it is JsonNull },
            isError = (result["isError"] as? JsonPrimitive)?.contentOrNull
                ?.toBooleanStrictOrNull() == true,
        )
    }
}
