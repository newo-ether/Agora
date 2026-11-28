package com.newoether.agora.remote

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class FiloAdapterDiscoveryTest {
    private val token = "a".repeat(64)
    private fun discovery(adapters: List<RemoteAdapter>, extension: String = "1") =
        """{"protocolVersion":2,"extensions":{"multiAdapter":$extension},"adapters":${Json.encodeToString(adapters)}}"""
    private fun <T> fixture(block: (FiloClient, (String) -> Unit) -> T): T {
        var response = "{}"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = applicationFixtureClient("http://127.0.0.1:${server.address.port}/", token)
            return block(client) { response = it }
        } finally { server.stop(0) }
    }

    @Test fun fourInstalledSourcesRetainIndependentNativeStates() = fixture { client, respond ->
        runBlocking {
            val sources = listOf(
                RemoteAdapter("codex-desktop", "codex", "desktop", "connected"),
                RemoteAdapter("codex-cli", "codex", "cli", "disconnected"),
                RemoteAdapter("claude-code", "claude", "cli", "unavailable"),
                RemoteAdapter("claude-desktop", "claude", "desktop", "connecting"),
            )
            respond(discovery(sources))
            assertEquals(RemoteDeviceStatus.CONNECTED, client.connect())
            assertEquals(sources, client.adapters)
            assertEquals(listOf(RemoteDeviceStatus.CONNECTED, RemoteDeviceStatus.IDLE,
                RemoteDeviceStatus.ERROR, RemoteDeviceStatus.CONNECTING), client.adapters!!.map { it.status })
            val offline = sources.map { it.copy(state = "disconnected") }
            respond(discovery(offline))
            assertEquals(RemoteDeviceStatus.IDLE, client.connect())
            assertEquals(offline, client.adapters)
        }
    }

    @Test fun claudeOnlyAndEmptyConfigurationsNeverAcquireLegacyDesktopCapabilities() = fixture { client, respond ->
        runBlocking {
            for (source in listOf(
                RemoteAdapter("claude-code", "claude", "cli", "connected", nativeScopeId = "scope",
                    capabilities = RemoteCapabilities(history = true)),
                RemoteAdapter("claude-desktop", "claude", "desktop", "disconnected", nativeScopeId = "desktop-scope",
                    capabilities = RemoteCapabilities(history = true)),
            )) {
                respond(discovery(listOf(source)))
                assertEquals(RemoteDeviceStatus.IDLE, client.connect())
                assertEquals(listOf(source), client.adapters)
                assertFalse(client.adapters!!.single().capabilities.create)
                assertFalse(client.adapters!!.single().capabilities.stop)
            }
            respond(discovery(emptyList()))
            client.connect()
            assertEquals(emptyList<RemoteAdapter>(), client.adapters)
        }
    }

    @Test fun malformedExtensionsDuplicateIdsAndConfusedFamilyOriginAreRejected() = fixture { client, respond ->
        runBlocking {
            val source = RemoteAdapter("codex-desktop", "codex", "desktop", "connected")
            val claudeDesktop = RemoteAdapter("claude-desktop", "claude", "desktop", "disconnected")
            val sources = listOf(source, source.copy(id = "codex-cli", origin = "cli"),
                RemoteAdapter("claude-code", "claude", "cli", "disconnected"), claudeDesktop)
            val invalid = listOf(discovery(listOf(source), "{}"), discovery(listOf(source), "2"),
                discovery(sources + source.copy(id = "unknown")),
                discovery(listOf(claudeDesktop.copy(family = "codex"))),
                discovery(listOf(claudeDesktop.copy(origin = "cli"))),
                discovery(listOf(source, source)), discovery(listOf(source.copy(family = "claude"))),
                discovery(listOf(source.copy(origin = "cli"))),
                discovery(listOf(source.copy(capabilities = RemoteCapabilities(activeSend = "append")))))
            for (payload in invalid) {
                respond(payload)
                try { client.connect(); fail("Invalid source discovery was accepted") }
                catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun sessionAccessMustMatchTheCapturedScopeAndAllowsWrappedNativeBinding() = fixture { client, respond ->
        runBlocking {
            val scoped = client.scoped(RemoteAccessPath("codex-cli", nativeScopeId = "scope-a"))
            val row = RemoteSession("opaque-native-id", "Task", "", 1,
                access = RemoteSessionAccess("scope-a", "canonical", "x".repeat(5000), RemoteCapabilities(history = true)))
            respond(Json.encodeToString(RemoteSessionPage(listOf(row), null)))
            assertEquals(row, scoped.sessions().sessions.single())
            respond(Json.encodeToString(RemoteSessionPage(listOf(row.copy(
                access = row.access!!.copy(nativeScopeId = "scope-b"))), null)))
            try { scoped.sessions(); fail("Different native scope was accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
