package com.newoether.agora.api.util

import android.app.Application
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.Participant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.internal.bytecode.InstrumentationConfiguration
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class ImageWireTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquirePackage("javax.imageio.")
            .doNotAcquirePackage("com.sun.net.httpserver.")
            .doNotAcquirePackage("sun.net.httpserver.")
            .build()
}

@RunWith(ImageWireTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamingJsonRequestBodyTest {
    @Test
    fun mixedImagesPreserveTextOriginalBytesAndReplayOrder() = withImage { file, artifact ->
        val registry = Base64FileRegistry()
        val images = (0 until 65).map { checkNotNull(registry.register(artifact)) }
        val request = checkNotNull(registry.prepare(template(images)))
        assertEquals(-1L, request.body.contentLength())
        val original = file.readBytes()
        repeat(2) {
            val json = write(request)
            assertEquals("图像🙂", json.getValue("text").jsonPrimitive.content)
            val parts = json.getValue("content").jsonArray
            assertEquals(65, parts.size)
            parts.forEach { assertArrayEquals(original, decoded(it.jsonObject)) }
        }
        assertTrue(request.diagnosticJson.contains("[STREAMED_BASE64:${original.size}_BYTES]"))
        assertFalse(request.diagnosticJson.contains(Base64.getEncoder().encodeToString(original)))
    }

    @Test
    fun missingUnreadableEmptyAndHeaderOnlyImagesSilentlyFailRegistration() = withImage { file, artifact ->
        val empty = File.createTempFile("agora-empty-image-", ".png")
        val corrupt = File.createTempFile("agora-corrupt-image-", ".png")
        try {
            corrupt.writeBytes(file.readBytes().copyOf(33))
            val invalid = listOf(
                artifact.copy(path = File(file.parentFile, "missing-${file.name}").absolutePath),
                artifact.copy(path = file.parentFile.absolutePath),
                artifact.copy(path = empty.absolutePath),
                artifact.copy(path = corrupt.absolutePath),
                artifact.copy(path = ""), artifact.copy(path = "relative.png"),
                artifact.copy(path = "content://unresolved/image"), artifact.copy(unavailable = true),
            )
            invalid.forEach {
                val registry = Base64FileRegistry()
                assertNull(registry.register(it))
                assertNull(registry.prepare("{}"))
            }
        } finally {
            empty.delete()
            corrupt.delete()
        }
    }

    @Test
    fun historicalMeasurementsAreNotIdentityAndFileUrisUseActualBytes() = withImage { file, artifact ->
        val registry = Base64FileRegistry()
        val image = checkNotNull(registry.register(artifact.copy(
            path = file.toURI().toString(), mimeType = "application/octet-stream", sizeBytes = 0,
            width = 99, height = 101, policyVersion = 0,
        )))
        assertEquals("image/png", image.mimeType)
        val request = checkNotNull(registry.prepare(template(listOf(image))))
        assertArrayEquals(file.readBytes(), decoded(write(request).getValue("content").jsonArray.single().jsonObject))
    }

    @Test
    fun deletionAfterPrepareDropsOnlyThatWholeImageAndKeepsValidJson() = withImage { first, firstArtifact ->
        withImage { second, secondArtifact ->
            val registry = Base64FileRegistry()
            val images = listOf(firstArtifact, secondArtifact, firstArtifact).map { checkNotNull(registry.register(it)) }
            val request = checkNotNull(registry.prepare(template(images)))
            assertTrue(first.delete())
            repeat(2) {
                val json = write(request)
                assertEquals("图像🙂", json.getValue("text").jsonPrimitive.content)
                val parts = json.getValue("content").jsonArray
                assertEquals(1, parts.size)
                assertArrayEquals(second.readBytes(), decoded(parts.single().jsonObject))
            }
        }
    }

    @Test
    fun corruptionAfterPrepareDropsImageWithoutRemovingOrdinaryText() = withImage { file, artifact ->
        val registry = Base64FileRegistry()
        val image = checkNotNull(registry.register(artifact))
        val imagePart = imagePart(image)
        val body = buildJsonObject {
            put("content", JsonArray(listOf(
                buildJsonObject { put("type", "text"); put("text", "inspect exactly") }, imagePart,
            )))
        }.toString()
        val request = checkNotNull(registry.prepare(body))
        file.writeBytes(file.readBytes().copyOf(33))
        val parts = write(request).getValue("content").jsonArray
        assertEquals(1, parts.size)
        assertEquals("inspect exactly", parts.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertFalse(parts.toString().contains("unavailable", ignoreCase = true))
    }

    @Test
    fun lastImageDisappearingKeepsLegalNeutralContentAndOllamaText() = withImage { file, artifact ->
        for (kind in listOf("chat", "responses", "anthropic", "gemini", "ollama")) {
            val registry = Base64FileRegistry()
            val image = checkNotNull(registry.register(artifact))
            val placeholder = image.placeholder
            val part = when (kind) {
                "responses" -> "{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,$placeholder\",\"detail\":\"auto\"}"
                "anthropic" -> "{\"type\":\"image\",\"source\":{\"media_type\":\"image/png\",\"data\":\"$placeholder\"}}"
                "gemini" -> "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"$placeholder\"}}"
                "ollama" -> "\"$placeholder\""
                else -> imagePart(image).toString()
            }
            val field = when (kind) { "gemini" -> "parts"; "ollama" -> "images"; else -> "content" }
            val request = checkNotNull(registry.prepare("{\"contentText\":\"original\",\"$field\":[$part]}"))
            val original = file.readBytes()
            try {
                assertTrue(file.delete())
                val json = write(request)
                assertEquals("original", json.getValue("contentText").jsonPrimitive.content)
                if (kind == "ollama") assertTrue(json.getValue(field).jsonArray.isEmpty())
                else assertEquals("Continue", json.getValue(field).jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
            } finally {
                file.writeBytes(original)
            }
        }
    }

    @Test
    fun readableReplacementUsesCurrentMimeBytesAndDimensionsWithoutIdentityRejection() = withImage { file, artifact ->
        val registry = Base64FileRegistry()
        val image = checkNotNull(registry.register(artifact))
        val request = checkNotNull(registry.prepare(template(listOf(image))))
        assertTrue(ImageIO.write(BufferedImage(31, 17, BufferedImage.TYPE_INT_RGB), "jpeg", file))
        val part = write(request).getValue("content").jsonArray.single().jsonObject
        assertTrue(part.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertArrayEquals(file.readBytes(), decoded(part))
    }

    @Test
    fun duplicatePathsKeepOccurrenceAvailabilityIndependent() = withImage { _, artifact ->
        val message = ChatMessage(
            text = "", participant = Participant.USER, images = listOf(artifact.path, artifact.path),
            attachmentMeta = AttachmentMeta(listOf(
                AttachmentItem(type = "image", imageIndex = 0, imageArtifacts = listOf(artifact)),
                AttachmentItem(type = "image", imageIndex = 1, imageArtifacts = listOf(artifact.copy(unavailable = true))),
            )),
        )
        assertEquals(listOf(artifact), message.imageArtifactsForSend())
    }

    @Test
    fun networkWriteFailureAndCancellationAreNeverSwallowed() = withImage { _, artifact ->
        val registry = Base64FileRegistry()
        val request = checkNotNull(registry.prepare(template(listOf(checkNotNull(registry.register(artifact))))))
        for (failure in listOf(IOException("network failed"), CancellationException("cancelled"))) {
            val sink = object : ForwardingSink(Buffer()) {
                override fun write(source: Buffer, byteCount: Long) { throw failure }
            }.buffer()
            val caught = assertThrows(failure.javaClass) {
                request.body.writeTo(sink)
                sink.flush()
            }
            assertSame(failure, caught)
        }
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedIOException::class.java) { request.body.writeTo(Buffer()) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun concurrentReplayKeepsEveryOccurrenceAndPreparedRegistryRejectsLateRegistration() = withImage { file, artifact ->
        val registry = Base64FileRegistry()
        val images = (0 until 24).map { checkNotNull(registry.register(artifact)) }
        val request = checkNotNull(registry.prepare(template(images)))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((0 until 4).map { Callable { write(request).toString() } })
            val expected = results.first().get(10, TimeUnit.SECONDS)
            results.forEach { assertEquals(expected, it.get(10, TimeUnit.SECONDS)) }
            assertEquals(24, Json.parseToJsonElement(expected).jsonObject.getValue("content").jsonArray.size)
            assertArrayEquals(file.readBytes(), decoded(write(request).getValue("content").jsonArray.first().jsonObject))
            assertThrows(IllegalStateException::class.java) { registry.register(artifact) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun preparingAnEmptyRegistryRejectsLateRegistration() = withImage { _, artifact ->
        val registry = Base64FileRegistry()
        assertNull(registry.prepare("{}"))
        assertThrows(IllegalStateException::class.java) { registry.register(artifact) }
    }

    private fun template(images: List<Base64FileRegistry.RegisteredImage>): String = buildJsonObject {
        put("text", "图像🙂")
        put("content", JsonArray(images.map(::imagePart)))
    }.toString()

    private fun imagePart(image: Base64FileRegistry.RegisteredImage) = buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
            put("url", "data:${image.mimeType};base64,${image.placeholder}")
            put("detail", "auto")
        })
    }

    private fun write(request: StreamingJsonRequest): JsonObject =
        Json.parseToJsonElement(Buffer().also(request.body::writeTo).readUtf8()).jsonObject

    private fun decoded(part: JsonObject): ByteArray = Base64.getDecoder().decode(
        part.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content.substringAfter(";base64,"),
    )

    private fun withImage(test: (File, ImageArtifact) -> Unit) {
        val file = File.createTempFile("agora-stream-image-", ".png")
        try {
            val pixels = BufferedImage(17, 11, BufferedImage.TYPE_INT_RGB)
            pixels.setRGB(3, 2, 0x456789)
            assertTrue(ImageIO.write(pixels, "png", file))
            test(file, ImageArtifact(file.absolutePath, "image/png", file.length(), 17, 11, policyVersion = 1))
        } finally {
            file.delete()
        }
    }
}
