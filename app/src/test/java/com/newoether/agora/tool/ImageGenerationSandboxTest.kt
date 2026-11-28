package com.newoether.agora.tool

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import com.newoether.agora.data.MessageAttachmentCloneSession
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.viewmodel.GenerationContext
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
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
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageGenerationSandboxTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun sandboxEditKeepsImmutablePreviewsAndForkPathsWhileDescriptorsRetainEditableOriginals() = runBlocking {
        val home = Files.createTempDirectory("image-sandbox-").toFile()
        val source = home.resolve("source.png").apply { writeBytes(imageBytes()) }
        val original = source.readBytes()
        val mask = home.resolve("mask.png").apply { writeBytes(original) }
        val directory = File(app.filesDir, "tool-media")
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requestBody = ""
        var contentType = ""
        val image = Base64.encodeToString(original, Base64.NO_WRAP)
        server.createContext("/v1/images/edits") { exchange ->
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            requestBody = exchange.requestBody.bufferedReader().readText()
            val response = """{"data":[{"b64_json":"$image"},{"b64_json":"$image"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val result = execute(
                ImageGenToolProvider(app, factory(home)),
                """{"prompt":"edit","n":2,"images":[{"server":"Local Sandbox","path":"/home/agora/source.png"}],"mask":{"server":"Local Sandbox","path":"/home/agora/mask.png"}}""",
                context(server),
            )
            assertFalse(result.text, result.isError)
            assertTrue(contentType.startsWith("multipart/form-data;"))
            assertTrue(requestBody.contains("name=\"image[]\"; filename=\"reference_0.png\""))
            assertTrue(requestBody.contains("name=\"mask\"; filename=\"mask.png\""))
            val json = Json.parseToJsonElement(result.text).jsonObject
            assertEquals("edit", json["operation"]!!.jsonPrimitive.content)
            val paths = json["images"]!!.jsonArray
            assertEquals(2, paths.size)
            paths.forEachIndexed { index, value ->
                val descriptor = value.jsonObject
                assertEquals("Local Sandbox", descriptor["server"]!!.jsonPrimitive.content)
                val path = descriptor["path"]!!.jsonPrimitive.content
                assertTrue(path.startsWith("/home/agora/attachments/generated_"))
                val file = home.resolve(path.removePrefix("/home/agora/"))
                val preview = File(result.images[index].path)
                assertNotEquals(file.canonicalPath, preview.canonicalPath)
                assertEquals(directory.canonicalFile, preview.parentFile.canonicalFile)
                assertTrue(file.isFile)
                assertArrayEquals(file.readBytes(), preview.readBytes())
                val frozen = preview.readBytes()
                file.writeText("changed original")
                assertArrayEquals(frozen, preview.readBytes())
                assertTrue(file.delete())
                assertArrayEquals(frozen, preview.readBytes())
                assertEquals(preview.length(), descriptor["size_bytes"]!!.jsonPrimitive.long)
                assertEquals(16, descriptor["width"]!!.jsonPrimitive.int)
                assertEquals(12, descriptor["height"]!!.jsonPrimitive.int)
            }
            val forkDirectory = File(app.filesDir, "fork-${java.util.UUID.randomUUID()}")
            val session = MessageAttachmentCloneSession(forkDirectory)
            val segment = MessageSegment(type = "tool", toolName = "image_generate", toolResult = result.text, toolImages = result.images)
            try {
                val message = MessageEntity(
                    id = "generated", conversationId = "conversation", text = "", images = result.images.map { it.path },
                    status = MessageStatus.SUCCESS, participant = Participant.MODEL, timestamp = 0, runId = "run",
                    toolCallJson = Json.encodeToString(listOf(segment)),
                )
                val cloned = session.cloneMessage(message)
                val clonedSegment = Json.decodeFromString<List<MessageSegment>>(cloned.toolCallJson!!).single()
                assertEquals(result.text, clonedSegment.toolResult)
                assertEquals(cloned.images, clonedSegment.toolImages.map { it.path })
                cloned.images.forEachIndexed { index, path ->
                    assertNotEquals(result.images[index].path, path)
                    assertArrayEquals(File(result.images[index].path).readBytes(), File(path).readBytes())
                }
                session.rollback()
                assertTrue(cloned.images.none { File(it).exists() })
                assertTrue(result.images.all { File(it.path).isFile })
            } finally {
                session.rollback()
                forkDirectory.deleteRecursively()
            }
            assertArrayEquals(original, source.readBytes())
            assertArrayEquals(original, mask.readBytes())
            result.images.forEach { File(it.path).delete() }
            assertEquals(before, directory.listFiles().orEmpty().map { it.name }.toSet())
        } finally {
            server.stop(0)
            home.deleteRecursively()
        }
    }

    @Test fun failedSandboxBatchRemovesBothPartialArtifactsAndItsPrivateOutputDirectory() = runBlocking {
        val home = Files.createTempDirectory("image-sandbox-").toFile()
        val image = Base64.encodeToString(imageBytes(), Base64.NO_WRAP)
        val directory = File(app.filesDir, "tool-media")
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/images/generations") { exchange ->
            exchange.requestBody.close()
            val response = """{"data":[{"b64_json":"$image"},{"b64_json":"broken"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val result = execute(ImageGenToolProvider(app, factory(home)), """{"prompt":"draw","n":2}""", context(server))
            assertTrue(result.isError)
            assertTrue(result.images.isEmpty())
            assertTrue(home.resolve("attachments").listFiles().isNullOrEmpty())
            assertEquals(before, directory.listFiles().orEmpty().map { it.name }.toSet())
        } finally {
            server.stop(0)
            home.deleteRecursively()
        }
    }

    @Test fun cancelledLaterDownloadRemovesBothCompletedPreviewAndSandboxOriginal() = runBlocking {
        val home = Files.createTempDirectory("image-sandbox-").toFile()
        val directory = File(app.filesDir, "tool-media")
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val image = Base64.encodeToString(imageBytes(), Base64.NO_WRAP)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/images/generations") { exchange ->
            exchange.requestBody.close()
            val response = """{"data":[{"b64_json":"$image"},{"url":"http://127.0.0.1:${server.address.port}/second.png"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/second.png") { exchange ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        val scope = com.newoether.agora.viewmodel.StreamScope()
        val request = async {
            com.newoether.agora.api.HttpClient.withStreamScope(scope) {
                execute(ImageGenToolProvider(app, factory(home)), """{"prompt":"draw","n":2}""", context(server))
            }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertEquals(1, directory.listFiles().orEmpty().count { it.name !in before })
            assertEquals(1, home.resolve("attachments").listFiles().orEmpty().single().listFiles().orEmpty().size)
            scope.cancelAll()
            withTimeout(2000) { request.cancelAndJoin() }
            assertTrue(request.isCancelled)
            assertTrue(home.resolve("attachments").listFiles().isNullOrEmpty())
            assertEquals(before, directory.listFiles().orEmpty().map { it.name }.toSet())
        } finally {
            scope.cancelAll()
            request.cancelAndJoin()
            release.countDown()
            server.stop(0)
            home.deleteRecursively()
        }
    }

    @Test fun cancellationAtIoReturnBeforeCompletedDeliveryRemovesBothOutputs() = runBlocking {
        val home = Files.createTempDirectory("image-sandbox-dispatch-").toFile()
        val directory = File(app.filesDir, "tool-media")
        val before = directory.listFiles().orEmpty().map { it.name }.toSet()
        val image = Base64.encodeToString(imageBytes(), Base64.NO_WRAP)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/images/generations") { exchange ->
            exchange.requestBody.close()
            val response = """{"data":[{"b64_json":"$image"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val returning = LinkedBlockingQueue<Runnable>()
        val firstDispatch = AtomicBoolean(true)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (firstDispatch.compareAndSet(true, false)) returning.add(block)
                else Dispatchers.Default.dispatch(context, block)
            }
        }
        var delivered = false
        val request = async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            ImageGenToolProvider(app, factory(home)).executeEvents("image_generate", """{"prompt":"draw"}""", context(server))
                .collect { if (it is ToolExecutionEvent.Completed) delivered = true }
        }
        var pausedReturn: Runnable? = null
        try {
            pausedReturn = withContext(Dispatchers.IO) { returning.poll(5, TimeUnit.SECONDS) }
            assertNotNull("The completed IO result must wait before its caller resumes", pausedReturn)
            assertTrue(request.isActive)
            assertFalse(delivered)
            val preview = directory.listFiles().orEmpty().filter { it.name !in before }.single()
            val original = home.resolve("attachments").listFiles().orEmpty().single().listFiles().orEmpty().single()
            assertArrayEquals(preview.readBytes(), original.readBytes())
            request.cancel()
            pausedReturn!!.run()
            pausedReturn = null
            withTimeout(2000) { request.join() }
            assertTrue(request.isCancelled)
            assertFalse(delivered)
            assertFalse(preview.exists())
            assertFalse(original.exists())
            assertTrue(home.resolve("attachments").listFiles().isNullOrEmpty())
            assertEquals(before, directory.listFiles().orEmpty().map { it.name }.toSet())
        } finally {
            request.cancel()
            pausedReturn?.run()
            returning.poll()?.run()
            withTimeout(2000) { request.join() }
            server.stop(0)
            home.deleteRecursively()
        }
    }

    @Test fun enabledButUnavailableSandboxDoesNotFallBackToPrivateOutput() = runBlocking {
        val result = execute(
            ImageGenToolProvider(app), """{"prompt":"draw"}""",
            GenerationContext(imageGenEnabled = true, sandboxEnabled = true, conversationId = "conversation", imageGenBaseUrl = "http://127.0.0.1:1/v1"),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("Local Sandbox is not enabled"))
        assertTrue(result.images.isEmpty())
    }

    private fun factory(home: File): SandboxManagerFactory {
        val manager = mockk<SandboxManager>()
        coEvery { manager.isAvailable() } returns true
        every { manager.resolveHostFile(any()) } answers {
            val path = firstArg<String>()
            require(path.startsWith("/home/agora/"))
            home.resolve(path.removePrefix("/home/agora/"))
        }
        return object : SandboxManagerFactory {
            override fun create() = manager
            override fun isAvailable() = true
        }
    }

    private fun context(server: HttpServer) = GenerationContext(
        imageGenEnabled = true, sandboxEnabled = true, conversationId = "conversation", imageGenModel = "local-image",
        imageGenBaseUrl = "http://127.0.0.1:${server.address.port}/v1",
    )

    private suspend fun execute(provider: ImageGenToolProvider, args: String, context: GenerationContext): ToolExecutionResult {
        var result: ToolExecutionResult? = null
        provider.executeEvents("image_generate", args, context).collect {
            if (it is ToolExecutionEvent.Completed) result = it.result
        }
        return requireNotNull(result)
    }

    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
