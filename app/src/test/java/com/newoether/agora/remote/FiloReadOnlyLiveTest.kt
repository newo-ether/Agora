package com.newoether.agora.remote

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in, read-only production encrypted-client verification. Never dispatches native input. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class FiloReadOnlyLiveTest {
    @Test fun claudeCatalogHistoryAndFirstEventDecodeThroughProductionClient() = runBlocking {
        val configPath = System.getenv("FILO_READ_CONFIG_FILE")
        val tokenPath = System.getenv("FILO_READ_TOKEN_FILE")
        assumeTrue(configPath != null && tokenPath != null)
        val config = Json.parseToJsonElement(File(configPath!!).readText()).jsonObject
        val endpoint = "http://${config.getValue("bind").jsonPrimitive.content}:${config.getValue("port").jsonPrimitive.content}/"
        val client = FiloClient(endpoint, File(tokenPath!!).readText().trim())
        client.connect()
        val adapter = client.adapters.orEmpty().single { it.id == "claude-code" }
        val scoped = client.scoped(RemoteAccessPath(adapter.id, nativeScopeId = adapter.nativeScopeId))
        val catalog = scoped.sessions()
        assertTrue("Qualification needs an existing Claude session", catalog.sessions.isNotEmpty())
        val id = catalog.sessions.first().id
        val page = scoped.conversation(id)
        assertEquals(page.messages.map { it.id }, page.nodes.map { it.id })
        val event = withTimeout(30000) { scoped.events(id).first() }
        assertEquals(event.messages.map { it.id }, event.nodes.map { it.id })
        assertTrue(page.messages.all { it.timestamp == null || it.timestamp >= 1_000_000_000_000L })
    }
}
