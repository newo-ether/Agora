package com.newoether.agora.api

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.nio.channels.SocketChannel
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Socket factory that lets OkHttp use an HTTPS forward proxy, i.e. a proxy that itself
 * terminates TLS (Caddy `forward_proxy`, Squid `https_port`, Envoy, ...). With such a proxy
 * the `CONNECT` request and the `Proxy-Authorization` header travel encrypted, so the
 * credentials and the destination hostnames are hidden from the network path.
 *
 * OkHttp has no native notion of TLS-to-proxy: it only distinguishes plaintext HTTP proxies
 * from SOCKS. For an HTTP-type [java.net.Proxy] it obtains the raw socket from the client's
 * [SocketFactory], sets the read timeout, calls `connect()`, and then speaks HTTP over that
 * socket (`CONNECT` for https:// targets, absolute-form requests for http:// ones). That is
 * enough of a seam: when the endpoint being connected is the configured HTTPS proxy, the
 * freshly connected TCP socket is upgraded to TLS before its streams are handed back.
 * OkHttp's own handshake with the destination is done by wrapping this socket again
 * (`sslSocketFactory.createSocket(rawSocket, host, port, true)`), so the destination TLS
 * session simply nests inside the proxy TLS session.
 *
 * [target] is read live on every connect so proxy settings can change without rebuilding
 * the client, matching how [HttpClient] treats its proxy selector.
 */
internal class ProxyTlsSocketFactory(
    private val target: () -> Target?,
) : SocketFactory() {
    /** The proxy endpoint that should receive TLS, or null when TLS-to-proxy is not active. */
    data class Target(val host: String, val port: Int)

    override fun createSocket(): Socket = ProxyTlsSocket(target)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().also {
            it.bind(InetSocketAddress(localHost, localPort))
            it.connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().also {
            it.bind(InetSocketAddress(localAddress, localPort))
            it.connect(InetSocketAddress(address, port))
        }
}

/**
 * A plain TCP [Socket] that, once connected, transparently switches its streams to a TLS
 * session when the remote endpoint is the active HTTPS proxy. Every other operation is
 * delegated to the underlying TCP socket so timeouts, options and close semantics behave
 * exactly as OkHttp expects from a socket it created itself.
 */
private class ProxyTlsSocket(
    private val target: () -> ProxyTlsSocketFactory.Target?,
) : Socket() {
    private val tcp = Socket()

    @Volatile
    private var tls: SSLSocket? = null

    /** The socket whose streams carry application data. */
    private val io: Socket get() = tls ?: tcp

    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        tcp.connect(endpoint, timeout)
        val proxy = target() ?: return
        val remote = endpoint as? InetSocketAddress ?: return
        if (!isProxyEndpoint(remote, proxy)) return
        try {
            tls = handshakeWithProxy(proxy, timeout)
        } catch (e: IOException) {
            closeQuietly()
            throw e
        } catch (e: RuntimeException) {
            closeQuietly()
            throw IOException("TLS handshake with proxy ${proxy.host}:${proxy.port} failed", e)
        }
    }

    private fun handshakeWithProxy(proxy: ProxyTlsSocketFactory.Target, connectTimeout: Int): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        // autoClose = true: closing the TLS socket closes the TCP socket underneath it.
        val ssl = factory.createSocket(tcp, proxy.host, proxy.port, true) as SSLSocket
        // Ask the TLS stack to verify the proxy certificate against the configured host
        // (RFC 2818 rules; Android's TrustManagerImpl honours this since API 24). No ALPN is
        // offered, so the proxy talks HTTP/1.1, which is what OkHttp uses for CONNECT.
        ssl.setSSLParameters(ssl.getSSLParameters().also { it.endpointIdentificationAlgorithm = "HTTPS" })

        // OkHttp sets soTimeout to the (long) read timeout before connect(); bound the
        // handshake by the connect timeout instead, then restore.
        val previousTimeout = tcp.soTimeout
        if (connectTimeout > 0) tcp.soTimeout = connectTimeout
        try {
            ssl.startHandshake()
        } finally {
            try {
                tcp.soTimeout = previousTimeout
            } catch (_: SocketException) {
                // Socket already closed by a failed handshake; the caller closes it anyway.
            }
        }
        return ssl
    }

    /** True when [remote] (as resolved by OkHttp) is the configured proxy endpoint. */
    private fun isProxyEndpoint(remote: InetSocketAddress, proxy: ProxyTlsSocketFactory.Target): Boolean {
        if (remote.port != proxy.port) return false
        val host = proxy.host.trim('[', ']')
        if (remote.hostString.equals(host, ignoreCase = true)) return true
        val address = remote.address ?: return false
        if (address.hostAddress == host) return true
        // OkHttp resolves the proxy hostname before connecting, so compare against the
        // resolved addresses (served from the platform DNS cache OkHttp already populated).
        return try {
            InetAddress.getAllByName(host).any { it == address }
        } catch (_: IOException) {
            false
        }
    }

    private fun closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        }
    }

    // ── Delegation ───────────────────────────────────────────────────────

    override fun getInputStream(): InputStream = io.getInputStream()
    override fun getOutputStream(): OutputStream = io.getOutputStream()

    override fun close() {
        try {
            tls?.close()
        } catch (_: IOException) {
            // A failed close_notify must not keep the TCP socket open.
        } finally {
            tcp.close()
        }
    }

    override fun bind(bindpoint: SocketAddress?) = tcp.bind(bindpoint)
    override fun getChannel(): SocketChannel? = null
    override fun getInetAddress(): InetAddress? = tcp.inetAddress
    override fun getLocalAddress(): InetAddress = tcp.localAddress
    override fun getPort(): Int = tcp.port
    override fun getLocalPort(): Int = tcp.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = tcp.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = tcp.localSocketAddress

    override fun setSoTimeout(timeout: Int) = tcp.setSoTimeout(timeout)
    override fun getSoTimeout(): Int = tcp.soTimeout
    override fun setTcpNoDelay(on: Boolean) = tcp.setTcpNoDelay(on)
    override fun getTcpNoDelay(): Boolean = tcp.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) = tcp.setSoLinger(on, linger)
    override fun getSoLinger(): Int = tcp.soLinger
    override fun setKeepAlive(on: Boolean) = tcp.setKeepAlive(on)
    override fun getKeepAlive(): Boolean = tcp.keepAlive
    override fun setReuseAddress(on: Boolean) = tcp.setReuseAddress(on)
    override fun getReuseAddress(): Boolean = tcp.reuseAddress
    override fun setOOBInline(on: Boolean) = tcp.setOOBInline(on)
    override fun getOOBInline(): Boolean = tcp.oobInline
    override fun setTrafficClass(tc: Int) = tcp.setTrafficClass(tc)
    override fun getTrafficClass(): Int = tcp.trafficClass
    override fun setSendBufferSize(size: Int) = tcp.setSendBufferSize(size)
    override fun getSendBufferSize(): Int = tcp.sendBufferSize
    override fun setReceiveBufferSize(size: Int) = tcp.setReceiveBufferSize(size)
    override fun getReceiveBufferSize(): Int = tcp.receiveBufferSize
    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) =
        tcp.setPerformancePreferences(connectionTime, latency, bandwidth)
    override fun sendUrgentData(data: Int) = tcp.sendUrgentData(data)

    override fun shutdownInput() = tcp.shutdownInput()
    override fun shutdownOutput() = tcp.shutdownOutput()
    override fun isConnected(): Boolean = tcp.isConnected
    override fun isBound(): Boolean = tcp.isBound
    override fun isClosed(): Boolean = tcp.isClosed
    override fun isInputShutdown(): Boolean = tcp.isInputShutdown
    override fun isOutputShutdown(): Boolean = tcp.isOutputShutdown

    override fun toString(): String = "ProxyTlsSocket(tls=${tls != null}, $tcp)"
}
