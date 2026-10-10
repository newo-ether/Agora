package com.newoether.agora.webui

import com.newoether.agora.util.DebugLog
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/**
 * HTTPS for the WebUI. Ktor's CIO engine has no TLS, so this listens on the public port,
 * terminates TLS with the platform stack and relays each connection's bytes to CIO, which
 * listens only on loopback. The relay is byte-for-byte, so HTTP keep-alive and WebSocket
 * upgrades pass through unchanged.
 *
 * The first byte of every connection tells TLS (a handshake record, `0x16`) from plain HTTP.
 * Plain HTTP is relayed to [plainBackendPort], whose pre-routing gate redirects to HTTPS
 * without executing authentication or serving application content.
 *
 * Throws from the constructor when the port cannot be bound.
 */
internal class WebUiTlsFront(
    identity: WebUiTlsIdentity,
    publicPort: Int,
    private val tlsBackendPort: Int,
    private val plainBackendPort: Int,
    bindHost: String = WebUiController.ANY_HOST,
) : Closeable {
    private val serverSocket = ServerSocket()
    private val tlsContext: SSLContext
    private val openSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    @Volatile private var closed = false

    /** The bound port; equals the requested one unless it was 0. */
    val localPort: Int get() = serverSocket.localPort

    init {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(identity.keyStore, identity.password) }
            .keyManagers
        tlsContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(bindHost, publicPort), BACKLOG)
        thread(name = "WebUiTlsAccept", isDaemon = true) { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed) {
            val client = try {
                serverSocket.accept()
            } catch (error: IOException) {
                if (closed || serverSocket.isClosed) return
                DebugLog.w(TAG, "WebUI accept failed", error)
                continue
            }
            thread(name = "WebUiTlsConnection", isDaemon = true) { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        val backend = Socket()
        openSockets += client
        openSockets += backend
        try {
            // A client that sends nothing, or never finishes the handshake, is dropped.
            client.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            val first = client.getInputStream().read()
            if (first < 0) return
            if (first == TLS_HANDSHAKE_RECORD) {
                val tls = WebUiTlsConnection(client, tlsContext, ALLOWED_PROTOCOLS, byteArrayOf(first.toByte()))
                tls.handshake()
                client.soTimeout = 0
                backend.connect(InetSocketAddress(LOOPBACK, tlsBackendPort), CONNECT_TIMEOUT_MILLIS)
                relay(
                    upstream = { ignoringClose { tls.readInto(backend.getOutputStream()) } },
                    downstream = { pump(backend.getInputStream()) { data, length -> tls.write(data, length) } },
                    client = client,
                    backend = backend,
                )
            } else {
                client.soTimeout = 0
                backend.connect(InetSocketAddress(LOOPBACK, plainBackendPort), CONNECT_TIMEOUT_MILLIS)
                backend.getOutputStream().write(first)
                relay(
                    upstream = { pump(client.getInputStream()) { data, length -> backend.getOutputStream().write(data, 0, length) } },
                    downstream = { pump(backend.getInputStream()) { data, length -> client.getOutputStream().write(data, 0, length) } },
                    client = client,
                    backend = backend,
                )
            }
        } catch (_: IOException) {
            // Handshake failures and resets are routine for a server on an open network.
        } finally {
            closeQuietly(client, backend)
            openSockets -= client
            openSockets -= backend
        }
    }

    /**
     * Runs both directions until either ends: CIO closes idle keep-alive connections itself,
     * and a closed browser tab ends the upstream side.
     */
    private fun relay(upstream: () -> Unit, downstream: () -> Unit, client: Socket, backend: Socket) {
        val up = thread(name = "WebUiTlsUpstream", isDaemon = true) {
            upstream()
            closeQuietly(client, backend)
        }
        downstream()
        closeQuietly(client, backend)
        up.join()
    }

    /** Copies [input] to [sink] until it ends or either side closes. */
    private fun pump(input: InputStream, sink: (ByteArray, Int) -> Unit) = ignoringClose {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return@ignoringClose
            sink(buffer, read)
        }
    }

    private inline fun ignoringClose(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            // The other side closed.
        }
    }

    private fun closeQuietly(vararg sockets: Socket) {
        sockets.forEach { runCatching { it.close() } }
    }

    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        openSockets.toList().forEach { runCatching { it.close() } }
        openSockets.clear()
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        private const val TAG = "WebUiTls"
        private const val TLS_HANDSHAKE_RECORD = 0x16
        private val ALLOWED_PROTOCOLS = setOf("TLSv1.2", "TLSv1.3")
        private const val BACKLOG = 50
        private const val HANDSHAKE_TIMEOUT_MILLIS = 10_000
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val BUFFER_BYTES = 16 * 1024
    }
}
