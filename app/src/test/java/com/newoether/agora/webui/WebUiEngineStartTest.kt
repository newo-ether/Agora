package com.newoether.agora.webui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class WebUiEngineStartTest {
    /**
     * Regression: when the server was built inside the coroutine, Ktor's
     * `CoroutineScope.embeddedServer` made it a child job, so `withContext` never returned and
     * the Settings status stayed on Starting while the server already answered requests.
     */
    @Test
    fun startReturnsInsideCoroutineContext(): Unit = runBlocking {
        val routes = WebUiServer(
            auth = WebUiAuth(store = io.mockk.mockk(relaxed = true), hasher = WebUiPasswordHasher()),
            readAsset = { null },
            syncSession = { _, _, _ -> },
        )
        val engine = withTimeoutOrNull(5_000) {
            withContext(Dispatchers.IO) { startWebUiEngine(port = 0, routes = routes) }
        }
        assertNotNull("server start did not return", engine)
        engine?.stop(100, 500)
    }
}
