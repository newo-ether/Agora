package com.newoether.agora.tool

import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.util.ShellFileReadResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ShellFileReadTest {
    @Test
    fun relativeMarkdownStillReadsDiskAndAddsMemoryHintInBothModes() = runTest {
        for (mode in listOf("", ",\"start_line\":1,\"max_lines\":1")) {
            val backend = mockk<Backend>(relaxed = true)
            every { backend.device } returns null
            coEvery { backend.fileRead("notes.md", any(), any()) } returns ShellFileReadResult(
                content = "disk text\n", lines = 1, totalLines = 1, totalBytes = 10,
                returnedBytes = 10, offset = 0, limit = 1_048_576, truncated = false,
            )
            val output = executeDiskFileRead(
                """{"server":"device","path":"notes.md"$mode}""",
                openBackend = { name -> assertEquals("device", name); backend },
                missingServer = { "missing $it" },
            )
            val root = Json.parseToJsonElement(output).jsonObject
            assertTrue(output.contains("disk text"))
            assertTrue(root["hint"]!!.jsonPrimitive.content.contains("memory_read"))
            coVerify(atLeast = 1) { backend.fileRead("notes.md", any(), any()) }
            verify(exactly = 1) { backend.close() }
        }
    }

    @Test
    fun missingDeviceHasHintButExplicitPathsAndOtherExtensionsDoNot() = runTest {
        val output = executeDiskFileRead("""{"path":"missing.md"}""", { null }, { "missing" })
        assertTrue(output.contains("memory_read"))
        for (path in listOf("/tmp/notes.md", "C:\\notes.md", "notes.txt")) {
            assertEquals("{}", withMemoryEntryHint(path, "{}"))
        }
    }

    @Test
    fun missingFileFailuresLeadWithReasonAndKeepServerPathAndDiagnosticInBothModes() = runTest {
        val path = "F:\\workspace\\repo\\Agora\\missing.kt"
        val diagnostic = "open: open $path: The system cannot find the file specified."
        val device = mockk<ShellDeviceConfig>()
        every { device.name } returns "Quantum"
        for (lineMode in listOf(false, true)) {
            for (throws in listOf(false, true)) {
                val backend = mockk<Backend>(relaxed = true)
                every { backend.device } returns device
                if (throws) {
                    coEvery { backend.fileRead(any(), any(), any()) } throws IllegalStateException(diagnostic)
                } else {
                    coEvery { backend.fileRead(any(), any(), any()) } returns ShellFileReadResult(
                        content = "", lines = 0, totalLines = 0, totalBytes = 0, returnedBytes = 0,
                        offset = 0, limit = 1_048_576, truncated = false, error = diagnostic,
                    )
                }
                val arguments = buildJsonObject {
                    put("path", path)
                    if (lineMode) put("start_line", 1)
                }.toString()
                val output = executeDiskFileRead(arguments, { backend }, { "missing server" })
                val root = Json.parseToJsonElement(output).jsonObject
                assertEquals("file_read", root["type"]!!.jsonPrimitive.content)
                assertEquals("error", root["error"]!!.jsonPrimitive.content)
                assertEquals("File not found: $path", root["message"]!!.jsonPrimitive.content)
                assertEquals("Quantum", root["server"]!!.jsonPrimitive.content)
                assertEquals(path, root["path"]!!.jsonPrimitive.content)
                assertEquals(diagnostic, root["detail"]!!.jsonPrimitive.content)
                assertTrue(shellToolModelText("file_read", output)!!.startsWith("error: File not found: $path"))
                verify(exactly = 1) { backend.close() }
            }
        }
    }

    @Test
    fun missingFileNormalizationRequiresExactPathAndKnownOsReason() {
        val path = "/tmp/note.txt"
        for (diagnostic in listOf(
            "open: open $path: no such file or directory",
            "stat $path: no such file or directory",
            "lstat: lstat $path: file does not exist",
            "$path (No such file or directory)",
            "File not found: $path",
        )) {
            val root = Json.parseToJsonElement(fileReadErrorJson("Quantum", path, diagnostic)).jsonObject
            assertEquals("File not found: $path", root["message"]!!.jsonPrimitive.content)
            if (diagnostic == "File not found: $path") assertNull(root["detail"])
        }
        for (diagnostic in listOf(
            "open: open $path: permission denied",
            "open: open $path: access is denied",
            "open: open /different/file: no such file or directory",
            "Conch response authentication failed at https://example.invalid",
            "HTTP 404: endpoint not found",
            "Read failed: no such file or directory mentioned in server log",
        )) {
            val root = Json.parseToJsonElement(fileReadErrorJson("Quantum", path, diagnostic)).jsonObject
            assertEquals(diagnostic, root["message"]!!.jsonPrimitive.content)
            assertNull(root["detail"])
        }
    }

    @Test
    fun successfulContentContainingMissingFileDiagnosticIsNeverReclassified() = runTest {
        val path = "/tmp/log.txt"
        val content = "open: open $path: no such file or directory\n"
        for (lineMode in listOf(false, true)) {
            val backend = mockk<Backend>(relaxed = true)
            every { backend.device } returns null
            coEvery { backend.fileRead(any(), any(), any()) } returns ShellFileReadResult(
                content = content, lines = 1, totalLines = 1, totalBytes = content.length.toLong(),
                returnedBytes = content.length.toLong(), offset = 0, limit = 1_048_576, truncated = false,
            )
            val arguments = buildJsonObject {
                put("path", path)
                if (lineMode) put("start_line", 1)
            }.toString()
            val root = Json.parseToJsonElement(executeDiskFileRead(arguments, { backend }, { "missing" })).jsonObject
            assertNull(root["error"])
            assertNull(root["detail"])
            assertTrue(root["content"]!!.jsonPrimitive.content.contains(content.trimEnd()))
            verify(exactly = 1) { backend.close() }
        }
    }

    @Test
    fun cancellationClosesBackendAndPropagatesInBothModes() = runTest {
        for (mode in listOf("", ",\"start_line\":1")) {
            val backend = mockk<Backend>(relaxed = true)
            every { backend.device } returns null
            coEvery { backend.fileRead(any(), any(), any()) } throws CancellationException("stop")
            try {
                executeDiskFileRead("""{"path":"notes.md"$mode}""", { backend }, { "missing" })
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                verify(exactly = 1) { backend.close() }
            }
        }
    }
}
