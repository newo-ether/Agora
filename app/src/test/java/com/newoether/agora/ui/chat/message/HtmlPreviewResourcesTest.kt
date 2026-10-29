package com.newoether.agora.ui.chat.message

import android.app.Application
import android.webkit.WebResourceResponse
import com.newoether.agora.model.ToolImageAttachment
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HtmlPreviewResourcesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val document = HtmlPreviewDocument("<p>Generated content</p>")
    private val library = "https://cdn.jsdelivr.net/npm/pkg/index.js"

    @Test
    fun deniesEveryGeneratedAndRemoteRouteBeforeProbeAdmission() {
        val requests = AtomicInteger()
        resources(client = client { chain ->
            requests.incrementAndGet()
            response(chain.request())
        }).use { loader ->
            listOf(
                Triple(document.rootUrl, "GET", true),
                Triple(document.frameUrl, "GET", false),
                Triple("${document.origin}/image/0", "GET", false),
                Triple(library, "GET", false),
                Triple("https://tile.openstreetmap.org/1/2/3.png", "GET", false),
                Triple("${document.origin}/probe.html", "GET", false),
                Triple("${document.origin}/probe-control.html", "GET", true),
                Triple("${document.origin}/probe-blocked.html", "POST", false),
            ).forEach { (url, method, mainFrame) ->
                assertBlocked(loader.response(url, method, mainFrame))
            }
        }
        assertEquals(0, requests.get())
    }

    @Test
    fun trustedProbeRoutesSeparateTheControlFromTheConnectionRestrictedFrame() {
        resources().use { loader ->
            val wrapper = loader.response("${document.origin}/probe.html", "GET", true)
            val control = loader.response("${document.origin}/probe-control.html", "GET", false)
            val blocked = loader.response("${document.origin}/probe-blocked.html", "GET", false)
            listOf(wrapper, control, blocked).forEach {
                assertEquals(200, it.statusCode)
                assertEquals("text/html", it.mimeType)
                assertEquals("no-store", it.responseHeaders["Cache-Control"])
                assertEquals("nosniff", it.responseHeaders["X-Content-Type-Options"])
                assertNull(it.responseHeaders["Access-Control-Allow-Origin"])
                assertNotNull(it.responseHeaders["Content-Security-Policy"])
                assertNotNull(it.responseHeaders["Permissions-Policy"])
            }
            assertNull(wrapper.responseHeaders["Connection-Allowlist"])
            assertNull(control.responseHeaders["Connection-Allowlist"])
            assertEquals(document.connectionPolicy, blocked.responseHeaders["Connection-Allowlist"])
            assertEquals(HtmlPreviewProbe.POLICY, control.responseHeaders["Content-Security-Policy"])
            assertEquals(HtmlPreviewProbe.POLICY, blocked.responseHeaders["Content-Security-Policy"])
            assertEquals(control.readText(), blocked.readText())
            assertFalse(wrapper.readText().contains("Generated content"))
        }
    }

    @Test
    fun admitsOnlyTheExactJavascriptResultAndCannotReopenAfterClose() {
        resources().use { loader ->
            listOf("", "null", "1,1:0,0", "\"1,1:1,1\"", "\"0,0:0,0\"", " \"1,1:0,0\"", "\"1,1:0,0\"\n").forEach {
                assertFalse("Admitted invalid result: $it", loader.admit(it))
                assertBlocked(loader.response(document.rootUrl, "GET", true))
            }
            assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
            val wrapper = loader.response(document.rootUrl, "GET", true)
            val frame = loader.response(document.frameUrl, "GET", false)
            assertEquals(document.wrapperPolicy, wrapper.responseHeaders["Content-Security-Policy"])
            assertEquals(document.contentPolicy, frame.responseHeaders["Content-Security-Policy"])
            assertTrue(wrapper.readText().contains("sandbox=\"allow-scripts\""))
            assertTrue(frame.readText().contains("<p>Generated content</p>"))
            loader.close()
            assertFalse(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
            assertBlocked(loader.response(document.rootUrl, "GET", true))
        }
    }

    @Test
    fun admittedRoutesKeepMainFrameAndMethodBoundaries() {
        resources().use { loader ->
            assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
            listOf(
                Triple(document.rootUrl, "GET", false),
                Triple(document.frameUrl, "GET", true),
                Triple(document.frameUrl, "POST", false),
                Triple(document.rootUrl, "HEAD", true),
                Triple(library, "GET", true),
                Triple("${document.origin}/probe.html", "GET", true),
                Triple("${document.origin}/unknown", "GET", false),
                Triple("${document.frameUrl}?query=x", "GET", false),
            ).forEach { (url, method, mainFrame) ->
                assertBlocked(loader.response(url, method, mainFrame))
            }
        }
    }

    @Test
    fun remoteFetchRejectsRedirectsAndUnexpectedOrMissingMimeTypes() {
        listOf(
            302 to "application/javascript",
            200 to "text/html",
            200 to "image/svg+xml",
            200 to null,
        ).forEach { (code, mime) ->
            val requests = AtomicInteger()
            val remote = client { chain ->
                requests.incrementAndGet()
                response(chain.request(), "<script>bad</script>".toResponseBody(mime?.toMediaType()), code)
            }
            resources(client = remote).use { loader ->
                assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
                assertBlocked(loader.response(library, "GET", false))
            }
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun approvedRemoteResponsesHaveCorsButNeverForwardCredentials() {
        val requests = mutableListOf<Request>()
        val remote = client { chain ->
            requests += chain.request()
            response(chain.request()).newBuilder().header("Set-Cookie", "secret=value; Secure").build()
        }
        resources(client = remote).use { loader ->
            assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
            repeat(2) {
                val result = loader.response(library, "GET", false)
                assertEquals(200, result.statusCode)
                assertEquals("application/javascript", result.mimeType)
                assertEquals("*", result.responseHeaders["Access-Control-Allow-Origin"])
                assertEquals("no-referrer", result.responseHeaders["Referrer-Policy"])
                assertEquals("const value = 1;", result.readText())
            }
        }
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("GET", request.method)
            assertNull(request.body)
            listOf("Authorization", "Proxy-Authorization", "Cookie", "Referer", "Origin").forEach {
                assertNull("Forwarded credential or browser header: $it", request.header(it))
            }
        }
        val production = HtmlPreviewResources.networkClient
        assertSame(CookieJar.NO_COOKIES, production.cookieJar)
        assertSame(Authenticator.NONE, production.authenticator)
        assertSame(Authenticator.NONE, production.proxyAuthenticator)
        assertEquals(Proxy.NO_PROXY, production.proxy)
        assertFalse(production.followRedirects)
        assertFalse(production.followSslRedirects)
        assertFalse(production.retryOnConnectionFailure)
    }

    @Test
    fun forbiddenRemoteUrlsNeverReachTheInjectedHttpClient() {
        val requests = AtomicInteger()
        resources(client = client { chain ->
            requests.incrementAndGet()
            response(chain.request())
        }).use { loader ->
            assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
            listOf(
                "https://user:secret@cdn.jsdelivr.net/index.js",
                "https://example.com/index.js",
                "http://cdn.jsdelivr.net/index.js",
                "https://cdn.jsdelivr.net/index.js?secret=value",
                "https://cdn.jsdelivr.net/image.svg",
            ).forEach { assertBlocked(loader.response(it, "GET", false)) }
        }
        assertEquals(0, requests.get())
    }

    @Test
    fun declaredAndStreamedOversizeResponsesAreClosedAndRejected() {
        listOf(HtmlPreviewDocument.MAX_RESOURCE_BYTES.toLong() + 1, -1L).forEach { declared ->
            val body = CountingBody(HtmlPreviewDocument.MAX_RESOURCE_BYTES.toLong() + 1, declared)
            resources(client = client { response(it.request(), body) }).use { loader ->
                assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
                assertBlocked(loader.response(library, "GET", false))
            }
            assertTrue(body.closed)
            if (declared > 0) assertEquals(0L, body.readBytes)
            else assertTrue(body.readBytes > HtmlPreviewDocument.MAX_RESOURCE_BYTES)
        }
    }

    @Test
    fun requestCountCapsRepeatedLoadsAcrossTheWholePreview() {
        resources().use { loader ->
            assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
            repeat(HtmlPreviewResources.MAX_REQUESTS) {
                assertEquals(200, loader.response(document.frameUrl, "GET", false).statusCode)
            }
            assertBlocked(loader.response(document.frameUrl, "GET", false))
            assertBlocked(loader.response(document.rootUrl, "GET", true))
        }
    }

    @Test
    fun localRasterPixelsHaveNoCorsAndOpenStreamsStopAtRelease() {
        val file = temporary.newFile("pixel.png")
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==")
        file.writeBytes(png)
        val url = "${document.origin}/image/0"
        resources(mapOf(url to attachment(file))).use { loader ->
            assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
            val result = loader.response(url, "GET", false)
            assertEquals(200, result.statusCode)
            assertEquals("image/png", result.mimeType)
            assertNull(result.responseHeaders["Access-Control-Allow-Origin"])
            assertArrayEquals(png, result.data.use { it.readBytes() })
            val pending = loader.response(url, "GET", false).data
            assertTrue(pending.read() >= 0)
            loader.close()
            assertEquals(-1, pending.read())
            assertBlocked(loader.response(url, "GET", false))
        }
    }

    @Test
    fun imageCapabilitiesArePerPreviewAndRejectSvgOrMissingFiles() {
        val svg = temporary.newFile("vector.svg").apply { writeText("<svg/>") }
        val url = "${document.origin}/image/0"
        val missing = File(temporary.root, "missing.png")
        listOf(attachment(svg, "image/svg+xml"), attachment(missing)).forEach { image ->
            resources(mapOf(url to image)).use { loader ->
                assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
                assertBlocked(loader.response(url, "GET", false))
            }
        }
        val image = temporary.newFile("image.png").apply { writeBytes(byteArrayOf(1)) }
        resources(mapOf(url to attachment(image))).use { loader ->
            assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
            assertBlocked(loader.response("${HtmlPreviewDocument("").origin}/image/0", "GET", false))
            assertBlocked(loader.response("$url?path=other.png", "GET", false))
            assertBlocked(loader.response("${document.origin}/image/1", "GET", false))
        }
    }

    @Test
    fun localAndRemoteResponsesShareOneAggregateByteBudget() {
        val file = temporary.newFile("budget.png")
        RandomAccessFile(file, "rw").use { it.setLength(HtmlPreviewDocument.MAX_RESOURCE_BYTES.toLong()) }
        val url = "${document.origin}/image/0"
        resources(mapOf(url to attachment(file)), client { response(it.request()) }).use { loader ->
            assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
            repeat(HtmlPreviewDocument.MAX_TOTAL_BYTES / HtmlPreviewDocument.MAX_RESOURCE_BYTES) {
                val result = loader.response(url, "GET", false)
                assertEquals(200, result.statusCode)
                result.data.close()
            }
            assertBlocked(loader.response(library, "GET", false))
            assertBlocked(loader.response(url, "GET", false))
        }
    }

    @Test
    fun closeCancelsAnInFlightHttpCallAndDiscardsItsLateResponse() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val observed = AtomicReference<Call>()
        val worker = Executors.newSingleThreadExecutor()
        val loader = resources(client = client { chain ->
            observed.set(chain.call())
            entered.countDown()
            if (!finish.await(5, TimeUnit.SECONDS)) throw IOException("Test response was not released")
            response(chain.request())
        })
        try {
            assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
            val result = worker.submit<WebResourceResponse> { loader.response(library, "GET", false) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            loader.close()
            assertTrue(observed.get().isCanceled())
            finish.countDown()
            assertBlocked(result.get(5, TimeUnit.SECONDS))
            assertBlocked(loader.response(document.rootUrl, "GET", true))
        } finally {
            finish.countDown()
            loader.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun atMostFourRemoteResponsesCanBeInFlight() {
        val entered = CountDownLatch(4)
        val finish = CountDownLatch(1)
        val requests = AtomicInteger()
        val workers = Executors.newFixedThreadPool(4)
        val loader = resources(client = client { chain ->
            requests.incrementAndGet()
            entered.countDown()
            if (!finish.await(5, TimeUnit.SECONDS)) throw IOException("Test responses were not released")
            response(chain.request())
        })
        try {
            assertTrue(loader.admit(HtmlPreviewProbe.ACCEPTED_RESULT))
            val results = (0 until 4).map {
                workers.submit<WebResourceResponse> { loader.response(library, "GET", false) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertBlocked(loader.response(library, "GET", false))
            assertEquals(4, requests.get())
            finish.countDown()
            results.forEach { assertEquals(200, it.get(5, TimeUnit.SECONDS).statusCode) }
        } finally {
            finish.countDown()
            loader.close()
            workers.shutdownNow()
        }
    }

    @Test
    fun privateAndLocalAddressesFailTheProductionDnsPolicy() {
        listOf(
            "0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1",
            "169.254.1.1", "100.64.0.1", "100.127.255.254", "224.0.0.1",
            "::", "::1", "fe80::1", "fc00::1", "fd00::1", "ff02::1",
        ).forEach {
            assertFalse("Accepted private address: $it", HtmlPreviewResources.publicPreviewAddress(InetAddress.getByName(it)))
        }
        listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111").forEach {
            assertTrue(HtmlPreviewResources.publicPreviewAddress(InetAddress.getByName(it)))
        }
        assertThrows(IOException::class.java) { HtmlPreviewResources.networkClient.dns.lookup("127.0.0.1") }
    }

    private fun resources(
        images: Map<String, ToolImageAttachment> = emptyMap(),
        client: OkHttpClient = client { throw AssertionError("Unexpected network access: ${it.request().url}") },
    ) = HtmlPreviewResources(document, images, "#000000", "#FFFFFF", client)

    private fun client(interceptor: Interceptor) = HtmlPreviewResources.networkClient.newBuilder()
        .addInterceptor(interceptor).build()

    private fun response(
        request: Request,
        body: ResponseBody = "const value = 1;".toResponseBody("application/javascript".toMediaType()),
        code: Int = 200,
    ): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(code).message(if (code == 200) "OK" else "Found")
        .header("Location", "https://example.com/forbidden.js").body(body).build()

    private fun attachment(file: File, mime: String = "image/png") =
        ToolImageAttachment(file.absolutePath, mime, file.length())

    private fun assertBlocked(response: WebResourceResponse) {
        assertEquals(403, response.statusCode)
        assertEquals(0, response.data.use { it.readBytes().size })
    }

    private fun WebResourceResponse.readText() = data.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private class CountingBody(private val size: Long, private val declaredSize: Long) : ResponseBody() {
        var readBytes = 0L
        var closed = false
        private val buffered = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (readBytes >= size) return -1
                val count = minOf(byteCount, size - readBytes).toInt()
                sink.write(ByteArray(count))
                readBytes += count
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        override fun contentType() = "application/javascript".toMediaType()
        override fun contentLength() = declaredSize
        override fun source(): BufferedSource = buffered
    }
}
