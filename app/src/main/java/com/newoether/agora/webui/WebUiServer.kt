package com.newoether.agora.webui

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.parseRangesSpecifier
import io.ktor.http.content.TextContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.request.receiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * HTTP surface of the WebUI: the packaged frontend plus the session API.
 *
 * The browser is only a remote control, so every route here is either a static asset or a
 * small JSON call or a bounded attachment stream. Cross-site requests are refused three ways: the session cookie is
 * SameSite=Strict, POSTs require JSON or upload-only octet-stream (which forces a CORS preflight this
 * server never answers), and a present Origin header must match the Host the browser used.
 */
internal class WebUiServer(
    private val auth: WebUiAuth,
    /** Reads a packaged frontend file by its path under the asset root, or null if absent. */
    private val readAsset: (String) -> ByteArray?,
    /** Serves one signed-in `/api/sync` connection: incoming text frames and a text sender. */
    private val syncSession: suspend (String, ReceiveChannel<String>, suspend (String) -> Unit) -> Unit,
    private val upload: suspend (String, String, Long, String, String?, String?, Long?, io.ktor.utils.io.ByteReadChannel) -> HttpStatusCode =
        { _, _, _, _, _, _, _, _ -> HttpStatusCode.NotFound },
    private val previewAttachment: suspend (String, String, Long, String, String, Int, suspend (java.io.File, String) -> Unit) -> Boolean =
        { _, _, _, _, _, _, _ -> false },
    /** CSS variables for the app's current theme; empty keeps the defaults in `style.css`. */
    private val themeCss: () -> String = { "" },
    /** The app font file served at [WebUiTheme.FONT_PATH], or null when the system font is used. */
    private val readAppFont: () -> ByteArray? = { null },
    /** The app's code font file for one style name under [MONO_FONT_PATH], or null if unknown. */
    private val readMonoFont: (String) -> ByteArray? = { null },
    /** True when this request arrived over HTTPS: its session cookie is then marked Secure. */
    private val secureCookies: (ApplicationCall) -> Boolean = { false },
    /** Configured public HTTPS port, or null while ordinary HTTP serving is enabled. */
    private val httpsRedirectPort: () -> Int? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val toolImages: WebUiToolImages? = null,
) {
    fun install(application: Application) = with(application) {
        install(SecurityHeaders)
        val httpsGate = createApplicationPlugin("WebUiHttpsGate") {
            onCall { call ->
                val port = httpsRedirectPort() ?: return@onCall
                if (secureCookies(call)) return@onCall
                val authority = call.request.headers.getAll(HttpHeaders.Host)?.singleOrNull()
                val target = call.request.local.uri
                val host = authority?.let { runCatching { java.net.URI("https://$it") }.getOrNull() }
                val path = runCatching { java.net.URI(target) }.getOrNull()
                if (host?.host == null || host.rawUserInfo != null || host.rawPath.isNotEmpty() ||
                    host.rawQuery != null || host.rawFragment != null || host.port !in setOf(-1, port) ||
                    !target.startsWith('/') || path == null || path.isAbsolute || path.rawAuthority != null ||
                    path.rawFragment != null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@onCall
                }
                val origin = java.net.URI("https", null, host.host, port, null, null, null).toASCIIString()
                call.response.header(HttpHeaders.Location, origin + target)
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText("", status = HttpStatusCode.TemporaryRedirect)
            }
        }
        install(httpsGate)
        install(WebSockets) {
            pingPeriod = SYNC_PING_PERIOD
            timeout = SYNC_TIMEOUT
            maxFrameSize = MAX_SYNC_FRAME_BYTES
        }
        // Checked before the upgrade: a refused browser gets a plain 403, never a socket.
        val syncGate = createRouteScopedPlugin("WebUiSyncGate") {
            onCall { call ->
                if (!call.isSameOrigin() || !auth.isValidSession(call.request.cookies[SESSION_COOKIE])) {
                    call.respond(HttpStatusCode.Forbidden)
                } else {
                    call.response.cookies.append(call.sessionCookie(call.request.cookies[SESSION_COOKIE]!!, COOKIE_MAX_AGE))
                }
            }
        }
        routing {
            route("/api/sync") {
                install(syncGate)
                webSocket { serveSync() }
            }
            route("/api/attachments/{connectionId}/{attachmentId}/{kind}/{index}") {
                install(syncGate)
                get {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    val token = call.request.cookies[SESSION_COOKIE] ?: return@get
                    val seq = call.request.queryParameters["seq"]?.toLongOrNull()?.takeIf { it >= 0 }
                    val index = call.parameters["index"]?.toIntOrNull()?.takeIf { it >= 0 }
                    val kind = call.parameters["kind"]?.takeIf { it in setOf("source", "page", "frame") }
                    if (seq == null || index == null || kind == null) return@get call.respond(HttpStatusCode.NotFound)
                    coroutineScope {
                        val requestJob = currentCoroutineContext()[kotlinx.coroutines.Job]!!
                        val revoke = launch { auth.awaitSessionEnd(token); requestJob.cancel() }
                        try {
                            val found = previewAttachment(token, call.parameters["connectionId"].orEmpty(), seq,
                                call.parameters["attachmentId"].orEmpty(), kind, index) { file, mime ->
                                if (!auth.isValidSession(token)) throw kotlinx.coroutines.CancellationException("Session revoked")
                                withContext(Dispatchers.IO) {
                                    val size = file.length()
                                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                                    // Ignore malformed/multipart or validator-bound requests; this route serves one exact range.
                                    val range = call.request.headers.getAll(HttpHeaders.Range)?.singleOrNull()
                                        ?.takeIf { call.request.headers[HttpHeaders.IfRange] == null }
                                        ?.let(::parseRangesSpecifier)?.takeIf { it.ranges.size == 1 }
                                    val bytes = range?.merge(size)?.singleOrNull()
                                    if (range != null && bytes == null) {
                                        call.response.header(HttpHeaders.ContentRange, "bytes */$size")
                                        call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                                        return@withContext
                                    }
                                    val length = bytes?.let { it.last - it.first + 1 } ?: size
                                    if (bytes != null) call.response.header(HttpHeaders.ContentRange, "bytes ${bytes.first}-${bytes.last}/$size")
                                    val streamContext = currentCoroutineContext()
                                    val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
                                    // Ktor may invoke the writer after respond returns; keep the session pin until it settles.
                                    call.respondOutputStream(ContentType.parse(mime),
                                        status = if (bytes == null) HttpStatusCode.OK else HttpStatusCode.PartialContent,
                                        contentLength = length) {
                                        try {
                                            withContext(streamContext) {
                                                if (!auth.isValidSession(token)) throw kotlinx.coroutines.CancellationException("Session revoked")
                                                java.io.FileInputStream(file).use { input ->
                                                    if (input.channel.size() != size) throw java.io.IOException("Attachment size changed before response")
                                                    input.channel.position(bytes?.first ?: 0)
                                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                                    var remaining = length
                                                    while (remaining > 0) {
                                                        currentCoroutineContext().ensureActive()
                                                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                                        if (count < 0) throw java.io.EOFException("Attachment ended before its recorded size")
                                                        write(buffer, 0, count)
                                                        remaining -= count
                                                    }
                                                }
                                            }
                                            finished.complete(Unit)
                                        } catch (error: Throwable) {
                                            finished.completeExceptionally(error)
                                            throw error
                                        }
                                    }
                                    finished.await()
                                }
                            }
                            if (!found) call.respond(HttpStatusCode.NotFound)
                        } finally {
                            revoke.cancel()
                        }
                    }
                }
            }
            route("/api/attachments/{connectionId}") {
                install(syncGate)
                post {
                    val token = call.request.cookies[SESSION_COOKIE] ?: return@post
                    if (!call.request.contentType().match(ContentType.Application.OctetStream)) {
                        return@post call.respond(HttpStatusCode.UnsupportedMediaType)
                    }
                    val params = call.request.queryParameters
                    val seq = params["seq"]?.toLongOrNull()?.takeIf { it >= 0 }
                    val name = params["name"]?.takeIf { it.isNotBlank() && it.length <= 256 }
                    val mime = params["mime"]?.takeIf { it.length <= 128 }
                    val forced = params["type"]
                    val rawSize = call.request.headers[HttpHeaders.ContentLength]
                    val size = rawSize?.toLongOrNull()
                    if (seq == null || name == null || (rawSize != null && (size == null || size < 0)) ||
                        (forced != null && forced !in setOf("image", "video"))) {
                        return@post call.respond(HttpStatusCode.BadRequest)
                    }
                    if (size != null && size > com.newoether.agora.util.AttachmentFiles.MAX_ATTACHMENT_BYTES) {
                        return@post call.respond(HttpStatusCode.PayloadTooLarge)
                    }
                    coroutineScope {
                        val requestJob = currentCoroutineContext()[kotlinx.coroutines.Job]!!
                        val revoke = launch { auth.awaitSessionEnd(token); requestJob.cancel() }
                        try {
                            val status = upload(token, call.parameters["connectionId"].orEmpty(), seq, name, mime, forced, size, call.receiveChannel())
                            call.response.header(HttpHeaders.CacheControl, "no-store")
                            call.respond(if (auth.isValidSession(token)) status else HttpStatusCode.Forbidden)
                        } finally {
                            revoke.cancel()
                        }
                    }
                }
            }
            route("$TOOL_IMAGE_PATH/{conversationId}/{messageId}/{detailIndex}/{imageIndex}") {
                install(syncGate)
                get {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    val conversationId = call.parameters["conversationId"] ?: return@get call.respond(HttpStatusCode.NotFound)
                    val messageId = call.parameters["messageId"] ?: return@get call.respond(HttpStatusCode.NotFound)
                    val detailIndex = call.parameters["detailIndex"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
                    val imageIndex = call.parameters["imageIndex"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)
                    val image = toolImages?.open(conversationId, messageId, detailIndex, imageIndex)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    if (!auth.isValidSession(call.request.cookies[SESSION_COOKIE])) {
                        return@get call.respond(HttpStatusCode.Forbidden)
                    }
                    call.respondOutputStream(ContentType.parse(image.mimeType), contentLength = image.size) {
                        withContext(Dispatchers.IO) {
                            java.io.FileInputStream(image.file).use { input ->
                                if (input.channel.size() != image.size) {
                                    throw java.io.IOException("Tool image size changed before response")
                                }
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var remaining = image.size
                                while (remaining > 0) {
                                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (count < 0) throw java.io.EOFException("Tool image ended before its recorded size")
                                    write(buffer, 0, count)
                                    remaining -= count
                                }
                            }
                        }
                    }
                }
            }
            get("/") { call.respondAsset(INDEX) }
            get("/assets/{path...}") {
                val path = call.parameters.getAll("path").orEmpty().joinToString("/")
                call.respondAsset(path)
            }
            // Public like the other static files: the sign-in page is themed too.
            get("/theme.css") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondText(themeCss(), ContentType.Text.CSS.withParameter("charset", "utf-8"))
            }
            get(WebUiTheme.FONT_PATH) {
                val bytes = readAppFont()
                if (bytes == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondBytes(bytes, fontTypeOf(bytes))
                }
            }
            // The code font behind the Markdown code styles in style.css.
            get("$MONO_FONT_PATH/{style}") {
                val bytes = call.parameters["style"]?.let(readMonoFont)
                if (bytes == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respondBytes(bytes, ContentType("font", "ttf"))
                }
            }
            post("/api/login") { call.login() }
            post("/api/logout") {
                if (!call.acceptsPost()) return@post
                auth.logout(call.request.cookies[SESSION_COOKIE])
                call.response.cookies.append(call.sessionCookie(value = "", maxAge = 0))
                call.respond(HttpStatusCode.NoContent)
            }
            get("/api/session") {
                val signedIn = auth.isValidSession(call.request.cookies[SESSION_COOKIE])
                if (signedIn) call.response.cookies.append(call.sessionCookie(call.request.cookies[SESSION_COOKIE]!!, COOKIE_MAX_AGE))
                call.respondJson(HttpStatusCode.OK, SessionResponse(signedIn))
            }
        }
    }

    /** Runs the sync session and closes the socket as soon as its login session ends. */
    private suspend fun DefaultWebSocketServerSession.serveSync() {
        val token = call.request.cookies[SESSION_COOKIE] ?: return
        val texts = incoming.consumeAsFlow()
            .filterIsInstance<Frame.Text>()
            .map { it.readText() }
            .produceIn(this)
        val signOut = launch {
            auth.awaitSessionEnd(token)
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "signed_out"))
        }
        try {
            syncSession(token, texts) { text -> outgoing.send(Frame.Text(text)) }
        } finally {
            signOut.cancel()
        }
    }

    private suspend fun ApplicationCall.login() {
        if (!acceptsPost()) return
        val length = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length == null || length > MAX_LOGIN_BODY_BYTES) {
            respondJson(HttpStatusCode.PayloadTooLarge, ErrorResponse("invalid_request"))
            return
        }
        val password = runCatching { json.decodeFromString<LoginRequest>(receiveText()).password }
            .getOrNull()
        if (password == null) {
            respondJson(HttpStatusCode.BadRequest, ErrorResponse("invalid_request"))
            return
        }
        when (val result = auth.login(password)) {
            is WebUiLoginResult.Success -> {
                response.cookies.append(sessionCookie(result.sessionToken, maxAge = COOKIE_MAX_AGE))
                respondJson(HttpStatusCode.OK, SessionResponse(signedIn = true))
            }
            is WebUiLoginResult.WrongPassword -> respondJson(
                HttpStatusCode.Unauthorized,
                ErrorResponse("wrong_password", attemptsLeft = result.attemptsLeft),
            )
            is WebUiLoginResult.LockedOut -> {
                val seconds = ((result.untilMillis - clock()).coerceAtLeast(0) + 999) / 1000
                response.header(HttpHeaders.RetryAfter, seconds.toString())
                respondJson(
                    HttpStatusCode.TooManyRequests,
                    ErrorResponse("locked", retryAfterSeconds = seconds),
                )
            }
            WebUiLoginResult.NotConfigured ->
                respondJson(HttpStatusCode.ServiceUnavailable, ErrorResponse("not_configured"))
        }
    }

    /** Responds 403/415 and returns false unless this POST is same-origin JSON. */
    private suspend fun ApplicationCall.acceptsPost(): Boolean {
        if (!isSameOrigin()) {
            respondJson(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            return false
        }
        if (!request.contentType().match(ContentType.Application.Json)) {
            respondJson(HttpStatusCode.UnsupportedMediaType, ErrorResponse("invalid_request"))
            return false
        }
        return true
    }

    private fun ApplicationCall.isSameOrigin(): Boolean {
        val origin = request.headers[HttpHeaders.Origin] ?: return true
        val host = request.headers[HttpHeaders.Host] ?: return false
        val parsed = runCatching { URLBuilder(origin).build() }.getOrNull() ?: return false
        // Compare host and effective port; either side may omit a default port.
        // IPv6 hosts are bracketed ("[::1]:8686"), so the port is only after the closing bracket.
        val portSeparator = host.lastIndexOf(':').takeIf { it > host.lastIndexOf(']') } ?: -1
        val hostName = (if (portSeparator >= 0) host.substring(0, portSeparator) else host)
            .removePrefix("[").removeSuffix("]")
        val hostPort = if (portSeparator >= 0) {
            host.substring(portSeparator + 1).toIntOrNull() ?: return false
        } else {
            parsed.protocol.defaultPort
        }
        val originHost = parsed.host.removePrefix("[").removeSuffix("]")
        return originHost.equals(hostName, ignoreCase = true) && parsed.port == hostPort
    }

    private suspend fun ApplicationCall.respondAsset(path: String) {
        val bytes = path.takeIf(::isSafeAssetPath)?.let(readAsset)
        if (bytes == null) {
            respond(HttpStatusCode.NotFound)
            return
        }
        respondBytes(bytes, contentTypeOf(path))
    }

    private suspend inline fun <reified T> ApplicationCall.respondJson(
        status: HttpStatusCode,
        body: T,
    ) {
        response.header(HttpHeaders.CacheControl, "no-store")
        respond(TextContent(json.encodeToString(body), ContentType.Application.Json, status))
    }

    private fun ApplicationCall.sessionCookie(value: String, maxAge: Int?) = Cookie(
        name = SESSION_COOKIE,
        value = value,
        maxAge = maxAge,
        path = "/",
        httpOnly = true,
        secure = secureCookies(this),
        extensions = mapOf("SameSite" to "Strict"),
    )

    @Serializable private data class LoginRequest(val password: String)

    @Serializable private data class SessionResponse(val signedIn: Boolean)

    @Serializable
    private data class ErrorResponse(
        val error: String,
        val attemptsLeft: Int? = null,
        val retryAfterSeconds: Long? = null,
    )

    companion object {
        // Browser retention ceiling, renewed on authenticated checks; the server has no expiry.
        const val COOKIE_MAX_AGE = 400 * 24 * 60 * 60
        const val SESSION_COOKIE = "agora_session"
        const val INDEX = "index.html"
        const val MONO_FONT_PATH = "/fonts/mono"
        const val TOOL_IMAGE_PATH = "/api/tool-images"
        private const val MAX_LOGIN_BODY_BYTES = 4_096L
        /** Browser commands are small; this bounds what one incoming frame may allocate. */
        private const val MAX_SYNC_FRAME_BYTES = 64L * 1024L
        private val SYNC_PING_PERIOD = 20.seconds
        private val SYNC_TIMEOUT = 45.seconds
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val SAFE_ASSET_PATH = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*(/[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*)*")

        /** Plain relative names only: no `..`, no leading slash, no encoded separators. */
        internal fun isSafeAssetPath(path: String): Boolean = SAFE_ASSET_PATH.matches(path)

        private fun fontTypeOf(bytes: ByteArray): ContentType =
            if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "OTTO") {
                ContentType("font", "otf")
            } else {
                ContentType("font", "ttf")
            }

        private fun contentTypeOf(path: String): ContentType = when (path.substringAfterLast('.')) {
            "html" -> ContentType.Text.Html.withParameter("charset", "utf-8")
            "js", "mjs" -> ContentType.Text.JavaScript.withParameter("charset", "utf-8")
            "css" -> ContentType.Text.CSS.withParameter("charset", "utf-8")
            "svg" -> ContentType.Image.SVG
            "png" -> ContentType.Image.PNG
            "json" -> ContentType.Application.Json
            "woff2" -> ContentType("font", "woff2")
            else -> ContentType.Application.OctetStream
        }
    }
}

/** Browser hardening for every response: no framing, no sniffing, no third-party loads. */
private val SecurityHeaders = createApplicationPlugin("WebUiSecurityHeaders") {
    onCall { call ->
        with(call.response) {
            header("X-Content-Type-Options", "nosniff")
            header("X-Frame-Options", "DENY")
            header("Referrer-Policy", "no-referrer")
            header(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                    "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
            )
        }
    }
}
