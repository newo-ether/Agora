package com.newoether.agora.webui

import com.newoether.agora.ui.chat.message.HtmlPreviewDocument
import com.newoether.agora.ui.chat.message.HtmlPreviewProbe
import com.newoether.agora.ui.chat.message.HtmlPreviewResources
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One bounded resource loader per browser card; source and private files never enter this owner. */
internal class WebUiHtmlPreview(private val auth: WebUiAuth) : AutoCloseable {
    private class Entry(val token: String, val resources: HtmlPreviewResources, val created: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun install(routing: Routing) = with(routing) {
        post("/api/html-preview") {
            if (!auth.isValidSession(call.request.cookies[WebUiServer.SESSION_COOKIE]) ||
                call.request.headers["Origin"]?.let { runCatching { java.net.URI(it).rawAuthority != call.request.headers["Host"] }.getOrDefault(true) } == true ||
                call.request.headers["Content-Type"]?.startsWith("application/json") != true) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }
            // The body carries no source, path, credentials or URL.
            if (call.request.headers["Content-Length"]?.toIntOrNull() !in 0..128) return@post call.respond(HttpStatusCode.BadRequest)
            val release = call.request.queryParameters["release"]
            val token = call.request.cookies[WebUiServer.SESSION_COOKIE]!!
            if (release != null) {
                entries[release]?.takeIf { it.token == token }?.let { if (entries.remove(release, it)) it.resources.close() }
                return@post call.respond(HttpStatusCode.NoContent)
            }
            call.receiveText()
            val expired = entries.entries.filter { (_, entry) ->
                System.currentTimeMillis() - entry.created > MAX_AGE || !auth.isValidSession(entry.token)
            }
            expired.forEach { (id, entry) -> if (entries.remove(id, entry)) entry.resources.close() }
            val id = UUID.randomUUID().toString()
            val resource = HtmlPreviewResources(HtmlPreviewDocument(""), emptyMap(), "#fff", "#111")
            resource.admit(HtmlPreviewProbe.ACCEPTED_RESULT)
            val accepted = synchronized(entries) {
                if (entries.size >= 64 || entries.values.count { it.token == token } >= 16) false
                else { entries[id] = Entry(token, resource, System.currentTimeMillis()); true }
            }
            if (!accepted) { resource.close(); return@post call.respond(HttpStatusCode.TooManyRequests) }
            call.response.header("Cache-Control", "no-store")
            call.respondText("{\"id\":\"$id\"}", ContentType.Application.Json)
        }
        get("$ROOT/{id}/{kind}") {
            val id = call.parameters["id"].orEmpty()
            val kind = call.parameters["kind"]
            if (active(id) == null || kind !in setOf("control", "blocked")) return@get call.respond(HttpStatusCode.NotFound)
            call.respondText(document(kind == "blocked"), ContentType.Text.Html)
        }
        get("$ROOT/{id}/resource/{host}/{path...}") {
            val id = call.parameters["id"].orEmpty()
            val entry = active(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val encoded = call.request.local.uri.substringAfter("/resource/")
            if (encoded.contains('?') || encoded.contains('#')) return@get call.respond(HttpStatusCode.Forbidden)
            val response = withContext(Dispatchers.IO) { entry.resources.response("https://$encoded", "GET", false) }
            val bytes = withContext(Dispatchers.IO) { response.data.use { it.readBytes() } }
            call.response.header("Access-Control-Allow-Origin", "*")
            call.respondBytes(bytes, ContentType.parse(response.mimeType), HttpStatusCode.fromValue(response.statusCode))
        }
    }

    private suspend fun active(id: String): Entry? {
        val entry = entries[id] ?: return null
        if (System.currentTimeMillis() - entry.created <= MAX_AGE && auth.isValidSession(entry.token)) return entry
        if (entries.remove(id, entry)) entry.resources.close()
        return null
    }

    override fun close() { entries.values.forEach { it.resources.close() }; entries.clear() }

    companion object {
        const val ROOT = "/html-preview"
        private const val MAX_AGE = 10 * 60 * 1000L
        private val ID_PATH = Regex("/html-preview/([a-f0-9-]{36})/(control|blocked|resource/.*)")
        const val PERMISSIONS = "camera=(), microphone=(), geolocation=(), payment=(), usb=(), clipboard-read=(), clipboard-write=()"
        fun id(path: String): String? = ID_PATH.matchEntire(path)?.groupValues?.get(1)
        fun policy(prefix: String) = "default-src 'none'; script-src 'unsafe-inline' $prefix/resource/; " +
            "style-src 'unsafe-inline' $prefix/resource/; img-src data: blob: $prefix/resource/; " +
            "font-src data: $prefix/resource/; connect-src 'none'; frame-src 'none'; worker-src 'none'; " +
            "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'; sandbox allow-scripts"
        fun connection(prefix: String) = "(\"$prefix/resource/*\");webrtc=block"

        fun document(blocked: Boolean): String {
            val probe = HtmlPreviewProbe.frame("*")
            if (!blocked) return probe
            val receiver = """
                <script>
                addEventListener('message', function receive(event) {
                  if (event.source !== parent || event.origin !== location.origin ||
                      typeof event.data?.source !== 'string' || event.data.source.length > ${HtmlPreviewDocument.MAX_SOURCE_CHARS}) return;
                  removeEventListener('message', receive);
                  document.open();
                  document.write(event.data.source);
                  document.close();
                });
                </script>
            """.trimIndent()
            return probe.replace("</body>", "$receiver</body>")
        }
    }
}
