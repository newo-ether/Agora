package com.newoether.agora.api.openai

import android.content.Context
import android.content.pm.ApplicationInfo
import com.newoether.agora.api.ModelFetchHttpException
import com.newoether.agora.util.DebugLog
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class RequestyProviderModelFetchTest {
    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    @Test
    fun managedPoliciesAreListedBeforeTheFullCatalogWithoutDuplicates() = withServer(
        managed = 200 to """{"object":"list","data":[{"id":"gpt-5-mini"},{"id":"claude-sonnet-4-5"}]}""",
        catalog = 200 to """{"object":"list","data":[{"id":"openai/gpt-4o-mini"},{"id":"claude-sonnet-4-5"},{"id":"anthropic/claude-sonnet-4-5"}]}""",
    ) { server ->
        val models = runBlocking { RequestyProvider().fetchModels("secret", server.baseUrl) }

        assertEquals(
            listOf("claude-sonnet-4-5", "gpt-5-mini", "anthropic/claude-sonnet-4-5", "openai/gpt-4o-mini"),
            models,
        )
        assertEquals(listOf("/v1/models/managed", "/v1/models"), server.paths)
        assertTrue(server.authorizations.all { it == "Bearer secret" })
    }

    @Test
    fun managedFailureFallsBackToTheCatalog() = withServer(
        managed = 500 to """{"error":{"message":"unavailable"}}""",
        catalog = 200 to """{"object":"list","data":[{"id":"openai/gpt-4o-mini"}]}""",
    ) { server ->
        val models = runBlocking { RequestyProvider().fetchModels("secret", server.baseUrl) }

        assertEquals(listOf("openai/gpt-4o-mini"), models)
    }

    @Test
    fun catalogFailureStillReturnsManagedPolicies() = withServer(
        managed = 200 to """{"object":"list","data":[{"id":"claude-sonnet-4-5"}]}""",
        catalog = 403 to """{"error":{"origin":"router","message":"Invalid authorization token"}}""",
    ) { server ->
        val models = runBlocking { RequestyProvider().fetchModels("bad", server.baseUrl) }

        assertEquals(listOf("claude-sonnet-4-5"), models)
    }

    @Test
    fun bothCatalogsFailingSurfacesTheCatalogError() = withServer(
        managed = 500 to """{"error":{"message":"unavailable"}}""",
        catalog = 403 to """{"error":{"origin":"router","message":"Invalid authorization token"}}""",
    ) { server ->
        try {
            runBlocking { RequestyProvider().fetchModels("bad", server.baseUrl) }
            fail("Expected the catalog failure to propagate")
        } catch (error: ModelFetchHttpException) {
            assertEquals(403, error.statusCode)
        }
    }

    private fun withServer(
        managed: Pair<Int, String>,
        catalog: Pair<Int, String>,
        test: (ModelServer) -> Unit,
    ) {
        ModelServer(managed, catalog).use(test)
    }

    private class ModelServer(
        managed: Pair<Int, String>,
        catalog: Pair<Int, String>,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val paths = CopyOnWriteArrayList<String>()
        val authorizations = CopyOnWriteArrayList<String>()
        val baseUrl = "http://127.0.0.1:${server.address.port}/v1"

        init {
            server.createContext("/") { exchange ->
                paths += exchange.requestURI.path
                exchange.requestHeaders.getFirst("Authorization")?.let { authorizations += it }
                val (status, body) = if (exchange.requestURI.path.endsWith("/managed")) managed else catalog
                val response = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }
}
