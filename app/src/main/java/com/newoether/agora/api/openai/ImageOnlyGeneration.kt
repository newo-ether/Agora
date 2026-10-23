package com.newoether.agora.api.openai

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.Constants
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * DashScope/Qwen image-generation models cannot use chat completions: compatible-mode rewrites
 * the request into the native image schema and rejects it with
 * "Input should be 'user': input.messages.0.role" regardless of the roles the client sends.
 * These helpers let the OpenAI-compatible provider collapse requests to a single user message
 * and, on DashScope-family hosts, bypass chat entirely via the synchronous
 * multimodal-generation endpoint (AIOPE's MEDIA-mode approach).
 */
internal fun isImageOnlyChatModel(modelId: String): Boolean {
    val id = modelId.lowercase()
    return id.contains("qwen-image") || id.contains("qwen_image")
}

/** Keeps only the latest user turn's text as one user message. */
internal fun collapseToSingleUserMessage(messages: List<ChatMessage>): List<ChatMessage> {
    val lastUser = messages.lastOrNull { it.participant == com.newoether.agora.model.Participant.USER }
        ?: messages.lastOrNull()
        ?: return messages
    return listOf(lastUser.copy(images = emptyList(), toolCall = null, segments = null))
}

internal fun isNativeImageHost(baseUrl: String): Boolean {
    val host = runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: baseUrl
    return host.contains("dashscope") || host.contains("aliyuncs") ||
        host.contains("qwencloudapi") || host.contains("qianwenai")
}

/** Mime type must match the actual format or the API rejects the reference image. */
private fun dataUrl(bytes: ByteArray): String {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val mime = bounds.outMimeType?.takeIf { it.startsWith("image/") } ?: "image/png"
    return "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}

/**
 * Direct-chat image generation for DashScope-family hosts. Calls the synchronous
 * multimodal-generation API (input.messages, single "user" turn) and emits the returned
 * image URL as a markdown answer chunk. Errors surface as provider API errors with the
 * HTTP status and a body excerpt.
 */
internal suspend fun FlowCollector<StreamEvent>.emitNativeImageResponse(
    json: Json,
    fallbackBaseUrl: String?,
    messages: List<ChatMessage>,
    config: ProviderConfig,
    baseUrl: String,
) {
    val nativeBase = listOf("/compatible-mode/v1", "/compatible-mode", "/api/v1", "/api")
        .fold(baseUrl) { acc, suffix -> acc.substringBefore(suffix) }
        .trimEnd('/')
    val modelId = config.modelId.substringAfterLast('/').lowercase()
    val lastUser = messages.lastOrNull { it.participant == com.newoether.agora.model.Participant.USER }
    val prompt = lastUser?.text
        ?: messages.lastOrNull()?.text
        ?: ""
    // Image-to-image: the user's latest turn may carry reference images (local files).
    val referenceImages = lastUser?.images.orEmpty().take(3).mapNotNull { path ->
        runCatching { java.io.File(path).takeIf { it.exists() && it.canRead() }?.readBytes() }.getOrNull()
    }
    val promptBody = buildJsonObject {
        put("text", JsonPrimitive(prompt))
    }
    val body = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("model", JsonPrimitive(modelId))
            put("input", buildJsonObject {
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            referenceImages.forEach { bytes ->
                                add(buildJsonObject {
                                    put("image", JsonPrimitive(dataUrl(bytes)))
                                })
                            }
                            add(promptBody)
                        })
                    })
                })
            })
            put("parameters", buildJsonObject {
                put("prompt_extend", JsonPrimitive(true))
                put("watermark", JsonPrimitive(false))
            })
        },
    )
    val headers = mapOf(
        "Content-Type" to "application/json",
        "Authorization" to "Bearer ${config.apiKey}",
    )
    val response = HttpClient.postTextResponse(
        "$nativeBase/api/v1/services/aigc/multimodal-generation/generation",
        body,
        headers,
        callTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
        readTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
    )
    if (!response.isSuccessful) {
        emit(
            StreamEvent.Error(
                GenerationError.Api(
                    code = response.code.toString(),
                    type = "http_error",
                    message = "Image generation failed: HTTP ${response.code}: " +
                        response.body.take(300),
                )
            )
        )
        return
    }
    val content = runCatching {
        val parsed = json.parseToJsonElement(response.body)
        parsed.jsonObject["output"]?.jsonObject?.get("choices")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")
    }.getOrNull()
    val contentText = when (content) {
        is JsonPrimitive -> content.content
        else -> content?.toString()
    }
    val imageUrl = contentText?.let {
        Regex("""https?://[^\s)\]"}']+""").find(it)?.value
    }
    if (imageUrl.isNullOrBlank()) {
        emit(
            StreamEvent.Error(
                GenerationError.Api(
                    code = null,
                    type = "no_image",
                    message = "Image generation returned no image URL: " +
                        response.body.take(300),
                )
            )
        )
        return
    }
    // Download and deliver raw bytes: markdown image links are never rendered for remote
    // URLs (the app only displays persisted, authenticated files), so the image must travel
    // through the ImageChunk channel to be stored and shown.
    val imageBytes = HttpClient.getBytes(
        imageUrl,
        callTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
        readTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
    )
    if (imageBytes == null) {
        emit(
            StreamEvent.Error(
                GenerationError.Api(
                    code = null,
                    type = "download_failed",
                    message = "Generated image download failed: $imageUrl",
                )
            )
        )
        return
    }
    emit(StreamEvent.ImageChunk(bytes = imageBytes, mimeType = "image/png"))
}
