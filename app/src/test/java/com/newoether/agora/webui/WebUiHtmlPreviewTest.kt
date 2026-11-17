package com.newoether.agora.webui

import com.newoether.agora.model.ToolImageAttachment
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.robolectric.annotation.GraphicsMode
import android.app.Application
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebUiHtmlPreviewTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun admissionRequiresSessionSameOriginJsonAndBoundedBody() = webUi { _, token ->
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/html-preview") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/html-preview") {
            header(HttpHeaders.Cookie, cookie(token))
            header(HttpHeaders.Origin, "https://other.invalid")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/html-preview") {
            header(HttpHeaders.Cookie, cookie(token))
            contentType(ContentType.Text.Plain)
            setBody("{}")
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/html-preview") {
            header(HttpHeaders.Cookie, cookie(token))
            contentType(ContentType.Application.Json)
            setBody("x".repeat(129))
        }.status)
        assertEquals(HttpStatusCode.OK, create(token).status)
    }

    @Test
    fun receiverDocumentCarriesResponseSandboxAndConnectionPolicyWithoutProbe() = webUi { _, token ->
        val id = createId(token)
        val response = client.get("/html-preview/$id/frame")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("SAMEORIGIN", response.headers["X-Frame-Options"])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        val policy = response.headers["Content-Security-Policy"]!!
        assertTrue(policy.contains("sandbox allow-scripts"))
        assertFalse(policy.contains("allow-same-origin"))
        assertTrue(policy.contains("connect-src 'none'"))
        assertTrue(policy.contains("frame-src 'none'"))
        assertTrue(policy.contains("form-action 'none'"))
        assertTrue(policy.contains("/html-preview/$id/resource/"))
        assertTrue(response.headers["Connection-Allowlist"]!!.contains("/html-preview/$id/resource/*"))
        assertTrue(response.headers["Connection-Allowlist"]!!.contains("webrtc=block"))
        val body = response.bodyAsText()
        assertFalse(body.contains(token))
        assertFalse(body.contains("RTCPeerConnection"))
        assertTrue(body.contains("document.write"))
        // The removed probe routes no longer exist.
        assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/$id/control").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/$id/blocked").status)
        assertEquals("DENY", client.get("/").headers["X-Frame-Options"])
        assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/00000000-0000-0000-0000-000000000000/frame").status)
    }

    @Test
    fun releaseIsBoundToCreatingSessionAndRevokesBothDocumentAndResources() = webUi { auth, token ->
        val id = createId(token)
        val other = (auth.login("pw") as WebUiLoginResult.Success).sessionToken
        assertEquals(HttpStatusCode.NoContent, release(other, id).status)
        assertEquals(HttpStatusCode.OK, client.get("/html-preview/$id/frame").status)
        assertEquals(HttpStatusCode.NoContent, release(token, id).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/$id/frame").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/$id/resource/unapproved.invalid/file.js").status)
        assertEquals(HttpStatusCode.NoContent, release(token, id).status)
    }

    @Test
    fun logoutRevokesAnExistingCapabilityWithoutRequiringBrowserCleanup() = webUi { auth, token ->
        val id = createId(token)
        auth.logout(token)
        assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/$id/frame").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/$id/resource/unapproved.invalid/file.js").status)
        assertEquals(HttpStatusCode.Forbidden, create(token).status)
    }

    @Test
    fun resourceRouteRejectsUnapprovedHostsPathsAndMethodsWithoutNetworkFallback() = webUi { _, token ->
        val id = createId(token)
        val prefix = "/html-preview/$id/resource/"
        for (path in listOf(
            "unapproved.invalid/file.js",
            "127.0.0.1/file.js",
            "cdn.jsdelivr.net/private.html",
            "tile.openstreetmap.org/not/a/tile.png",
            "cdn.jsdelivr.net/file.js?secret=1",
        )) {
            val response = client.get(prefix + path)
            assertEquals(path, HttpStatusCode.Forbidden, response.status)
            assertEquals("DENY", response.headers["X-Frame-Options"])
            assertEquals("", response.bodyAsText())
        }
        assertTrue(client.post(prefix + "unapproved.invalid/file.js") {
            setBody("payload")
        }.status.value in 400..499)
    }

    @Test
    fun liveCardLimitIsBoundedAndExplicitReleaseMakesRoom() = webUi { _, token ->
        val ids = List(16) { createId(token) }
        assertEquals(16, ids.toSet().size)
        assertEquals(HttpStatusCode.TooManyRequests, create(token).status)
        release(token, ids.first())
        assertEquals(HttpStatusCode.OK, create(token).status)
    }

    @Test
    fun declaredShellImagesUsePerCardRasterCapabilitiesWithoutCors() {
        val bytes = webUiSameLengthPngs().first
        val file = temporary.newFile("preview.png").apply { writeBytes(bytes) }
        val link = "shell://Local%20Sandbox/home/a%20b.png"
        val loads = mutableListOf<String>()
        webUi(loadImage = { value ->
            loads += value
            ToolImageAttachment(file.path, "text/html", 1)
        }) { _, token ->
            val admission = declare(token, listOf(link, link))
            assertEquals(HttpStatusCode.OK, admission.status)
            val json = Json.parseToJsonElement(admission.bodyAsText()).jsonObject
            val id = json.getValue("id").jsonPrimitive.content
            val images = json.getValue("images").jsonObject
            assertEquals(setOf(link), images.keys)
            val route = images.getValue(link).jsonPrimitive.content
            assertEquals("/html-preview/$id/image/0", route)
            assertTrue(loads.isEmpty())
            val response = client.get(route)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("image/png", response.headers[HttpHeaders.ContentType])
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("DENY", response.headers["X-Frame-Options"])
            assertEquals(null, response.headers["Access-Control-Allow-Origin"])
            assertArrayEquals(bytes, response.bodyAsBytes())
            assertEquals(listOf(link), loads)
            assertEquals(HttpStatusCode.NotFound, client.get(route + "?path=/private.png").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/$id/image/1").status)
            val other = createId(token)
            assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/$other/image/0").status)
            release(token, id)
            assertEquals(HttpStatusCode.NotFound, client.get(route).status)
            assertEquals(listOf(link), loads)
        }
    }

    @Test
    fun declarationsRejectNonShellOversizedAndUnexpectedPayloads() = webUi { _, token ->
        val invalid = listOf(
            "[]", "{\"html\":\"<img>\"}", "{\"images\":null}", "{\"images\":[1]}",
            Json.encodeToString(mapOf("images" to listOf("https://example.invalid/a.png"))),
            Json.encodeToString(mapOf("images" to listOf("shell://Quantum/%GG.png"))),
            Json.encodeToString(mapOf("images" to listOf("shell://Quantum/" + "x".repeat(WebUiHtmlPreview.MAX_LINK_CHARS)))),
            Json.encodeToString(mapOf("images" to List(17) { "shell://Quantum/F:/image-$it.png" })),
            " ".repeat(WebUiHtmlPreview.MAX_BODY_BYTES + 1),
        )
        for (body in invalid) {
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/html-preview") {
                header(HttpHeaders.Cookie, cookie(token))
                contentType(ContentType.Application.Json)
                setBody(body)
            }.status)
        }
        assertEquals(HttpStatusCode.OK, declare(token, List(16) { "shell://Quantum/F:/image-$it.png" }).status)
    }

    @Test
    fun missingAndNonRasterImagesCannotServeClaimedImageMetadata() {
        val file = temporary.newFile("fake.png").apply { writeText("<svg xmlns='http://www.w3.org/2000/svg'/>") }
        webUi(loadImage = { link ->
            if (link.contains("missing")) null else ToolImageAttachment(file.path, "image/png", file.length())
        }) { _, token ->
            val admission = declare(token, listOf("shell://Unknown/home/missing.png", "shell://Quantum/F:/fake.png"))
            val images = Json.parseToJsonElement(admission.bodyAsText()).jsonObject.getValue("images").jsonObject
            for (route in images.values) {
                val response = client.get(route.jsonPrimitive.content)
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertEquals(null, response.headers["Access-Control-Allow-Origin"])
                assertTrue(response.bodyAsBytes().isEmpty())
            }
        }
    }

    @Test
    fun releaseCancelsSuspendedPreparationAndRevokesImageCapability() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        webUi(loadImage = {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }) { _, token ->
            coroutineScope {
                val admission = declare(token, listOf("shell://Quantum/F:/slow.png"))
                val json = Json.parseToJsonElement(admission.bodyAsText()).jsonObject
                val id = json.getValue("id").jsonPrimitive.content
                val route = json.getValue("images").jsonObject.values.single().jsonPrimitive.content
                val request = async { client.get(route) }
                withTimeout(5_000) { started.await() }
                assertEquals(HttpStatusCode.NoContent, release(token, id).status)
                withTimeout(5_000) { cancelled.await() }
                assertEquals(HttpStatusCode.NotFound, withTimeout(5_000) { request.await() }.status)
                assertEquals(HttpStatusCode.NotFound, client.get(route).status)
            }
        }
    }

    @Test
    fun sessionRevocationCancelsPreparationBeforeReadingTheNextImage() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val loads = java.util.concurrent.CopyOnWriteArrayList<String>()
        val first = "shell://Quantum/F:/slow.png"
        webUi(loadImage = { link ->
            loads += link
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }) { auth, token ->
            coroutineScope {
                val admission = declare(token, listOf(first, "shell://Quantum/F:/second.png"))
                val json = Json.parseToJsonElement(admission.bodyAsText()).jsonObject
                val id = json.getValue("id").jsonPrimitive.content
                val route = json.getValue("images").jsonObject.getValue(first).jsonPrimitive.content
                val request = async { client.get(route) }
                withTimeout(5_000) { started.await() }
                auth.revokeAllSessions()
                withTimeout(5_000) { cancelled.await() }
                assertEquals(HttpStatusCode.NotFound, withTimeout(5_000) { request.await() }.status)
                assertEquals(listOf(first), loads.toList())
                assertEquals(HttpStatusCode.NotFound, client.get(route).status)
                assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/$id/frame").status)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.declare(token: String, images: List<String>) = client.post("/api/html-preview") {
        header(HttpHeaders.Cookie, cookie(token))
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(mapOf("images" to images)))
    }

    private fun cookie(token: String) = "${WebUiServer.SESSION_COOKIE}=$token"

    private suspend fun ApplicationTestBuilder.create(token: String) = client.post("/api/html-preview") {
        header(HttpHeaders.Cookie, cookie(token))
        contentType(ContentType.Application.Json)
        setBody("{}")
    }

    private suspend fun ApplicationTestBuilder.createId(token: String): String {
        val response = create(token)
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.release(token: String, id: String) = client.post("/api/html-preview?release=$id") {
        header(HttpHeaders.Cookie, cookie(token))
        contentType(ContentType.Application.Json)
        setBody("{}")
    }

    private fun webUi(
        loadImage: suspend (String) -> ToolImageAttachment? = { null },
        block: suspend ApplicationTestBuilder.(WebUiAuth, String) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val path = temporary.newFolder().resolve("auth.preferences_pb")
        val store = WebUiSettingsStore(PreferenceDataStoreFactory.create(
            scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { path.absolutePath.toPath() },
        ))
        val hasher = WebUiPasswordHasher(iterations = 1_000)
        val auth = WebUiAuth(store, hasher)
        val token = runBlocking {
            store.savePasswordHash(hasher.hash("pw"))
            (auth.login("pw") as WebUiLoginResult.Success).sessionToken
        }
        try {
            testApplication {
                application {
                    WebUiServer(auth, readAsset = { "<!doctype html><title>Agora</title>".toByteArray() },
                        syncSession = { _, _, _ -> }, htmlPreviewImage = loadImage,
                    ).install(this)
                }
                block(auth, token)
            }
        } finally {
            runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join() }
        }
    }
}
