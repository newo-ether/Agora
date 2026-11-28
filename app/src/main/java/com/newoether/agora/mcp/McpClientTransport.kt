package com.newoether.agora.mcp

import com.newoether.agora.api.HttpClient
import com.newoether.agora.data.McpTransportType
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Transport boundary for the shared MCP JSON-RPC lifecycle.
 *
 * [ensureReady] returns the identity of the active server session. A changed identity forces the
 * protocol client to initialize again before sending any other request.
 */
internal interface McpClientTransport : AutoCloseable {
    suspend fun ensureReady(): Long

    suspend fun send(
        envelope: JsonObject,
        expectResponse: Boolean,
        callTimeoutMillis: Long,
        progress: com.newoether.agora.util.NetworkRequestProgress? = null,
    ): JsonObject?

    fun resetSession()
    fun updateProtocolVersion(protocolVersion: String)
}

internal fun createMcpClientTransport(
    type: McpTransportType,
    endpoint: String,
    customHeaders: Map<String, String>,
    protocolVersion: String,
): McpClientTransport = when (type) {
    McpTransportType.STREAMABLE_HTTP -> StreamableHttpMcpTransport(
        endpoint = endpoint,
        customHeaders = customHeaders,
        protocolVersion = protocolVersion,
    )
    McpTransportType.SSE -> LegacySseMcpTransport(
        endpoint = endpoint,
        customHeaders = customHeaders,
        protocolVersion = protocolVersion,
    )
}

internal class McpSessionExpiredException(
    message: String = "MCP session expired",
    cause: Throwable? = null,
) : IOException(message, cause)

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

internal val MCP_RESERVED_HEADERS: Set<String> = setOf(
    "accept",
    "content-type",
    "content-length",
    "host",
    "connection",
    "cache-control",
    "mcp-session-id",
    "mcp-protocol-version",
)

private val HTTP_TOKEN_PUNCTUATION = "!#$%&'*+-.^_`|~".toSet()

internal fun isValidMcpHeaderName(value: String): Boolean {
    val name = value.trim()
    return name.isNotEmpty() && name.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in HTTP_TOKEN_PUNCTUATION
    }
}

internal fun isValidMcpHeaderValue(value: String): Boolean =
    value.isNotBlank() && value.all { char ->
        char == '\t' || char.code in 0x20..0x7e || char.code >= 0x80
    }

internal fun isReservedMcpHeaderName(value: String): Boolean =
    value.trim().lowercase(Locale.ROOT) in MCP_RESERVED_HEADERS

private fun normalizedMcpHeaders(headers: Map<String, String>): Map<String, String> {
    val seen = mutableSetOf<String>()
    val normalized = linkedMapOf<String, String>()
    headers.forEach { (rawName, rawValue) ->
        val name = rawName.trim()
        val value = rawValue.trim()
        val lookup = name.lowercase(Locale.ROOT)
        if (
            isValidMcpHeaderName(name) &&
            isValidMcpHeaderValue(value) &&
            lookup !in MCP_RESERVED_HEADERS &&
            seen.add(lookup)
        ) {
            normalized[name] = value
        }
    }
    return normalized
}

private fun Request.Builder.applyMcpHeaders(headers: Map<String, String>) = apply {
    headers.forEach { (name, value) -> header(name, value) }
}

private fun guardMcpCredentials(endpoint: String, headers: Map<String, String>) {
    HttpClient.guardCleartextCredentials(
        endpoint,
        if (headers.isEmpty()) {
            emptyMap()
        } else {
            // Header names are user-defined, so conservatively treat every non-empty set as
            // credentials when applying the cleartext transport guard.
            headers + ("Authorization" to "<configured>")
        },
    )
}

private suspend fun Call.awaitResponse(activeCalls: MutableSet<Call>): Response =
    suspendCancellableCoroutine { continuation ->
        activeCalls += this
        continuation.invokeOnCancellation {
            cancel()
            activeCalls -= this
        }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeCalls -= call
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    activeCalls -= call
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(response))
                    } else {
                        response.close()
                    }
                }
            },
        )
    }

/**
 * MCP Streamable HTTP transport. Every JSON-RPC envelope is posted to the configured endpoint;
 * responses may be JSON or a finite SSE response body.
 */
private class StreamableHttpMcpTransport(
    endpoint: String,
    customHeaders: Map<String, String>,
    protocolVersion: String,
) : McpClientTransport {
    @Volatile
    private var protocolVersion: String = protocolVersion
    private val endpoint = endpoint.toHttpUrl()
    private val headers = normalizedMcpHeaders(customHeaders)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val sessionGeneration = AtomicLong(0)
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val client = HttpClient.client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(Constants.NETWORK_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Constants.TOOL_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(Constants.NETWORK_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var closed = false

    override suspend fun ensureReady(): Long {
        check(!closed) { "MCP transport is closed" }
        return sessionGeneration.get()
    }

    override suspend fun send(
        envelope: JsonObject,
        expectResponse: Boolean,
        callTimeoutMillis: Long,
        progress: com.newoether.agora.util.NetworkRequestProgress?,
    ): JsonObject? {
        check(!closed) { "MCP transport is closed" }
        guardMcpCredentials(endpoint.toString(), headers)
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", protocolVersion)
            .applyMcpHeaders(headers)
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
            .post(envelope.toString().toRequestBody(JSON_MEDIA_TYPE))
            .tag(com.newoether.agora.util.NetworkRequestProgress::class.java, progress)
            .build()
        val call = client.newCall(request).also {
            it.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        }
        return withContext(Dispatchers.IO) {
            call.awaitResponse(activeCalls).use { response ->
                if ((response.code == 404 || response.code == 410) && sessionId != null) {
                    throw McpSessionExpiredException()
                }
                if (!response.isSuccessful) {
                    throw mcpHttpException(response)
                }
                response.header("Mcp-Session-Id")
                    ?.takeIf(String::isNotBlank)
                    ?.let { sessionId = it }
                if (!expectResponse || response.code == 202) return@use null
                val body = response.body ?: throw IOException("MCP response body is empty")
                if (response.header("Content-Type").orEmpty().contains("text/event-stream", true)) {
                    parseFiniteSseResponse(body.source(), json)
                } else {
                    parseJsonRpcEnvelope(body.string(), json)
                }
            }
        }
    }

    override fun resetSession() {
        sessionId = null
        sessionGeneration.incrementAndGet()
    }

    override fun updateProtocolVersion(protocolVersion: String) {
        this.protocolVersion = protocolVersion
    }

    override fun close() {
        closed = true
        activeCalls.toList().forEach(Call::cancel)
        activeCalls.clear()
    }
}

/**
 * Legacy MCP HTTP+SSE transport from protocol version 2024-11-05.
 *
 * The configured URL is opened as a long-lived GET event stream. Its `endpoint` event advertises
 * the same-origin URL used for client POSTs; JSON-RPC responses arrive asynchronously in `message`
 * events and are routed by request id.
 */
private class LegacySseMcpTransport(
    endpoint: String,
    customHeaders: Map<String, String>,
    protocolVersion: String,
) : McpClientTransport {
    @Volatile
    private var protocolVersion: String = protocolVersion
    private class Connection(
        val generation: Long,
        val messageEndpoint: CompletableDeferred<HttpUrl> = CompletableDeferred(),
    ) {
        lateinit var job: Job

        @Volatile
        var call: Call? = null
    }

    private data class PendingResponse(
        val generation: Long,
        val response: CompletableDeferred<JsonObject>,
    )

    private data class ReadyConnection(
        val connection: Connection,
        val messageEndpoint: HttpUrl,
    )

    private val streamEndpoint = endpoint.toHttpUrl()
    private val headers = normalizedMcpHeaders(customHeaders)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val stateLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streamClient = HttpClient.client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(Constants.NETWORK_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(Constants.NETWORK_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val postClient = HttpClient.client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(Constants.NETWORK_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Constants.TOOL_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(Constants.NETWORK_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val activePostCalls = ConcurrentHashMap.newKeySet<Call>()
    private val pendingResponses = mutableMapOf<String, PendingResponse>()
    private var nextGeneration = 0L
    private var connection: Connection? = null
    private var closed = false

    override suspend fun ensureReady(): Long = awaitReadyConnection().connection.generation

    override suspend fun send(
        envelope: JsonObject,
        expectResponse: Boolean,
        callTimeoutMillis: Long,
        progress: com.newoether.agora.util.NetworkRequestProgress?,
    ): JsonObject? {
        val ready = awaitReadyConnection()
        val requestId = (envelope["id"] as? JsonPrimitive)?.contentOrNull
        val pending = if (expectResponse) {
            require(!requestId.isNullOrBlank()) { "MCP request is missing an id" }
            CompletableDeferred<JsonObject>().also { deferred ->
                synchronized(stateLock) {
                    if (connection !== ready.connection || !ready.connection.job.isActive) {
                        throw McpSessionExpiredException(
                            "MCP SSE session ended before the request was sent",
                        )
                    }
                    pendingResponses[requestId] = PendingResponse(
                        generation = ready.connection.generation,
                        response = deferred,
                    )
                }
            }
        } else {
            null
        }

        return try {
            val directResponse = postEnvelope(
                endpoint = ready.messageEndpoint,
                envelope = envelope,
                callTimeoutMillis = callTimeoutMillis,
                progress = progress,
            )
            if (!expectResponse) return null
            if (directResponse != null) {
                synchronized(stateLock) { pendingResponses.remove(requestId) }
                return directResponse
            }
            try {
                withTimeout(callTimeoutMillis) {
                    checkNotNull(pending).await()
                }
            } catch (error: TimeoutCancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                throw java.net.SocketTimeoutException("MCP SSE request timed out")
            }
        } finally {
            if (requestId != null) {
                synchronized(stateLock) {
                    val current = pendingResponses[requestId]
                    if (current?.response === pending) pendingResponses.remove(requestId)
                }
            }
        }
    }

    override fun resetSession() {
        invalidateConnection(McpSessionExpiredException())
    }

    override fun updateProtocolVersion(protocolVersion: String) {
        this.protocolVersion = protocolVersion
    }

    override fun close() {
        val toCancel: Connection?
        val pending: List<CompletableDeferred<JsonObject>>
        synchronized(stateLock) {
            if (closed) return
            closed = true
            toCancel = connection
            connection = null
            pending = pendingResponses.values.map(PendingResponse::response)
            pendingResponses.clear()
        }
        toCancel?.call?.cancel()
        toCancel?.job?.cancel()
        activePostCalls.toList().forEach(Call::cancel)
        activePostCalls.clear()
        pending.forEach { it.cancel(CancellationException("MCP transport closed")) }
        scope.cancel()
    }

    private suspend fun awaitReadyConnection(): ReadyConnection {
        val selected = synchronized(stateLock) {
            check(!closed) { "MCP transport is closed" }
            connection?.takeIf { it.job.isActive } ?: newConnectionLocked()
        }
        val endpoint = try {
            withTimeout(Constants.NETWORK_TOOL_TIMEOUT_MS) {
                selected.messageEndpoint.await()
            }
        } catch (error: TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val failure = java.net.SocketTimeoutException("MCP SSE endpoint timed out")
            invalidateConnection(failure, expected = selected)
            throw failure
        } catch (error: Throwable) {
            invalidateConnection(
                if (error is CancellationException) error else {
                    McpSessionExpiredException("MCP SSE endpoint was not established", error)
                },
                expected = selected,
            )
            throw error
        }
        synchronized(stateLock) {
            if (closed || connection !== selected || !selected.job.isActive) {
                throw McpSessionExpiredException("MCP SSE session ended")
            }
        }
        return ReadyConnection(selected, endpoint)
    }

    private fun newConnectionLocked(): Connection {
        val created = Connection(generation = ++nextGeneration)
        created.job = scope.launch(start = CoroutineStart.LAZY) {
            runEventStream(created)
        }
        connection = created
        created.job.start()
        return created
    }

    private fun runEventStream(active: Connection) {
        var terminalError: Throwable? = null
        try {
            guardMcpCredentials(streamEndpoint.toString(), headers)
            val request = Request.Builder()
                .url(streamEndpoint)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("MCP-Protocol-Version", protocolVersion)
                .applyMcpHeaders(headers)
                .get()
                .build()
            val call = streamClient.newCall(request)
            active.call = call
            synchronized(stateLock) {
                if (closed || connection !== active) {
                    call.cancel()
                    throw CancellationException("MCP SSE session was closed")
                }
            }
            call.execute().use { response ->
                if (!response.isSuccessful) throw mcpHttpException(response)
                if (!response.header("Content-Type").orEmpty().contains("text/event-stream", true)) {
                    throw IOException("MCP SSE endpoint did not return text/event-stream")
                }
                val source = response.body?.source()
                    ?: throw IOException("MCP SSE response body is empty")
                val parser = McpSseEventParser()
                while (!source.exhausted()) {
                    parser.accept(source.readUtf8Line() ?: break)?.let { event ->
                        dispatchEvent(active, event)
                    }
                }
                parser.finish()?.let { event -> dispatchEvent(active, event) }
                terminalError = McpSessionExpiredException("MCP SSE stream ended")
            }
        } catch (error: CancellationException) {
            terminalError = error
            throw error
        } catch (error: Throwable) {
            terminalError = error
        } finally {
            finishConnection(
                active,
                terminalError ?: McpSessionExpiredException("MCP SSE stream ended"),
            )
        }
    }

    private fun dispatchEvent(active: Connection, event: McpSseEvent) {
        when (event.event) {
            "endpoint" -> {
                val endpoint = resolveLegacySseMessageEndpoint(streamEndpoint, event.data)
                active.messageEndpoint.complete(endpoint)
            }
            "message" -> {
                val envelope = runCatching {
                    parseJsonRpcEnvelope(event.data, json)
                }.getOrElse { error ->
                    throw IOException("Invalid JSON-RPC message from MCP SSE stream", error)
                }
                if (envelope["result"] == null && envelope["error"] == null) return
                val id = (envelope["id"] as? JsonPrimitive)?.contentOrNull ?: return
                val target = synchronized(stateLock) {
                    pendingResponses[id]
                        ?.takeIf { it.generation == active.generation }
                        ?.also { pendingResponses.remove(id) }
                }
                target?.response?.complete(envelope)
            }
        }
    }

    private suspend fun postEnvelope(
        endpoint: HttpUrl,
        envelope: JsonObject,
        callTimeoutMillis: Long,
        progress: com.newoether.agora.util.NetworkRequestProgress?,
    ): JsonObject? {
        guardMcpCredentials(endpoint.toString(), headers)
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", protocolVersion)
            .applyMcpHeaders(headers)
            .post(envelope.toString().toRequestBody(JSON_MEDIA_TYPE))
            .tag(com.newoether.agora.util.NetworkRequestProgress::class.java, progress)
            .build()
        val call = postClient.newCall(request).also {
            it.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        }
        return withContext(Dispatchers.IO) {
            call.awaitResponse(activePostCalls).use { response ->
                if (response.code == 404 || response.code == 410) {
                    throw McpSessionExpiredException()
                }
                if (!response.isSuccessful) throw mcpHttpException(response)
                if (response.code == 202) return@use null
                val body = response.body ?: return@use null
                val contentType = response.header("Content-Type").orEmpty()
                when {
                    contentType.contains("text/event-stream", true) ->
                        parseFiniteSseResponse(body.source(), json)
                    contentType.contains("application/json", true) -> {
                        val payload = body.string()
                        if (payload.isBlank()) null else parseJsonRpcEnvelope(payload, json)
                    }
                    else -> null
                }
            }
        }
    }

    private fun invalidateConnection(
        error: Throwable,
        expected: Connection? = null,
    ) {
        val toCancel: Connection?
        val pending: List<CompletableDeferred<JsonObject>>
        synchronized(stateLock) {
            val current = connection
            if (expected != null && current !== expected) return
            toCancel = current
            connection = null
            pending = if (current == null) {
                emptyList()
            } else {
                pendingResponses.entries
                    .filter { it.value.generation == current.generation }
                    .map { it.key to it.value.response }
                    .also { entries -> entries.forEach { pendingResponses.remove(it.first) } }
                    .map { it.second }
            }
        }
        toCancel?.call?.cancel()
        toCancel?.job?.cancel()
        pending.forEach { it.completeExceptionally(error) }
    }

    private fun finishConnection(active: Connection, error: Throwable) {
        if (!active.messageEndpoint.isCompleted) {
            active.messageEndpoint.completeExceptionally(error)
        }
        val pending: List<CompletableDeferred<JsonObject>>
        synchronized(stateLock) {
            if (connection === active) connection = null
            pending = pendingResponses.entries
                .filter { it.value.generation == active.generation }
                .map { it.key to it.value.response }
                .also { entries -> entries.forEach { pendingResponses.remove(it.first) } }
                .map { it.second }
        }
        val failure = if (error is CancellationException) {
            error
        } else {
            McpSessionExpiredException("MCP SSE session ended", error)
        }
        pending.forEach { it.completeExceptionally(failure) }
    }
}

internal data class McpSseEvent(
    val event: String,
    val data: String,
)

/** Small incremental SSE parser shared by finite Streamable HTTP bodies and legacy SSE streams. */
internal class McpSseEventParser {
    private var eventType: String? = null
    private val data = StringBuilder()

    fun accept(rawLine: String): McpSseEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(':')) return null
        val separator = line.indexOf(':')
        val field = if (separator < 0) line else line.substring(0, separator)
        val rawValue = if (separator < 0) "" else line.substring(separator + 1)
        val value = rawValue.removePrefix(" ")
        when (field) {
            "event" -> eventType = value
            "data" -> data.append(value).append('\n')
        }
        return null
    }

    fun finish(): McpSseEvent? = dispatch()

    private fun dispatch(): McpSseEvent? {
        if (data.isEmpty()) {
            eventType = null
            return null
        }
        val event = McpSseEvent(
            event = eventType?.ifBlank { "message" } ?: "message",
            data = data.toString().removeSuffix("\n"),
        )
        eventType = null
        data.clear()
        return event
    }
}

internal fun resolveLegacySseMessageEndpoint(
    streamEndpoint: HttpUrl,
    advertisedEndpoint: String,
): HttpUrl {
    val advertised = advertisedEndpoint.trim()
    if (advertised.isEmpty()) {
        throw IOException("MCP SSE server advertised an empty message endpoint")
    }
    val resolved = streamEndpoint.resolve(advertised)
        ?: throw IOException("MCP SSE server advertised an invalid message endpoint")
    if (
        resolved.scheme != streamEndpoint.scheme ||
        !resolved.host.equals(streamEndpoint.host, ignoreCase = true) ||
        resolved.port != streamEndpoint.port ||
        resolved.username.isNotEmpty() ||
        resolved.password.isNotEmpty() ||
        resolved.fragment != null
    ) {
        throw IOException("MCP SSE message endpoint must use the same origin")
    }
    return resolved
}

private fun parseJsonRpcEnvelope(payload: String, json: Json): JsonObject =
    json.parseToJsonElement(payload).jsonObject

private fun parseFiniteSseResponse(source: BufferedSource, json: Json): JsonObject {
    val parser = McpSseEventParser()
    while (!source.exhausted()) {
        val event = parser.accept(source.readUtf8Line() ?: break) ?: continue
        val parsed = runCatching { parseJsonRpcEnvelope(event.data, json) }.getOrNull()
        if (parsed != null && (parsed["result"] != null || parsed["error"] != null)) {
            return parsed
        }
    }
    parser.finish()?.let { event ->
        val parsed = runCatching { parseJsonRpcEnvelope(event.data, json) }.getOrNull()
        if (parsed != null && (parsed["result"] != null || parsed["error"] != null)) {
            return parsed
        }
    }
    throw java.io.EOFException("MCP SSE stream ended before a JSON-RPC response")
}

private fun mcpHttpException(response: Response): IOException {
    val body = try {
        response.body?.string().orEmpty().take(2_048)
    } catch (failure: Exception) {
        com.newoether.agora.util.NetworkRetryPolicy.throwIfCancelled(failure)
        "Response body could not be read"
    }
    return com.newoether.agora.util.NetworkHttpException(
        response.code, response.header("Retry-After"),
        "MCP HTTP ${response.code}${body.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}",
    )
}
