package com.newoether.agora.webui

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.async
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readFully
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.*
import okio.FileSystem
import okio.Path.Companion.toPath
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolImageAttachment

class WebUiServerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val hasher = WebUiPasswordHasher(iterations = 1_000)
    private val assets = mapOf(
        "index.html" to "<!doctype html><title>Agora</title>".toByteArray(),
        "app.js" to "export {}".toByteArray(),
    )

    @Test
    fun servesPackagedAssetsWithHardeningHeadersAndRejectsTraversal() = webUi { _ ->
        val index = client.get("/")
        assertEquals(HttpStatusCode.OK, index.status)
        assertTrue(index.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))
        assertEquals("DENY", index.headers["X-Frame-Options"])
        assertTrue(index.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))

        val script = client.get("/assets/app.js")
        assertTrue(script.headers[HttpHeaders.ContentType]!!.startsWith("text/javascript"))

        assertEquals(HttpStatusCode.NotFound, client.get("/assets/missing.js").status)
        assertFalse(WebUiServer.isSafeAssetPath("../secret"))
        assertFalse(WebUiServer.isSafeAssetPath("a/../b.js"))
        assertFalse(WebUiServer.isSafeAssetPath("/abs.js"))
        assertFalse(WebUiServer.isSafeAssetPath("a%2Fb.js"))
        assertTrue(WebUiServer.isSafeAssetPath("vendor/preact.mjs"))
    }

    @Test
    fun loginSetsAStrictHttpOnlySessionCookieAndLogoutClearsIt() = webUi { auth ->
        val login = login("pw")
        assertEquals(HttpStatusCode.OK, login.status)
        val cookie = login.headers.getAll(HttpHeaders.SetCookie)!!.single()
        assertTrue(cookie.startsWith("${WebUiServer.SESSION_COOKIE}="))
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Strict"))
        assertTrue(cookie.contains("Max-Age=${WebUiServer.COOKIE_MAX_AGE}"))
        val token = cookie.substringAfter('=').substringBefore(';')
        assertTrue(auth.isValidSession(token))

        val session = client.get("/api/session") {
            header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
        }
        assertEquals("""{"signedIn":true}""", session.bodyAsText())
        assertTrue(session.headers[HttpHeaders.SetCookie]!!.contains("Max-Age=${WebUiServer.COOKIE_MAX_AGE}"))

        val logout = client.post("/api/logout") {
            header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertTrue(logout.headers[HttpHeaders.SetCookie]!!.contains("Max-Age=0"))
        assertFalse(auth.isValidSession(token))
        assertEquals("""{"signedIn":false}""", client.get("/api/session").bodyAsText())
    }

    @Test
    fun wrongPasswordsReportAttemptsLeftThenLockWithRetryAfter() = webUi { _ ->
        val first = login("bad")
        assertEquals(HttpStatusCode.Unauthorized, first.status)
        assertEquals("""{"error":"wrong_password","attemptsLeft":9}""", first.bodyAsText())
        repeat(8) { login("bad") }

        val locked = login("bad")
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertEquals("300", locked.headers[HttpHeaders.RetryAfter])
        assertTrue(locked.headers.getAll(HttpHeaders.SetCookie).isNullOrEmpty())
        assertEquals(HttpStatusCode.TooManyRequests, login("pw").status)
    }

    @Test
    fun crossOriginAndNonJsonPostsAreRefusedBeforeAnyPasswordCheck() = webUi { auth ->
        val crossSite = client.post("/api/login") {
            header(HttpHeaders.Host, "192.168.1.5:8686")
            header(HttpHeaders.Origin, "http://evil.example")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"pw"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, crossSite.status)

        val form = client.post("/api/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("password=pw")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, form.status)

        val otherPort = client.post("/api/login") {
            header(HttpHeaders.Host, "192.168.1.5:8686")
            header(HttpHeaders.Origin, "http://192.168.1.5:9999")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"pw"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, otherPort.status)

        // Neither refused request counted as a failed password.
        assertEquals("""{"error":"wrong_password","attemptsLeft":9}""", login("bad").bodyAsText())
        // Browsers always send Host; the test engine does not, so set it like a browser would.
        val sameOrigin = client.post("/api/login") {
            header(HttpHeaders.Host, "192.168.1.5:8686")
            header(HttpHeaders.Origin, "http://192.168.1.5:8686")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"pw"}""")
        }
        assertEquals(HttpStatusCode.OK, sameOrigin.status)
        auth.revokeAllSessions()
    }

    @Test
    fun loginWithoutAPasswordConfiguredIsUnavailable() = webUi(hash = null) { _ ->
        val response = login("anything")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("""{"error":"not_configured"}""", response.bodyAsText())
    }

    @Test
    fun malformedOrOversizedLoginBodiesAreRejected() = webUi { _ ->
        val malformed = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)

        val oversized = client.post("/api/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"${"x".repeat(5_000)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
    }

    private suspend fun ApplicationTestBuilder.login(password: String, origin: String? = null) =
        client.post("/api/login") {
            origin?.let { header(HttpHeaders.Origin, it) }
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$password"}""")
        }

    @Test
    fun syncRefusesTheUpgradeWithoutASessionOrFromAnotherOrigin() {
        var served = 0
        webUi(syncSession = { _, _, _ -> served++ }) { _ ->
            val sockets = createClient { install(ClientWebSockets) }
            val anonymous = runCatching { sockets.webSocket("/api/sync") {} }
            assertTrue(anonymous.isFailure)
            val token = sessionToken()
            val crossSite = runCatching {
                sockets.webSocket("/api/sync", request = {
                    header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                    header(HttpHeaders.Host, "192.168.1.5:8686")
                    header(HttpHeaders.Origin, "http://evil.example")
                }) {}
            }
            assertTrue(crossSite.isFailure)
            assertEquals(0, served)
            // The same browser from its own origin is let through.
            sockets.webSocket("/api/sync", request = {
                header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                header(HttpHeaders.Host, "192.168.1.5:8686")
                header(HttpHeaders.Origin, "http://192.168.1.5:8686")
            }) {}
        }
        assertEquals(1, served)
    }

    @Test
    fun syncServesTextFramesAndClosesWhenTheSessionEnds() = webUi(
        syncSession = { _, incoming, send -> for (text in incoming) send("echo:$text") },
    ) { auth ->
        val token = sessionToken()
        val sockets = createClient { install(ClientWebSockets) }
        sockets.webSocket("/api/sync", request = {
            header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
        }) {
            send(Frame.Text("hello"))
            assertEquals("echo:hello", (incoming.receive() as Frame.Text).readText())
            auth.logout(token)
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }

    private suspend fun ApplicationTestBuilder.sessionToken(): String =
        login("pw").headers.getAll(HttpHeaders.SetCookie)!!.single()
            .substringAfter('=').substringBefore(';')
    @Test
    fun uploadsRefuseUnauthenticatedCrossSiteAndSimpleContentTypesBeforeIngress() {
        var calls = 0
        webUi(upload = { _, _, _, _, _, _, _, _ -> calls++; HttpStatusCode.Accepted }) { _ ->
            val token = sessionToken()
            suspend fun request(cookie: Boolean, origin: String?, type: ContentType) =
                client.post("/api/attachments/tab?seq=2&name=f.txt&mime=text/plain") {
                    if (cookie) header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                    if (origin != null) header(HttpHeaders.Origin, origin)
                    header(HttpHeaders.Host, "localhost")
                    contentType(type)
                    setBody(byteArrayOf(1))
                }
            assertEquals(HttpStatusCode.Forbidden, request(false, null, ContentType.Application.OctetStream).status)
            assertEquals(HttpStatusCode.Forbidden, request(true, "https://foreign.example", ContentType.Application.OctetStream).status)
            assertEquals(HttpStatusCode.UnsupportedMediaType, request(true, "http://localhost", ContentType.Text.Plain).status)
            assertEquals(HttpStatusCode.UnsupportedMediaType, request(true, "http://localhost", ContentType.MultiPart.FormData).status)
            assertEquals(0, calls)
            assertEquals(HttpStatusCode.Accepted, request(true, "http://localhost", ContentType.Application.OctetStream).status)
            assertEquals(1, calls)
        }
    }
    @Test
    fun uploadForwardsAuthenticatedConnectionMetadataAndStreamsBytes() {
        var calls = 0
        webUi(upload = { login, id, seq, name, mime, type, size, input ->
            assertTrue(login.isNotBlank())
            assertEquals("tab", id)
            assertEquals(7L, seq)
            assertEquals("notes.txt", name)
            assertEquals("text/plain", mime)
            assertEquals(null, type)
            assertEquals(5L, size)
            val bytes = ByteArray(5)
            input.readFully(bytes)
            assertEquals("hello", bytes.toString(Charsets.UTF_8))
            calls++
            HttpStatusCode.Accepted
        }) { _ ->
            val token = sessionToken()
            suspend fun request(query: String, size: Long? = null) = client.post("/api/attachments/tab?$query") {
                header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                contentType(ContentType.Application.OctetStream)
                setBody(object : OutgoingContent.ReadChannelContent() {
                    override val contentLength: Long = size ?: 5L
                    override val contentType = ContentType.Application.OctetStream
                    override fun readFrom() = ByteReadChannel("hello".toByteArray())
                })
            }
            assertEquals(HttpStatusCode.BadRequest, request("seq=bad&name=notes.txt").status)
            assertEquals(HttpStatusCode.BadRequest, request("seq=7&name=notes.txt&type=pdf").status)
            assertEquals(HttpStatusCode.PayloadTooLarge, request("seq=7&name=notes.txt", 104857601).status)
            assertEquals(0, calls)
            val accepted = request("seq=7&name=notes.txt&mime=text/plain")
            assertEquals(HttpStatusCode.Accepted, accepted.status)
            assertEquals("no-store", accepted.headers[HttpHeaders.CacheControl])
            assertEquals(1, calls)
        }
    }

    @Test
    fun toolImagesRequireTheSessionAndOriginBeforeLoadingAnyMessage() {
        val root = temporary.newFolder("media")
        val bytes = byteArrayOf(1, 2, 3, 4)
        val image = File(root, "image.png").apply { writeBytes(bytes) }
        var loads = 0
        val images = WebUiToolImages(root) { conversation, message ->
            loads++
            if (conversation == "c" && message == "m") imageMessage(image, bytes.size.toLong()) else null
        }
        webUi(toolImages = images) { auth ->
            val path = "${WebUiServer.TOOL_IMAGE_PATH}/c/m/0/0"
            assertEquals(HttpStatusCode.Forbidden, client.get(path).status)
            val token = sessionToken()
            val cookie = "${WebUiServer.SESSION_COOKIE}=$token"
            assertEquals(HttpStatusCode.Forbidden, client.get(path) {
                header(HttpHeaders.Cookie, cookie)
                header(HttpHeaders.Host, "localhost:8686")
                header(HttpHeaders.Origin, "https://other.example")
            }.status)
            assertEquals(0, loads)
            val response = client.get(path) {
                header(HttpHeaders.Cookie, cookie)
                header(HttpHeaders.Host, "localhost:8686")
                header(HttpHeaders.Origin, "https://localhost:8686")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            org.junit.Assert.assertArrayEquals(bytes, response.body<ByteArray>())
            assertEquals("image/png", response.headers[HttpHeaders.ContentType])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            for (invalid in listOf("c/m/bad/0", "c/m/-1/0", "c/m/0/99", "other/m/0/0", "c/missing/0/0")) {
                assertEquals(HttpStatusCode.NotFound, client.get("${WebUiServer.TOOL_IMAGE_PATH}/$invalid") {
                    header(HttpHeaders.Cookie, cookie)
                }.status)
            }
            val beforeLogout = loads
            auth.logout(token)
            assertEquals(HttpStatusCode.Forbidden, client.get(path) { header(HttpHeaders.Cookie, cookie) }.status)
            assertEquals(beforeLogout, loads)
        }
    }
    @Test
    fun persistedMessageAttachmentRequiresAuthAndOwnedPath() {
        val root = temporary.newFolder("attachment-root")
        val imageRoot = File(root, "images").apply { mkdir() }
        val image = File(imageRoot, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val outside = temporary.newFile("outside.jpg").apply { writeBytes(byteArrayOf(4)) }
        val normalized = File(root, "img_00000000-0000-0000-0000-000000000001.jpg")
            .apply { writeBytes(byteArrayOf(5)) }
        var loads = 0
        val images = WebUiToolImages(root, root) { conversation, id ->
            loads++
            if (conversation != "c") null else ChatMessage(id = id, text = "", participant = Participant.USER,
                images = listOf(when (id) { "outside" -> outside.path; "normalized" -> normalized.path; else -> image.path }))
        }
        webUi(toolImages = images) { auth ->
            val path = "/api/message-attachments/c/m/0"
            assertEquals(HttpStatusCode.Forbidden, client.get(path).status)
            assertEquals(0, loads)
            val token = sessionToken()
            suspend fun request(target: String, origin: String? = null) = client.get(target) {
                header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                origin?.let { header(HttpHeaders.Origin, it) }
            }
            assertEquals(HttpStatusCode.Forbidden, request(path, "https://other.example").status)
            val response = request(path)
            assertEquals(HttpStatusCode.OK, response.status)
            org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3), response.body<ByteArray>())
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals(HttpStatusCode.OK, request("/api/message-attachments/c/normalized/0").status)
            assertEquals(HttpStatusCode.NotFound, request("/api/message-attachments/c/outside/0").status)
            assertEquals(HttpStatusCode.NotFound, request("/api/message-attachments/c/m/4").status)
            auth.logout(token)
            assertEquals(HttpStatusCode.Forbidden, request(path).status)
        }
    }

    @Test
    fun imageSessionRevokedDuringMessageReadNeverReceivesBytes() {
        val root = temporary.newFolder("media")
        val file = File(root, "image.png").apply { writeBytes(byteArrayOf(1)) }
        lateinit var auth: WebUiAuth
        val images = WebUiToolImages(root) { _, _ ->
            auth.revokeAllSessions()
            imageMessage(file, 1)
        }
        webUi(toolImages = images) { currentAuth ->
            auth = currentAuth
            val token = sessionToken()
            val response = client.get("${WebUiServer.TOOL_IMAGE_PATH}/c/m/0/0") {
                header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("", response.bodyAsText())
        }
    }

    @Test
    fun attachmentPreviewAuthenticatesThenStreamsTheExactArtifactWithoutCaching() {
        val file = temporary.newFile("preview.jpg").apply { writeText("raster") }
        var calls = 0
        webUi(previewAttachment = { _, connection, seq, id, kind, index, consume ->
            calls++
            assertEquals("tab", connection)
            assertEquals(7L, seq)
            assertEquals("pick", id)
            assertEquals("source", kind)
            assertEquals(0, index)
            consume(file, "image/jpeg")
            true
        }) { auth ->
            val path = "/api/attachments/tab/pick/source/0?seq=7"
            assertEquals(HttpStatusCode.Forbidden, client.get(path).status)
            val token = sessionToken()
            suspend fun request(url: String, origin: String? = null) = client.get(url) {
                header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                header(HttpHeaders.Host, "localhost")
                origin?.let { header(HttpHeaders.Origin, it) }
            }
            assertEquals(HttpStatusCode.Forbidden, request(path, "http://foreign.example").status)
            for (invalid in listOf("source/bad?seq=7", "source/-1?seq=7", "source/0?seq=-1", "source/0", "other/0?seq=7")) {
                assertEquals(HttpStatusCode.NotFound, request("/api/attachments/tab/pick/$invalid").status)
            }
            assertEquals(0, calls)
            val response = request(path, "http://localhost")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("raster", response.bodyAsText())
            assertEquals("image/jpeg", response.headers[HttpHeaders.ContentType])
            assertEquals("6", response.headers[HttpHeaders.ContentLength])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals(1, calls)
            auth.logout(token)
            assertEquals(HttpStatusCode.Forbidden, request(path).status)
            assertEquals(1, calls)
        }
    }

    @Test
    fun attachmentPreviewStreamsBoundedOpenAndSuffixRangesThroughTheSameOwner() {
        val file = temporary.newFile("range.mp4").apply { writeText("0123456789") }
        var settled = 0
        webUi(previewAttachment = { _, _, _, _, _, _, consume ->
            try { consume(file, "video/mp4"); true } finally { settled++ }
        }) { auth ->
            val token = sessionToken()
            suspend fun request(range: String, signedIn: Boolean = true, ifRange: String? = null) = client.get("/api/attachments/tab/pick/source/0?seq=7") {
                if (signedIn) header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                header(HttpHeaders.Range, range)
                ifRange?.let { header(HttpHeaders.IfRange, it) }
            }
            assertEquals(HttpStatusCode.Forbidden, request("bytes=2-4", false).status)
            assertEquals(0, settled)
            for ((range, expected, header) in listOf(
                Triple("bytes=2-4", "234", "bytes 2-4/10"),
                Triple("bytes=7-", "789", "bytes 7-9/10"),
                Triple("bytes=-2", "89", "bytes 8-9/10"),
                Triple("bytes=8-99", "89", "bytes 8-9/10"),
            )) {
                val response = request(range)
                assertEquals(HttpStatusCode.PartialContent, response.status)
                assertEquals(expected, response.bodyAsText())
                assertEquals(header, response.headers[HttpHeaders.ContentRange])
                assertEquals(expected.length.toString(), response.headers[HttpHeaders.ContentLength])
                assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            }
            val unsatisfiable = request("bytes=10-")
            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, unsatisfiable.status)
            assertEquals("bytes */10", unsatisfiable.headers[HttpHeaders.ContentRange])
            for (ignored in listOf("bytes=bad", "bytes=0-1,8-9", "items=0-1")) {
                val response = request(ignored)
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("0123456789", response.bodyAsText())
            }
            assertEquals("0123456789", request("bytes=2-4", ifRange = "unknown").bodyAsText())
            assertEquals(9, settled)
            auth.logout(token)
            assertEquals(HttpStatusCode.Forbidden, request("bytes=2-4").status)
            assertEquals(9, settled)
        }
    }
    @Test
    fun revocationCancelsTheAdmittedPreviewAndSettlesItsOwner() {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val settled = kotlinx.coroutines.CompletableDeferred<Unit>()
        webUi(previewAttachment = { _, _, _, _, _, _, _ ->
            entered.complete(Unit)
            try { kotlinx.coroutines.awaitCancellation() } finally { settled.complete(Unit) }
        }) { auth ->
            val token = sessionToken()
            kotlinx.coroutines.coroutineScope {
                val request = async {
                    runCatching { client.get("/api/attachments/tab/pick/source/0?seq=7") {
                        header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                        header(HttpHeaders.Range, "bytes=0-")
                    } }
                }
                kotlinx.coroutines.withTimeout(5000) { entered.await() }
                auth.logout(token)
                kotlinx.coroutines.withTimeout(5000) { settled.await(); request.await() }
            }
        }
    }

    private fun imageMessage(file: File, size: Long) = ChatMessage(
        id = "m", text = "", participant = Participant.MODEL, status = MessageStatus.SUCCESS,
        segments = listOf(MessageSegment(type = "tool", toolName = "view_image", toolResult = "{}",
            toolImages = listOf(ToolImageAttachment(file.absolutePath, "image/png", size, sha256 = "hash")),
        )),
    )

    private fun webUi(
        hash: String? = hasher.hash("pw"),
        syncSession: suspend (String, ReceiveChannel<String>, suspend (String) -> Unit) -> Unit = { _, _, _ -> },
        toolImages: WebUiToolImages? = null,
        upload: suspend (String, String, Long, String, String?, String?, Long?, io.ktor.utils.io.ByteReadChannel) -> HttpStatusCode =
            { _, _, _, _, _, _, _, _ -> HttpStatusCode.NotFound },
        previewAttachment: suspend (String, String, Long, String, String, Int, suspend (File, String) -> Unit) -> Boolean =
            { _, _, _, _, _, _, _ -> false },
        block: suspend ApplicationTestBuilder.(WebUiAuth) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val path = temporary.newFolder().resolve("auth.preferences_pb")
        val store = WebUiSettingsStore(PreferenceDataStoreFactory.create(scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { path.absolutePath.toPath() },
        ))
        if (hash != null) runBlocking { store.savePasswordHash(hash) }
        val auth = WebUiAuth(store = store, hasher = hasher, clock = { 0L })
        val server = WebUiServer(
            auth = auth,
            readAsset = assets::get,
            syncSession = syncSession,
            clock = { 0L },
            toolImages = toolImages,
            upload = upload,
            previewAttachment = previewAttachment,
        )
        try {
            testApplication {
                application { server.install(this) }
                block(auth)
            }
        } finally { runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join() } }
    }
}
