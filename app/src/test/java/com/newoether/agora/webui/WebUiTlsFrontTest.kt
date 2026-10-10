package com.newoether.agora.webui

import java.io.File
import java.net.Socket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebUiTlsFrontTest {
    private val dir: File = Files.createTempDirectory("webui-tls").toFile()
    private val identity = WebUiCertificateStore(
        directory = dir,
        seal = { it },
        unseal = { it },
        addresses = { listOf(InetAddress.getByName("127.0.0.1")) },
    ).loadOrCreate()
    @Volatile private var tlsPort = -1
    @Volatile private var publicPort = -1
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = WebUiSettingsStore(PreferenceDataStoreFactory.create(scope = scope,
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
            dir.resolve("auth.preferences_pb").absolutePath.toPath()
        })).also { runBlocking { it.savePasswordHash(WebUiPasswordHasher(iterations = 1_000).hash("correct horse")) } }
    private val routes = WebUiServer(
        auth = WebUiAuth(
            store = store,
            hasher = WebUiPasswordHasher(iterations = 1_000),
        ),
        readAsset = { path -> if (path == WebUiServer.INDEX) "<title>Agora</title>".toByteArray() else null },
        syncSession = { _, _, _ -> },
        // As in WebUiController: only requests on the TLS backend connector get a Secure cookie.
        secureCookies = { call -> call.request.local.localPort == tlsPort },
        httpsRedirectPort = { publicPort },
    )
    private val backend = startWebUiEngine(port = 0, routes = routes, host = WebUiTlsFront.LOOPBACK, extraConnectors = 1)
    private val backendPorts = runBlocking { backend.engine.resolvedConnectors().map { it.port } }
    private val fronts = mutableListOf<WebUiTlsFront>()

    init {
        tlsPort = backendPorts[0]
    }

    private fun front() =
        WebUiTlsFront(
            identity,
            publicPort = 0,
            tlsBackendPort = backendPorts[0],
            plainBackendPort = backendPorts[1],
            bindHost = WebUiTlsFront.LOOPBACK,
        ).also { fronts.add(it); publicPort = it.localPort }

    @After
    fun tearDown() {
        fronts.forEach(WebUiTlsFront::close)
        backend.stop(100, 500)
        runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join() }
        dir.deleteRecursively()
    }

    /** A client that trusts exactly this self-signed certificate, as a browser does after accepting it. */
    private fun openTls(front: WebUiTlsFront, path: String): HttpsURLConnection {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("webui", identity.certificate)
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(trust) }.trustManagers, null)
        }
        return (URL("https://127.0.0.1:${front.localPort}$path").openConnection() as HttpsURLConnection)
            .apply { sslSocketFactory = context.socketFactory }
    }

    private fun openPlain(front: WebUiTlsFront, path: String) =
        (URL("http://127.0.0.1:${front.localPort}$path").openConnection() as HttpURLConnection)
            .apply { instanceFollowRedirects = false }

    private fun HttpURLConnection.login(): HttpURLConnection = apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write("""{"password":"correct horse"}""".toByteArray()) }
    }

    @Test
    fun servesPagesOverTlsWithTheStoredCertificate() {
        val connection = openTls(front(), "/")
        assertEquals(200, connection.responseCode)
        assertTrue(connection.inputStream.bufferedReader().readText().contains("Agora"))
        assertEquals(identity.certificate, connection.serverCertificates.first())
    }

    @Test
    fun loginOverTlsSetsASecureCookie() {
        val connection = openTls(front(), "/api/login").login()
        assertEquals(200, connection.responseCode)
        val cookie = connection.getHeaderField("Set-Cookie")
        assertTrue(cookie, cookie.contains("Secure"))
        assertTrue(cookie, cookie.contains("HttpOnly"))
    }

    @Test
    fun plainHttpRedirectsOnTheSamePortWithoutServingOrAuthenticating() {
        val front = front()
        val page = openPlain(front, "/assets/file%20name.js?q=a%2Fb&x=1")
        assertEquals(307, page.responseCode)
        assertEquals("https://127.0.0.1:${front.localPort}/assets/file%20name.js?q=a%2Fb&x=1", page.getHeaderField("Location"))
        assertEquals("", page.inputStream.bufferedReader().readText())
        val login = openPlain(front, "/api/login").login()
        assertEquals(307, login.responseCode)
        assertEquals("https://127.0.0.1:${front.localPort}/api/login", login.getHeaderField("Location"))
        assertEquals(null, login.getHeaderField("Set-Cookie"))
        runBlocking { assertTrue(store.sessionDigests.first().isEmpty()) }
    }

    @Test
    fun redirectsBrowserHostNamesAndIpv6AndRejectsUnsafeTargets() {
        val front = front()
        fun request(host: String, target: String): String = Socket("127.0.0.1", front.localPort).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
        for (host in listOf("localhost", "192.168.1.5", "[::1]")) {
            val response = request("$host:${front.localPort}", "/?q=a%2Fb")
            assertTrue(response, response.contains("307"))
            assertTrue(response, response.contains("https://$host:${front.localPort}/?q=a%2Fb"))
        }
        for ((host, target) in listOf("user@evil.example" to "/", "localhost:99999" to "/",
            "localhost" to "//evil.example/", "localhost" to "http://evil.example/")) {
            val response = request(host, target)
            assertTrue(response, response.contains("400"))
            assertFalse(response.contains("Location:", ignoreCase = true))
        }
    }
}
