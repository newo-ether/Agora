package com.newoether.agora.tool

import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** Owns image-tool wire formats; input artifacts are already prepared by ToolImageStore. */
internal object ImageGenerationApi {
    const val MAX_OUTPUTS = 4
    const val MAX_REFERENCES = 10
    const val MAX_RESPONSE_BYTES = 120L * 1024L * 1024L
    private val openAiOptions = setOf(
        "quality", "background", "output_format", "output_compression", "input_fidelity",
        "response_format", "moderation", "seed",
    )
    private val geminiOptions = setOf("aspect_ratio", "image_size")

    fun validate(args: JsonObject, protocol: String) {
        require((args["prompt"] as? JsonPrimitive)?.isString == true && args.string("prompt").isNotBlank()) { "prompt is required" }
        require(protocol == "openai" || protocol == "google") {
            "The selected provider does not expose a supported image API. Use OpenAI or Google protocol."
        }
        val count = args["n"]?.takeUnless { it is JsonNull }?.let {
            val value = it as? JsonPrimitive
            require(value != null && !value.isString) { "n must be an integer" }
            value.intOrNull ?: throw IllegalArgumentException("n must be an integer")
        } ?: 1
        require(count in 1..MAX_OUTPUTS) { "n must be between 1 and $MAX_OUTPUTS" }
        if (protocol == "google") {
            require(count == 1) { "Gemini image generation uses n=1; returned image parts are all retained." }
            require((openAiOptions + "size").none { args[it] != null && args[it] !is JsonNull }) {
                "OpenAI output controls are not supported by the native Gemini image API."
            }
            require(args["mask"] == null || args["mask"] is JsonNull) {
                "For Gemini, include an annotated image in images and describe the edit in prompt."
            }
        } else {
            require(geminiOptions.none { args[it] != null && args[it] !is JsonNull }) {
                "aspect_ratio and image_size are native Gemini options; use size for OpenAI-compatible APIs."
            }
        }
        args["images"]?.takeUnless { it is JsonNull }?.let {
            require(it is JsonArray && it.size <= MAX_REFERENCES) {
                "images must be an array with at most $MAX_REFERENCES references"
            }
        }
        args["mask"]?.takeUnless { it is JsonNull }?.let {
            require(it is JsonObject) { "mask must be a server/path reference" }
            require((args["images"] as? JsonArray)?.isNotEmpty() == true) { "mask requires at least one reference image" }
        }
        val references = (args["images"] as? JsonArray).orEmpty() + listOfNotNull(args["mask"]?.takeUnless { it is JsonNull })
        references.forEach { reference ->
            require(reference is JsonObject && listOf("server", "path").all { key ->
                val value = reference[key] as? JsonPrimitive
                value?.isString == true && value.content.isNotBlank()
            }) { "Each image or mask reference requires nonempty server and path strings" }
        }
        (openAiOptions - setOf("output_compression", "seed") + geminiOptions + "size").forEach { key ->
            args[key]?.takeUnless { it is JsonNull }?.let {
                require(it is JsonPrimitive && it.isString && it.content.isNotBlank()) { "$key must be a nonempty string" }
            }
        }
        args["output_compression"]?.takeUnless { it is JsonNull }?.let {
            val value = it as? JsonPrimitive
            require(value != null && !value.isString && value.intOrNull in 0..100) {
                "output_compression must be an integer between 0 and 100"
            }
        }
        args["seed"]?.takeUnless { it is JsonNull }?.let {
            val value = it as? JsonPrimitive
            require(value != null && !value.isString && value.longOrNull != null) { "seed must be an integer" }
        }
    }

    fun endpoint(ctx: GenerationContext, editing: Boolean): String {
        val base = ctx.imageGenBaseUrl.trimEnd('/')
        require(base.isNotBlank()) { "The image provider has no base URL" }
        if (ctx.imageGenProtocol == "google") {
            val versioned = if (Regex("/v1(beta|alpha)?$").containsMatchIn(base)) base else "$base/v1beta"
            val model = ctx.imageGenModel.removePrefix("models/")
            require(Regex("[A-Za-z0-9._-]+").matches(model)) { "Invalid Gemini image model ID" }
            return "$versioned/models/$model:generateContent"
        }
        return "$base/images/${if (editing) "edits" else "generations"}"
    }

    fun headers(ctx: GenerationContext): Map<String, String> =
        if (ctx.imageGenApiKey.isBlank()) emptyMap()
        else if (ctx.imageGenProtocol == "google") mapOf("x-goog-api-key" to ctx.imageGenApiKey)
        else mapOf("Authorization" to "Bearer ${ctx.imageGenApiKey}")

    fun body(
        args: JsonObject,
        ctx: GenerationContext,
        images: List<Pair<File, String>>,
        mask: Pair<File, String>?,
    ): RequestBody {
        val json = if (ctx.imageGenProtocol == "google") geminiBody(args, images) else buildJsonObject {
            put("model", ctx.imageGenModel)
            put("prompt", args.string("prompt"))
            put("size", args.string("size").ifBlank { ctx.imageGenSize })
            put("n", args["n"]?.takeUnless { it is JsonNull } ?: JsonPrimitive(1))
            openAiOptions.forEach { key -> args[key]?.takeUnless { it is JsonNull }?.let { put(key, it) } }
        }
        if (ctx.imageGenProtocol == "google" || images.isEmpty()) {
            require(mask == null) { "mask requires at least one reference image" }
            return json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        }
        return MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            json.forEach { (key, value) -> addFormDataPart(key, value.jsonPrimitive.content) }
            images.forEachIndexed { index, (file, mime) ->
                addFormDataPart("image[]", "reference_$index.${extension(mime)}", file.asRequestBody(mime.toMediaType()))
            }
            mask?.let { (file, mime) ->
                require(mime == "image/png") { "mask must remain a PNG with transparency" }
                addFormDataPart("mask", "mask.png", file.asRequestBody(mime.toMediaType()))
            }
        }.build()
    }

    private fun geminiBody(args: JsonObject, images: List<Pair<File, String>>): JsonObject = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", args.string("prompt")) })
                    images.forEach { (file, mime) ->
                        add(buildJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", mime)
                                put("data", java.util.Base64.getEncoder().encodeToString(file.readBytes()))
                            }
                        })
                    }
                }
            })
        }
        putJsonObject("generationConfig") {
            putJsonArray("responseModalities") { add("TEXT"); add("IMAGE") }
            if (geminiOptions.any { args.string(it).isNotBlank() }) {
                putJsonObject("imageConfig") {
                    args.string("aspect_ratio").takeIf { it.isNotBlank() }?.let { put("aspectRatio", it) }
                    args.string("image_size").takeIf { it.isNotBlank() }?.let { put("imageSize", it) }
                }
            }
        }
    }

    /** Returns only final image payloads, never Gemini thought images or text. */
    fun imagePayloads(response: String, protocol: String): List<JsonObject> {
        val root = Json.parseToJsonElement(response).jsonObject
        val images = if (protocol == "google") {
            root["candidates"]?.jsonArray.orEmpty().flatMap { candidate ->
                candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                    .mapNotNull { part ->
                        val value = part.jsonObject
                        if (value["thought"]?.jsonPrimitive?.booleanOrNull == true) null
                        else (value["inlineData"] ?: value["inline_data"]) as? JsonObject
                    }.filter { it.string("mimeType").ifBlank { it.string("mime_type") }.startsWith("image/") }
            }
        } else root["data"]?.jsonArray.orEmpty().map { it.jsonObject }
        require(images.isNotEmpty()) { "The endpoint returned no final image data (the request may have been blocked)." }
        require(images.size <= MAX_OUTPUTS) { "The endpoint returned more than $MAX_OUTPUTS images." }
        return images
    }

    fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun extension(mime: String) = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}
