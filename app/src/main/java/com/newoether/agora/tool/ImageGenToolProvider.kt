package com.newoether.agora.tool

import android.app.Application
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool that generates images through an OpenAI-compatible `/images/generations` endpoint.
 * Successful bytes are persisted by [ToolImageStore] and returned on the owning tool result.
 */
class ImageGenToolProvider(
    private val app: Application,
    /** Newest conversation image, used as the img2img reference when the model passes none. */
    private val latestImagePath: suspend (conversationId: String?) -> String? = { null },
) : ToolProvider {

    private val imageStore = ToolImageStore(app)

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.imageGenEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "generate_image",
                description = "Generate an image from a text prompt. The generated image is shown to the user automatically — do NOT attempt to embed or describe the raw image data. Use this whenever the user asks to create, draw, paint, or generate a picture.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "prompt" to ToolProperty("string", "A detailed description of the image to generate. For image-to-image edits, describe the change to make to the reference images."),
                        "size" to ToolProperty("string", "Optional image size, e.g. 1024x1024, 1024x1536, or 1536x1024."),
                        "image_paths" to ToolProperty(
                            "array",
                            "Optional file paths of reference images (up to 3) for image-to-image generation, e.g. paths returned by earlier generate_image calls.",
                            items = ToolProperty("string", "A reference image file path."),
                        ),
                    ),
                    required = listOf("prompt")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name == "generate_image"

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = executeResult(name, arguments, ctx).text

    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        emit(ToolExecutionEvent.Completed(executeResult(name, arguments, ctx)))
    }

    private suspend fun executeResult(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val textAndImage = executeOffMain(name, arguments, ctx)
        ToolExecutionResult(
            text = textAndImage.first,
            images = listOfNotNull(textAndImage.second),
            isError = textAndImage.second == null,
        )
    }

    private suspend fun executeOffMain(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Pair<String, com.newoether.agora.model.ToolImageAttachment?> {
        ctx.conversationId?.takeIf { it.isNotBlank() }
            ?: return err("missing_conversation", "Image generation requires a conversation.") to null
        val argsStr = arguments.ifBlank { "{}" }
        val args = try {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        } catch (_: Exception) { emptyMap() }
        val prompt = (args["prompt"] as? JsonPrimitive)?.content
            ?: return err("no_prompt", null) to null
        val size = (args["size"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: ctx.imageGenSize.ifBlank { "1024x1024" }
        // Optional reference images for image-to-image: file paths (plain or file://), comma-
        // separated string or JSON array, mirroring AIOPE's image_generate contract.
        val refPaths: List<String> = when (val v = args["image_paths"]) {
            is kotlinx.serialization.json.JsonArray ->
                v.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
            is JsonPrimitive -> v.content.split(",")
                .map(String::trim).filter(String::isNotEmpty)
            else -> emptyList()
        }
        val explicitRefs = refPaths.take(3).mapNotNull { raw ->
            runCatching {
                java.io.File(raw.removePrefix("file://"))
                    .takeIf { it.exists() && it.canRead() }?.readBytes()
            }.getOrNull()
        }
        // The model cannot know attachment paths, so an edit request without explicit
        // image_paths falls back to the newest image in this conversation (an attached photo,
        // or a previous generation, enabling chained edits).
        val referenceImages = explicitRefs.ifEmpty {
            latestImagePath(ctx.conversationId)?.let { path ->
                runCatching {
                    java.io.File(path).takeIf { it.exists() && it.canRead() }?.readBytes()
                }.getOrNull()
            }?.let { listOf(it) } ?: emptyList()
        }

        val apiKey = ctx.imageGenApiKey
        if (apiKey.isBlank()) return err("no_api_key", null) to null
        val baseUrl = ctx.imageGenBaseUrl.ifBlank { ProviderDefaults.OPENAI_BASE_URL }.trimEnd('/')
        val model = ctx.imageGenModel.ifBlank { "gpt-image-1" }

        return withContext(Dispatchers.IO) {
            try {
                val host = java.net.URI(baseUrl).host ?: baseUrl
                // DashScope-shaped native APIs (DashScope intl/mainland and QwenCloud) serve
                // Qwen image models only through the synchronous multimodal-generation endpoint:
                // input.messages with a single "user" message. Routing is by HOST, never by model
                // prefix, because OpenAI-compatible gateways that proxy these models still expect
                // the standard /images/generations shape.
                val isNativeDashScopeHost =
                    host.contains("dashscope") || host.contains("aliyuncs") ||
                        host.contains("qwencloudapi") || host.contains("qianwenai")
                val isQwenImageModel = QWEN_IMAGE_MODEL_MARKERS.any { model.lowercase().contains(it) }
                val responseText: String
                val imageBytes: ByteArray
                if (isNativeDashScopeHost) {
                    val nativeBase = DASHSCOPE_COMPATIBLE_PATHS.fold(baseUrl) { acc, suffix ->
                        acc.substringBefore(suffix)
                    }.trimEnd('/')
                    val body = buildJsonObject {
                        put("model", normalizeQwenImageModelId(model))
                        put(
                            "input",
                            buildJsonObject {
                                put(
                                    "messages",
                                    buildJsonArray {
                                        add(buildJsonObject {
                                            put("role", "user")
                                            put(
                                                "content",
                                                buildJsonArray {
                                                    // Image-to-image: up to 3 reference image
                                                    // data URLs precede the text part.
                                                    referenceImages.forEach { bytes ->
                                                        add(buildJsonObject {
                                                            put("image", dataUrl(bytes))
                                                        })
                                                    }
                                                    add(buildJsonObject { put("text", prompt) })
                                                },
                                            )
                                        })
                                    },
                                )
                            },
                        )
                        put(
                            "parameters",
                            buildJsonObject {
                                put("size", normalizeDashScopeSize(size))
                                put("prompt_extend", true)
                                put("watermark", false)
                            },
                        )
                    }.toString()
                    val apiResponse = HttpClient.postTextResponse(
                        "$nativeBase/api/v1/services/aigc/multimodal-generation/generation",
                        body,
                        mapOf("Authorization" to "Bearer $apiKey"),
                        callTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                        readTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                    )
                    if (!apiResponse.isSuccessful) {
                        return@withContext err(
                            "no_response",
                            "DashScope HTTP ${apiResponse.code}: " +
                                apiResponse.body.take(400),
                        ) to null
                    }
                    responseText = apiResponse.body
                    val content = extractDashScopeImageContent(responseText)
                        ?: return@withContext err(
                            "no_image",
                            "No image URL in the DashScope response.",
                        ) to null
                    imageBytes = downloadImage(content)
                        ?: return@withContext err("download_failed", content.take(200)) to null
                } else if (isQwenImageModel) {
                    // Other OpenAI-compatible relays expose these models via chat completions;
                    // every message role must be "user", so send exactly one user message.
                    val body = buildJsonObject {
                        put("model", model)
                        put(
                            "messages",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("role", "user")
                                    put("content", prompt)
                                })
                            },
                        )
                        put("stream", false)
                    }.toString()
                    val apiResponse = HttpClient.postTextResponse(
                        "$baseUrl/chat/completions",
                        body,
                        mapOf("Authorization" to "Bearer $apiKey"),
                        callTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                        readTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                    )
                    if (!apiResponse.isSuccessful) {
                        return@withContext err(
                            "no_response",
                            "HTTP ${apiResponse.code}: " + apiResponse.body.take(400),
                        ) to null
                    }
                    val content = extractChatCompletionContent(apiResponse.body)
                        ?: return@withContext err(
                            "no_image",
                            "The chat completion contained no message content.",
                        ) to null
                    imageBytes = downloadImage(content)
                        ?: return@withContext err(
                            "no_image",
                            "No image URL in the chat completion response.",
                        ) to null
                } else {
                    val body = buildJsonObject {
                        put("model", model)
                        put("prompt", prompt)
                        put("size", size)
                        put("n", 1)
                    }.toString()
                    val apiResponse = HttpClient.postTextResponse(
                        "$baseUrl/images/generations",
                        body,
                        mapOf("Authorization" to "Bearer $apiKey"),
                        callTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                        readTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                    )
                    if (!apiResponse.isSuccessful) {
                        return@withContext err(
                            "no_response",
                            "HTTP ${apiResponse.code}: " + apiResponse.body.take(400),
                        ) to null
                    }
                    imageBytes = extractImagesGenerationsBytes(apiResponse.body)
                        ?: return@withContext err(
                            "no_image",
                            "No b64_json or url in the response.",
                        ) to null
                }
                val attachment = imageStore.persistGeneratedBytes(
                    bytes = imageBytes,
                    filePrefix = "generated_image",
                )
                buildJsonObject {
                    put("type", "image_generation")
                    put("status", "ok")
                    put("size", size)
                    // Let the model chain image-to-image edits from this result.
                    put("saved_path", attachment.path)
                }.toString() to attachment
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("ImageGenTool", "generate_image failed", e)
                err("generation_error", e.message) to null
            }
        }
    }

    /** DashScope model ids are bare and lowercase: "Qwen/Qwen-Image" -> "qwen-image". */
    private fun normalizeQwenImageModelId(model: String): String =
        model.substringAfterLast('/').lowercase()

    /** Mime type must match the actual format or the API rejects the reference image. */
    private fun dataUrl(bytes: ByteArray): String {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val mime = bounds.outMimeType?.takeIf { it.startsWith("image/") } ?: "image/png"
        return "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /** DashScope expects width*height with an asterisk: "1024x1024" -> "1024*1024". */
    private fun normalizeDashScopeSize(size: String): String {
        val trimmed = size.trim()
        val shape = Regex("""(\d+)\s*[xX*]\s*(\d+)""").find(trimmed) ?: return trimmed
        val (w, h) = shape.destructured
        return "${w}*${h}"
    }

    private suspend fun downloadImage(content: String): ByteArray? {
        val url = IMAGE_URL_REGEX.find(content)?.value ?: return null
        return HttpClient.getBytes(
            url,
            callTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
            readTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
        )
    }

    /**
     * DashScope native multimodal-generation reply: output.choices[0].message.content is an
     * array whose items carry either an "image" URL or descriptive text. Returns the raw
     * content element string so the URL regex can find the image link either way.
     */
    private fun extractDashScopeImageContent(response: String): String? = try {
        val json = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(response)
        val content = json["output"]?.jsonObject?.get("choices")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
        when (content) {
            is JsonPrimitive -> content.content
            null -> null
            else -> content.toString()
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun extractImagesGenerationsBytes(response: String): ByteArray? {
        val json = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(response)
        val first = json["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val b64 = (first["b64_json"] as? JsonPrimitive)?.content
        if (!b64.isNullOrBlank()) {
            return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        }
        val url = (first["url"] as? JsonPrimitive)?.content ?: return null
        return HttpClient.getBytes(
            url,
            callTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
            readTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
        )
    }

    /**
     * Qwen image endpoints return the picture as a markdown/plain URL inside the reply
     * content. Content may be a plain string or a multipart array; both forms still carry
     * the URL, so fall back to the raw element string for URL extraction.
     */
    private fun extractChatCompletionContent(response: String): String? = try {
        val json = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(response)
        val content = json["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
        when (content) {
            is JsonPrimitive -> content.content
            null -> null
            else -> content.toString()
        }
    } catch (_: Exception) {
        null
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "image_generation")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()

    private companion object {
        val QWEN_IMAGE_MODEL_MARKERS = listOf("qwen-image", "qwen_image")
        val IMAGE_URL_REGEX = Regex("""https?://[^\s)\]}"']+""")
        val DASHSCOPE_COMPATIBLE_PATHS = listOf("/compatible-mode/v1", "/compatible-mode", "/v1")
    }
}
