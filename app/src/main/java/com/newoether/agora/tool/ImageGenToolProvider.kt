package com.newoether.agora.tool

import android.app.Application
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.tool.ImageGenerationApi.string
import com.newoether.agora.util.AttachmentFiles
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.util.UUID

/** Owns image-tool execution and attachment publication; protocol formats live in ImageGenerationApi. */
class ImageGenToolProvider(
    private val app: Application,
    private val sandboxFactory: SandboxManagerFactory? = null,
) : ToolProvider {
    private val imageStore = ToolImageStore(app)

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.imageGenEnabled) return emptyList()
        val reference = ToolProperty(
            "object", "An existing reference image. Bytes are read from the named server without entering tool arguments.",
            properties = mapOf(
                "server" to ToolProperty("string", "Exact server name: Local Sandbox or an enabled Conch device."),
                "path" to ToolProperty("string", "Absolute image path on that server."),
            ),
            required = listOf("server", "path"),
        )
        val options = if (ctx.imageGenProtocol == "google") mapOf(
            "aspect_ratio" to ToolProperty("string", "Gemini aspect ratio, e.g. 1:1, 4:3, 3:2, 16:9 or 9:16; supported values depend on the model."),
            "image_size" to ToolProperty("string", "Gemini output resolution, e.g. 1K, 2K or 4K, when supported by the selected model."),
        ) else mapOf(
            "size" to ToolProperty("string", "Optional output dimensions, e.g. 1024x1024, 1536x1024 or auto; accepted sizes depend on the endpoint."),
            "n" to ToolProperty("integer", "Number of images, 1 to 4. Omit for one image; the selected model may support fewer."),
            "quality" to ToolProperty("string", "Endpoint-supported quality, e.g. auto, low, medium, high; legacy models may use standard or hd."),
            "background" to ToolProperty("string", "Background: auto, opaque or transparent, when supported."),
            "output_format" to ToolProperty("string", "Generated format: png, jpeg or webp, when supported."),
            "output_compression" to ToolProperty("integer", "JPEG/WebP output compression quality from 0 to 100, when supported."),
            "response_format" to ToolProperty("string", "Legacy/local endpoint response: b64_json or url. Omit for GPT Image models."),
            "input_fidelity" to ToolProperty("string", "Edit input fidelity, low or high, only when supported by the model."),
            "moderation" to ToolProperty("string", "Endpoint-supported moderation level, e.g. auto or low. Omit unless needed."),
            "seed" to ToolProperty("integer", "Optional reproducible seed for compatible local endpoints; omit unless the endpoint supports it."),
            "mask" to reference.copy(description = "Optional PNG edit mask. Transparent pixels mark the edit area; dimensions must match the first reference."),
        )
        return listOf(ToolDefinition(function = ToolFunction(
            name = "image_generate",
            description = "Generate or edit images. Generated images are displayed automatically. Supply images as server/path references for edits or visual guidance. Use this when the user asks to create, draw, paint or modify an image. The selected API protocol is ${ctx.imageGenProtocol}; only use controls supported by its model.",
            parameters = ToolParameters(
                properties = mapOf(
                    "prompt" to ToolProperty("string", "A detailed generation prompt or edit instruction."),
                    "images" to ToolProperty("array", "Optional ordered reference images, at most 10; the selected model may support fewer.", items = reference),
                ) + options,
                required = listOf("prompt"),
            ),
        )))
    }

    override fun handles(name: String): Boolean = BuiltInToolNames.canonical(name) == "image_generate"

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        deliverResult(arguments, ctx) { it.text }

    override fun executeEvents(name: String, arguments: String, ctx: GenerationContext): Flow<ToolExecutionEvent> = flow {
        deliverResult(arguments, ctx) { emit(ToolExecutionEvent.Completed(it)) }
    }

    private suspend fun <T> deliverResult(
        arguments: String,
        ctx: GenerationContext,
        accept: suspend (ToolExecutionResult) -> T,
    ): T {
        val batch = GeneratedOutputBatch()
        var delivered = false
        try {
            val result = executeResult(arguments, ctx, batch)
            val accepted = accept(result)
            delivered = !result.isError
            return accepted
        } finally {
            if (!delivered) withContext(NonCancellable + Dispatchers.IO) {
                batch.images.forEach { File(it.path).delete() }
                batch.originals.forEach(File::delete)
                batch.directory?.delete()
            }
        }
    }

    private suspend fun executeResult(
        arguments: String,
        ctx: GenerationContext,
        batch: GeneratedOutputBatch,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val temporaryInputs = mutableListOf<ToolImageAttachment>()
        val outputs = batch.images
        try {
            require(ctx.imageGenEnabled) { "Image generation is disabled" }
            require(!ctx.conversationId.isNullOrBlank()) { "Image generation requires a conversation" }
            ImageGenerationApi.endpoint(ctx, false)
            val args = Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
            ImageGenerationApi.validate(args, ctx.imageGenProtocol)
            require(ctx.imageGenModel.isNotBlank()) { "No image model is selected" }
            if (ctx.sandboxEnabled) {
                val sandbox = requireSandbox(ctx)
                val directory = sandbox.resolveHostFile("/home/agora/attachments/generated_${UUID.randomUUID()}")
                    ?: throw IOException("Cannot resolve sandbox output directory")
                require(!directory.exists()) { "Generated output directory already exists" }
                if (!directory.mkdirs()) throw IOException("Cannot create sandbox output directory")
                batch.directory = directory
            }
            val references = (args["images"] as? JsonArray).orEmpty().map { reference ->
                loadReference(reference.jsonObject, ctx).also { temporaryInputs += it }
            }
            val mask = (args["mask"] as? JsonObject)?.let {
                loadReference(it, ctx).also { input -> temporaryInputs += input }
            }
            if (mask != null) {
                require(references.isNotEmpty()) { "mask requires a reference image" }
                require(mask.mimeType == "image/png" && mask.width == references.first().width && mask.height == references.first().height) {
                    "PNG mask dimensions must match the first prepared reference image"
                }
            }
            val response = HttpClient.postBodyResponse(
                url = ImageGenerationApi.endpoint(ctx, references.isNotEmpty()),
                body = ImageGenerationApi.body(args, ctx, references.map { File(it.path) to it.mimeType }, mask?.let { File(it.path) to it.mimeType }),
                headers = ImageGenerationApi.headers(ctx),
                callTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                readTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                replaySafe = false,
                maxResponseBytes = ImageGenerationApi.MAX_RESPONSE_BYTES,
            )
            if (!response.isSuccessful) throw IOException("Image API HTTP ${response.code}: ${response.body.take(2000)}")
            for (payload in ImageGenerationApi.imagePayloads(response.body, ctx.imageGenProtocol)) {
                currentCoroutineContext().ensureActive()
                val encoded = payload.string("b64_json").ifBlank { payload.string("data") }
                val image = if (encoded.isNotBlank()) {
                    imageStore.persistBase64(encoded, "image/unknown", "generated_image")
                } else {
                    val url = payload.string("url")
                    require(url.startsWith("https://") || url.startsWith("http://")) { "Image response has no usable image bytes or URL" }
                    val bytes = HttpClient.getBytes(
                        url,
                        callTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                        readTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                        maxBytes = ToolImageStore.MAX_IMAGE_BYTES,
                    ) ?: throw IOException("Generated image download failed")
                    imageStore.persistGeneratedBytes(bytes, "generated_image")
                }
                outputs += image
                batch.directory?.let { directory ->
                    val original = File(directory, File(image.path).name)
                    batch.originals += original
                    val coroutineContext = currentCoroutineContext()
                    when (val copy = AttachmentFiles.copyBounded(
                        File(image.path).inputStream(), original, expectedSize = image.sizeBytes,
                        maxBytes = ToolImageStore.MAX_IMAGE_BYTES,
                        onProgress = { _, _ -> coroutineContext.ensureActive() },
                    )) {
                        is AttachmentFiles.CopyResult.Success -> Unit
                        AttachmentFiles.CopyResult.TooLarge -> throw IOException("Generated image exceeds its byte bound")
                        is AttachmentFiles.CopyResult.Failure -> throw IOException("Cannot publish sandbox image", copy.cause)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val result = ToolExecutionResult(
                text = buildJsonObject {
                    put("type", "image_generation")
                    put("status", "ok")
                    put("count", outputs.size)
                    put("protocol", ctx.imageGenProtocol)
                    put("model", ctx.imageGenModel)
                    put("operation", if (references.isEmpty()) "generate" else "edit")
                    batch.directory?.let { directory ->
                        putJsonArray("images") {
                            outputs.forEach { image ->
                                add(buildJsonObject {
                                    put("server", "Local Sandbox")
                                    put("path", "/home/agora/attachments/${directory.name}/${File(image.path).name}")
                                    put("mime_type", image.mimeType)
                                    put("size_bytes", image.sizeBytes)
                                    image.width?.let { put("width", it) }
                                    image.height?.let { put("height", it) }
                                })
                            }
                        }
                    }
                }.toString(),
                images = outputs.toList(),
            )
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugLog.e("ImageGenTool", "image_generate failed", error)
            ToolExecutionResult(
                text = buildJsonObject {
                    put("type", "image_generation")
                    put("error", "generation_error")
                    put("message", error.message.orEmpty().take(2000))
                }.toString(),
                isError = true,
            )
        } finally {
            temporaryInputs.forEach { File(it.path).delete() }
        }
    }

    private class GeneratedOutputBatch {
        val images = mutableListOf<ToolImageAttachment>()
        val originals = mutableListOf<File>()
        var directory: File? = null
    }

    private suspend fun requireSandbox(ctx: GenerationContext): SandboxManager {
        require(ctx.sandboxEnabled && sandboxFactory?.isAvailable() == true) { "Local Sandbox is not enabled" }
        return requireNotNull(sandboxFactory).create().also {
            require(it.isAvailable()) { "Local Sandbox is not installed" }
        }
    }

    private suspend fun loadReference(reference: JsonObject, ctx: GenerationContext): ToolImageAttachment {
        val server = reference.string("server")
        val path = reference.string("path")
        require(server.isNotBlank() && path.isNotBlank()) { "Each reference requires server and path" }
        if (server.equals("Local Sandbox", ignoreCase = true)) {
            val sandbox = requireSandbox(ctx)
            val file = sandbox.resolveHostFile(path) ?: throw IOException("Cannot resolve sandbox image path")
            require(file.isFile) { "Reference is not a regular file" }
            require(file.length() in 1..ToolImageStore.MAX_IMAGE_BYTES) { "Reference image exceeds its byte bound" }
            return file.inputStream().use { imageStore.persistStream(it, "image/unknown", "image_reference") }
        }
        require(ctx.shellEnabled) { "Shell access is disabled for image references" }
        val backend = openShellBackend(server, ctx, sandbox = null, standardOnly = true)
            ?: throw IOException(shellServerNotFoundMessage(server, ctx, standardOnly = true))
        return try {
            backend.loadImage(path, imageStore)
        } finally {
            backend.close()
        }
    }
}
