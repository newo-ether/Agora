package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.viewmodel.GenerationToolExecutor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellToolMinimalDefinitionsTest {

    private val wireJson = Json { encodeDefaults = true; explicitNulls = false }

    private fun context(
        shellEnabled: Boolean = true,
        webSearchEnabled: Boolean = true,
    ) = GenerationContext(
        shellEnabled = shellEnabled,
        webSearchEnabled = webSearchEnabled,
        sandboxEnabled = true,
    )

    private fun minimalExecutor() = GenerationToolExecutor.forTest(
        listOf(ShellToolProvider(), WebSearchToolProvider()),
    )

    @Test
    fun `minimal set is exactly the two composer switches' tools`() {
        val names = minimalExecutor().minimalDefinitions(context()).map { it.function.name }

        assertEquals(
            setOf("shell_list", "shell_exec", "file_view_image", "web_search", "web_fetch"),
            names.toSet(),
        )
    }

    @Test
    fun `shell switch alone gates the shell trio`() {
        val names = minimalExecutor()
            .minimalDefinitions(context(shellEnabled = false))
            .map { it.function.name }

        assertEquals(setOf("web_search", "web_fetch"), names.toSet())
    }

    @Test
    fun `web search switch alone gates the web pair`() {
        val names = minimalExecutor()
            .minimalDefinitions(context(webSearchEnabled = false))
            .map { it.function.name }

        assertEquals(setOf("shell_list", "shell_exec", "file_view_image"), names.toSet())
    }

    @Test
    fun `minimal shell schema omits durable-job and file families`() {
        val names = ShellToolProvider().minimalDefinitions(context()).map { it.function.name }

        assertEquals(listOf("shell_list", "shell_exec", "file_view_image"), names)
    }

    @Test
    fun `minimal schemas stay within the small-window budget`() {
        val definitions: List<ToolDefinition> = minimalExecutor().minimalDefinitions(context())
        val bytes = wireJson.encodeToString(definitions).toByteArray(Charsets.UTF_8).size

        // ~600 tokens at cl100k density. Bump only with a deliberate budget decision.
        assertTrue("minimal tool schema grew to $bytes bytes", bytes <= 3000)
    }
}
