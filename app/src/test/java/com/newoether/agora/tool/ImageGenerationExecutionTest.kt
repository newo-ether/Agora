package com.newoether.agora.tool

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import com.newoether.agora.viewmodel.GenerationContext
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageGenerationExecutionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun localGenerationPublishesAllImagesAndDoesNotSendDummyAuthorization() = runBlocking {
        val image = encodedImage()
        val calls = AtomicInteger()
        var authorization: String? = "not called"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/images/generations") { exchange ->
            calls.incrementAndGet()
            authorization = exchange.requestHeaders.getFirst("Authorization")
            val request = exchange.requestBody.bufferedReader().readText()
            assertTrue(request.contains("\"n\":2"))
            val response = """{"data":[{"b64_json":"$image"},{"b64_json":"$image"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val context = context(server)
        try {
            val result = execute(ImageGenToolProvider(app), """{"prompt":"draw","n":2}""", context)
            assertFalse(result.isError)
            assertEquals(2, result.images.size)
            assertTrue(result.images.all { File(it.path).isFile && it.width == 16 && it.height == 12 })
            assertNull(authorization)
            assertEquals(1, calls.get())
            result.images.forEach { File(it.path).delete() }
        } finally { server.stop(0) }
    }

    @Test fun malformedLaterImageRollsBackUnpublishedEarlierImageAndDoesNotRetry() = runBlocking {
        val image = encodedImage()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger()
        server.createContext("/v1/images/generations") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.close()
            val response = """{"data":[{"b64_json":"$image"},{"b64_json":"not image bytes"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val directory = File(app.filesDir, "tool-media")
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        try {
            val result = execute(ImageGenToolProvider(app), """{"prompt":"draw","n":2}""", context(server))
            assertTrue(result.isError)
            assertTrue(result.images.isEmpty())
            assertEquals(before, directory.listFiles().orEmpty().map { it.name }.toSet())
            assertEquals(1, calls.get())
        } finally { server.stop(0) }
    }

    @Test fun providerErrorIsReturnedOnceWithItsDetail() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger()
        server.createContext("/v1/images/generations") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.close()
            val response = """{"error":{"message":"quality is unsupported"}}""".toByteArray()
            exchange.sendResponseHeaders(400, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val result = execute(ImageGenToolProvider(app), """{"prompt":"draw"}""", context(server))
            assertTrue(result.isError)
            assertTrue(result.text.contains("quality is unsupported"))
            assertTrue(result.text.contains("400"))
            assertEquals(1, calls.get())
        } finally { server.stop(0) }
    }

    @Test fun disabledShellReferenceFailsWithoutSendingGeneration() = runBlocking {
        val result = execute(
            ImageGenToolProvider(app),
            """{"prompt":"edit","images":[{"server":"Quantum","path":"C:/image.png"}]}""",
            GenerationContext(imageGenEnabled = true, conversationId = "conversation", imageGenBaseUrl = "http://127.0.0.1:1/v1"),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("Shell access is disabled"))
    }

    @Test fun cancellationClosesTheGenerationRequestWithoutPublishingMedia() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/images/generations") { exchange ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        val directory = File(app.filesDir, "tool-media")
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        try {
            val scope = com.newoether.agora.viewmodel.StreamScope()
            val request = async {
                com.newoether.agora.api.HttpClient.withStreamScope(scope) {
                    execute(ImageGenToolProvider(app), """{"prompt":"draw"}""", context(server))
                }
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            scope.cancelAll()
            withTimeout(2000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
            assertEquals(before, directory.listFiles().orEmpty().map { it.name }.toSet())
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    private fun context(server: HttpServer) = GenerationContext(
        imageGenEnabled = true, conversationId = "conversation", imageGenModel = "local-image",
        imageGenBaseUrl = "http://127.0.0.1:${server.address.port}/v1",
    )

    private suspend fun execute(provider: ImageGenToolProvider, args: String, ctx: GenerationContext): ToolExecutionResult {
        var result: ToolExecutionResult? = null
        provider.executeEvents("image_generate", args, ctx).collect {
            if (it is ToolExecutionEvent.Completed) result = it.result
        }
        return requireNotNull(result)
    }

    private fun encodedImage(): String {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        return try {
            val bytes = ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }
}
