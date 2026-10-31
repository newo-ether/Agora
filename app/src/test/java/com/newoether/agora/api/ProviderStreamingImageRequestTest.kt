package com.newoether.agora.api

import android.app.Application
import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.ollama.OllamaProvider
import com.newoether.agora.api.openai.OpenAiProvider
import com.newoether.agora.api.util.ImageWireTestRunner
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.api.util.projectAssistantImagesToLatestUserMessage
import com.newoether.agora.api.util.projectToolResultImagesToUserMessage
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import com.sun.net.httpserver.HttpServer
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

@RunWith(ImageWireTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderStreamingImageRequestTest {
    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        mockkObject(DebugLog)
        every { DebugLog.d(any(), any()) } just Runs
        every { DebugLog.e(any(), any()) } just Runs
        every { DebugLog.w(any(), any()) } just Runs
    }

    @After
    fun restoreAndroidLogging() {
        unmockkObject(DebugLog)
    }

    @Test
    fun openAiChatStreamsDataUriImage() = withImage { image, expected ->
        withServer(openAiChatResponse()) { server ->
            collect(
                OpenAiProvider(),
                config(server, model = "gpt-4o"),
                image,
            )
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            val imageUrl = request.json["messages"]!!.jsonArray
                .flatMap { it.jsonObject["content"]!!.jsonArray }
                .map(JsonElement::jsonObject)
                .first { it["type"]!!.jsonPrimitive.content == "image_url" }
                .getValue("image_url").jsonObject
            assertEquals("data:image/png;base64,$expected", imageUrl.getValue("url").jsonPrimitive.content)
            assertEquals("auto", imageUrl.getValue("detail").jsonPrimitive.content)
            request.assertExactLength()
        }
    }

    @Test
    fun openAiResponsesStreamsInputImage() = withImage { image, expected ->
        withServer(openAiResponsesResponse()) { server ->
            collect(
                OpenAiProvider(),
                config(server, model = "gpt-4o").copy(responsesApiEnabled = true),
                image,
            )
            val request = server.takeRequest()
            assertEquals("/v1/responses", request.path)
            val imagePart = request.json["input"]!!.jsonArray
                .flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
                .map(JsonElement::jsonObject)
                .first { it["type"]!!.jsonPrimitive.content == "input_image" }
            assertEquals("data:image/png;base64,$expected", imagePart.getValue("image_url").jsonPrimitive.content)
            assertEquals("auto", imagePart.getValue("detail").jsonPrimitive.content)
            request.assertExactLength()
        }
    }

    @Test
    fun anthropicStreamsImageSourceData() = withImage { image, expected ->
        withServer(anthropicResponse()) { server ->
            collect(
                AnthropicProvider(),
                config(server, model = "claude-3-5-sonnet-20240620"),
                image,
            )
            val request = server.takeRequest()
            val source = request.json["messages"]!!.jsonArray
                .flatMap { it.jsonObject["content"]!!.jsonArray }
                .map(JsonElement::jsonObject)
                .first { it["type"]!!.jsonPrimitive.content == "image" }
                .getValue("source").jsonObject
            assertEquals("image/png", source.getValue("media_type").jsonPrimitive.content)
            assertEquals(expected, source.getValue("data").jsonPrimitive.content)
            request.assertExactLength()
        }
    }

    @Test
    fun geminiStreamsInlineData() = withImage { image, expected ->
        withServer(geminiResponse()) { server ->
            collect(
                GeminiProvider(),
                config(server, model = "gemini-2.5-flash"),
                image,
            )
            val request = server.takeRequest()
            val inlineData = request.json["contents"]!!.jsonArray
                .flatMap { it.jsonObject["parts"]!!.jsonArray }
                .map(JsonElement::jsonObject)
                .first { it.containsKey("inlineData") }
                .getValue("inlineData").jsonObject
            assertEquals("image/png", inlineData.getValue("mimeType").jsonPrimitive.content)
            assertEquals(expected, inlineData.getValue("data").jsonPrimitive.content)
            request.assertExactLength()
        }
    }

    @Test
    fun ollamaStreamsImageArrayEntry() = withImage { image, expected ->
        withServer(ollamaResponse(), contentType = "application/x-ndjson") { server ->
            collect(
                OllamaProvider(),
                config(server, model = "qwen3:8b"),
                image,
            )
            val request = server.takeRequest()
            val images = request.json["messages"]!!.jsonArray
                .map(JsonElement::jsonObject)
                .first { it["role"]!!.jsonPrimitive.content == "user" }
                .getValue("images").jsonArray
            assertEquals(expected, images.single().jsonPrimitive.content)
            request.assertExactLength()
        }
    }

    @Test
    fun anthropicAdjacentUserBatchReachesWireAsOrderedLosslessBlocks() = withImage { image, expected ->
        withServer(anthropicResponse()) { server ->
            val messages = adjacentUserBatch(image)
            val prepared = prepareMessages(messages, contextTokenBudget = 16_384)
            assertEquals(messages, prepared)
            collect(AnthropicProvider(), config(server, model = "claude-3-5-sonnet-20240620"), prepared)

            val request = server.takeRequest()
            val wireMessages = request.json.getValue("messages").jsonArray
            assertEquals(1, wireMessages.size)
            val blocks = wireMessages.single().jsonObject.getValue("content").jsonArray
                .map(JsonElement::jsonObject)
            assertEquals(listOf("text", "image", "text", "text"), blocks.map {
                it.getValue("type").jsonPrimitive.content
            })
            assertEquals(messages.map { it.text }, blocks.mapNotNull { it["text"]?.jsonPrimitive?.content })
            assertEquals(expected, blocks[1].getValue("source").jsonObject.getValue("data").jsonPrimitive.content)
            assertEquals(messages, prepared)
            request.assertExactLength()
        }
    }

    @Test
    fun geminiAdjacentUserBatchReachesWireAsOrderedLosslessParts() = withImage { image, expected ->
        withServer(geminiResponse()) { server ->
            val messages = adjacentUserBatch(image)
            val prepared = prepareMessages(messages, contextTokenBudget = 16_384)
            assertEquals(messages, prepared)
            collect(GeminiProvider(), config(server, model = "gemini-2.5-flash"), prepared)

            val request = server.takeRequest()
            val wireContents = request.json.getValue("contents").jsonArray
            assertEquals(1, wireContents.size)
            val parts = wireContents.single().jsonObject.getValue("parts").jsonArray
                .map(JsonElement::jsonObject)
            assertEquals(listOf("text", "text", "inlineData", "text"), parts.map { it.keys.single() })
            assertEquals(messages.map { it.text }, parts.mapNotNull { it["text"]?.jsonPrimitive?.content })
            assertEquals(expected, parts[2].getValue("inlineData").jsonObject.getValue("data").jsonPrimitive.content)
            assertEquals(messages, prepared)
            request.assertExactLength()
        }
    }

    @Test
    fun pngAndJpegUseMeasuredMimeAndOriginalBytesForEveryReferenceAndRemoteWire() {
        for ((format, suffix) in listOf("png" to ".jpg", "jpeg" to ".png")) {
            withImage(format, suffix) { image, _ ->
                val artifact = measuredArtifact(image)
                val original = image.readBytes()
                val references = listOf(
                    image.absolutePath,
                    image.toURI().toString(),
                    "content://agora-test/imported/$format",
                )
                for (wire in RemoteWire.entries) withServer(wire.response(), wire.contentType) { server ->
                    for (reference in references) {
                        collect(wire.provider(), wire.config(server), listOf(imageMessage(reference, artifact)))
                        assertCapturedImage(wire, server.takeRequest(), artifact, original)
                        assertArrayEquals(original, image.readBytes())
                    }
                }
            }
        }
    }

    @Test
    fun attachmentPagesFramesToolResultsAndLegacyAssistantImagesReachEveryRemoteWire() {
        for ((format, suffix) in listOf("png" to ".jpg", "jpeg" to ".png")) {
            withImage(format, suffix) { image, _ ->
                val artifact = measuredArtifact(image)
                val original = image.readBytes()
                for (wire in RemoteWire.entries) withServer(wire.response(), wire.contentType) { server ->
                    for (source in ImageSource.entries) {
                        val messages = sourceMessages(source, artifact)
                        collect(wire.provider(), wire.config(server), messages)
                        assertCapturedImage(wire, server.takeRequest(), artifact, original)
                        assertArrayEquals(original, image.readBytes())
                    }
                }
            }
        }
    }

    @Test
    fun missingUnavailableEmptyAndCorruptImagesAreOmittedBeforeEveryRemoteRequest() = withImage { image, _ ->
        val valid = measuredArtifact(image)
        val corrupt = File.createTempFile("agora-wire-corrupt-", ".png")
        val empty = File.createTempFile("agora-wire-empty-", ".png")
        try {
            corrupt.writeBytes(image.readBytes().copyOf(33))
            val missing = valid.copy(path = File(image.parentFile, "missing-${image.name}").absolutePath)
            val archived = valid.copy(path = "", unavailable = true)
            val invalid = listOf(
                imageMessage(valid.path, valid).copy(imageArtifacts = emptyMap()),
                imageMessage(missing.path, missing),
                imageMessage(valid.path, valid.copy(unavailable = true)),
                imageMessage(corrupt.absolutePath, valid.copy(path = corrupt.absolutePath)),
                imageMessage(empty.absolutePath, valid.copy(path = empty.absolutePath)),
                imageMessage("", valid.copy(path = "")), imageMessage("", valid), imageMessage(" ", valid),
                imageMessage("", archived),
            ).map { listOf(it) }
            val sources = ImageSource.entries.flatMap { source ->
                listOf(sourceMessages(source, missing), sourceMessages(source, archived))
            }
            for (wire in RemoteWire.entries) withServer(wire.response(), wire.contentType) { server ->
                for (messages in invalid + sources) {
                    collect(wire.provider(), wire.config(server), messages)
                    val request = server.takeRequest()
                    assertCapturedImages(wire, request, emptyList(), emptyList())
                    assertTrue(request.bytes.decodeToString().contains("inspect"))
                    assertFalse(request.bytes.decodeToString().contains("Attachment unavailable"))
                }
            }
        } finally {
            corrupt.delete()
            empty.delete()
        }
    }

    @Test
    fun mixedImagesKeepReadableOccurrencesTextAndValidJsonForEveryRemoteWire() = withImage { image, _ ->
        val valid = measuredArtifact(image)
        val missing = valid.copy(path = File(image.parentFile, "missing-${image.name}").absolutePath)
        val artifacts = listOf(missing, valid, valid.copy(unavailable = true), valid)
        val input = imageMessage(valid.path, valid).copy(
            text = "keep this exact text", images = artifacts.map { it.path }, imageArtifacts = emptyMap(),
            attachmentMeta = AttachmentMeta(artifacts.mapIndexed { index, artifact ->
                AttachmentItem(type = "image", imageIndex = index, imageArtifacts = listOf(artifact))
            }),
        )
        for (wire in RemoteWire.entries) withServer(wire.response(), wire.contentType) { server ->
            collect(wire.provider(), wire.config(server), listOf(input))
            val request = server.takeRequest()
            assertCapturedImages(wire, request, listOf(valid, valid), listOf(image.readBytes(), image.readBytes()))
            assertTrue(request.bytes.decodeToString().contains("keep this exact text"))
            assertFalse(request.bytes.decodeToString().contains("Attachment unavailable"))
        }
    }

    @Test
    fun invalidOnlyImageWithNoTextSendsNeutralContentWithoutWarningOrError() = withImage { image, _ ->
        val artifact = measuredArtifact(image).copy(unavailable = true)
        val input = imageMessage(artifact.path, artifact).copy(text = "")
        for (wire in RemoteWire.entries) withServer(wire.response(), wire.contentType) { server ->
            collect(wire.provider(), wire.config(server), listOf(input))
            val request = server.takeRequest()
            assertCapturedImages(wire, request, emptyList(), emptyList())
            assertTrue(request.bytes.decodeToString().contains("Continue"))
            assertFalse(request.bytes.decodeToString().contains("unavailable", ignoreCase = true))
        }
    }

    @Test
    fun staleMeasurementsAndReadableReplacementUseCurrentMimeAndBytesForEveryRemoteWire() =
        withImage("png", ".jpg") { image, _ ->
            val old = measuredArtifact(image)
            assertTrue(ImageIO.write(BufferedImage(31, 19, BufferedImage.TYPE_INT_RGB), "jpeg", image))
            val measured = measuredArtifact(image)
            val stale = old.copy(mimeType = "application/octet-stream", sizeBytes = 0, width = 1, height = 1)
            for (wire in RemoteWire.entries) withServer(wire.response(), wire.contentType) { server ->
                collect(wire.provider(), wire.config(server), listOf(imageMessage(stale.path, stale)))
                assertCapturedImage(wire, server.takeRequest(), measured, image.readBytes())
            }
        }

    @Test
    fun everyRemoteWireKeepsRepeatedImageOccurrencesInOrder() {
        withImage("png", ".jpg") { first, _ ->
            withImage("jpeg", ".png") { second, _ ->
                val firstArtifact = measuredArtifact(first)
                val secondArtifact = measuredArtifact(second)
                val originals = listOf(first.readBytes(), second.readBytes(), first.readBytes())
                val artifacts = listOf(firstArtifact, secondArtifact, firstArtifact)
                val message = imageMessage(firstArtifact.path, firstArtifact).copy(
                    images = artifacts.map(ImageArtifact::path),
                    imageArtifacts = artifacts.associateBy(ImageArtifact::path),
                )
                for (wire in RemoteWire.entries) withServer(wire.response(), wire.contentType) { server ->
                    collect(wire.provider(), wire.config(server), listOf(message))
                    assertCapturedImages(wire, server.takeRequest(), artifacts, originals)
                }
            }
        }
    }

    private fun sourceMessages(source: ImageSource, artifact: ImageArtifact): List<ChatMessage> {
        val message = imageMessage(artifact.path, artifact)
        return when (source) {
            ImageSource.ATTACHMENT, ImageSource.PDF_PAGE, ImageSource.VIDEO_FRAME -> listOf(
                message.copy(
                    imageArtifacts = emptyMap(),
                    attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
                        type = when (source) {
                            ImageSource.PDF_PAGE -> "pdf"
                            ImageSource.VIDEO_FRAME -> "video"
                            else -> "image"
                        },
                        imageIndex = 0,
                        pageCount = 1.takeIf { source == ImageSource.PDF_PAGE },
                        imageArtifacts = listOf(artifact),
                    ))),
                ),
            )
            ImageSource.TOOL_RESULT -> toolImageMessages(artifact)
            ImageSource.LEGACY_ASSISTANT -> projectAssistantImagesToLatestUserMessage(
                listOf(
                    ChatMessage(id = "draw", text = "draw", participant = Participant.USER),
                    message.copy(id = "generated", text = "done", participant = Participant.MODEL),
                    ChatMessage(id = "follow-up", text = "inspect", participant = Participant.USER),
                ),
                includeImages = true,
            )
        }
    }

    private fun toolImageMessages(artifact: ImageArtifact): List<ChatMessage> {
        val call = MessageSegment(type = "tool", toolName = "inspect_image", toolArgs = "{}", toolCallId = "call-image")
        val result = call.copy(toolResult = "image ready", toolImages = listOf(artifact))
        val projected = projectToolResultImagesToUserMessage(
            listOf(
                ChatMessage(id = "inspect", text = "inspect", participant = Participant.USER),
                ChatMessage(id = "tool_image", text = "", participant = Participant.MODEL, segments = listOf(call)),
                ChatMessage(
                    id = "result_image", text = "image ready", participant = Participant.USER,
                    images = listOf(artifact.path), segments = listOf(result),
                ),
            ),
            includeImages = true,
        )
        if (artifact.path.isNotBlank() && !artifact.unavailable) {
            assertEquals(listOf(artifact.path), projected.single { it.id.startsWith("image_context_") }.images)
        }
        return projected
    }

    private fun assertCapturedImage(
        wire: RemoteWire,
        request: CapturedRequest,
        artifact: ImageArtifact,
        original: ByteArray,
    ) = assertCapturedImages(wire, request, listOf(artifact), listOf(original))

    private fun assertCapturedImages(
        wire: RemoteWire,
        request: CapturedRequest,
        artifacts: List<ImageArtifact>,
        originals: List<ByteArray>,
    ) {
        assertEquals(wire.path, request.path)
        request.assertExactLength()
        val images = mutableListOf<Pair<String?, String>>()
        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    when {
                        element["type"]?.jsonPrimitive?.content == "image_url" -> {
                            val image = element.getValue("image_url").jsonObject
                            assertEquals("auto", image.getValue("detail").jsonPrimitive.content)
                            val uri = image.getValue("url").jsonPrimitive.content
                            images += uri.substringAfter("data:").substringBefore(";base64,") to
                                uri.substringAfter(";base64,")
                        }
                        element["type"]?.jsonPrimitive?.content == "input_image" -> {
                            assertEquals("auto", element.getValue("detail").jsonPrimitive.content)
                            val uri = element.getValue("image_url").jsonPrimitive.content
                            images += uri.substringAfter("data:").substringBefore(";base64,") to
                                uri.substringAfter(";base64,")
                        }
                        element["type"]?.jsonPrimitive?.content == "image" -> {
                            val source = element.getValue("source").jsonObject
                            images += source.getValue("media_type").jsonPrimitive.content to
                                source.getValue("data").jsonPrimitive.content
                        }
                        element.containsKey("inlineData") -> {
                            val data = element.getValue("inlineData").jsonObject
                            images += data.getValue("mimeType").jsonPrimitive.content to
                                data.getValue("data").jsonPrimitive.content
                        }
                    }
                    if (wire == RemoteWire.OLLAMA) element["images"]?.jsonArray?.forEach {
                        images += null to it.jsonPrimitive.content
                    }
                    element.values.forEach(::visit)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(request.json)
        assertEquals("$wire must preserve every image occurrence", artifacts.size, images.size)
        images.forEachIndexed { index, image ->
            if (wire != RemoteWire.OLLAMA) assertEquals(artifacts[index].mimeType, image.first)
            assertArrayEquals(originals[index], Base64.getDecoder().decode(image.second))
        }
    }

    private enum class ImageSource { ATTACHMENT, PDF_PAGE, VIDEO_FRAME, TOOL_RESULT, LEGACY_ASSISTANT }

    private enum class RemoteWire(val model: String, val path: String) {
        CHAT("gpt-4o", "/v1/chat/completions"),
        RESPONSES("gpt-4o", "/v1/responses"),
        ANTHROPIC("claude-3-5-sonnet-20240620", "/v1/messages"),
        GEMINI("gemini-2.5-flash", "/v1beta/models/gemini-2.5-flash:streamGenerateContent"),
        OLLAMA("qwen3:8b", "/api/chat");

        val contentType: String
            get() = if (this == OLLAMA) "application/x-ndjson" else "text/event-stream"

        fun provider(): LlmProvider = when (this) {
            ANTHROPIC -> AnthropicProvider()
            GEMINI -> GeminiProvider()
            OLLAMA -> OllamaProvider()
            else -> OpenAiProvider()
        }

        fun config(server: RecordingServer) = ProviderConfig(
            apiKey = "", modelId = model,
            baseUrl = server.baseUrl + if (this in setOf(CHAT, RESPONSES, ANTHROPIC)) "/v1" else "",
            includeImages = true, thinkingEnabled = false, responsesApiEnabled = this == RESPONSES,
        )

        fun response(): String = when (this) {
            CHAT -> openAiChatResponse()
            RESPONSES -> openAiResponsesResponse()
            ANTHROPIC -> anthropicResponse()
            GEMINI -> geminiResponse()
            OLLAMA -> ollamaResponse()
        }
    }

    private fun adjacentUserBatch(image: File): List<ChatMessage> = listOf(
        ChatMessage(id = "typed", text = "typed", participant = Participant.USER, runId = "batch"),
        ChatMessage(
            id = "answer", parentId = "typed", runId = "batch",
            text = "<ask_user_answers>answer</ask_user_answers>",
            images = listOf(image.absolutePath), participant = Participant.USER,
            imageArtifacts = mapOf(image.absolutePath to measuredArtifact(image)),
            source = MessageSource.askUser(listOf(MessageSource.AskUserItem("Which?", "First"))),
        ),
        ChatMessage(
            id = "completion", parentId = "answer", runId = "batch",
            text = "<subagent_result>done</subagent_result>", participant = Participant.USER,
            source = MessageSource(MessageSource.Kind.SUBAGENT, subagentResults = mapOf("child" to "result")),
        ),
    )

    private fun collect(
        provider: LlmProvider,
        config: ProviderConfig,
        messages: List<ChatMessage>,
    ) {
        val events = runBlocking {
            withTimeout(3_000L) { provider.generateResponse(messages, config).toList() }
        }
        assertTrue("${provider.name}: $events", events.none { it is StreamEvent.Error })
        assertTrue("${provider.name}: $events", events.any { it is StreamEvent.TextChunk && it.text == "ok" })
    }

    private fun collect(
        provider: LlmProvider,
        config: ProviderConfig,
        image: File,
    ) = collect(provider, config, listOf(imageMessage(image.absolutePath, measuredArtifact(image))))

    private fun config(
        server: RecordingServer,
        model: String,
    ) = ProviderConfig(
        apiKey = "",
        modelId = model,
        baseUrl = when {
            model.startsWith("claude") || model.startsWith("gpt") -> "${server.baseUrl}/v1"
            else -> server.baseUrl
        },
        includeImages = true,
        thinkingEnabled = false,
    )

    private fun withImage(
        format: String = "png",
        suffix: String = ".png",
        test: (File, String) -> Unit,
    ) {
        val image = File.createTempFile("agora-stream-image-", suffix)
        try {
            val pixels = BufferedImage(137, 83, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                pixels.setRGB(x, y, ((x * 37 + y * 17) shl 16) xor (x * 11 shl 8) xor (y * 29))
            }
            assertTrue(ImageIO.write(pixels, format, image))
            test(image, Base64.getEncoder().encodeToString(image.readBytes()))
        } finally {
            image.delete()
        }
    }

    private fun measuredArtifact(image: File): ImageArtifact {
        val decoded = checkNotNull(ImageIO.read(image))
        val format = ImageIO.createImageInputStream(image).use { input ->
            val reader = ImageIO.getImageReaders(input).next()
            try {
                reader.formatName.lowercase()
            } finally {
                reader.dispose()
            }
        }
        return ImageArtifact(
            path = image.absolutePath,
            mimeType = if (format == "jpeg" || format == "jpg") "image/jpeg" else "image/$format",
            sizeBytes = image.length(),
            width = decoded.width,
            height = decoded.height,
            policyVersion = 1,
        )
    }

    private fun imageMessage(reference: String, artifact: ImageArtifact) = ChatMessage(
        id = "visual-input",
        text = "inspect",
        images = listOf(reference),
        participant = Participant.USER,
        imageArtifacts = mapOf(reference to artifact),
    )

    private fun withServer(
        response: String,
        contentType: String = "text/event-stream",
        test: (RecordingServer) -> Unit,
    ) = RecordingServer(response, contentType).use(test)

    private data class CapturedRequest(
        val path: String,
        val contentLength: Long?,
        val bytes: ByteArray,
        val chunked: Boolean,
    ) {
        val json: JsonObject
            get() = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

        fun assertExactLength() {
            contentLength?.let { assertEquals(bytes.size.toLong(), it) }
            if (contentLength == null) assertTrue("Streaming image requests use chunked transfer", chunked)
            assertTrue(bytes.decodeToString().contains("__AGORA_BASE64_").not())
        }
    }

    private class RecordingServer(
        private val responseBody: String,
        private val contentType: String,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val requests = LinkedBlockingQueue<CapturedRequest>()
        val baseUrl = "http://127.0.0.1:${server.address.port}"

        init {
            server.createContext("/") { exchange ->
                val bytes = exchange.requestBody.use { it.readBytes() }
                requests.add(
                    CapturedRequest(
                        path = exchange.requestURI.path,
                        contentLength = exchange.requestHeaders
                            .getFirst("Content-Length")
                            ?.toLongOrNull(),
                        bytes = bytes,
                        chunked = exchange.requestHeaders.getFirst("Transfer-Encoding")
                            ?.contains("chunked", ignoreCase = true) == true,
                    ),
                )
                val response = responseBody.toByteArray()
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }

        fun takeRequest(): CapturedRequest = checkNotNull(
            requests.poll(1, TimeUnit.SECONDS),
        )

        override fun close() = server.stop(0)
    }

    private companion object {
        fun openAiChatResponse() =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}," +
                "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

        fun openAiResponsesResponse() =
            "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1," +
                "\"delta\":\"ok\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"sequence_number\":2," +
                "\"response\":{\"status\":\"completed\"}}\n\n"

        fun anthropicResponse() = listOf(
            "{\"type\":\"content_block_start\",\"index\":0," +
                "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "{\"type\":\"content_block_delta\",\"index\":0," +
                "\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}",
            "{\"type\":\"content_block_stop\",\"index\":0}",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}",
            "{\"type\":\"message_stop\"}",
        ).joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" }

        fun geminiResponse() =
            "data: {\"candidates\":[{\"content\":{\"role\":\"model\"," +
                "\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n"

        fun ollamaResponse() =
            "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}," +
                "\"done\":true,\"done_reason\":\"stop\"," +
                "\"prompt_eval_count\":1,\"eval_count\":1}\n"
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
