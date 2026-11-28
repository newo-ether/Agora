package com.newoether.agora.ui.chat.message

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import com.newoether.agora.tool.ToolImageStore
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellMarkdownMediaHttpTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sameHttpUrlAtTwoPositionsRetainsFirstBytesAndReopensWithoutServer() = runBlocking {
        val old = png(Color.RED)
        val updated = png(Color.BLUE)
        val payload = AtomicReference(old)
        val requests = AtomicInteger()
        val server = server(payload, requests)
        val url = "http://127.0.0.1:${server.address.port}/image.png"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val context = context()
        try {
            val loader = loader(context, scope)
            assertNotNull(loader.peek(url))
            assertNull(loader.peek("ftp://example.test/image.png"))
            assertNull(loader.previewImage(url))
            assertEquals(0, requests.get())
            val first = requireNotNull(loader.previewImage(url, "m:answer:0"))
            payload.set(updated)
            val second = requireNotNull(loader.previewImage(url, "m:answer:1"))
            assertEquals(2, requests.get())
            assertNotEquals(first.path, second.path)
            assertEquals("image/png", first.mimeType)
            assertEquals(16, first.width)
            assertEquals(12, first.height)
            assertArrayEquals(old, File(first.path).readBytes())
            assertArrayEquals(updated, File(second.path).readBytes())
            server.stop(0)
            val reopened = loader(context, scope)
            val reopenedFirst = requireNotNull(reopened.previewImage(url, "m:answer:0"))
            val reopenedSecond = requireNotNull(reopened.previewImage(url, "m:answer:1"))
            assertEquals(first.path, reopenedFirst.path)
            assertEquals(second.path, reopenedSecond.path)
            assertArrayEquals(old, File(reopenedFirst.path).readBytes())
            assertArrayEquals(updated, File(reopenedSecond.path).readBytes())
            assertEquals(2, requests.get())
        } finally {
            server.stop(0)
            scope.cancel()
        }
    }

    @Test fun invalidAndOversizedHttpReadsDoNotCommitAndCanRetry() = runBlocking {
        val payload = AtomicReference("not a raster".toByteArray())
        val requests = AtomicInteger()
        val server = server(payload, requests)
        val url = "http://127.0.0.1:${server.address.port}/image.png"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val context = context()
        try {
            val loader = loader(context, scope)
            assertNull(loader.previewImage(url, "m:answer:0"))
            payload.set(ByteArray((ToolImageStore.MAX_IMAGE_BYTES + 1).toInt()))
            assertNull(loader.previewImage(url, "m:answer:0"))
            assertTrue(File(context.filesDir, "markdown-media").listFiles().orEmpty().isEmpty())
            val valid = png(Color.GREEN)
            payload.set(valid)
            val image = requireNotNull(loader.previewImage(url, "m:answer:0"))
            assertArrayEquals(valid, File(image.path).readBytes())
            assertEquals(3, requests.get())
        } finally {
            server.stop(0)
            scope.cancel()
        }
    }

    private fun context(): Context {
        val files = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
    }

    private fun loader(context: Context, scope: CoroutineScope) = ShellMarkdownMedia(
        context, devices = { emptyList() }, sandboxEnabled = { false }, sandbox = { null }, scope = scope,
    )

    private fun server(payload: AtomicReference<ByteArray>, requests: AtomicInteger): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/image.png") { exchange ->
                requests.incrementAndGet()
                val bytes = payload.get()
                // The raster bytes, rather than the untrusted response header, determine MIME.
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
