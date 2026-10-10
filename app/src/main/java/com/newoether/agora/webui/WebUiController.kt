package com.newoether.agora.webui

import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.util.DebugLog
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether the WebUI server is listening. */
internal sealed interface WebUiStatus {
    data object Stopped : WebUiStatus
    data object Starting : WebUiStatus
    data class Running(val port: Int, val https: Boolean) : WebUiStatus
    /** The server could not start, typically because the port is in use. */
    data class Failed(val message: String) : WebUiStatus
}

/**
 * Binds and starts the server, returning once it listens.
 *
 * Deliberately a plain function: inside a coroutine body, `embeddedServer` resolves to Ktor's
 * `CoroutineScope.embeddedServer`, which makes the server a child of that coroutine, so the
 * enclosing `withContext` would never return while the server runs.
 */
internal fun startWebUiEngine(
    port: Int,
    routes: WebUiServer,
    host: String = WebUiController.ANY_HOST,
    extraConnectors: Int = 0,
): EmbeddedServer<*, *> =
    embeddedServer(
        CIO,
        configure = {
            // The first connector takes [port]; each extra one takes a free port on the same host.
            repeat(1 + extraConnectors) { index ->
                connector {
                    this.host = host
                    this.port = if (index == 0) port else 0
                }
            }
        },
    ) {
        routes.install(this)
    }.start(wait = false)

/**
 * Process-scoped owner of the WebUI: settings, authentication and the embedded server.
 *
 * [WebUiService] keeps the process alive and calls [startServer]/[stopServer]; the Settings page
 * calls the `set*` functions. The server listens on every interface (LAN, Tailscale), so it only
 * runs while a password is set. With HTTPS on (the default), [WebUiTlsFront] owns the public port
 * and CIO listens only on loopback.
 */
internal class WebUiController(
    private val appContext: Context,
    private val store: WebUiSettingsStore,
    scope: CoroutineScope,
    private val certificates: WebUiCertificateStore,
    /** Serves one `/api/sync` connection; [WebUiSync.serve] in production. */
    syncSession: suspend (String, ReceiveChannel<String>, suspend (String) -> Unit) -> Unit,
    upload: suspend (String, String, Long, String, String?, String?, Long?, io.ktor.utils.io.ByteReadChannel) -> io.ktor.http.HttpStatusCode,
    previewAttachment: suspend (String, String, Long, String, String, Int, suspend (java.io.File, String) -> Unit) -> Boolean,
    private val hasher: WebUiPasswordHasher = WebUiPasswordHasher(),
    toolImages: WebUiToolImages? = null,
) {
    private val auth = WebUiAuth(store = store, hasher = hasher)
    @Volatile private var theme: WebUiTheme? = null
    @Volatile private var servingHttps = false
    /** CIO port that receives decrypted TLS traffic; a request on any other port is plain HTTP. */
    @Volatile private var tlsBackendPort = NO_PORT
    @Volatile private var publicPort = NO_PORT
    private val routes = WebUiServer(
        auth = auth,
        readAsset = ::readAsset,
        syncSession = syncSession,
        upload = upload,
        previewAttachment = previewAttachment,
        themeCss = { theme?.toCss().orEmpty() },
        readAppFont = ::readAppFont,
        readMonoFont = ::readMonoFont,
        secureCookies = { call -> call.request.local.localPort == tlsBackendPort },
        httpsRedirectPort = { publicPort.takeIf { servingHttps && it > 0 } },
        toolImages = toolImages,
    )
    private val serverLock = Mutex()
    private var engine: EmbeddedServer<*, *>? = null
    private var tlsFront: WebUiTlsFront? = null
    private val _status = MutableStateFlow<WebUiStatus>(WebUiStatus.Stopped)
    private val _fingerprint = MutableStateFlow<String?>(null)

    val status: StateFlow<WebUiStatus> = _status.asStateFlow()
    val enabled: Flow<Boolean> = store.enabled
    val port: Flow<Int> = store.port
    val hasPassword: Flow<Boolean> = store.passwordHash.map { it != null }
    val https: Flow<Boolean> = store.https

    /** SHA-256 fingerprint of the HTTPS certificate, once [loadCertificate] or a start ran. */
    val certificateFingerprint: StateFlow<String?> = _fingerprint.asStateFlow()

    /** Stores the new password's hash and signs every browser out. */
    suspend fun setPassword(password: String) {
        require(password.length >= MIN_PASSWORD_LENGTH) { "Password is too short" }
        val hash = withContext(Dispatchers.Default) { hasher.hash(password) }
        store.savePasswordHash(hash)
    }

    /** Returns false when enabling is refused because no password is set. */
    suspend fun setEnabled(enabled: Boolean): Boolean {
        if (enabled && store.passwordHash.first() == null) return false
        store.saveEnabled(enabled)
        if (enabled) WebUiService.start(appContext) else WebUiService.stop(appContext)
        return true
    }

    /** Saves the port and, if the server is running, moves it to the new port. */
    suspend fun setPort(port: Int) {
        store.savePort(port)
        restartIfRunning()
    }

    /** Switches between HTTPS and plain HTTP, restarting a running server. */
    suspend fun setHttps(enabled: Boolean) {
        store.saveHttps(enabled)
        restartIfRunning()
    }

    /** Loads (creating once) the certificate so Settings can show its fingerprint. */
    suspend fun loadCertificate() {
        val identity = withContext(Dispatchers.IO) { certificates.loadOrCreate() }
        _fingerprint.value = identity.fingerprintSha256
    }

    /** Makes a new certificate; browsers must accept it again. Restarts an HTTPS server. */
    suspend fun regenerateCertificate() {
        val identity = withContext(Dispatchers.IO) { certificates.regenerate() }
        _fingerprint.value = identity.fingerprintSha256
        if (servingHttps) restartIfRunning()
    }

    /** Called while the app is in the foreground: restores a server the user left enabled. */
    suspend fun startIfEnabled() {
        if (store.enabled.first() && store.passwordHash.first() != null &&
            _status.value !is WebUiStatus.Running
        ) {
            WebUiService.start(appContext)
        }
    }

    suspend fun startServer() = serverLock.withLock {
        if (engine != null) return@withLock
        val port = store.port.first()
        val https = store.https.first()
        _status.value = WebUiStatus.Starting
        _status.value = withContext(Dispatchers.IO) {
            try {
                // startHttps records the TLS backend port before the public port opens, so the
                // session cookie of the very first login over TLS is already Secure.
                servingHttps = https
                publicPort = port
                if (https) startHttps(port) else engine = startWebUiEngine(port, routes)
                WebUiStatus.Running(port, https)
            } catch (error: Exception) {
                DebugLog.e(TAG, "WebUI server failed to start on port $port", error)
                closeServer()
                WebUiStatus.Failed(error.localizedMessage ?: error.javaClass.simpleName)
            }
        }
    }

    suspend fun stopServer() = serverLock.withLock {
        if (engine == null) return@withLock
        withContext(Dispatchers.IO) { closeServer() }
        _status.value = WebUiStatus.Stopped
    }

    private suspend fun restartIfRunning() {
        if (serverLock.withLock { engine != null }) {
            stopServer()
            startServer()
        }
    }

    /**
     * CIO on two free loopback ports, with the TLS front on the public port relaying to them:
     * decrypted TLS to the first, plaintext HTTPS redirects to the second.
     */
    private suspend fun startHttps(port: Int) {
        val identity = certificates.loadOrCreate()
        _fingerprint.value = identity.fingerprintSha256
        val backend = startWebUiEngine(port = 0, routes = routes, host = WebUiTlsFront.LOOPBACK, extraConnectors = 1)
        engine = backend
        val (tlsPort, plainPort) = backend.engine.resolvedConnectors().map { it.port }
        tlsBackendPort = tlsPort
        tlsFront = WebUiTlsFront(identity, publicPort = port, tlsBackendPort = tlsPort, plainBackendPort = plainPort)
    }

    /** Closes whatever is open; safe after a partial start. Call under [serverLock]. */
    private fun closeServer() {
        tlsFront?.close()
        tlsFront = null
        engine?.let { running ->
            runCatching { running.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS) }
                .onFailure { DebugLog.e(TAG, "WebUI server failed to stop cleanly", it) }
        }
        engine = null
        servingHttps = false
        tlsBackendPort = NO_PORT
        publicPort = NO_PORT
    }

    /** `http(s)://<address>:<port>` for every non-loopback IPv4 address that is up. */
    fun accessUrls(port: Int, https: Boolean): List<String> {
        val scheme = if (https) "https" else "http"
        return interfaceAddresses().map { "$scheme://${it.hostAddress}:$port" }
    }

    /** Called by the Compose theme; the next page load in the browser uses it. */
    fun publishTheme(theme: WebUiTheme) {
        this.theme = theme
    }

    private fun readAppFont(): ByteArray? = runCatching {
        when (val font = theme?.font) {
            WebUiFont.AppDefault ->
                appContext.resources.openRawResource(R.font.mioutfit_variable).use { it.readBytes() }
            is WebUiFont.Custom -> File(font.path).takeIf { it.isFile }?.readBytes()
            WebUiFont.System, null -> null
        }
    }.getOrNull()

    private fun readMonoFont(style: String): ByteArray? {
        val font = MONO_FONTS[style] ?: return null
        return runCatching { appContext.resources.openRawResource(font).use { it.readBytes() } }.getOrNull()
    }
    private fun readAsset(path: String): ByteArray? = runCatching {
        appContext.assets.open("$ASSET_ROOT/$path").use { it.readBytes() }
    }.getOrNull()

    companion object {
        const val ANY_HOST = "0.0.0.0"
        const val MIN_PASSWORD_LENGTH = 8
        private const val TAG = "WebUi"
        private const val ASSET_ROOT = "webui"
        private const val NO_PORT = -1
        private const val STOP_GRACE_MILLIS = 500L
        private const val STOP_TIMEOUT_MILLIS = 2_000L
        /** The files of the app's MonoFamily (ui/theme/Type.kt) by the style names style.css asks for. */
        private val MONO_FONTS = mapOf(
            "regular" to R.font.jetbrains_mono_regular,
            "bold" to R.font.jetbrains_mono_bold,
            "italic" to R.font.jetbrains_mono_italic,
            "bolditalic" to R.font.jetbrains_mono_bolditalic,
        )

        /** Non-loopback IPv4 addresses of interfaces that are up (LAN, Tailscale, hotspot). */
        fun interfaceAddresses(): List<Inet4Address> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .distinct()
        }.getOrDefault(emptyList())
    }
}
