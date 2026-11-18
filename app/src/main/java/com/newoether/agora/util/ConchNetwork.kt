package com.newoether.agora.util

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.readBoundedWireText
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Conch owns its retry policy; ordinary Provider calls keep their existing policy. */
internal data class ConchTextResponse(
    val code: Int,
    val body: String,
    val isSuccessful: Boolean,
    val signature: String,
    val retryAfter: String?,
)

internal object ConchNetwork {
    /** Connection attempts stay short: three retries, then the caller sees the failure. */
    const val MAX_RETRIES = 3
    const val CONTROL_LIMIT = 64L shl 10
    const val RESPONSE_LIMIT = 40L shl 20
    const val LINE_LIMIT = 10L shl 20

    private val readOnlyPaths = setOf(
        "/file/read", "/file/image", "/file/glob", "/file/grep", "/jobs/list", "/jobs/get",
        "/file/transfer/capabilities", "/file/transfer/info", "/file/transfer/read", "/file/transfer/status",
    )

    fun isReadOnly(path: String): Boolean = path in readOnlyPaths

    /** The caller rebuilds the authenticated envelope on every safe replay. */
    suspend fun <T> retryRead(path: String, block: suspend () -> T): T {
        if (!isReadOnly(path)) return block()
        var retryAfter: String? = null
        return NetworkRetryPolicy.retry(
            maxRetries = MAX_RETRIES,
            canRetry = { failure ->
                val http = NetworkRetryPolicy.causes(failure).filterIsInstance<NetworkHttpException>().firstOrNull()
                retryAfter = http?.retryAfter
                if (http != null) http.statusCode in NetworkRetryPolicy.HTTP_CODES
                else NetworkRetryPolicy.isTransient(failure)
            },
            retryAfter = { retryAfter },
            block = block,
        )
    }

    /** Conch closes idle connections at 120s; expire pooled connections strictly earlier. */
    private const val POOL_KEEP_ALIVE_SECONDS = 45L

    val client by lazy {
        HttpClient.client.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionPool(ConnectionPool(5, POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
            .build()
    }

    /** Unknown execution outcomes must never trigger transparent replay. */
    val streamCalls: Call.Factory get() = client

    fun getTextResponse(url: String, headers: Map<String, String>): ConchTextResponse =
        execute(Request.Builder().url(url).get(), headers, CONTROL_LIMIT, 30_000)

    fun postTextResponse(
        url: String,
        body: String,
        headers: Map<String, String>,
        callTimeoutMillis: Long? = null,
        replaySafe: Boolean = false,
        maxRetries: Int = MAX_RETRIES,
    ): ConchTextResponse = execute(
        Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())),
        headers, RESPONSE_LIMIT, callTimeoutMillis ?: 130_000, replaySafe, maxRetries,
    )

    private fun execute(
        builder: Request.Builder,
        headers: Map<String, String>,
        limit: Long,
        timeoutMillis: Long,
        replaySafe: Boolean = builder.build().method == "GET",
        maxRetries: Int = MAX_RETRIES,
    ): ConchTextResponse {
        require(timeoutMillis > 0)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.build()
        return client.executeWithNetworkRetry(
            request, replaySafe = replaySafe, callTimeoutMillis = timeoutMillis, maxRetries = maxRetries,
        ) { response ->
            ConchTextResponse(
                code = response.code,
                body = readBody(response, limit),
                isSuccessful = response.isSuccessful,
                signature = response.header("X-Conch-Response-Signature").orEmpty(),
                retryAfter = response.header("Retry-After"),
            )
        }
    }
    private fun readBody(response: Response, limit: Long): String = try {
        response.body.source().readBoundedWireText(if (response.isSuccessful) limit else CONTROL_LIMIT)
    } catch (failure: Exception) {
        NetworkRetryPolicy.throwIfCancelled(failure)
        if (response.isSuccessful) throw failure
        throw NetworkHttpException(
            response.code, response.header("Retry-After"), "HTTP ${response.code}: response body could not be read",
        ).apply { initCause(failure) }
    }
}
