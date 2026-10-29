package com.newoether.agora.ui.chat.message

import android.webkit.WebResourceResponse
import com.newoether.agora.model.ToolImageAttachment
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** WebView never performs network requests itself. Every response passes this bounded loader. */
internal class HtmlPreviewResources(
    private val document: HtmlPreviewDocument,
    private val images: Map<String, ToolImageAttachment>,
    private val background: String,
    private val foreground: String,
    private val client: OkHttpClient = networkClient,
) : Closeable {
    private val closed = AtomicBoolean()
    private val admitted = AtomicBoolean()
    private val consumed = AtomicInteger()
    private val attempts = AtomicInteger()
    private val permits = Semaphore(4)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val streams = ConcurrentHashMap.newKeySet<InputStream>()

    fun response(url: String, method: String, mainFrame: Boolean): WebResourceResponse {
        if (closed.get() || method != "GET" || attempts.incrementAndGet() > MAX_REQUESTS) return blocked()
        if (!admitted.get()) return probe(url, mainFrame)
        if (url == document.rootUrl && mainFrame) {
            return html(document.wrapper(background), document.wrapperPolicy)
        }
        if (mainFrame) return blocked()
        if (url == document.frameUrl) return html(document.frame(background, foreground), document.contentPolicy)
        if (!permits.tryAcquire()) return blocked()
        return try {
            images[url]?.let { return image(it) }
            if (!allowedHtmlResource(url)) return blocked()
            remote(url)
        } finally { permits.release() }
    }

    fun admit(probeResult: String): Boolean {
        if (closed.get() || probeResult != HtmlPreviewProbe.ACCEPTED_RESULT) return false
        admitted.set(true)
        return true
    }

    private fun probe(url: String, mainFrame: Boolean): WebResourceResponse = when {
        mainFrame && url == "${document.origin}/probe.html" -> html(
            HtmlPreviewProbe.wrapper(document.origin),
            "default-src 'none'; script-src 'unsafe-inline'; frame-src ${document.origin}; base-uri 'none'",
            connection = null,
        )
        !mainFrame && url == "${document.origin}/probe-control.html" -> html(
            HtmlPreviewProbe.frame(document.origin), HtmlPreviewProbe.POLICY, connection = null,
        )
        !mainFrame && url == "${document.origin}/probe-blocked.html" -> html(
            HtmlPreviewProbe.frame(document.origin), HtmlPreviewProbe.POLICY,
        )
        else -> blocked()
    }

    private fun image(image: ToolImageAttachment): WebResourceResponse = try {
        val file = File(image.path)
        val length = file.length()
        if (image.mimeType !in RASTER_TYPES || !file.isFile || length <= 0 ||
            length > HtmlPreviewDocument.MAX_TOTAL_BYTES || !reserve(length.toInt())) {
            blocked()
        } else {
            val stream = object : FilterInputStream(file.inputStream()) {
                private var remaining = length
                override fun read(): Int {
                    if (closed.get() || remaining <= 0) return -1
                    return super.read().also { if (it >= 0) remaining-- }
                }
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                    if (closed.get() || remaining <= 0) return -1
                    return `in`.read(bytes, offset, minOf(count.toLong(), remaining).toInt())
                        .also { if (it > 0) remaining -= it }
                }
                override fun close() { streams.remove(this); super.close() }
            }
            streams.add(stream)
            if (closed.get()) { stream.close(); blocked() }
            // An opaque-origin frame may display this image but cannot read its pixels.
            else WebResourceResponse(image.mimeType, null, 200, "OK", headers(), stream)
        }
    } catch (_: IOException) { blocked() }

    private fun remote(url: String): WebResourceResponse {
        val call = client.newCall(Request.Builder().url(url).get().build())
        calls.add(call)
        if (closed.get()) call.cancel()
        return try {
            call.execute().use { response ->
                if (response.code != 200) return blocked()
                val body = response.body
                val mime = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: return blocked()
                if (!validType(url, mime) || body.contentLength() > HtmlPreviewDocument.MAX_RESOURCE_BYTES) return blocked()
                val bytes = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (!closed.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > HtmlPreviewDocument.MAX_RESOURCE_BYTES || !reserve(count)) return blocked()
                        bytes.write(buffer, 0, count)
                    }
                }
                if (closed.get()) return blocked()
                WebResourceResponse(mime, "UTF-8", 200, "OK",
                    headers() + ("Access-Control-Allow-Origin" to "*"),
                    ByteArrayInputStream(bytes.toByteArray()))
            }
        } catch (_: IOException) { blocked() }
        finally { calls.remove(call) }
    }

    private fun reserve(bytes: Int): Boolean {
        while (true) {
            val current = consumed.get()
            if (bytes > HtmlPreviewDocument.MAX_TOTAL_BYTES - current) return false
            if (consumed.compareAndSet(current, current + bytes)) return true
        }
    }

    override fun close() {
        closed.set(true)
        calls.forEach(Call::cancel)
        calls.clear()
        streams.forEach { runCatching { it.close() } }
        streams.clear()
    }

    private fun html(text: String, policy: String, connection: String? = document.connectionPolicy) = WebResourceResponse(
        "text/html", "UTF-8", 200, "OK",
        headers() + mapOf(
            "Content-Security-Policy" to policy,
            "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=(), usb=(), clipboard-read=(), clipboard-write=()",
        ) + if (connection == null) emptyMap() else mapOf("Connection-Allowlist" to connection),
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
    )

    companion object {
        const val MAX_REQUESTS = 128
        val RASTER_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/avif")
        internal val networkClient by lazy {
            OkHttpClient.Builder()
                .cookieJar(CookieJar.NO_COOKIES)
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(20, TimeUnit.SECONDS)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                        if (addresses.isEmpty() || addresses.any { !publicPreviewAddress(it) }) {
                            throw IOException("Non-public preview resource address")
                        }
                    }
                }).build()
        }
        private fun headers() = mapOf(
            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
        )
        fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
            headers(), ByteArrayInputStream(ByteArray(0)))

        internal fun validType(url: String, mime: String): Boolean = when {
            url.endsWith(".js", true) || url.endsWith(".mjs", true) -> mime in setOf("application/javascript", "text/javascript")
            url.endsWith(".css", true) -> mime == "text/css"
            url.endsWith(".png", true) -> mime == "image/png"
            else -> mime in setOf("font/woff", "font/woff2", "font/ttf", "application/font-woff", "application/octet-stream")
        }

        internal fun publicPreviewAddress(address: InetAddress): Boolean {
            val bytes = address.address
            return !(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress ||
                (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) ||
                (bytes.size == 4 && (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xff) in 64..127))
        }
    }
}
