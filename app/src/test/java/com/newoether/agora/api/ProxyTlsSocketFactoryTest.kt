package com.newoether.agora.api

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProxyTlsSocketFactory] is the only thing standing between a user's proxy password and the
 * local network once the HTTPS proxy type is selected: if the upgrade is skipped, OkHttp
 * happily sends CONNECT and Proxy-Authorization in cleartext to the same port. These tests pin
 * down when the upgrade is and is not attempted without needing a certificate — a TLS
 * ClientHello is recognisable by its first record byte (0x16), and a plaintext server is
 * enough to observe it.
 */
class ProxyTlsSocketFactoryTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val executor = Executors.newSingleThreadExecutor()

    @Test
    fun noActiveTargetLeavesTheSocketPlain() {
        ServerSocket(0, 1, loopback).use { server ->
            val echoed = executor.submit(Callable { server.accept().use { it.echoFirstByte() } })
            val socket = ProxyTlsSocketFactory { null }.createSocket()
            socket.connect(InetSocketAddress(loopback, server.localPort), 5_000)
            socket.getOutputStream().apply { write('p'.code); flush() }
            assertEquals('p'.code, socket.getInputStream().read())
            assertEquals('p'.code, echoed.get(5, TimeUnit.SECONDS))
            socket.close()
            assertTrue(socket.isClosed)
        }
    }

    @Test
    fun endpointOtherThanTheProxyLeavesTheSocketPlain() {
        ServerSocket(0, 1, loopback).use { server ->
            val echoed = executor.submit(Callable { server.accept().use { it.echoFirstByte() } })
            val target = ProxyTlsSocketFactory.Target(loopback.hostAddress, server.localPort + 1)
            val socket = ProxyTlsSocketFactory { target }.createSocket()
            socket.connect(InetSocketAddress(loopback, server.localPort), 5_000)
            socket.getOutputStream().apply { write('p'.code); flush() }
            assertEquals('p'.code, socket.getInputStream().read())
            assertEquals('p'.code, echoed.get(5, TimeUnit.SECONDS))
            socket.close()
        }
    }

    @Test
    fun proxyEndpointStartsTlsAndFailsClosedAgainstAPlaintextServer() {
        ServerSocket(0, 1, loopback).use { server ->
            // Read one byte of whatever the client sends first, then hang up.
            val firstByte = executor.submit(Callable { server.accept().use { it.getInputStream().read() } })
            val target = ProxyTlsSocketFactory.Target(loopback.hostAddress, server.localPort)
            val socket = ProxyTlsSocketFactory { target }.createSocket()
            val error = runCatching {
                socket.connect(InetSocketAddress(loopback.hostAddress, server.localPort), 5_000)
            }.exceptionOrNull()

            assertEquals(0x16, firstByte.get(5, TimeUnit.SECONDS)) // TLS handshake record
            assertTrue("expected an IOException, got $error", error is IOException)
            assertTrue("a failed proxy handshake must not leave a usable plaintext socket", socket.isClosed)
        }
    }

    private fun java.net.Socket.echoFirstByte(): Int {
        val b = getInputStream().read()
        getOutputStream().apply { write(b); flush() }
        return b
    }
}
