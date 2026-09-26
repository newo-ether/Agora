package com.newoether.agora.util

import com.newoether.agora.api.HttpClient
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class SubmissionMessage(
    val id: String,
    val title: String,
    val body: String,
    val buttonText: String? = null,
)

data class SubmissionResponse(
    val accepted: Boolean,
    val message: SubmissionMessage? = null,
)

internal fun parseSubmissionMessage(body: String): SubmissionMessage? = runCatching {
    val message = (Json.parseToJsonElement(body) as? JsonObject)
        ?.get("message") as? JsonObject ?: return null
    fun text(key: String, limit: Int): String? {
        val value = message[key] as? JsonPrimitive ?: return null
        return value.takeIf { it.isString }?.content?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= limit }
    }
    SubmissionMessage(
        id = text("id", 128) ?: return null,
        title = text("title", 200) ?: return null,
        body = text("body", 8000) ?: return null,
        buttonText = text("buttonText", 80),
    )
}.getOrNull()

internal fun submitFeedback(endpoint: String, json: String): SubmissionResponse {
    val request = Request.Builder().url(endpoint)
        .post(json.toRequestBody("application/json".toMediaType())).build()
    return try {
        HttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return SubmissionResponse(false)
            // Optional response content cannot invalidate an accepted submission.
            val message = try {
                val source = response.body.source()
                if (source.request(65_537)) null
                else parseSubmissionMessage(source.readUtf8())
            } catch (_: IOException) {
                null
            }
            SubmissionResponse(true, message)
        }
    } catch (_: IOException) {
        SubmissionResponse(false)
    }
}
