package com.newoether.agora.tool

import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.*
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ImageGenerationApiTest {
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject
    private val context = GenerationContext(
        imageGenEnabled = true, conversationId = "conversation", imageGenModel = "Qwen-Image-2.1",
        imageGenBaseUrl = "http://192.168.1.2:8000/v1", imageGenApiKey = "",
    )

    @Test fun openAiGenerationRetainsControlsAndLocalServerNeedsNoFakeCredential() {
        val input = args("""{"prompt":"draw","size":"2048x2048","n":3,"quality":"high","output_format":"png","background":"transparent","seed":42}""")
        ImageGenerationApi.validate(input, "openai")
        val buffer = Buffer()
        ImageGenerationApi.body(input, context, emptyList(), null).writeTo(buffer)
        val sent = args(buffer.readUtf8())
        assertEquals("Qwen-Image-2.1", sent["model"]?.jsonPrimitive?.content)
        assertEquals(3, sent["n"]?.jsonPrimitive?.int)
        assertEquals("transparent", sent["background"]?.jsonPrimitive?.content)
        assertEquals(42, sent["seed"]?.jsonPrimitive?.int)
        assertEquals("http://192.168.1.2:8000/v1/images/generations", ImageGenerationApi.endpoint(context, false))
        assertTrue(ImageGenerationApi.headers(context).isEmpty())
        assertEquals("Bearer secret", ImageGenerationApi.headers(context.copy(imageGenApiKey = "secret"))["Authorization"])
    }

    @Test fun editingUsesMultipartAndPreservesReferenceOrderAndMaskBytes() {
        val directory = Files.createTempDirectory("image-api-").toFile()
        try {
            val first = directory.resolve("one").apply { writeText("first-image-bytes") }
            val second = directory.resolve("two").apply { writeText("second-image-bytes") }
            val mask = directory.resolve("mask").apply { writeText("mask-png-bytes") }
            val body = ImageGenerationApi.body(
                args("""{"prompt":"replace background","input_fidelity":"high"}"""), context,
                listOf(first to "image/png", second to "image/webp"), mask to "image/png",
            ) as MultipartBody
            assertEquals("http://192.168.1.2:8000/v1/images/edits", ImageGenerationApi.endpoint(context, true))
            val buffer = Buffer()
            body.writeTo(buffer)
            val sent = buffer.readUtf8()
            assertTrue(sent.contains("name=\"image[]\"; filename=\"reference_0.png\""))
            assertTrue(sent.contains("name=\"image[]\"; filename=\"reference_1.webp\""))
            assertTrue(sent.indexOf("first-image-bytes") < sent.indexOf("second-image-bytes"))
            assertTrue(sent.contains("name=\"mask\"; filename=\"mask.png\""))
            assertTrue(sent.contains("mask-png-bytes"))
            assertTrue(sent.contains("input_fidelity"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun geminiUsesNativeContentImageConfigAndApiKeyHeaderWithoutOpenAiFields() {
        val image = Files.createTempFile("image-api-", ".png").toFile()
        try {
            image.writeBytes(byteArrayOf(1, 2, 3))
            val ctx = context.copy(imageGenProtocol = "google", imageGenModel = "gemini-3.1-flash-image", imageGenBaseUrl = "https://generativelanguage.googleapis.com", imageGenApiKey = "secret")
            val input = args("""{"prompt":"edit this","aspect_ratio":"16:9","image_size":"2K"}""")
            ImageGenerationApi.validate(input, "google")
            val buffer = Buffer()
            ImageGenerationApi.body(input, ctx, listOf(image to "image/png"), null).writeTo(buffer)
            val sent = args(buffer.readUtf8())
            assertFalse(sent.containsKey("model"))
            assertFalse(sent.containsKey("n"))
            assertFalse(sent.containsKey("size"))
            val parts = sent["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
            assertEquals("AQID", parts[1].jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
            val config = sent["generationConfig"]!!.jsonObject
            assertEquals(listOf("TEXT", "IMAGE"), config["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("16:9", config["imageConfig"]!!.jsonObject["aspectRatio"]!!.jsonPrimitive.content)
            assertEquals("2K", config["imageConfig"]!!.jsonObject["imageSize"]!!.jsonPrimitive.content)
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-image:generateContent"
            assertEquals(endpoint, ImageGenerationApi.endpoint(ctx, true))
            assertEquals(endpoint, ImageGenerationApi.endpoint(ctx.copy(imageGenBaseUrl = "https://generativelanguage.googleapis.com/v1beta/"), false))
            assertEquals(mapOf("x-goog-api-key" to "secret"), ImageGenerationApi.headers(ctx))
        } finally { image.delete() }
    }

    @Test fun everyOpenAiImageAndOnlyFinalGeminiImagePartsAreRetainedInOrder() {
        val openAi = ImageGenerationApi.imagePayloads("""{"data":[{"b64_json":"one"},{"url":"https://example.test/two"}]}""", "openai")
        assertEquals(2, openAi.size)
        assertEquals("one", openAi[0]["b64_json"]!!.jsonPrimitive.content)
        val gemini = ImageGenerationApi.imagePayloads("""{"candidates":[{"content":{"parts":[{"thought":true,"inlineData":{"mimeType":"image/png","data":"thought"}},{"text":"caption"},{"inlineData":{"mimeType":"image/png","data":"first"}},{"inline_data":{"mime_type":"image/webp","data":"second"}}]}}]}""", "google")
        assertEquals(listOf("first", "second"), gemini.map { it["data"]!!.jsonPrimitive.content })
    }

    @Test fun invalidOrUnsupportedControlsFailBeforeARequest() {
        for (input in listOf(
            """{"prompt":""}""", """{"prompt":"x","n":0}""", """{"prompt":"x","n":5}""",
            """{"prompt":"x","n":1.5}""", """{"prompt":"x","n":"2"}""",
            """{"prompt":"x","output_compression":101}""", """{"prompt":"x","output_compression":"80"}""",
            """{"prompt":"x","seed":"42"}""", """{"prompt":"x","images":[{"server":12,"path":"/input.png"}]}""",
            """{"prompt":"x","mask":{"server":"Local Sandbox","path":"/mask.png"}}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { ImageGenerationApi.validate(args(input), "openai") }
        }
        for (input in listOf("""{"prompt":"x","n":2}""", """{"prompt":"x","quality":"high"}""", """{"prompt":"x","mask":{}}""")) {
            assertThrows(IllegalArgumentException::class.java) { ImageGenerationApi.validate(args(input), "google") }
        }
        assertThrows(IllegalArgumentException::class.java) { ImageGenerationApi.validate(args("""{"prompt":"x"}"""), "anthropic") }
        assertThrows(IllegalArgumentException::class.java) { ImageGenerationApi.imagePayloads("""{"candidates":[],"promptFeedback":{"blockReason":"SAFETY"}}""", "google") }
        assertThrows(IllegalArgumentException::class.java) { ImageGenerationApi.imagePayloads("""{"data":[{},{},{},{},{}]}""", "openai") }
    }
}
