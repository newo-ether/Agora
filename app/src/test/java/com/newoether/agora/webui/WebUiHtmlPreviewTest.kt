package com.newoether.agora.webui

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
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
    fun capabilityDocumentsCarryResponseSandboxAndOnlyBlockedProbeHasConnectionPolicy() = webUi { _, token ->
        val id = createId(token)
        val control = client.get("/html-preview/$id/control")
        val blocked = client.get("/html-preview/$id/blocked")
        for (response in listOf(control, blocked)) {
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
        }
        assertNull(control.headers["Connection-Allowlist"])
        assertTrue(blocked.headers["Connection-Allowlist"]!!.contains("/html-preview/$id/resource/*"))
        assertTrue(blocked.headers["Connection-Allowlist"]!!.contains("webrtc=block"))
        assertFalse(control.bodyAsText().contains(token))
        assertFalse(blocked.bodyAsText().contains(token))
        assertEquals("DENY", client.get("/").headers["X-Frame-Options"])
        assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/00000000-0000-0000-0000-000000000000/blocked").status)
    }

    @Test
    fun releaseIsBoundToCreatingSessionAndRevokesBothDocumentAndResources() = webUi { auth, token ->
        val id = createId(token)
        val other = (auth.login("pw") as WebUiLoginResult.Success).sessionToken
        assertEquals(HttpStatusCode.NoContent, release(other, id).status)
        assertEquals(HttpStatusCode.OK, client.get("/html-preview/$id/blocked").status)
        assertEquals(HttpStatusCode.NoContent, release(token, id).status)
        assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/$id/blocked").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/$id/resource/unapproved.invalid/file.js").status)
        assertEquals(HttpStatusCode.NoContent, release(token, id).status)
    }

    @Test
    fun logoutRevokesAnExistingCapabilityWithoutRequiringBrowserCleanup() = webUi { auth, token ->
        val id = createId(token)
        auth.logout(token)
        assertEquals(HttpStatusCode.NotFound, client.get("/html-preview/$id/blocked").status)
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

    private fun webUi(block: suspend ApplicationTestBuilder.(WebUiAuth, String) -> Unit) {
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
                        syncSession = { _, _, _ -> },
                    ).install(this)
                }
                block(auth, token)
            }
        } finally {
            runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join() }
        }
    }
}
