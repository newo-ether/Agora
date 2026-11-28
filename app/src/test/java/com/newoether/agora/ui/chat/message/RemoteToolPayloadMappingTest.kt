package com.newoether.agora.ui.chat.message

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteToolPayloadMappingTest {
    private fun map(name: String, args: String, result: String? = null) =
        mapRemoteToolDetail("filo", name, args, result, "succeeded")

    @Test fun nestedArgumentsAndResultsKeepShellContentAndMetadata() {
        val args = JsonPrimitive("""{"command":["pwsh","-Command","echo hello"]}""").toString()
        val result = JsonPrimitive("""{"stdout":"hello","stderr":"warning","exit_code":0}""").toString()
        val detail = requireNotNull(map("shell", args, result))
        val shell = detail.body as ToolDetailBody.Shell
        assertEquals("hello\nwarning", shell.output)
        assertEquals("0", shell.status)
        assertTrue(detail.supplemental.isNotEmpty())
    }

    @Test fun contentBlocksAndStructuredOutputUseExistingTerminal() {
        for (result in listOf(
            """[{"type":"text","text":"hello"}]""",
            """{"content":[{"type":"text","text":"hello","annotations":{"x":true}}]}""",
            """{"output":{"content":[{"type":"text","text":"hello"}],"extra":7},"exit_code":0}""",
            """{"aggregated_output":"hello","exitCode":0}""",
        )) {
            val detail = requireNotNull(map("Bash", """{"command":"echo"}""", result))
            assertEquals("hello", (detail.body as ToolDetailBody.Shell).output)
            if (result.contains("extra") || result.contains("annotations")) assertTrue(detail.supplemental.any { it == result })
        }
        assertNull(map("Bash", """{"command":"echo"}""", """[{"type":"image","data":"x"}]"""))
        assertNull(map("Bash", """{"command":"echo"}""", """{"output":{"unknown":"x"}}"""))
    }

    @Test fun readBlocksAndPathArraysRemainLossless() {
        val result = """{"content":[{"type":"text","text":"line"}],"truncated":false}"""
        val detail = requireNotNull(map("Read", """{"file_path":"a.kt"}""", result))
        assertEquals("line", (detail.body as ToolDetailBody.FileContent).content)
        assertEquals(listOf(result), detail.supplemental)
        assertEquals(2, (map("Glob", """{"pattern":"*"}""", """["a.kt","b.kt"]""")!!.body as ToolDetailBody.Paths).values.size)
    }

    @Test fun literalReadJsonAndProtocolErrorsKeepTheirMeaning() {
        val raw = """{"settings":{"enabled":true}}"""
        assertEquals(raw, (map("Read", """{"file_path":"a.json"}""", raw)!!.body as ToolDetailBody.FileContent).content)
        assertEquals("hello", (map("Bash", """{"command":"echo"}""", JsonPrimitive("hello").toString())!!.body as ToolDetailBody.Shell).output)
        assertNull(map("Bash", """{"command":"echo"}""", """{"content":[{"type":"text","text":"denied"}],"isError":true}"""))
        assertNull(map("Bash", """{"command":"echo"}""", """{"output":"partial"""))
    }
    @Test fun largerEditsWritesAndPatchStringsStayRenderable() {
        val text = "line\n".repeat(10000)
        val input = buildJsonObject { put("path", "a.kt"); put("content", text) }.toString()
        assertEquals(text, (map("file_write", input)!!.body as ToolDetailBody.FileContent).content)
        val edit = buildJsonObject {
            put("file_path", "a.kt")
            putJsonArray("edits") { add(buildJsonObject { put("old_string", text); put("new_string", "new"); put("replace_all", false) }) }
        }.toString()
        assertNotNull(map("MultiEdit", JsonPrimitive(edit).toString(), "done"))
        val patch = "*** Begin Patch\n*** Add File: b.kt\n+hello\n*** End Patch"
        assertTrue(map("apply_patch", JsonPrimitive(patch).toString(), "done")!!.body is ToolDetailBody.Edit)
        assertNull(map("unknown", input, "raw"))
        assertNull(map("file_edit", edit.dropLast(10), "raw"))
    }
}
