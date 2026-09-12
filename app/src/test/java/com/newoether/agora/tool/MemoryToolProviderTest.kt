package com.newoether.agora.tool

import com.newoether.agora.data.MemoryManager
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
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
        every { pinFile(any(), any()) } returns "Pinned"
        every { updateActiveMemory(any(), any(), any(), any()) } returns "Updated"
    }
    private val provider = MemoryToolProvider(memoryManager)
    private val enabled = GenerationContext(
        accessSavedMemories = true,
        accessActiveMemory = true,
    )

    @Test
    fun definitionsExposeSixMemoryToolsAndExplicitEditOperation() {
        val definitions = provider.definitions(enabled)
        assertEquals(7, definitions.size)
        assertEquals(
            setOf(
                "list_memory_files",
                "read_memory_file",
                "create_memory_file",
                "edit_memory_file",
                "delete_memory_file",
                "pin_memory_file",
                "update_active_memory",
            ),
            definitions.map { it.function.name }.toSet(),
        )

        val edit = definitions.single { it.function.name == "edit_memory_file" }.function.parameters
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
    }

    @Test
    fun definitionsRespectMemoryAccessSettings() {
        val activeOnly = enabled.copy(accessSavedMemories = false)
        assertEquals(
            listOf("update_active_memory"),
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
        val listResult = provider.execute("list_memory_files", "{}", enabled)
        assertTrue(listResult.contains("list_memory_files"))
        assertTrue(listResult.contains("notes.md"))
        assertTrue(listResult.contains("My notes"))
        assertEquals(
            "file content",
            provider.execute(
                "read_memory_file",
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
                "create_memory_file",
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
                "edit_memory_file",
                """{"name":"notes.md","operation":"replace","content":"replacement","old_string":"","new_string":"","new_name":"","description":""}""",
                enabled,
            ),
        )
        assertEquals(
            "Edited",
            provider.execute(
                "edit_memory_file",
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
                "edit_memory_file",
                """{"name":"notes.md","operation":"patch","content":"","old_string":"old","new_string":"new","new_name":"","description":""}""",
                enabled,
            ),
        )
        assertEquals(
            "Edited",
            provider.execute(
                "edit_memory_file",
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
                "edit_memory_file",
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
                "edit_memory_file",
                """{"name":"notes.md","operation":"describe","content":"","old_string":"","new_string":"","new_name":"","description":"Reference notes"}""",
                enabled,
            ),
        )
        assertEquals(
            "Edited",
            provider.execute(
                "edit_memory_file",
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
                provider.execute("edit_memory_file", arguments, enabled)
                    .contains("Error", ignoreCase = true),
            )
        }
        verify(exactly = 0) {
            memoryManager.editFile(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun updateActiveMemoryPatch() = runTest {
        assertEquals(
            "Updated",
            provider.execute(
                "update_active_memory",
                """{"content":"placeholder","mode":"patch","old_string":"foo","new_string":"bar"}""",
                enabled,
            ),
        )
        verify { memoryManager.updateActiveMemory("placeholder", "patch", "foo", "bar") }
    }

    @Test
    fun handlesOnlyMemoryTools() {
        assertTrue(provider.handles("list_memory_files"))
        assertTrue(provider.handles("update_active_memory"))
        assertTrue(provider.handles("pin_memory_file"))
        assertFalse(provider.handles("web_search"))
        assertFalse(provider.handles("unknown_tool"))
    }
}
