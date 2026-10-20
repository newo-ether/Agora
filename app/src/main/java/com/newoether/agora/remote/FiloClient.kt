package com.newoether.agora.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
private data class RemoteModels(val models: List<RemoteModel>)
@Serializable
private data class FiloInfo(
    val protocolVersion: Int, val agent: String, val sessionMode: String,
    val messageDelivery: String, val outputMode: String, val device: String,
    val supportsLazyMessages: Boolean = false,
)
@Serializable
private data class SendInput(val text: String, val clientId: String, val attachments: List<String> = emptyList())
@Serializable
private data class SendResult(val turnId: String, val clientId: String)
// createSessionResponse: the full session spread plus the first-turn receipt.
@Serializable
private data class CreateResult(val id: String, val title: String, val cwd: String, val updatedAt: Long,
    val status: String? = null, val turnId: String, val clientId: String)

@Serializable
private data class FiloError(val code: String? = null, val error: String? = null)

internal class FiloHttpException(val status: Int, val code: String? = null, val detail: String? = null) : IOException("Filo HTTP $status")
internal class FiloStreamException(val detail: String? = null, val code: String? = null) : IOException("Filo could not read the native session")
internal fun remoteErrorDetail(error: Exception): String? = when (error) {
    is FiloHttpException -> error.detail
    is FiloStreamException -> error.detail
    is RemoteAttachmentException -> error.message
    else -> null
}
internal fun remoteErrorCode(error: Exception): String? = when (error) {
    is FiloHttpException -> error.code
    is FiloStreamException -> error.code
    else -> null
}
internal class FiloInputException : IllegalArgumentException("Invalid Filo message")
internal class FiloConfigurationException : IllegalArgumentException("Invalid Filo connection")
internal enum class RemoteFailure { NETWORK, AUTHENTICATION, CONFIGURATION, PROTOCOL, SERVICE, STORAGE, SESSION_BUSY, CONTENT_TOO_LARGE, UNKNOWN }

internal fun classifyRemoteFailure(error: Exception): RemoteFailure = when (error) {
    is RemoteContentLimitException -> RemoteFailure.CONTENT_TOO_LARGE
    is RemoteStorageException -> RemoteFailure.STORAGE
    is RemoteAttachmentException -> RemoteFailure.STORAGE
    is com.newoether.agora.util.ConchChannelException -> RemoteFailure.PROTOCOL
    is FiloConfigurationException, is FiloInputException -> RemoteFailure.CONFIGURATION
    is FiloStreamException -> RemoteFailure.SERVICE
    is FiloHttpException -> when {
        error.status == 409 && error.code == "session_busy" -> RemoteFailure.SESSION_BUSY
        error.status == 401 || error.status == 403 -> RemoteFailure.AUTHENTICATION
        else -> RemoteFailure.SERVICE
    }
    is IllegalArgumentException -> RemoteFailure.PROTOCOL
    is IOException -> RemoteFailure.NETWORK
    else -> RemoteFailure.UNKNOWN
}

/** A dedicated transport: credentials, redirects and retries never enter the provider client. */
internal class FiloClient(
    address: String,
    private val token: String,
    private val calls: Call.Factory = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        // Conch closes idle connections at 120s, so pooled ones must expire earlier. Without this
        // the encrypted path reuses a dead socket and the POST cannot be replayed safely.
        .connectionPool(okhttp3.ConnectionPool(5, 45, TimeUnit.SECONDS))
        .addInterceptor(com.newoether.agora.util.ConchEncryptedHttp(token))
        .callTimeout(30, TimeUnit.SECONDS).build(),
    mutationTimeoutMillis: Long = 210_000,
) {
    private val endpoint = try { address.trim().toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null &&
            it.fragment == null && it.encodedPath == "/")
        require(token.matches(Regex("[a-fA-F0-9]{64}")))
    } } catch (_: IllegalArgumentException) { throw FiloConfigurationException() }
    val address: String get() = endpoint.toString()
    private val json = Json { ignoreUnknownKeys = true }
    private fun decodeError(text: String): FiloError {
        val error = runCatching { json.decodeFromString<FiloError>(text) }.getOrNull() ?: return FiloError()
        return error.copy(code = error.code?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,63}")) }, error = error.error?.replace(token, "[redacted]")
            ?.filter { !it.isISOControl() || it == '\n' }?.trim()?.take(2048)?.takeIf { it.isNotBlank() })
    }
    private fun httpError(response: Response, text: String = response.body.source().readRemoteResponse()): FiloHttpException {
        val error = decodeError(text)
        return FiloHttpException(response.code, error.code, error.error)
    }
    // A server can close an idle pooled connection just before its next use. GETs can
    // recover on a fresh connection; native mutations keep the non-retrying transport.
    private val readCalls: Call.Factory = (calls as? OkHttpClient)?.newBuilder()
        ?.retryOnConnectionFailure(calls.interceptors.none { it is com.newoether.agora.util.ConchEncryptedHttp })?.build() ?: calls
    // Filo allows 180 seconds for cold executor readiness and native mutation acknowledgement.
    // Both the socket read and whole-call deadline must outlive that inner operation.
    private val mutationCalls: Call.Factory = (calls as? OkHttpClient)?.newBuilder()
        ?.retryOnConnectionFailure(false)?.followRedirects(false)?.followSslRedirects(false)
        ?.readTimeout(mutationTimeoutMillis, TimeUnit.MILLISECONDS)
        ?.callTimeout(mutationTimeoutMillis, TimeUnit.MILLISECONDS)?.build() ?: calls

    suspend fun connect(): String {
        val info = json.decodeFromString<FiloInfo>(request("v1/info"))
        require(info.protocolVersion == 2 && info.agent in setOf("codex", "pi") &&
            info.sessionMode == "existing" && info.messageDelivery == "native-steer" &&
            info.outputMode == "live-messages" && info.supportsLazyMessages) { "Incompatible Filo service" }
        return info.device
    }

    suspend fun sessions(cursor: String? = null): RemoteSessionPage = withContext(Dispatchers.Default) {
        val page = json.decodeFromString<RemoteSessionPage>(request("v1/sessions", cursor))
        page.copy(sessions = page.sessions.map { it.copy(listCursor = cursor) })
    }

    suspend fun conversation(id: String, cursor: String? = null): RemoteConversationPage = withContext(Dispatchers.Default) {
        decodePage(
            request("v1/sessions/${sessionId(id)}", cursor, includeActivity = true, includeMetadata = true),
        )
    }

    suspend fun image(
        id: String, requested: RemotePayloadRequest,
        persist: (java.io.InputStream, String) -> com.newoether.agora.model.ToolImageAttachment,
    ): com.newoether.agora.model.ToolImageAttachment = suspendCancellableCoroutine { continuation ->
        val url = endpoint.newBuilder().addPathSegments("v1/sessions/${sessionId(id)}/image")
            .addQueryParameter("messages", json.encodeToString(listOf(requested))).build()
        val call = readCalls.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw httpError(it)
                        if (it.body.contentLength() > com.newoether.agora.tool.ToolImageStore.MAX_IMAGE_BYTES)
                            throw RemoteContentLimitException()
                        val image = persist(it.body.byteStream(), it.header("Content-Type").orEmpty())
                        continuation.resume(image) { _, value, _ -> java.io.File(value.path).delete() }
                    } catch (error: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private fun decodePage(text: String): RemoteConversationPage =
        json.decodeFromString<RemoteConversationPage>(text).also { page ->
            require(page.messages.all { it.role == "user" || it.role == "assistant" })
            require(page.messages.map { it.id }.toSet().size == page.messages.size)
            require(page.inputReceipts.size <= 128 && page.inputReceipts.all {
                it.clientId.isNotBlank() && it.turnId.isNotBlank() && it.messageId.isNotBlank()
            }) { "Invalid native input receipt" }
            require(page.inputReceipts.map { it.clientId }.distinct().size == page.inputReceipts.size)
            page.messages.forEach { message -> message.activity?.let { activity ->
                require(message.role == "assistant" && activity.type in setOf("thought", "tool"))
                require(activity.type != "tool" || !activity.toolName.isNullOrBlank())
                require(activity.durationMs == null || activity.durationMs >= 0)
                require(activity.state == null || activity.state in setOf("running", "succeeded", "failed", "stopped"))
            } }
        }.let { page -> page.copy(runtime = page.runtime?.let { it.copy(skills = distinctRemoteSkills(it.skills)) }) }

    fun events(id: String): Flow<RemoteConversationPage> = eventStream(id, "paged", ::decodePage)

    private fun <T> eventStream(id: String, view: String?, decode: (String) -> T): Flow<T> = callbackFlow {
        val request = Request.Builder().url(endpoint.newBuilder()
            .addPathSegments("v1/sessions/${sessionId(id)}/events")
            .apply { view?.let { addQueryParameter("view", it) } }.build())
            .header("Authorization", "Bearer $token").build()
        val call = readCalls.newCall(request)
        call.timeout().clearTimeout()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw httpError(it)
                        val source = it.body.source()
                        var errorEvent = false
                        while (!call.isCanceled()) {
                            val line = source.readRemoteEventLine() ?: break
                            if (line.startsWith("event:")) errorEvent = line.removePrefix("event:").trim() == "error"
                            if (line.isEmpty() && errorEvent) throw FiloStreamException()
                            if (line.startsWith("data: ")) {
                                val data = line.removePrefix("data: ")
                                if (errorEvent) {
                                    val failure = decodeError(data)
                                    throw FiloStreamException(failure.error, failure.code)
                                }
                                trySend(decode(data))
                            }
                        }
                        if (errorEvent) throw FiloStreamException()
                        close(IOException("Filo stream closed"))
                    } catch (error: Exception) { close(error) }
                }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(Channel.CONFLATED)

    suspend fun rename(id: String, name: String): RemoteSession =
        json.decodeFromString<RemoteSession>(request("v1/sessions/${sessionId(id)}/rename",
            body = json.encodeToString(mapOf("name" to name)))).also { require(it.id == id) }
    suspend fun archiveSession(id: String) {
        val result = json.parseToJsonElement(request("v1/sessions/${sessionId(id)}/archive", body = "{}")).jsonObject
        require(result["archived"]?.jsonPrimitive?.booleanOrNull == true) { "Native archive is unconfirmed" }
    }

    // Creation always carries the first message: no session ever exists without one.
    suspend fun create(text: String, clientId: String, attachments: List<String> = emptyList(), settings: RemoteSettings? = null): RemoteSession {
        if ((text.isBlank() && attachments.isEmpty()) || attachments.size > REMOTE_ATTACHMENT_COUNT ||
            attachments.any { !it.matches(Regex("[a-f0-9]{32}")) }) throw FiloInputException()
        if (settings?.model.isNullOrEmpty()) throw FiloInputException()
        val body = JsonObject(buildJsonObject {
            put("text", JsonPrimitive(text)); put("clientId", JsonPrimitive(clientId))
            put("attachments", JsonArray(attachments.map(::JsonPrimitive)))
            settings?.let { put("settings", it.jsonSettings()) }
        }).toString()
        if (body.toByteArray(Charsets.UTF_8).size > 65536) throw FiloInputException()
        return json.decodeFromString<CreateResult>(request("v1/sessions", body = body))
            .also { require(it.clientId == clientId && it.turnId.isNotBlank() && it.id.isNotBlank()) }
            .let { RemoteSession(it.id, it.title, it.cwd, it.updatedAt, it.status, acceptedTurnId = it.turnId) }
    }

    // Only the three next-turn keys leave the device; a malformed body never reaches native.
    private fun RemoteSettings.jsonSettings(): JsonObject {
        val source = (json.parseToJsonElement(body()) as? JsonObject) ?: JsonObject(emptyMap())
        val settings = buildJsonObject {
            source["model"]?.let { put("model", it) }
            source["effort"]?.let { put("effort", it) }
            source["serviceTier"]?.let { put("serviceTier", it) }
        }
        require((settings["model"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true) { "Settings need a model" }
        return JsonObject(settings)
    }
    suspend fun models(): List<RemoteModel> = json.decodeFromString<RemoteModels>(request("v1/models")).models
    suspend fun usage(): RemoteUsage = json.decodeFromString(request("v1/usage"))
    suspend fun setModel(id: String, model: String) {
        request("v1/sessions/${sessionId(id)}/model", body = json.encodeToString(mapOf("model" to model)))
    }
    suspend fun updateSettings(id: String, settings: RemoteSettings) {
        request("v1/sessions/${sessionId(id)}/settings", body = settings.body())
    }
    suspend fun stop(id: String, turnId: String) {
        request("v1/sessions/${sessionId(id)}/stop", body = json.encodeToString(mapOf("turnId" to turnId)))
    }

    suspend fun upload(item: com.newoether.agora.model.SelectedAttachment): RemoteUpload =
        uploadRemoteAttachment(item) { path, method, payload -> request(path, uploadBody = payload, method = method) }

    suspend fun send(id: String, text: String, clientId: String, attachments: List<String> = emptyList()): String {
        val body = json.encodeToString(SendInput(text, clientId, attachments))
        if ((text.isBlank() && attachments.isEmpty()) || attachments.size > REMOTE_ATTACHMENT_COUNT ||
            attachments.any { !it.matches(Regex("[a-f0-9]{32}")) } || body.toByteArray(Charsets.UTF_8).size > 65536) throw FiloInputException()
        return json.decodeFromString<SendResult>(
            request("v1/sessions/${sessionId(id)}/messages", body = body),
        ).also { require(it.clientId == clientId && it.turnId.isNotBlank()) }.turnId
    }

    private fun sessionId(id: String): String {
        require(id.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
        return id
    }

    private suspend fun request(
        path: String, cursor: String? = null, body: String? = null, includeActivity: Boolean = false,
        includeMetadata: Boolean = false,
        uploadBody: okhttp3.RequestBody? = null, method: String? = null,
    ): String =
        suspendCancellableCoroutine { continuation ->
            val url = requireNotNull(endpoint.resolve(path)).newBuilder().apply {
                cursor?.let { addQueryParameter("cursor", it) }
                if (includeActivity) addQueryParameter("includeActivity", "true")
                if (includeMetadata) addQueryParameter("includeMetadata", "true")
            }.build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .apply {
                    val payload = uploadBody ?: body?.toRequestBody("application/json".toMediaType())
                    if (payload != null) method(method ?: "POST", payload)
                }.build()
            val call = (if (body == null && uploadBody == null) readCalls else mutationCalls).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val text = it.body.source().readRemoteResponse()
                            if (!it.isSuccessful) throw httpError(it, text)
                            if (!continuation.isCancelled) continuation.resume(text)
                        } catch (error: Exception) {
                            if (!continuation.isCancelled) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }
}
