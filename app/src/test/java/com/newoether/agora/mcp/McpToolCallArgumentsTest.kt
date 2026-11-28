package com.newoether.agora.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpToolCallArgumentsTest {
    @Test
    fun `complete root ID resolves before large nested arguments finish`() {
        val prefix = """{"tool_id":"mcp_server_read_ab1234","arguments":{"content":""""
        assertEquals("mcp_server_read_ab1234", mcpToolIdForPresentation(prefix + "x".repeat(100_000)))
    }

    @Test
    fun `small reverse order arguments and JSON escapes resolve exactly`() {
        assertEquals(
            "mcp_server_read_ab1234",
            mcpToolIdForPresentation(
                """{"arguments":{"tool_id":"wrong","list":[{"value":"}\\\""}]},"tool_id":"mcp_server_\u0072ead_ab1234"}""",
            ),
        )
    }

    @Test
    fun `incomplete nested numeric blank duplicate and malformed IDs never resolve`() {
        val invalid = listOf(
            null,
            "",
            """{"tool_id":"mcp_server_re""",
            """{"tool_id":"mcp_server_\u00""",
            """{"arguments":{"tool_id":"nested"}}""",
            """[{"tool_id":"nested"}]""",
            """{"tool_id":12}""",
            """{"tool_id":null}""",
            """{"tool_id":" "}""",
            """{"tool_id":"first","tool_id":"second"}""",
            """{"arguments":oops,"tool_id":"later"}""",
            """{"arguments":[],"tool_id":"later"}""",
            """{"tool_id":"earlier","arguments":"{}"}""",
            """{"tool_id":"id" garbage}""",
            """{"tool_id":"id"} trailing""",
        )
        invalid.forEach { assertNull(it, mcpToolIdForPresentation(it)) }
    }

    @Test
    fun `huge reverse order payload never scans outside the prefix`() {
        val arguments = """{"arguments":{"content":"""" + "x".repeat(100_000) +
            """"},"tool_id":"mcp_server_read_ab1234"}"""
        assertNull(mcpToolIdForPresentation(arguments))
        assertNull(mcpToolIdForPresentation(" ".repeat(4096) + """{"tool_id":"late"}"""))
    }

    @Test
    fun `ID closing quote must fit inside the scan budget`() {
        val completeAtLimit = " ".repeat(4078) + """{"tool_id":"abcde""""
        assertEquals(4096, completeAtLimit.length)
        assertEquals("abcde", mcpToolIdForPresentation(completeAtLimit + "}"))
        assertNull(mcpToolIdForPresentation(" " + completeAtLimit + "}"))
    }
}
