package com.newoether.agora.tool

import android.util.Log
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SHELL_COMMAND_MAX_BYTES
import com.newoether.agora.util.SHELL_FILE_WRITE_MAX_BYTES
import com.newoether.agora.util.SHELL_WORKDIR_MAX_BYTES
import com.newoether.agora.util.ShellClient
import com.newoether.agora.util.ShellFileReadResult
import com.newoether.agora.util.editShellFileContent
import com.newoether.agora.util.fileEditMatchRanges
import com.newoether.agora.util.replaceFileEditMatches
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer

class WebSearchToolProviderTest {
    private val provider = WebSearchToolProvider()
    private val enabledCtx = GenerationContext(webSearchEnabled = true)
    private val disabledCtx = GenerationContext(webSearchEnabled = false)

    @Test
    fun definitions_whenEnabled_returnsTwoTools() {
        val defs = provider.definitions(enabledCtx)
        assertEquals(2, defs.size)
        assertEquals("web_search", defs[0].function.name)
        assertEquals("web_fetch", defs[1].function.name)
    }

    @Test
    fun definitions_whenDisabled_returnsEmpty() {
        assertTrue(provider.definitions(disabledCtx).isEmpty())
    }

    @Test
    fun handles_returnsTrueForWebTools() {
        assertTrue(provider.handles("web_search"))
        assertTrue(provider.handles("web_fetch"))
        assertFalse(provider.handles("unknown"))
    }
}

class RagToolProviderTest {
    private val provider = RagToolProvider(mockk(relaxed = true))
    private val enabledCtx = GenerationContext(accessPastConversations = true)
    private val disabledCtx = GenerationContext(accessPastConversations = false)

    @Test
    fun definitions_whenEnabled_returnsThreeTools() {
        val defs = provider.definitions(enabledCtx)
        assertEquals(3, defs.size)
        assertEquals("conversation_search", defs[0].function.name)
        assertEquals("conversation_list", defs[1].function.name)
        assertEquals("conversation_read", defs[2].function.name)
    }

    @Test
    fun definitions_whenDisabled_returnsEmpty() {
        assertTrue(provider.definitions(disabledCtx).isEmpty())
    }

    @Test
    fun handles_returnsTrueForConversationTools() {
        // RagToolProvider now owns execution of the conversation tools.
        assertTrue(provider.handles("conversation_search"))
        assertTrue(provider.handles("conversation_list"))
        assertTrue(provider.handles("conversation_read"))
        assertFalse(provider.handles("web_search"))
    }
}

class ShellToolProviderTest {
    private val provider = ShellToolProvider()
    private val emptyCtx = GenerationContext(shellEnabled = false)
    private val disabledCtx = GenerationContext(shellEnabled = true, shellDevices = emptyList())

    @Test
    fun definitions_whenDisabled_returnsEmpty() {
        assertTrue(provider.definitions(emptyCtx).isEmpty())
        assertTrue(provider.definitions(disabledCtx).isEmpty())
    }

    @Test
    fun definitions_whenSingleDevice_shellAndFileTools() {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(
                com.newoether.agora.data.ShellDeviceConfig(name = "server1", serverUrl = "http://localhost")
            )
        )
        val defs = provider.definitions(ctx)
        assertEquals(13, defs.size)
        val names = defs.map { it.function.name }.toSet()
        assertEquals(
            setOf(
                "shell_list",
                "shell_exec",
                "shell_job_list",
                "shell_job_get",
                "shell_job_wait",
                "shell_job_stop",
                "file_read",
                "file_write",
                "file_edit",
                "file_multi_edit",
                "file_glob",
                "file_grep",
                "file_view_image",
            ),
            names,
        )
        val command = defs.single { it.function.name == "shell_exec" }
        assertTrue(command.function.parameters.properties.containsKey("background"))
        assertTrue(command.function.parameters.properties.containsKey("head_lines"))
        assertTrue(command.function.parameters.properties.containsKey("tail_lines"))
        defs.filter { it.function.name.startsWith("shell_") }.forEach {
            assertTrue(it.function.parameters.properties.containsKey("description"))
            assertTrue(it.function.parameters.required.contains("description"))
        }
        val listJobs = defs.single { it.function.name == "shell_job_list" }
        assertTrue(listJobs.function.parameters.properties.keys.containsAll(listOf("offset", "limit")))
        val getJob = defs.single { it.function.name == "shell_job_get" }
        assertEquals(listOf("job_id", "description"), getJob.function.parameters.required)
        val edit = defs.single { it.function.name == "file_edit" }.function.parameters
        assertTrue("edits" !in edit.properties)
        assertTrue(edit.required.containsAll(listOf("old_string", "new_string")))
        val multi = defs.single { it.function.name == "file_multi_edit" }.function.parameters
        assertTrue(listOf("old_string", "new_string", "replace_all").none { it in multi.properties })
        assertTrue("edits" in multi.required)
    }

    @Test
    fun definitions_whenMultiDevice_commandRequiresServer() {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(
                com.newoether.agora.data.ShellDeviceConfig(name = "s1", serverUrl = "http://a"),
                com.newoether.agora.data.ShellDeviceConfig(name = "s2", serverUrl = "http://b")
            )
        )
        val defs = provider.definitions(ctx)
        val cmdTool = defs.find { it.function.name == "shell_exec" }
        assertNotNull(cmdTool)
        assertEquals(
            listOf("description", "command", "server", "timeout_ms"),
            cmdTool!!.function.parameters.required,
        )
    }

    @Test
    fun definitions_singleDevice_commandRequiresTimeout() {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(
                com.newoether.agora.data.ShellDeviceConfig(name = "s1", serverUrl = "http://a"),
            ),
        )
        val defs = provider.definitions(ctx)
        val cmdTool = defs.single { it.function.name == "shell_exec" }
        assertEquals(listOf("description", "command", "timeout_ms"), cmdTool.function.parameters.required)
        assertTrue(
            cmdTool.function.parameters.properties.getValue("timeout_ms").description
                .contains("never killed or restarted")
        )
        val waitTool = defs.single { it.function.name == "shell_job_wait" }
        assertTrue(waitTool.function.parameters.required.contains("timeout_ms"))
        assertTrue(waitTool.function.parameters.required.contains("job_id"))
        assertTrue(
            waitTool.function.parameters.properties.getValue("timeout_ms").description
                .contains("295000ms")
        )
        assertEquals(295_000, ShellToolProvider.maxWaitMs(ctx))
        assertTrue(
            defs.single { it.function.name == "file_glob" }.function.description
                .contains("1000"),
        )
        assertTrue(
            defs.single { it.function.name == "file_grep" }.function.description
                .contains("500"),
        )
        assertTrue(
            cmdTool.function.parameters.properties.getValue("command").description
                .contains("64KB"),
        )
        assertTrue(
            cmdTool.function.parameters.properties.getValue("workdir").description
                .contains("32KB"),
        )
        assertTrue(cmdTool.function.description.contains("1MB"))
    }

    @Test
    fun executeShellCommand_missingTimeout_returnsError() = kotlinx.coroutines.test.runTest {
        val ctx = GenerationContext(
            shellEnabled = true,
            shellDevices = listOf(
                com.newoether.agora.data.ShellDeviceConfig(name = "s1", serverUrl = "http://a"),
            ),
        )
        val result = provider.execute(
            "shell_exec", """{"command":"echo hi","server":"s1"}""", ctx,
        )
        assertTrue(result.contains("timeout_ms is required"))
    }

    @Test
    fun executeShellCommandValidatesUtf8LimitsInOneShotAndEventPaths() =
        kotlinx.coroutines.test.runTest {
            val ctx = GenerationContext(shellEnabled = true)

            suspend fun assertBothPaths(arguments: String, expected: String) {
                val oneShot = provider.execute("shell_exec", arguments, ctx)
                val events = provider.executeEvents(
                    "shell_exec",
                    arguments,
                    ctx,
                ).toList()
                val streamed = (events.single() as ToolExecutionEvent.Completed).result

                assertTrue(oneShot.contains(expected))
                // The model gets plain text; the JSON envelope rides along as structured content.
                assertEquals(oneShot, streamed.structuredContent)
                assertTrue(streamed.text.startsWith("error: $expected"))
            }

            val oversizedCommand = "界".repeat(SHELL_COMMAND_MAX_BYTES / 3 + 1)
            assertBothPaths(
                buildJsonObject {
                    put("command", oversizedCommand)
                    put("timeout_ms", 1_000)
                }.toString(),
                "command exceeds 64KB limit",
            )

            val oversizedWorkdir = "界".repeat(SHELL_WORKDIR_MAX_BYTES / 3 + 1)
            assertBothPaths(
                buildJsonObject {
                    put("command", "echo ok")
                    put("workdir", oversizedWorkdir)
                    put("timeout_ms", 1_000)
                }.toString(),
                "workdir exceeds 32KB limit",
            )
        }

    @Test
    fun fileWriteRejectsUtf8ContentOverOneMbBeforeBackendResolution() =
        kotlinx.coroutines.test.runTest {
            val oversized = "\u754c".repeat(SHELL_FILE_WRITE_MAX_BYTES / 3 + 1)
            val result = provider.execute(
                "file_write",
                buildJsonObject {
                    put("path", "/home/agora/large.txt")
                    put("content", oversized)
                }.toString(),
                GenerationContext(shellEnabled = true),
            )

            assertTrue(result.contains("content exceeds 1MB limit"))
        }

    @Test
    fun conchFileEditSendsBooleanReplaceAllAndMapsResponse() = runBlocking {
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        var requestBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/file/edit") { exchange ->
                requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
                val response =
                    """{"ok":true,"replacements":2,"sha256":"abc123"}"""
                        .toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        try {
            val result = ShellClient(
                serverUrl = "http://127.0.0.1:${server.address.port}",
                apiKey = "",
            ).fileEdit(
                path = "/tmp/test.txt",
                oldString = "old",
                newString = "new",
                replaceAll = true,
            )
            val request = Json.parseToJsonElement(requestBody).jsonObject

            assertFalse(request.getValue("replace_all").jsonPrimitive.isString)
            assertEquals("true", request.getValue("replace_all").jsonPrimitive.content)
            assertEquals(2, result.replacements)
            assertEquals("abc123", result.sha256)
            assertNull(result.error)
        } finally {
            server.stop(0)
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun fileSearchPreservesConchTruncationMetadataInToolJson() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/file/glob") { exchange ->
                exchange.requestBody.close()
                val response = """{"files":["/tmp/a.kt"],"truncated":true}"""
                    .toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            createContext("/file/grep") { exchange ->
                exchange.requestBody.close()
                val response =
                    """{"matches":[{"path":"/tmp/a.kt","line":3,"content":"needle"}],"truncated":true}"""
                        .toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        try {
            val ctx = GenerationContext(
                shellEnabled = true,
                shellDevices = listOf(
                    com.newoether.agora.data.ShellDeviceConfig(
                        name = "server1",
                        serverUrl = "http://127.0.0.1:${server.address.port}",
                    ),
                ),
            )

            val glob = Json.parseToJsonElement(
                provider.execute("file_glob", """{"pattern":"*.kt"}""", ctx),
            ).jsonObject
            val grep = Json.parseToJsonElement(
                provider.execute("file_grep", """{"pattern":"needle"}""", ctx),
            ).jsonObject

            assertEquals("true", glob.getValue("truncated").jsonPrimitive.content)
            assertEquals("/tmp/a.kt", glob.getValue("files").toString().trim('[', ']', '"'))
            assertEquals("true", grep.getValue("truncated").jsonPrimitive.content)
            assertTrue(grep.getValue("matches").toString().contains("needle"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fileEdit_lfMatcherUpdatesCrLfWithoutNormalizingUnmatchedContent() {
        val content = "before\r\nalpha\r\nbeta\r\nafter\r\n"
        val matches = fileEditMatchRanges(content, "alpha\nbeta")

        assertEquals(1, matches.size)
        assertEquals(
            "before\r\ngamma\r\ndelta\r\nafter\r\n",
            replaceFileEditMatches(content, matches, "gamma\ndelta"),
        )
    }

    @Test
    fun fileEdit_crLfMatcherUpdatesLfAndKeepsLf() {
        val content = "before\nalpha\nbeta\nafter\n"
        val matches = fileEditMatchRanges(content, "alpha\r\nbeta")

        assertEquals(1, matches.size)
        assertEquals(
            "before\ngamma\ndelta\nafter\n",
            replaceFileEditMatches(content, matches, "gamma\r\ndelta"),
        )
    }

    @Test
    fun fileEdit_replaceAllUsesEachMatchedSpanLineEnding() {
        val content = "one\r\ntwo|one\ntwo|tail\r\n"
        val matches = fileEditMatchRanges(content, "one\ntwo")

        assertEquals(2, matches.size)
        assertEquals(
            "three\r\nfour|three\nfour|tail\r\n",
            replaceFileEditMatches(content, matches, "three\nfour"),
        )
    }

    @Test
    fun fileEdit_multilineReplacementForSingleLineMatchUsesFileLineEnding() {
        val content = "head\r\ntarget\r\ntail\r\n"
        val matches = fileEditMatchRanges(content, "target")

        assertEquals(
            "head\r\nfirst\r\nsecond\r\ntail\r\n",
            replaceFileEditMatches(content, matches, "first\nsecond"),
        )
    }

    @Test
    fun fileEdit_matchCountPreservesNotFoundAndAmbiguousGuards() {
        assertTrue(fileEditMatchRanges("alpha\r\nbeta", "alpha gamma").isEmpty())
        assertEquals(2, fileEditMatchRanges("same\r\nsame\n", "same").size)
    }

    @Test
    fun fileEditContentReturnsSharedBackendResultWithoutPartialMutation() {
        val content = "same\r\nsame\r\ntail\r\n"
        val (ambiguousContent, ambiguousResult) = editShellFileContent(
            content = content,
            oldString = "same",
            newString = "changed",
            replaceAll = false,
        )
        assertNull(ambiguousContent)
        assertEquals(0, ambiguousResult.replacements)
        assertTrue(ambiguousResult.error.orEmpty().contains("found 2 matches"))

        val (editedContent, editedResult) = editShellFileContent(
            content = content,
            oldString = "same",
            newString = "changed",
            replaceAll = true,
        )
        assertEquals("changed\r\nchanged\r\ntail\r\n", editedContent)
        assertEquals(2, editedResult.replacements)
        assertNull(editedResult.error)
    }

    @Test
    fun fileReadEnvelopeBoundsEscapedContentWithoutBreakingJson() {
        val content = buildString {
            repeat(40_000) {
                append('"')
                append('\\')
                append('\n')
                append("\uD83D\uDE00")
            }
        }
        val totalBytes = content.toByteArray(Charsets.UTF_8).size.toLong()

        val raw = boundedFileReadJson(
            server = "server1",
            path = "/tmp/large.txt",
            result = ShellFileReadResult(
                content = content,
                lines = 40_001,
                totalLines = 40_001,
                totalBytes = totalBytes,
                returnedBytes = totalBytes,
                offset = 7,
                limit = 1_048_576,
                truncated = false,
            ),
        )
        val json = Json.parseToJsonElement(raw).jsonObject
        val returnedContent = json.getValue("content").jsonPrimitive.content

        assertTrue(raw.length <= Constants.MAX_TOOL_RESULT_LENGTH)
        assertTrue(returnedContent.isNotEmpty())
        assertTrue(content.startsWith(returnedContent))
        assertEquals("true", json.getValue("truncated").jsonPrimitive.content)
        assertEquals(
            returnedContent.toByteArray(Charsets.UTF_8).size.toLong(),
            json.getValue("returned_bytes").jsonPrimitive.content.toLong(),
        )
        assertEquals(totalBytes, json.getValue("total_bytes").jsonPrimitive.content.toLong())
        assertEquals(7L, json.getValue("offset").jsonPrimitive.content.toLong())
    }

    @Test
    fun fileReadEnvelopePreservesCompleteReadMetadata() {
        val content = "alpha\nbeta"
        val raw = boundedFileReadJson(
            server = "Local Sandbox",
            path = "/tmp/small.txt",
            result = ShellFileReadResult(
                content = content,
                lines = 2,
                totalLines = 2,
                totalBytes = 10,
                returnedBytes = 10,
                offset = 0,
                limit = 1_048_576,
                truncated = false,
            ),
        )
        val json = Json.parseToJsonElement(raw).jsonObject

        assertEquals(content, json.getValue("content").jsonPrimitive.content)
        assertEquals("false", json.getValue("truncated").jsonPrimitive.content)
        assertEquals(2, json.getValue("lines").jsonPrimitive.content.toInt())
        assertEquals(2, json.getValue("total_lines").jsonPrimitive.content.toInt())
        assertEquals(10L, json.getValue("returned_bytes").jsonPrimitive.content.toLong())
    }

    @Test
    fun handles_returnsTrueForShellAndFileTools() {
        assertTrue(provider.handles("shell_list"))
        assertTrue(provider.handles("shell_exec"))
        assertTrue(provider.handles("shell_job_list"))
        assertTrue(provider.handles("shell_job_get"))
        assertTrue(provider.handles("shell_job_wait"))
        assertTrue(provider.handles("shell_job_stop"))
        assertTrue(provider.handles("file_read"))
        assertTrue(provider.handles("file_write"))
        assertTrue(provider.handles("file_edit"))
        assertTrue(provider.handles("file_multi_edit"))
        assertTrue(provider.handles("file_glob"))
        assertTrue(provider.handles("file_grep"))
        assertTrue(provider.handles("file_view_image"))
        assertFalse(provider.handles("unknown"))
    }
}
