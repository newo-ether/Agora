package com.newoether.agora.webui

import android.app.Application
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolImageAttachment
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebUiMarkdownImageRouteTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun routeRequiresCurrentSessionAndExactMessageSourceBeforeServingRasterBytes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val path = temporary.newFolder().resolve("auth.preferences_pb")
        val store = WebUiSettingsStore(PreferenceDataStoreFactory.create(scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { path.absolutePath.toPath() }))
        val hasher = WebUiPasswordHasher(iterations = 1_000)
        val auth = WebUiAuth(store, hasher)
        val token = runBlocking {
            store.savePasswordHash(hasher.hash("pw"))
            (auth.login("pw") as WebUiLoginResult.Success).sessionToken
        }
        val bytes = webUiSameLengthPngs().first
        val image = temporary.newFile("image.png").apply { writeBytes(bytes) }
        val link = "shell://Local%20Sandbox/home/a%20b.png"
        var loads = 0
        val images = WebUiToolImages(temporary.root, markdownImage = {
            loads++
            ToolImageAttachment(image.path, "image/png", bytes.size.toLong())
        }) { conversation, message ->
            if (conversation == "c" && message == "m") ChatMessage(id = "m", text = "![x]($link)",
                participant = Participant.MODEL, status = MessageStatus.SUCCESS) else null
        }
        val route = "/api/markdown-images/c/m?link=" + URLEncoder.encode(link, "UTF-8")
        try {
            testApplication {
                application { WebUiServer(auth, readAsset = { byteArrayOf() },
                    syncSession = { _, _, _ -> }, toolImages = images).install(this) }
                assertEquals(HttpStatusCode.Forbidden, client.get(route).status)
                assertEquals(0, loads)
                assertEquals(HttpStatusCode.Forbidden, client.get(route) {
                    header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                    header(HttpHeaders.Origin, "https://other.invalid")
                }.status)
                val response = client.get(route) { header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token") }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                assertEquals("image/png", response.headers[HttpHeaders.ContentType])
                assertArrayEquals(bytes, response.bodyAsBytes())
                assertEquals(1, loads)
                assertEquals(HttpStatusCode.NotFound, client.get(route.replace("/c/m?", "/other/m?")) {
                    header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                }.status)
                assertEquals(HttpStatusCode.NotFound, client.get("/api/markdown-images/c/m?link=shell%3A%2F%2FQuantum%2Fprivate.png") {
                    header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                }.status)
                assertEquals(1, loads)
                auth.revokeAllSessions()
                assertEquals(HttpStatusCode.Forbidden, client.get(route) {
                    header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                }.status)
                assertEquals(1, loads)
            }
        } finally {
            runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join() }
        }
    }
}
