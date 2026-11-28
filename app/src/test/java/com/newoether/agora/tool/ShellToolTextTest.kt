package com.newoether.agora.tool

import com.newoether.agora.model.ToolCallData
import com.newoether.agora.util.MAX_FILE_EDIT_SNIPPETS
import com.newoether.agora.util.MAX_FILE_EDIT_SNIPPET_LINES
import com.newoether.agora.util.ShellFileEdit
import com.newoether.agora.util.ShellFileEditResult
import com.newoether.agora.util.ShellFileEditSnippet
import com.newoether.agora.util.editShellFileContent
import com.newoether.agora.util.shellExpectedSha256Error
import com.newoether.agora.util.shellFileSha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellToolTextTest {
    private fun numberedLines(n: Int) = (1..n).joinToString("") { "l$it\n" }

    private fun snippetsFor(content: String, vararg edits: ShellFileEdit): ShellFileEditResult {
        val (edited, result) = editShellFileContent(content, edits.toList())
        checkNotNull(edited) { result.error.orEmpty() }
        return result
    }

    // ── Snippets: parity with Conch handler/file_edit_snippets_test.go ──

    @Test
    fun snippetsShowContextAroundSingleEdit() {
        val result = snippetsFor(numberedLines(20), ShellFileEdit("l10\n", "X\nY\n"))
        assertEquals(
            listOf(ShellFileEditSnippet(7, listOf("l7", "l8", "l9", "X", "Y", "l11", "l12", "l13"))),
            result.snippets,
        )
        assertFalse(result.snippetsTruncated)
    }

    @Test
    fun snippetsTrackEarlierEditsAcrossLaterShifts() {
        val snippets = snippetsFor(
            numberedLines(40),
            ShellFileEdit("l30\n", "THIRTY\n"),
            ShellFileEdit("l2\n", "a\nb\nc\n"),
        ).snippets
        assertEquals(2, snippets.size)
        assertEquals(1, snippets[0].startLine)
        assertEquals("a", snippets[0].lines[1])
        // Two extra lines above l30 put THIRTY on line 32.
        assertEquals("THIRTY", snippets[1].lines[32 - snippets[1].startLine])
    }

    @Test
    fun snippetsMergeNearbyAndCoverOverwrittenRegions() {
        val snippets = snippetsFor(
            numberedLines(20),
            ShellFileEdit("l5\n", "five\n"),
            ShellFileEdit("l7\n", "seven\n"),
            ShellFileEdit("five\n", "FIVE\n"),
        ).snippets
        assertEquals(
            listOf(ShellFileEditSnippet(2, listOf("l2", "l3", "l4", "FIVE", "l6", "seven", "l8", "l9", "l10"))),
            snippets,
        )
    }

    @Test
    fun snippetsHandleDeletionCrLfAndFileEdges() {
        assertEquals(
            listOf(ShellFileEditSnippet(1, listOf("a", "b"))),
            snippetsFor("a\r\nb\r\nc\r\n", ShellFileEdit("c\r\n", "")).snippets,
        )
        assertTrue(snippetsFor("only", ShellFileEdit("only", "")).snippets.isEmpty())
    }

    @Test
    fun snippetsAreBounded() {
        val many = snippetsFor("x\n\n\n\n\n\n\n\n\n\n".repeat(50), ShellFileEdit("x", "y", replaceAll = true))
        assertTrue(many.snippetsTruncated)
        assertEquals(MAX_FILE_EDIT_SNIPPETS, many.snippets.size)

        val long = snippetsFor("start\n", ShellFileEdit("start\n", "n\n".repeat(200)))
        assertTrue(long.snippetsTruncated)
        assertEquals(MAX_FILE_EDIT_SNIPPET_LINES, long.snippets.single().lines.size)
    }

    // ── expected_sha256 ──

    @Test
    fun expectedSha256GuardMatchesConchSemantics() {
        val bytes = "hello".toByteArray()
        val hash = shellFileSha256(bytes)
        assertNull(shellExpectedSha256Error("", bytes))
        assertNull(shellExpectedSha256Error(hash.uppercase(), bytes))
        assertEquals(
            "file changed concurrently: expected sha256 abc, got $hash",
            shellExpectedSha256Error("abc", bytes),
        )
        assertTrue(shellExpectedSha256Error(hash, null).orEmpty().startsWith("verify expected_sha256"))
    }

    // ── Model-facing plain text ──

    @Test
    fun durableShellResultRendersHeaderThenRawOutput() {
        val json = """{"type":"execute_shell_command","server":"Quantum","command":"echo","job_id":"j1",""" +
            """"durable":true,"result":{"state":"succeeded","exit_code":0,"truncated":false,""" +
            """"output":"say \"hi\"\npath C:\\x\n","output_bytes":20,"created_at":"t"}}"""
        assertEquals(
            "server: Quantum\njob_id: j1\nstate: succeeded\nexit_code: 0\n\nsay \"hi\"\npath C:\\x\n",
            shellToolModelText("execute_shell_command", json),
        )
    }

    @Test
    fun tailLinesKeepsOnlyTheLastLinesForTheModel() {
        val json = """{"type":"execute_shell_command","server":"S","exit_code":0,"output":"1\n2\n3\n4\n"}"""
        assertEquals(
            "server: S\nexit_code: 0\noutput_tail_lines: last 2 of 4\noutput_omitted_lines: 2\n\n... (2 lines omitted) ...\n3\n4",
            shellToolModelText("execute_shell_command", json, tailLines = 2),
        )
        // file_read ignores tail_lines.
        val read = """{"type":"file_read","path":"/a","content":"1\n2\n3\n"}"""
        assertEquals("path: /a\n\n1\n2\n3\n", shellToolModelText("file_read", read, tailLines = 1))
        assertEquals(2, parseTailLines("""{"tail_lines":2}"""))
        assertNull(parseTailLines("""{"tail_lines":0}"""))
    }

    @Test
    fun headAndTailSelectBothEndsWithoutDuplicatingOverlapOrChangingStructuredOutput() {
        val json = """{"output":"1\n2\n3\n4\n5\n"}"""
        val both = shellToolModelText("shell_exec", json, tailLines = 1, headLines = 2)!!
        assertTrue(both.endsWith("1\n2\n... (2 lines omitted) ...\n5"))
        assertTrue(both.contains("output_omitted_lines: 2"))
        assertEquals("1\n2\n3\n4\n5\n", shellToolModelText("shell_exec", json, tailLines = 4, headLines = 4))
        assertTrue(shellToolModelText("shell_job_get", json, headLines = 2)!!.endsWith("1\n2\n... (3 lines omitted) ..."))
        assertEquals("1\n2\n3\n4\n5\n", shellToolModelText("shell_exec", json, tailLines = Int.MAX_VALUE, headLines = Int.MAX_VALUE))
        val result = shellModelFacingResult("shell_exec", """{"head_lines":1,"tail_lines":1}""", ToolExecutionResult(text = json))
        assertEquals(json, result.structuredContent)
        assertTrue(result.text.endsWith("1\n... (3 lines omitted) ...\n5"))
        assertEquals(2, parseHeadLines("""{"head_lines":2}"""))
        assertNull(parseHeadLines("""{"head_lines":-1}"""))
    }

    @Test
    fun errorsAndEmptyOutputStayReadable() {
        assertEquals(
            "error: path is required",
            shellToolModelText("file_read", jsonError("file_read", "path is required")),
        )
        val empty = """{"type":"execute_shell_command","server":"S","exit_code":1,"output":""}"""
        assertEquals("server: S\nexit_code: 1\n\n(no output)", shellToolModelText("execute_shell_command", empty))
    }

    @Test
    fun fileEditRendersNumberedSnippets() {
        val result = ShellFileEditResult(
            replacements = 2,
            sha256 = "ab12",
            snippets = listOf(
                ShellFileEditSnippet(9, listOf("x", "y")),
                ShellFileEditSnippet(120, listOf("z")),
            ),
        )
        assertEquals(
            "edited: /f\nserver: S\nreplaced: 2\nedits: 2\nsha256: ab12\n\n" +
                "     9\tx\n    10\ty\n...\n   120\tz",
            shellToolModelText("file_multi_edit", fileEditSuccessJson("S", "/f", result, 2)),
        )
    }

    @Test
    fun globAndGrepRenderOneEntryPerLine() {
        assertEquals(
            "a.kt\nb/c.kt",
            shellToolModelText("file_glob", """{"type":"file_glob","files":["a.kt","b/c.kt"],"truncated":false}"""),
        )
        val grep = """{"type":"file_grep","matches":[{"path":"a","line":5,"content":"hit","before":["b4"],"after":["af"]},""" +
            """{"path":"b","line":1,"content":"two"}],"truncated":true}"""
        assertEquals(
            "truncated: true\n\na-4-b4\na:5:hit\na-6-af\n--\nb:1:two",
            shellToolModelText("file_grep", grep),
        )
    }

    @Test
    fun grepClampingIsReportedWithoutChangingStructuredResult() {
        val raw = """{"type":"file_grep","matches":[],"truncated":false}"""
        for ((requested, effective) in listOf(30 to 10, -3 to 0)) {
            val result = shellModelFacingResult("file_grep", """{"context":$requested}""", ToolExecutionResult(raw))
            assertTrue(result.text.startsWith("context: $effective (requested $requested)\n"))
            assertEquals(raw, result.structuredContent)
        }
        assertFalse(shellModelFacingResult("file_grep", """{"context":10}""", ToolExecutionResult(raw)).text.contains("requested"))
    }

    @Test
    fun globAndGrepShowPathsRelativeToTheSearchRoot() {
        val glob = """{"type":"file_glob","files":["C:\\repo\\src\\a.kt","C:\\repo\\b.kt","D:\\other.kt"],"truncated":false}"""
        val globResult = shellModelFacingResult("file_glob", """{"path":"C:\\repo\\"}""", ToolExecutionResult(glob))
        assertEquals("root: C:\\repo\n\nsrc\\a.kt\nb.kt\nD:\\other.kt", globResult.text)
        assertEquals(glob, globResult.structuredContent)

        val grep = """{"type":"file_grep","matches":[{"path":"/srv/app/x.go","line":3,"content":"hit"}],"truncated":false}"""
        assertEquals(
            "root: /srv/app\n\nx.go:3:hit",
            shellModelFacingResult("file_grep", """{"path":"/srv/app"}""", ToolExecutionResult(grep)).text,
        )
        // A sibling that only shares the prefix, or grepping a single file, keeps the full path.
        val sibling = """{"type":"file_grep","matches":[{"path":"/srv/apple/y.go","line":1,"content":"z"}]}"""
        assertEquals("/srv/apple/y.go:1:z", shellToolModelText("file_grep", sibling, searchRoot = "/srv/app"))
        val single = """{"type":"file_grep","matches":[{"path":"/srv/app/x.go","line":1,"content":"z"}]}"""
        assertEquals("/srv/app/x.go:1:z", shellToolModelText("file_grep", single, searchRoot = "/srv/app/x.go"))
    }

    @Test
    fun fileReadHeaderDropsPagingNoiseAndKeepsSha256() {
        val lineMode = """{"type":"file_read","server":"S","path":"/a","content":"     7\tx","start_line":7,""" +
            """"end_line":7,"lines":1,"total_lines":9,"total_bytes":90,"sha256":"ab","offset":0,""" +
            """"has_more":true,"truncated":true,"next_start_line":8,"next_offset":80}"""
        assertEquals(
            "server: S\npath: /a\nstart_line: 7\nend_line: 7\ntotal_lines: 9\ntotal_bytes: 90\nsha256: ab\n" +
                "truncated: true\nnext_start_line: 8\n\n     7\tx",
            shellToolModelText("file_read", lineMode),
        )
        val byteMode = """{"type":"file_read","server":"S","path":"/a","content":"x","lines":1,"total_lines":1,""" +
            """"total_bytes":1,"returned_bytes":1,"offset":0,"limit":1048576,"sha256":"cd","truncated":false}"""
        assertEquals(
            "server: S\npath: /a\nlines: 1\ntotal_lines: 1\ntotal_bytes: 1\noffset: 0\nsha256: cd\n\nx",
            shellToolModelText("file_read", byteMode),
        )
    }

    @Test
    fun fileReadJsonCarriesTheBackendSha256() = kotlinx.coroutines.runBlocking {
        val slice = com.newoether.agora.util.ShellFileReadResult(
            content = "a\nb\n", lines = 2, totalLines = 2, totalBytes = 4, returnedBytes = 4,
            offset = 0, limit = 1 shl 20, truncated = false, bytesRead = 4, sha256 = "beef",
        )
        val byteJson = boundedFileReadJson("S", "/f", slice)
        assertEquals(true, byteJson.contains("\"sha256\":\"beef\""))
        val lineJson = lineModeFileReadJson("S", "/f", 0, 1, 10) { _, _ -> slice }
        assertEquals(true, lineJson.contains("\"sha256\":\"beef\""))
        val legacy = boundedFileReadJson("S", "/f", slice.copy(sha256 = ""))
        assertEquals(false, legacy.contains("sha256"))
    }

    @Test
    fun modelFacingResultKeepsJsonAsStructuredContent() {
        val json = """{"type":"file_write","path":"/a","ok":true,"sha256":"ff"}"""
        val result = shellModelFacingResult("file_write", "{}", ToolExecutionResult(json))
        assertEquals("path: /a\nok: true\nsha256: ff", result.text)
        assertEquals(json, result.structuredContent)
        // Non-shell tools and already-structured results pass through untouched.
        assertEquals(json, shellModelFacingResult("web_search", "{}", ToolExecutionResult(json)).text)
    }

    @Test
    fun terminalJobAcknowledgementReadsStructuredResult() {
        val json = """{"type":"wait_for_job","job_id":"j9","result":{"state":"succeeded"}}"""
        val acks = terminalShellJobAcknowledgements(
            listOf(
                ToolCallData(
                    toolName = "wait_for_job",
                    arguments = """{"server":"Quantum","job_id":"j9"}""",
                    result = "job_id: j9\nstate: succeeded",
                    structuredResult = json,
                ),
            ),
        )
        assertEquals(listOf(TerminalShellJobAcknowledgement("Quantum", "j9")), acks)
    }
}
