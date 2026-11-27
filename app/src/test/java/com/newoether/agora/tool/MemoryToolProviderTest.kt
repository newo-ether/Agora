package com.newoether.agora.tool

import com.newoether.agora.data.MemoryManager
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolProviderTest {
    private val memoryManager = mockk<MemoryManager> {
        every { listFiles() } returns listOf(
            MemoryManager.MemoryFileInfo("notes.md", "My notes"),
            MemoryManager.MemoryFileInfo("data.json", "JSON data"),
        )
        every { readFile(any()) } returns "file content"
        every { createFile(any(), any(), any()) } returns "Created"
        every { editFile(any(), any(), any(), any(), any(), any()) } returns "Edited"
        every { deleteFile(any()) } returns "Deleted"
        every { updateActiveMemory(any(), any(), any(), any()) } returns "Updated"
    }
    private val provider = MemoryToolProvider(memoryManager)
    private val enabled = GenerationContext(
        accessSavedMemories = true,
        accessActiveMemory = true,
    )

    @Test
    fun diskPathsAndMissingEntriesSuggestDiskToolsAndActiveReplaceStaysExplicit() = runTest {
        for (tool in listOf("memory_read", "memory_edit", "read_memory_file", "edit_memory_file")) {
            val result = provider.executeOneShotResult(tool, """{"name":"C:/notes.md","operation":"replace","content":"x"}""", enabled)
            assertTrue(result.isError)
            assertTrue(result.text.contains("file_read") && result.text.contains("server"))
        }
        val editWithExtraNames = provider.executeOneShotResult(
            "memory_edit", """{"name":"C:/notes.md","names":["notes.md"],"operation":"replace","content":"x"}""", enabled,
        )
        assertTrue(editWithExtraNames.isError)
        every { memoryManager.editFile("missing.md", any(), any(), any(), any(), any()) } throws
            IllegalArgumentException("File not found: missing.md")
        assertTrue(provider.executeOneShotResult(
            "memory_edit", """{"name":"missing.md","operation":"replace","content":"x"}""", enabled,
        ).text.contains("file_read"))
        every { memoryManager.readFile("missing.md") } throws IllegalArgumentException("File not found: missing.md")
        assertTrue(provider.executeOneShotResult("memory_read", """{"name":"missing.md"}""", enabled).text.contains("file_read"))
        assertEquals("Updated", provider.execute(
            "active_memory_update", """{"content":"all","mode":"replace"}""", enabled,
        ))
        verify { memoryManager.updateActiveMemory("all", "replace", null, null) }
        assertEquals("file content", provider.execute("read_memory_file", """{"name":"notes.md"}""", enabled))
    }

    @Test
    fun definitionsExposeSevenMemoryToolsAndExplicitOperations() {
        val definitions = provider.definitions(enabled)
        assertEquals(7, definitions.size)
        assertEquals(
            setOf(
                "memory_list",
                "memory_read",
                "memory_create",
                "memory_edit",
                "memory_delete",
                "active_memory_read",
                "active_memory_update",
            ),
            definitions.map { it.function.name }.toSet(),
        )

        val edit = definitions.single { it.function.name == "memory_edit" }.function.parameters
        assertEquals(listOf("name", "operation"), edit.required)
        assertEquals(
            setOf(
                "name",
                "operation",
                "content",
                "old_string",
                "new_string",
                "new_name",
                "description",
            ),
            edit.properties.keys,
        )
        val active = definitions.single { it.function.name == "active_memory_update" }.function
        assertEquals(listOf("content", "mode"), active.parameters.required)
        assertFalse(active.description.contains("Default is replace"))
        assertFalse(active.parameters.properties.getValue("mode").description.contains("Default is replace"))
    }

    @Test
    fun definitionsRespectMemoryAccessSettings() {
        val activeOnly = enabled.copy(accessSavedMemories = false)
        assertEquals(
            listOf("active_memory_read", "active_memory_update"),
            provider.definitions(activeOnly).map { it.function.name },
        )
        assertTrue(
            provider.definitions(
                enabled.copy(accessSavedMemories = false, accessActiveMemory = false),
            ).isEmpty(),
        )
    }

    @Test
    fun listAndReadReturnSavedMemoryData() = runTest {
        val listResult = provider.execute("memory_list", "{}", enabled)
        assertTrue(listResult.contains("memory_list"))
        assertTrue(listResult.contains("notes.md"))
        assertTrue(listResult.contains("My notes"))
        assertEquals(
            "file content",
            provider.execute(
                "memory_read",
                """{"name":"notes.md"}""",
                enabled,
            ),
        )
    }

    @Test
    fun createMemoryFile() = runTest {
        assertEquals(
            "Created",
            provider.execute(
                "memory_create",
                """{"name":"new.md","content":"hello"}""",
                enabled,
            ),
        )
    }

    @Test
    fun editSupportsFullReplacementIncludingEmptyContent() = runTest {
        assertEquals(
            "Edited",
            provider.execute(
                "memory_edit",
                """{"name":"notes.md","operation":"replace","content":"replacement","old_string":"","new_string":"","new_name":"","description":""}""",
                enabled,
            ),
        )
        assertEquals(
            "Edited",
            provider.execute(
                "memory_edit",
                """{"name":"notes.md","operation":"replace","content":"","old_string":"","new_string":"","new_name":"","description":""}""",
                enabled,
            ),
        )
        verify {
            memoryManager.editFile(
                name = "notes.md",
                content = "replacement",
                newName = null,
                description = null,
                oldString = null,
                newString = null,
            )
            memoryManager.editFile(
                name = "notes.md",
                content = "",
                newName = null,
                description = null,
                oldString = null,
                newString = null,
            )
        }
    }

    @Test
    fun editSupportsPatchIncludingDeletion() = runTest {
        assertEquals(
            "Edited",
            provider.execute(
                "memory_edit",
                """{"name":"notes.md","operation":"patch","content":"","old_string":"old","new_string":"new","new_name":"","description":""}""",
                enabled,
            ),
        )
        assertEquals(
            "Edited",
            provider.execute(
                "memory_edit",
                """{"name":"notes.md","operation":"patch","content":"","old_string":"remove","new_string":"","new_name":"","description":""}""",
                enabled,
            ),
        )
        verify {
            memoryManager.editFile(
                name = "notes.md",
                content = null,
                newName = null,
                description = null,
                oldString = "old",
                newString = "new",
            )
            memoryManager.editFile(
                name = "notes.md",
                content = null,
                newName = null,
                description = null,
                oldString = "remove",
                newString = "",
            )
        }
    }

    @Test
    fun editSupportsRenameWithoutPassingBridgeDefaults() = runTest {
        assertEquals(
            "Edited",
            provider.execute(
                "memory_edit",
                """{"name":"notes.md","operation":"rename","content":"","old_string":"","new_string":"","new_name":"renamed.md","description":""}""",
                enabled,
            ),
        )
        verify {
            memoryManager.editFile(
                name = "notes.md",
                content = null,
                newName = "renamed.md",
                description = null,
                oldString = null,
                newString = null,
            )
        }
    }

    @Test
    fun editSupportsSettingAndClearingDescription() = runTest {
        assertEquals(
            "Edited",
            provider.execute(
                "memory_edit",
                """{"name":"notes.md","operation":"describe","content":"","old_string":"","new_string":"","new_name":"","description":"Reference notes"}""",
                enabled,
            ),
        )
        assertEquals(
            "Edited",
            provider.execute(
                "memory_edit",
                """{"name":"notes.md","operation":"describe","content":"","old_string":"","new_string":"","new_name":"","description":""}""",
                enabled,
            ),
        )
        verify {
            memoryManager.editFile(
                name = "notes.md",
                content = null,
                newName = null,
                description = "Reference notes",
                oldString = null,
                newString = null,
            )
            memoryManager.editFile(
                name = "notes.md",
                content = null,
                newName = null,
                description = "",
                oldString = null,
                newString = null,
            )
        }
    }

    @Test
    fun invalidEditOperationsDoNotMutateMemory() = runTest {
        listOf(
            """{"name":"notes.md","operation":"","content":"","old_string":"","new_string":"","new_name":"","description":""}""",
            """{"name":"notes.md","operation":"patch","content":"","old_string":"","new_string":"","new_name":"","description":""}""",
            """{"name":"notes.md","operation":"rename","content":"","old_string":"","new_string":"","new_name":"","description":""}""",
            """{"name":"notes.md","operation":"move","content":"","old_string":"","new_string":"","new_name":"other.md","description":""}""",
        ).forEach { arguments ->
            assertTrue(
                provider.execute("memory_edit", arguments, enabled)
                    .contains("Error", ignoreCase = true),
            )
        }
        verify(exactly = 0) {
            memoryManager.editFile(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun activeMemoryUpdateRequiresAValidExplicitModeWithoutWritingOnFailure() = runTest {
        listOf(
            """{"content":"all"}""",
            """{"content":"all","mode":""}""",
            """{"content":"all","mode":"replace_all"}""",
        ).forEach { arguments ->
            val result = provider.executeOneShotResult("active_memory_update", arguments, enabled)
            assertTrue(result.isError)
            assertTrue(result.text.contains("mode", ignoreCase = true))
        }
        verify(exactly = 0) { memoryManager.updateActiveMemory(any(), any(), any(), any()) }

        assertEquals(
            "Updated",
            provider.execute(
                "active_memory_update", """{"content":"all","mode":"replace"}""", enabled,
            ),
        )
        verify(exactly = 1) { memoryManager.updateActiveMemory("all", "replace", null, null) }
    }

    @Test
    fun updateActiveMemoryPatch() = runTest {
        assertEquals(
            "Updated",
            provider.execute(
                "active_memory_update",
                """{"content":"placeholder","mode":"patch","old_string":"foo","new_string":"bar"}""",
                enabled,
            ),
        )
        verify { memoryManager.updateActiveMemory("placeholder", "patch", "foo", "bar") }
    }

    @Test
    fun successfulErrorPrefixedReadAndValidationFailureHaveExplicitMetadata() = runTest {
        every { memoryManager.readFile("notes.md") } returns "Error: documented example"
        val success = provider.executeEvents("memory_read", """{"name":"notes.md"}""", enabled)
            .toList().single() as ToolExecutionEvent.Completed
        assertFalse(success.result.isError)
        assertEquals("Error: documented example", success.result.text)
        val failure = provider.executeEvents("memory_read", "{}", enabled)
            .toList().single() as ToolExecutionEvent.Completed
        assertTrue(failure.result.isError)
        assertTrue(failure.result.text.contains("No entry name"))
    }

    @Test
    fun readActiveMemoryReturnsStoredText() = runTest {
        every { memoryManager.getActiveMemory() } returns "# index\n- a.md"
        assertEquals("# index\n- a.md", provider.execute("active_memory_read", "{}", enabled))
    }
    @Test
    fun handlesOnlyMemoryTools() {
        assertTrue(provider.handles("memory_list"))
        assertTrue(provider.handles("active_memory_read"))
        assertTrue(provider.handles("active_memory_update"))
        assertFalse(provider.handles("web_search"))
        assertFalse(provider.handles("unknown_tool"))
    }
}
