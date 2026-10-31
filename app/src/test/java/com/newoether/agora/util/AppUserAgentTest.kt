package com.newoether.agora.util

import com.newoether.agora.api.HttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AppUserAgentTest {
    private val userAgents = CopyOnWriteArrayList<List<String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { exchange ->
            userAgents += exchange.requestHeaders["User-Agent"].orEmpty()
            val response = "{}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
    }

    @After
    fun restoreDefaultIdentity() {
        AppUserAgent.initialize(null)
        server.stop(0)
    }

    @Test
    fun requestsThroughTheSharedClientIdentifyAgora() {
        AppUserAgent.initialize("2.1.0")

        HttpClient.getTextResponse(endpoint())

        assertEquals(listOf("Agora/2.1.0"), userAgents.single())
    }

    @Test
    fun callerUserAgentIsNotOverridden() {
        AppUserAgent.initialize("2.1.0")

        HttpClient.getTextResponse(
            endpoint(),
            mapOf("User-Agent" to Constants.WEB_FETCH_USER_AGENT),
        )

        assertEquals(listOf(Constants.WEB_FETCH_USER_AGENT), userAgents.single())
    }

    private fun endpoint(): String = "http://127.0.0.1:${server.address.port}/v1/models"
}
