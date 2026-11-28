package com.newoether.agora.webui

import android.app.Application
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
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
class WebUiMarkdownVideoRouteTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun routeRequiresSessionAndOwnedVideoBeforeStreamingOneRange() {
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
        val link = "shell://Local%20Sandbox/home/agora/clip.mp4"
        val context = ApplicationProvider.getApplicationContext<Application>()
        // `/home/agora` is the sandbox guest home, backed by `filesDir/sandbox-home` itself.
        val directory = File(context.filesDir, "sandbox-home").apply { mkdirs() }
        val bytes = ByteArray(8) { (it + 1).toByte() }
        File(directory, "clip.mp4").writeBytes(bytes)
        val videos = WebUiMarkdownVideos(context, devices = { emptyList() }) { conversation, message ->
            if (conversation == "c" && message == "m") ChatMessage(id = "m", text = "![clip]($link)",
                participant = Participant.MODEL, status = MessageStatus.SUCCESS) else null
        }
        val route = "/api/markdown-videos/c/m?link=" + URLEncoder.encode(link, "UTF-8")
        try {
            testApplication {
                application { WebUiServer(auth, readAsset = { byteArrayOf() },
                    syncSession = { _, _, _ -> }, markdownVideos = videos).install(this) }
                assertEquals(HttpStatusCode.Forbidden, client.get(route).status)
                val response = client.get(route) { header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token") }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                assertEquals("video/mp4", response.headers[HttpHeaders.ContentType])
                assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
                assertArrayEquals(bytes, response.bodyAsBytes())
                val ranged = client.get(route) {
                    header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                    header(HttpHeaders.Range, "bytes=2-3")
                }
                assertEquals(HttpStatusCode.PartialContent, ranged.status)
                assertEquals("bytes 2-3/8", ranged.headers[HttpHeaders.ContentRange])
                assertArrayEquals(bytes.copyOfRange(2, 4), ranged.bodyAsBytes())
                val unsatisfiable = client.get(route) {
                    header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                    header(HttpHeaders.Range, "bytes=9-")
                }
                assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, unsatisfiable.status)
                assertEquals("bytes */8", unsatisfiable.headers[HttpHeaders.ContentRange])
                assertEquals(HttpStatusCode.NotFound, client.get(route.replace("/c/m?", "/other/m?")) {
                    header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                }.status)
                assertEquals(HttpStatusCode.NotFound, client.get("/api/markdown-videos/c/m?link=shell%3A%2F%2FQuantum%2FF%3A%2Fprivate.mp4") {
                    header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                }.status)
                auth.revokeAllSessions()
                assertEquals(HttpStatusCode.Forbidden, client.get(route) {
                    header(HttpHeaders.Cookie, "${WebUiServer.SESSION_COOKIE}=$token")
                }.status)
            }
        } finally {
            runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join() }
        }
    }
}
