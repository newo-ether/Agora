package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ToolDetailPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources
    private fun segment(name: String, result: String? = null, state: String? = null, args: String? = null) =
        MessageSegment(type = "tool", toolName = name, toolResult = result, toolState = state, toolArgs = args)
    private fun body(segment: MessageSegment) = resources.toolDetailPresentation(segment).body

    @Test fun lifecycleUsesTheExistingResolverWithoutReplayingResults() {
        val active = segment("unknown").copy(toolProgress = "partial")
        assertEquals(ToolDetailBody.Active(resources.toolSummary(active), "partial"), body(active))
        assertTrue(body(segment("unknown")) is ToolDetailBody.Active)
        val failed = segment("mcp_test", "legacy", ToolExecutionStates.FAILED).copy(
            toolResultText = "not visible", toolStructuredResult = "{\"private\":1}",
        )
        assertEquals(ToolDetailBody.Failed(resources.getString(R.string.tool_call_failed), null), body(failed))
        assertEquals(
            ToolDetailBody.Stopped(resources.stoppedToolSummary(ToolPresentationResolver.resolve(segment("web_search", "{}", ToolExecutionStates.STOPPED)))),
            body(segment("web_search", "{}", ToolExecutionStates.STOPPED)),
        )
        val background = segment("unknown", "{\"output\":\"live\"}", ToolExecutionStates.BACKGROUND_RUNNING)
        assertEquals(ToolDetailBody.Active(resources.toolSummary(background), "live"), body(background))
    }

    @Test fun failedMcpRendersItsSavedPayloadBelowTheFailureLabel() {
        val payload = """{"ok":true,"error":null,"data":{"locator":"dex_class:Lahw;"}}"""
        val failed = segment("mcp_call_tool", payload, ToolExecutionStates.FAILED).copy(
            toolStructuredResult = payload,
        )
        compose.setContent { MaterialTheme { ToolDetailContent(failed) { _, _ -> } } }
        compose.onNodeWithText(resources.getString(R.string.tool_call_failed)).assertExists()
        compose.onNodeWithText("dex_class:Lahw;", substring = true).assertExists()
    }

    @Test fun shellKeepsCommandExitCodeAndCompletedOutputPriority() {
        val shell = segment("execute_shell_command", "{\"exit_code\":7,\"output\":\"done\"}").copy(
            toolTarget = "Device", toolProgress = "old",
        )
        val presentation = ToolPresentationResolver.resolve(shell)
        assertEquals(ToolPresentationState.COMPLETED, presentation.state)
        assertEquals(ToolDetailBody.Shell(resources.shellExecutionSummary(presentation), "Device", null, "done"), body(shell))
        assertEquals(
            resources.getString(R.string.tool_no_output),
            (body(segment("wait_for_job").copy(toolProgress = "Connecting to Device")) as ToolDetailBody.Shell).output,
        )
    }

    @Test fun fileListsAndMalformedMatchesKeepEmptyAndOrderedSemantics() {
        assertEquals(
            ToolDetailBody.Paths(listOf(ToolDetailPath("a", null), ToolDetailPath("b.kt", "src"))),
            body(segment("file_glob", """{"files":["/r/a","/r/src/b.kt"]}""", args = """{"path":"/r"}""")),
        )
        assertEquals(ToolDetailBody.Muted(resources.getString(R.string.tool_found_no_files)), body(segment("file_glob", "{}")))
        assertEquals(ToolDetailBody.Muted(resources.getString(R.string.tool_found_no_matches)), body(segment("file_grep", "{\"matches\":[null,1]}")))
        // A legacy clipped envelope does not parse; the model-facing match text is shown, not "No matches".
        val clipped = segment("file_grep", "a.py:1:hit").copy(toolStructuredResult = """{"type":"file_grep","matches":[{"path":"a.py","li""")
        assertEquals(ToolDetailBody.JsonContent(listOf("a.py:1:hit")), body(clipped))
        val unknown = resources.getString(R.string.file_path_unknown)
        val grep = body(segment("file_grep", """{"matches":[{"content":"first"},{"path":"$unknown","line":3,"content":"second"},{"path":"","content":"third"}]}""")) as ToolDetailBody.Grep
        assertEquals(2, grep.groups.size)
        assertEquals(listOf("first", "third"), grep.groups[0].rows.map { (it as ToolDetailCodeRow.Line).text })
        assertEquals(listOf(ToolDetailCodeRow.Line(3, "second")), grep.groups[1].rows)
    }

    @Test fun grepMergesContextDedentsAndHighlights() {
        val result = """{"matches":[
            {"path":"F:\\w\\a.kt","line":3,"content":"    val foo = 1","before":["  fun x() {"],"after":["    foo()"]},
            {"path":"F:\\w\\a.kt","line":4,"content":"    foo()","before":["    val foo = 1"]},
            {"path":"F:\\w\\a.kt","line":9,"content":"  Foo"}]}"""
        val grep = body(segment("file_grep", result, args = """{"pattern":"fo+","case_insensitive":true,"path":"F:\\w"}""")) as ToolDetailBody.Grep
        val group = grep.groups.single()
        assertEquals(ToolDetailPath("a.kt", null), group.path)
        assertEquals(
            listOf(
                ToolDetailCodeRow.Line(2, "fun x() {", emphasized = false),
                ToolDetailCodeRow.Line(3, "  val foo = 1", highlights = listOf(6..8)),
                ToolDetailCodeRow.Line(4, "  foo()", highlights = listOf(2..4)),
                ToolDetailCodeRow.Gap,
                ToolDetailCodeRow.Line(9, "Foo", highlights = listOf(0..2)),
            ),
            group.rows,
        )
        val invalid = body(segment("file_grep", result, args = """{"pattern":"("}""")) as ToolDetailBody.Grep
        assertTrue(invalid.groups.single().rows.all { (it as? ToolDetailCodeRow.Line)?.highlights.isNullOrEmpty() })
    }

    @Test fun commandAndWrittenContentMoveOutOfTheArgumentJson() {
        val write = resources.toolDetailPresentation(segment(
            "file_write", """{"type":"file_write","ok":true}""",
            args = """{"path":"/p/a.txt","content":"one\ntwo\n","server":"Quantum"}""",
        ))
        assertEquals("""{"path":"/p/a.txt","server":"Quantum"}""", write.arguments)
        assertEquals(
            ToolDetailCodeArgument(
                "content", "one\ntwo\n",
                listOf(ToolDetailCodeRow.Line(1, "one"), ToolDetailCodeRow.Line(2, "two")),
            ),
            write.codeArgument,
        )
        val shell = resources.toolDetailPresentation(segment(
            "execute_shell_command", args = """{"command":"ls -la","server":"Quantum"}""",
        ))
        assertEquals("""{"server":"Quantum"}""", shell.arguments)
        assertEquals(ToolDetailCodeArgument("command", "ls -la", null), shell.codeArgument)
        val onlyCommand = resources.toolDetailPresentation(segment("execute_shell_command", args = """{"command":"pwd"}"""))
        assertNull(onlyCommand.arguments)
        assertEquals("pwd", onlyCommand.codeArgument?.text)
        val read = resources.toolDetailPresentation(segment("file_read", args = """{"path":"/p/a.txt"}"""))
        assertNull(read.codeArgument)
        assertEquals("""{"path":"/p/a.txt"}""", read.arguments)
    }

    @Test fun memoryAndSkillContentRendersAsCodeAndPatchesAsDiffs() {
        val done = """"ok""""
        val create = resources.toolDetailPresentation(segment(
            "create_memory_file", done, args = """{"name":"n.md","content":"a\nb"}""",
        ))
        assertEquals("""{"name":"n.md"}""", create.arguments)
        assertEquals(listOf(ToolDetailCodeRow.Line(1, "a"), ToolDetailCodeRow.Line(2, "b")), create.codeArgument?.rows)
        val replace = resources.toolDetailPresentation(segment(
            "edit_skill_file", done, args = """{"name":"s.md","operation":" Replace ","content":"x"}""",
        ))
        assertEquals("x", replace.codeArgument?.text)
        assertNotNull(replace.codeArgument?.rows)
        val append = resources.toolDetailPresentation(segment(
            "update_active_memory", done, args = """{"mode":"append","content":"tail"}""",
        ))
        assertEquals(ToolDetailCodeArgument("content", "tail", null), append.codeArgument)
        assertEquals("""{"mode":"append"}""", append.arguments)
        val defaultReplace = resources.toolDetailPresentation(segment(
            "update_active_memory", done, args = """{"content":"all"}""",
        ))
        assertNotNull(defaultReplace.codeArgument?.rows)
        assertNull(defaultReplace.arguments)

        val patch = resources.toolDetailPresentation(segment(
            "edit_memory_file", done,
            args = """{"name":"n.md","operation":"patch","old_string":"a\nold","new_string":"a\nnew"}""",
        ))
        assertNull(patch.codeArgument)
        assertNull(patch.arguments)
        val edit = patch.body as ToolDetailBody.Edit
        assertEquals(ToolDetailPath("n.md", null), edit.path)
        assertEquals(
            listOf(
                ToolDetailDiffLine(ToolDetailDiffKind.CONTEXT, "a"),
                ToolDetailDiffLine(ToolDetailDiffKind.REMOVED, "old"),
                ToolDetailDiffLine(ToolDetailDiffKind.ADDED, "new"),
            ),
            edit.hunks.single().lines,
        )
        val activePatch = resources.toolDetailPresentation(segment(
            "update_active_memory", done, args = """{"mode":"patch","content":"","old_string":"x","new_string":""}""",
        ))
        assertNull(activePatch.codeArgument)
        assertNull((activePatch.body as ToolDetailBody.Edit).path)
        val rename = resources.toolDetailPresentation(segment(
            "edit_memory_file", done, args = """{"name":"n.md","operation":"rename","new_name":"m.md"}""",
        ))
        assertNull(rename.codeArgument)
        assertTrue(rename.body !is ToolDetailBody.Edit)
        assertEquals("""{"name":"n.md","operation":"rename","new_name":"m.md"}""", rename.arguments)
    }

    @Test fun editBuildsGitStyleHunksAndHidesRawArguments() {
        val args = """{"path":"/p/a.txt","edits":[
            {"old_string":"keep\nold\ntail","new_string":"keep\nnew\ntail"},
            {"old_string":"gone","new_string":"","replace_all":true}]}"""
        val seg = segment("file_multi_edit", """{"type":"file_edit","path":"/p/a.txt","replaced":3,"edits":2}""", args = args)
        val detail = resources.toolDetailPresentation(seg)
        assertNull(detail.arguments)
        val edit = detail.body as ToolDetailBody.Edit
        assertEquals(ToolDetailPath("a.txt", "/p"), edit.path)
        assertEquals(resources.getString(R.string.tool_edit_count, 2), edit.editCount)
        assertEquals(
            listOf(
                ToolDetailDiffLine(ToolDetailDiffKind.CONTEXT, "keep"),
                ToolDetailDiffLine(ToolDetailDiffKind.REMOVED, "old"),
                ToolDetailDiffLine(ToolDetailDiffKind.ADDED, "new"),
                ToolDetailDiffLine(ToolDetailDiffKind.CONTEXT, "tail"),
            ),
            edit.hunks[0].lines,
        )
        assertEquals(ToolDetailDiffHunk(2, true, listOf(ToolDetailDiffLine(ToolDetailDiffKind.REMOVED, "gone"))), edit.hunks[1])
        val single = body(segment("file_edit", """{"replaced":1}""", args = """{"path":"a","old_string":"x","new_string":"y"}""")) as ToolDetailBody.Edit
        assertNull(single.editCount)
        assertNull(single.hunks.single().index)
    }

    @Test fun pathSplitKeepsWindowsDriveRoots() {
        assertEquals(ToolDetailPath("a.txt", "F:\\"), toolDetailPath("F:\\a.txt"))
        assertEquals(ToolDetailPath("b", "F:\\x\\y"), toolDetailPath("F:\\x\\y\\b"))
        assertEquals(ToolDetailPath("b", "y"), toolDetailPath("F:\\x\\y\\b", "f:\\X\\"))
        assertEquals(ToolDetailPath("b", "/xy"), toolDetailPath("/xy/b", "/x"))
    }

    @Test fun fileTruncationUsesReturnedBytesOrUtf8Content() {
        val file = body(segment("file_read", """{"path":"/tmp/a","lines":1,"content":"é","offset":10,"truncated":true}""")) as ToolDetailBody.FileContent
        assertEquals(resources.getString(R.string.tool_read_file_truncated, 12L), file.truncationText)
        assertEquals(resources.getString(R.string.tool_line_count, 1), file.lineCount)
        val bytes = body(segment("file_read", """{"content":"abc","returned_bytes":7,"offset":10,"truncated":true}""")) as ToolDetailBody.FileContent
        assertEquals(resources.getString(R.string.tool_read_file_truncated, 17L), bytes.truncationText)
        assertNull(bytes.lines)
    }

    @Test fun lineModeReadParsesGutterAndContinuesByLine() {
        val read = body(segment("file_read", """{"path":"/a","content":"     3\tgamma\n     4\t\tdelta","start_line":3,"lines":2,"truncated":true,"next_start_line":5,"next_offset":40}""")) as ToolDetailBody.FileContent
        assertEquals(listOf(ToolDetailCodeRow.Line(3, "gamma"), ToolDetailCodeRow.Line(4, "\tdelta")), read.lines)
        assertEquals(resources.getString(R.string.tool_read_file_truncated_line, 5L), read.truncationText)
    }

    @Test fun searchKeepsDisplayUrlsButOnlySafeDestinationsAreClickable() {
        val search = body(segment("web_search", """{"results":[{"href":"https://example.com","description":"desc"},{"url":"javascript:alert(1)","body":"body"},null]}""")) as ToolDetailBody.Search
        assertEquals("https://example.com", search.results[0].safeUrl)
        assertEquals("desc", search.results[0].snippet)
        assertEquals("javascript:alert(1)", search.results[1].url)
        assertNull(search.results[1].safeUrl)
        assertEquals(resources.getString(R.string.tool_web_result, 3), search.results[2].title)
        assertTrue(body(segment("web_search", "{\"results\":[]}")) is ToolDetailBody.Muted)
    }

    @Test fun mcpAndUnknownJsonKeepOriginalStringsForThePrefixAwareRenderer() {
        val mcp = segment("mcp_test", "legacy").copy(toolResultText = "text", toolStructuredResult = "{\"a\":1}")
        assertEquals(ToolDetailBody.JsonContent(listOf("text", "{\"a\":1}")), body(mcp))
        val raw = segment("unknown", "{\"a\":").copy(toolArgs = "{\"long\":")
        assertEquals(raw.toolArgs, resources.toolDetailPresentation(raw).arguments)
        assertEquals(ToolDetailBody.JsonContent(listOf(raw.toolResult!!)), body(raw))
        assertNull(resources.toolDetailPresentation(raw.copy(toolArgs = "{}")).arguments)
    }

    @Test fun memoryReadsKeepLiteralMarkdownAndSingleFileLineNumbers() {
        val markdown = "# title\nError: this is stored content\n{\"error\":\"literal\"}"
        val read = body(segment("read_memory_file", markdown, args = """{"name":"notes.md"}""")) as ToolDetailBody.FileContent
        assertEquals(markdown, read.content)
        assertEquals(ToolDetailPath("notes.md", null), read.path)
        assertEquals(listOf(1, 2, 3), read.lines!!.map { (it as ToolDetailCodeRow.Line).number })
        val active = body(segment("read_active_memory", markdown)) as ToolDetailBody.FileContent
        assertNull(active.path)
        assertEquals(read.lines, active.lines)
        val batch = body(segment("read_memory_file", markdown, args = """{"names":["a","b"]}""")) as ToolDetailBody.FileContent
        assertNull(batch.lines)
        val failed = body(segment("stop_shell_job", "", ToolExecutionStates.FAILED)) as ToolDetailBody.Shell
        assertNull(failed.output)
    }
    @Test fun composeRendersTheSharedShellAndFileDetails() {
        val current = mutableStateOf(segment("execute_shell_command", "{\"exit_code\":7,\"output\":\"command output\"}"))
        compose.setContent { MaterialTheme { ToolDetailContent(current.value) { _, _ -> } } }
        compose.onNodeWithText("command output").assertExists()
        compose.runOnIdle { current.value = segment("file_read", "{\"path\":\"/file\",\"content\":\"file content\"}") }
        compose.onNodeWithText("file").assertExists()
        compose.onNodeWithText("file content").assertExists()
        compose.onNodeWithText("command output").assertDoesNotExist()
    }

    @Test fun subagentConcurrencyErrorUsesExistingFailedToolPresentation() {
        val error = "Error: Subagent concurrency limit reached (4/4 running for this conversation). " +
            "Wait for a running subagent to finish (wait_agents) before starting another."
        val rejected = segment("spawn_agent", error, ToolExecutionStates.FAILED)
        val failed = body(rejected) as ToolDetailBody.Failed
        assertTrue(failed.text.contains("4/4"))
        assertTrue(failed.text.contains("wait_agents"))
        compose.setContent { MaterialTheme { ToolDetailContent(rejected) { _, _ -> } } }
        compose.onNodeWithText(failed.text).assertExists()
        compose.onNodeWithText("started").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "zh") fun composeFailureAndStopKeepLocalizedTerminalTextOnly() {
        val current = mutableStateOf(segment("mcp_test", "", ToolExecutionStates.FAILED))
        compose.setContent { MaterialTheme { ToolDetailContent(current.value) { _, _ -> } } }
        compose.onNodeWithText(resources.getString(R.string.tool_call_failed)).assertExists()
        compose.onNodeWithText("hidden result").assertDoesNotExist()
        compose.runOnIdle { current.value = segment("web_search", "{}", ToolExecutionStates.STOPPED) }
        compose.onNodeWithText(resources.stoppedToolSummary(ToolPresentationResolver.resolve(current.value))).assertExists()
    }
}
