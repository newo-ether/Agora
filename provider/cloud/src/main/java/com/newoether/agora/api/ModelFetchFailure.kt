package com.newoether.agora.api

import com.newoether.agora.util.CloudDebugLog as DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl

class ModelFetchHttpException(
    val statusCode: Int,
    val detail: String?,
) : IOException(
    buildString {
        append("HTTP ").append(statusCode)
        detail?.takeIf(String::isNotBlank)?.let { append(" — ").append(it) }
    }
)

class ModelFetchEmptyResultException : IOException("No models returned")

class ModelFetchTimeoutException : IOException("Request timed out")

class ModelFetchInvalidResponseException(
    cause: Throwable,
) : IOException(cause.message, cause)

private val modelFetchErrorJson = Json { ignoreUnknownKeys = true }
private val modelFetchWhitespace = Regex("\\s+")

fun normalizeModelFetchDetail(
    value: String,
    maxLength: Int = 240,
): String {
    require(maxLength >= 2)
    val normalized = modelFetchWhitespace.replace(
        value.filterNot { char -> char.isISOControl() && !char.isWhitespace() },
        " ",
    ).trim()
    return if (normalized.length <= maxLength) {
        normalized
    } else {
        normalized.take(maxLength - 1).trimEnd() + "…"
    }
}

internal fun extractModelFetchErrorDetail(body: String): String? {
    val normalizedBody = normalizeModelFetchDetail(body)
    if (normalizedBody.isBlank()) return null
    val parsed = runCatching { modelFetchErrorJson.parseToJsonElement(body) }.getOrNull()
    val structured = parsed?.modelFetchErrorDetail()
    return normalizeModelFetchDetail(structured ?: normalizedBody).takeIf(String::isNotBlank)
}

internal fun HttpClient.TextResponse.requireModelFetchBody(): String {
    if (!isSuccessful) {
        throw ModelFetchHttpException(
            statusCode = code,
            detail = extractModelFetchErrorDetail(body),
        )
    }
    if (body.isBlank()) {
        throw ModelFetchInvalidResponseException(IOException("Empty response body"))
    }
    return body
}

internal inline fun <T> decodeModelFetchResponse(block: () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: ModelFetchHttpException) {
    throw error
} catch (error: Exception) {
    throw ModelFetchInvalidResponseException(error)
}

private fun JsonElement.modelFetchErrorDetail(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> {
        sequenceOf("message", "detail", "error_description")
            .mapNotNull { key -> this[key]?.modelFetchErrorDetail() }
            .firstOrNull(String::isNotBlank)
            ?: this["error"]?.modelFetchErrorDetail()
    }
    else -> null
}

internal fun openAiEndpointCandidates(
    baseUrl: String,
    path: String,
    retryMissingV1BaseUrl: Boolean,
): List<String> {
    val normalizedBaseUrl = baseUrl.trimEnd('/')
    val cleanPath = path.trimStart('/')
    val primary = "$normalizedBaseUrl/$cleanPath"
    if (!retryMissingV1BaseUrl || normalizedBaseUrl.isBlank() ||
        BaseUrlResolver.hasVersionSegment(normalizedBaseUrl)
    ) {
        return listOf(primary)
    }
    return listOf(primary, "$normalizedBaseUrl/v1/$cleanPath")
}

internal fun openAiAuthHeaders(apiKey: String): Map<String, String> =
    if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

internal fun nextOpenAiModelPageUrl(endpointUrl: String, cursor: String): String =
    endpointUrl.toHttpUrl()
        .newBuilder()
        .addQueryParameter("after", cursor)
        .build()
        .toString()
