package com.newoether.agora.webui

import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.ui.chat.message.HtmlPreviewDocument
import com.newoether.agora.ui.chat.message.HtmlPreviewResources
import com.newoether.agora.ui.chat.message.parseShellMediaLink
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One session-bound resource loader per card; only explicitly declared shell images grant reads. */
internal class WebUiHtmlPreview(
    private val auth: WebUiAuth,
    private val loadImage: suspend (String) -> ToolImageAttachment? = { null },
) : AutoCloseable {
    private class Entry(
        val token: String,
        val document: HtmlPreviewDocument,
        val created: Long,
        loadImage: suspend (String) -> ToolImageAttachment?,
    ) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Any()
        private var closed = false
        private var prepared: HtmlPreviewResources? = null
        val resources = scope.async(start = CoroutineStart.LAZY) {
            val images = linkedMapOf<String, ToolImageAttachment>()
            var total = 0L
            for ((url, link) in document.images) {
                currentCoroutineContext().ensureActive()
                val image = try { loadImage(link) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null } ?: continue
                val file = File(image.path)
                val measured = try {
                    inspectWebUiImageArtifact(file, image, HtmlPreviewDocument.MAX_TOTAL_BYTES.toLong())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: java.io.IOException) {
                    null
                } ?: continue
                if (measured.mimeType !in HtmlPreviewResources.RASTER_TYPES ||
                    measured.sizeBytes > HtmlPreviewDocument.MAX_TOTAL_BYTES - total) continue
                total += measured.sizeBytes
                images[url] = measured
            }
            currentCoroutineContext().ensureActive()
            val loader = HtmlPreviewResources(document, images, "#fff", "#111")
            synchronized(lock) {
                if (closed) { loader.close(); throw CancellationException("Preview released") }
                prepared = loader
            }
            loader
        }
        fun watchSession(auth: WebUiAuth, revoke: () -> Unit) {
            scope.launch {
                auth.awaitSessionEnd(token)
                revoke()
            }
        }
        override fun close() {
            val loader = synchronized(lock) {
                closed = true
                prepared.also { prepared = null }
            }
            scope.cancel()
            loader?.close()
        }
    }
    private val entries = ConcurrentHashMap<String, Entry>()

    fun install(routing: Routing) = with(routing) {
        post("/api/html-preview") {
            if (!auth.isValidSession(call.request.cookies[WebUiServer.SESSION_COOKIE]) ||
                call.request.headers["Origin"]?.let { runCatching { java.net.URI(it).rawAuthority != call.request.headers["Host"] }.getOrDefault(true) } == true ||
                call.request.headers["Content-Type"]?.substringBefore(';')?.trim() != "application/json") {
                return@post call.respond(HttpStatusCode.Forbidden)
            }
            val size = call.request.headers["Content-Length"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val release = call.request.queryParameters["release"]
            if (size !in 0..(if (release == null) MAX_BODY_BYTES else 128)) return@post call.respond(HttpStatusCode.BadRequest)
            val token = call.request.cookies[WebUiServer.SESSION_COOKIE]!!
            val body = call.boundedBody(size) ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (release != null) {
                entries[release]?.takeIf { it.token == token }?.let { if (entries.remove(release, it)) it.close() }
                return@post call.respond(HttpStatusCode.NoContent)
            }
            val links = imageDeclarations(body) ?: return@post call.respond(HttpStatusCode.BadRequest)
            val expired = entries.entries.filter { (_, entry) ->
                System.currentTimeMillis() - entry.created > MAX_AGE || !auth.isValidSession(entry.token)
            }
            expired.forEach { (id, entry) -> if (entries.remove(id, entry)) entry.close() }
            val id = UUID.randomUUID().toString()
            val document = HtmlPreviewDocument("", links)
            val entry = Entry(token, document, System.currentTimeMillis(), loadImage)
            val accepted = synchronized(entries) {
                if (entries.size >= 64 || entries.values.count { it.token == token } >= 16) false
                else { entries[id] = entry; true }
            }
            if (!accepted) { entry.close(); return@post call.respond(HttpStatusCode.TooManyRequests) }
            entry.watchSession(auth) { if (entries.remove(id, entry)) entry.close() }
            val images = document.images.entries.associate { (url, link) ->
                link to "$ROOT/$id/image/${url.substringAfterLast('/')}"
            }
            call.response.header("Cache-Control", "no-store")
            call.respondText(Json.encodeToString(mapOf("id" to JsonPrimitive(id),
                "images" to JsonObject(images.mapValues { JsonPrimitive(it.value) }))), ContentType.Application.Json)
        }
        get("$ROOT/{id}/{kind}") {
            val id = call.parameters["id"].orEmpty()
            if (active(id) == null || call.parameters["kind"] != "frame") return@get call.respond(HttpStatusCode.NotFound)
            call.respondText(document(), ContentType.Text.Html)
        }
        get("$ROOT/{id}/image/{index}") {
            val id = call.parameters["id"].orEmpty()
            val entry = active(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val index = call.parameters["index"].orEmpty()
            val url = "${entry.document.origin}/image/$index"
            if (call.request.queryParameters.names().isNotEmpty() || url !in entry.document.images) {
                return@get call.respond(HttpStatusCode.NotFound)
            }
            val resources = try { entry.resources.await() }
                catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    return@get call.respond(HttpStatusCode.NotFound)
                }
            if (active(id) !== entry) return@get call.respond(HttpStatusCode.NotFound)
            val response = withContext(Dispatchers.IO) { resources.response(url, "GET", false) }
            // No ACAO: the opaque generated frame can display raster bytes but cannot read pixels.
            call.respondOutputStream(ContentType.parse(response.mimeType), HttpStatusCode.fromValue(response.statusCode)) {
                withContext(Dispatchers.IO) { response.data.use { it.copyTo(this@respondOutputStream) } }
            }
        }
        get("$ROOT/{id}/resource/{host}/{path...}") {
            val id = call.parameters["id"].orEmpty()
            val entry = active(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val encoded = call.request.local.uri.substringAfter("/resource/")
            if (encoded.contains('?') || encoded.contains('#')) return@get call.respond(HttpStatusCode.Forbidden)
            val resources = try { entry.resources.await() }
                catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    return@get call.respond(HttpStatusCode.NotFound)
                }
            if (active(id) !== entry) return@get call.respond(HttpStatusCode.NotFound)
            val response = withContext(Dispatchers.IO) { resources.response("https://$encoded", "GET", false) }
            val bytes = withContext(Dispatchers.IO) { response.data.use { it.readBytes() } }
            call.response.header("Access-Control-Allow-Origin", "*")
            call.respondBytes(bytes, ContentType.parse(response.mimeType), HttpStatusCode.fromValue(response.statusCode))
        }
    }

    private suspend fun ApplicationCall.boundedBody(size: Int): String? {
        val input = receiveChannel()
        val bytes = ByteArrayOutputStream(minOf(size, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.readAvailable(buffer)
            if (count < 0) break
            if (bytes.size() + count > size) return null
            bytes.write(buffer, 0, count)
        }
        return if (bytes.size() == size) bytes.toString(Charsets.UTF_8.name()) else null
    }

    private fun imageDeclarations(body: String): List<String>? {
        val value = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (value.keys.any { it != "images" }) return null
        val images = value["images"] ?: return emptyList()
        if (images !is JsonArray || images.size > HtmlPreviewDocument.MAX_IMAGES) return null
        return images.map { element ->
            val link = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (link.length > MAX_LINK_CHARS || parseShellMediaLink(link) == null) return null
            link
        }.distinct()
    }

    private suspend fun active(id: String): Entry? {
        val entry = entries[id] ?: return null
        if (System.currentTimeMillis() - entry.created <= MAX_AGE && auth.isValidSession(entry.token)) return entry
        if (entries.remove(id, entry)) entry.close()
        return null
    }

    override fun close() { entries.values.forEach(Entry::close); entries.clear() }

    companion object {
        const val ROOT = "/html-preview"
        internal const val MAX_LINK_CHARS = 8_192
        internal const val MAX_BODY_BYTES = HtmlPreviewDocument.MAX_IMAGES * MAX_LINK_CHARS * 6 + 256
        private const val MAX_AGE = 10 * 60 * 1000L
        private val ID_PATH = Regex("/html-preview/([a-f0-9-]{36})/(frame|resource/.*|image/[0-9]+)")
        const val PERMISSIONS = "camera=(), microphone=(), geolocation=(), payment=(), usb=(), clipboard-read=(), clipboard-write=()"
        fun id(path: String): String? = ID_PATH.matchEntire(path)?.groupValues?.get(1)
        fun policy(prefix: String) = "default-src 'none'; script-src 'unsafe-inline' $prefix/resource/; " +
            "style-src 'unsafe-inline' $prefix/resource/; img-src data: blob: $prefix/resource/ $prefix/image/; " +
            "font-src data: $prefix/resource/; connect-src 'none'; frame-src 'none'; worker-src 'none'; " +
            "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'; sandbox allow-scripts"
        fun connection(prefix: String) = "(\"$prefix/resource/*\" \"$prefix/image/*\");webrtc=block"

        /** Receiver shell; the parent posts the generated source once. No capability probe:
         *  Connection-Allowlist is an origin trial that stock browsers ignore. */
        fun document(): String = """
                <!doctype html><html><head><meta charset="utf-8"></head><body><script>
                addEventListener('message', function receive(event) {
                  if (event.source !== parent || event.origin !== location.origin ||
                      typeof event.data?.source !== 'string' || event.data.source.length > ${HtmlPreviewDocument.MAX_SOURCE_CHARS}) return;
                  removeEventListener('message', receive);
                  document.open();
                  document.write(event.data.source);
                  document.close();
                });
                </script></body></html>
            """.trimIndent()
    }
}
