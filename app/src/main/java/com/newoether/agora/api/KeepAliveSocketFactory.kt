package com.newoether.agora.api

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Socket factory that enables TCP keepalive on all connections.
 * Prevents NAT timeout drops on cellular networks during long SSE streams
 * where the server may be silent for 30-60s while generating a response.
 */
object KeepAliveSocketFactory : SocketFactory() {
    private val default = getDefault()

    override fun createSocket(): Socket = default.createSocket().apply { keepAlive = true }

    override fun createSocket(host: String, port: Int): Socket =
        default.createSocket(host, port).apply { keepAlive = true }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        default.createSocket(host, port, localHost, localPort).apply { keepAlive = true }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        default.createSocket(host, port).apply { keepAlive = true }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        default.createSocket(address, port, localAddress, localPort).apply { keepAlive = true }
}
