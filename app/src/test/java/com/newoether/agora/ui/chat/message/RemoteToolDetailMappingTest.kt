package com.newoether.agora.ui.chat.message

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteToolDetailMappingTest {
    private fun args(vararg values: Pair<String, String>) = buildJsonObject { values.forEach { (key, value) -> put(key, value) } }.toString()
    private fun map(name: String, args: String, result: String? = null, state: String? = "succeeded", source: String = "filo") =
        mapRemoteToolDetail(source, name, args, result, state)

    @Test fun shellAcrossAllFourSourcesKeepsOutputExitAndUnknownMetadata() {
        for (source in listOf("claude-cli", "claude-desktop", "codex-cli", "codex-desktop")) {
            val name = if (source.startsWith("claude")) "Bash" else "execute_shell_command"
            val mapped = requireNotNull(map(name, args("command" to "echo hello", "cwd" to "/repo"),
                """{"output":"hello","exit_code":7,"extra":"retained"}""", "failed", source))
            val shell = mapped.body as ToolDetailBody.Shell
            assertEquals("hello", shell.output)
            assertEquals("7", shell.status)
            assertEquals("echo hello", mapped.codeArgument!!.text)
            assertTrue(mapped.arguments!!.contains("/repo"))
            assertTrue(mapped.supplemental.single().contains("retained"))
        }
        val argv = map("shell", """{"command":["pwsh","-Command","echo hello"]}""", "ok")!!
        assertEquals("pwsh -Command \"echo hello\"", argv.codeArgument!!.text)
        assertTrue(argv.supplemental.single().contains("["))
        val exec = map("exec_command", args("cmd" to "pwd"), "Process exited with code 0\nOutput:\n/repo")!!
        assertEquals("/repo", (exec.body as ToolDetailBody.Shell).output)
    }

    @Test fun runningCommandsAndWritesDoNotRequireResults() {
        assertEquals("echo", map("Bash", args("command" to "echo"), state = "running")!!.codeArgument!!.text)
        val write = map("Write", args("file_path" to "a.kt", "content" to "line\n"), state = "running")!!
        assertEquals("line\n", (write.body as ToolDetailBody.FileContent).content)
        assertTrue(map("Read", args("file_path" to "a.kt"), state = "running")!!.body is ToolDetailBody.Active)
    }

    @Test fun readsStripOnlyConsecutiveNativeGuttersAndPreserveLiteralContent() {
        val input = args("file_path" to "src/a.kt")
        val read = map("Read", input, "   4→    val a = 1\n   5→next\n")!!.body as ToolDetailBody.FileContent
        assertEquals("    val a = 1\nnext", read.content)
        assertEquals(listOf(4, 5), read.lines!!.map { it.number })
        val literal = "1→not\n3→consecutive"
        assertEquals(literal, (map("Read", input, literal)!!.body as ToolDetailBody.FileContent).content)
        val structured = map("file_read", args("path" to "a.kt"), """{"path":"a.kt","content":"x","truncated":true}""")!!
        assertEquals("x", (structured.body as ToolDetailBody.FileContent).content)
        assertTrue(structured.supplemental.single().contains("truncated"))
    }

    @Test fun writesAndEditsReuseFileModelsAndRetainReceipt() {
        val write = map("Write", args("file_path" to "a.kt", "content" to "<literal>"), "written")!!
        assertEquals("<literal>", (write.body as ToolDetailBody.FileContent).content)
        assertEquals(listOf("written"), write.supplemental)
        val edit = map("Edit", args("file_path" to "a.kt", "old_string" to "old", "new_string" to "new"), "done")!!
        val lines = (edit.body as ToolDetailBody.Edit).hunks.single().lines
        assertEquals(listOf(ToolDetailDiffKind.REMOVED, ToolDetailDiffKind.ADDED), lines.map { it.kind })
        val multi = """{"file_path":"a.kt","edits":[{"old_string":"a","new_string":"b"},{"old_string":"c","new_string":"d"}]}"""
        assertEquals(2, (map("MultiEdit", multi, "done")!!.body as ToolDetailBody.Edit).hunks.size)
    }

    @Test fun completePatchesAndCodexChangesBecomeDiffs() {
        val patch = "*** Begin Patch\n*** Update File: a.kt\n@@\n-old\n+new\n*** Add File: b.kt\n+hello\n*** Delete File: gone.kt\n*** End Patch"
        for (input in listOf(args("patch" to patch), patch)) {
            val body = map("apply_patch", input, "Success")!!.body as ToolDetailBody.Edit
            assertEquals(3, body.files.size)
            assertTrue(body.files.first().hunks.single().lines.any { it.kind == ToolDetailDiffKind.REMOVED && it.text == "old" })
        }
        val changes = """{"changes":[{"path":"a.kt","kind":{"type":"update"},"diff":"@@ -1 +1 @@\n-old\n+new"}]}"""
        assertTrue(map("file_edit", changes, changes)!!.body is ToolDetailBody.Edit)
        assertNull(map("file_edit", """{"changes":[{"path":"a.kt"}]}""", "done"))
        assertNull(map("apply_patch", args("patch" to patch.removeSuffix("*** End Patch")), "done"))
    }

    @Test fun listsGrepAndSearchUseExistingModels() {
        assertEquals(2, (map("Glob", args("pattern" to "*.kt"), "a.kt\nb.kt")!!.body as ToolDetailBody.Paths).values.size)
        assertEquals(1, (map("LS", args("path" to "/repo"), "dir/")!!.body as ToolDetailBody.Paths).values.size)
        val grep = map("Grep", args("pattern" to "hit"), "C:\\repo\\a.kt:7:hit")!!.body as ToolDetailBody.Grep
        assertEquals(7, (grep.groups.single().rows.single() as ToolDetailCodeRow.Line).number)
        assertTrue(map("web_search", args("query" to "hello"), """{"results":[{"title":"A","url":"https://example.com","snippet":"result"}]}""")!!.body is ToolDetailBody.Search)
        assertTrue(map("WebFetch", args("url" to "https://example.com"), "<html>literal</html>")!!.body is ToolDetailBody.FileContent)
    }

    @Test fun supplementalFieldsSurviveAndInvalidStructuredMatchesFallBack() {
        val shell = map("Bash", args("command" to "x"),
            """{"output":"main","stdout":"other","stderr":"error","exit_code":0,"exitCode":4}""")!!
        assertEquals("main", (shell.body as ToolDetailBody.Shell).output)
        assertTrue(shell.supplemental.single().contains("other"))
        assertTrue(shell.supplemental.single().contains("error"))
        assertTrue(shell.supplemental.single().contains("exitCode"))
        val wrapper = "Wall time: 0.1 seconds\nProcess exited with code 0\nOutput:\nhello"
        assertTrue(map("exec_command", args("cmd" to "x"), wrapper)!!.supplemental.single().contains("Wall time"))
        assertNull(map("Bash", args("command" to "x"), "output", "stopped"))
        for (match in listOf(
            """{"path":"a","content":"hit","line":"7"}""",
            """{"path":"a","content":"hit","line":7,"unknown":true}""",
            """{"path":"a","content":"hit","before":["context"]}""",
        )) assertNull(map("file_grep", args("pattern" to "hit"), """{"matches":[$match]}"""))
        assertNull(map("file_read", args("path" to "a"), "{bad"))
        assertNull(map("Glob", args("pattern" to "*"), "{bad"))
        val patch = "*** Begin Patch\n*** Update File: a.kt\n*** Move to: b.kt\n@@\n-old\n+new\n*** End Patch"
        val moved = map("apply_patch", args("patch" to patch), "done")!!.body as ToolDetailBody.Edit
        assertEquals(ToolDetailPath("b.kt", null), moved.files.single().moveTo)
        assertTrue(moved.files.single().hunks.single().lines.none { it.text.startsWith("***") })
    }

    @Test fun remoteShellMappingPreservesRecordedLifecycleAndNativePayload() {
        for (source in listOf("filo", "codex-cli", "codex-desktop", "claude-cli", "claude-desktop")) {
            val name = if (source.startsWith("codex")) "execute_shell_command" else "Bash"
            val segment = com.newoether.agora.model.MessageSegment(type = "tool", toolName = name,
                toolArgs = args("command" to "echo hello"), toolResult = """{"output":"hello","exit_code":7}""",
                toolState = "failed", remoteToolSource = source)
            val resolved = ToolPresentationResolver.resolve(segment)
            assertEquals(ToolKind.SHELL_EXECUTE, resolved.kind)
            assertEquals(ToolPresentationState.FAILED, resolved.state)
            assertEquals(7, resolved.exitCode)
            assertEquals("failed", segment.toolState)
            assertEquals("""{"output":"hello","exit_code":7}""", resolved.rawResult)
            assertEquals("hello", (mapRemoteToolDetail(source, name, segment.toolArgs,
                segment.toolResult, segment.toolState)!!.body as ToolDetailBody.Shell).output)
            assertEquals(ToolPresentationState.FAILED, ToolPresentationResolver.resolve(segment.copy(
                toolResult = """{"output":"hello","exit_code":0}""")).state)
            assertEquals(ToolPresentationState.COMPLETED, ToolPresentationResolver.resolve(segment.copy(
                toolState = "succeeded", toolResult = """{"output":"hello","exit_code":0}""")).state)
            assertEquals(ToolPresentationState.COMPLETED, ToolPresentationResolver.resolve(segment.copy(
                toolState = null)).state)
            assertEquals(ToolPresentationState.FAILED, ToolPresentationResolver.resolve(segment.copy(
                toolState = "succeeded", toolResult = """{"output":"hello","exit_code":7,"error":"connection_lost"}""")).state)
            assertEquals(ToolPresentationState.RUNNING, ToolPresentationResolver.resolve(segment.copy(
                toolResult = null, toolProgress = "partial", toolState = "running")).state)
            assertEquals(ToolPresentationState.STOPPED, ToolPresentationResolver.resolve(segment.copy(
                toolState = "stopped")).state)
        }
    }

    @Test fun unknownMissingMalformedAndWrongTypesFailClosed() {
        val valid = args("file_path" to "a.kt", "old_string" to "old", "new_string" to "new")
        for (name in listOf("Task", "TodoWrite", "update_plan", "unknown", "server/Read")) assertNull(map(name, valid, "raw"))
        for (name in listOf("Bash", "Read", "Write", "Edit", "MultiEdit", "Glob", "Grep", "LS", "apply_patch", "WebFetch", "web_search")) {
            for (bad in listOf("{bad", "[]", "{}", "null")) assertNull("$name $bad", map(name, bad, "raw"))
        }
        assertNull(map("Read", """{"file_path":3}""", "raw"))
        assertNull(map("Edit", """{"path":"a","old_string":"b","new_string":3}""", "raw"))
        assertNull(map("Edit", valid, "denied", "failed"))
        assertNull(map("Bash", args("command" to "x"), """{"output":3}"""))
        assertNull(map("Bash", args("command" to "x"), "denied", "failed"))
        assertNull(map("Read", args("file_path" to "x"), "raw", source = "other"))
        assertNull(map("web_search", args("query" to "x"), """{"results":[{"title":"A","url":"https://example.com","unknown":1}]}"""))
    }
}
