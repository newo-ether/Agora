package com.newoether.agora.tool

import com.newoether.agora.data.SkillManager
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

class SkillToolProviderTest {
    private val skillManager = mockk<SkillManager> {
        every { listFiles() } returns listOf(
            SkillManager.SkillFileInfo("review.md", "Review changes"),
        )
        every { document(any()) } answers { com.newoether.agora.skills.SkillDocument(firstArg(), "skill body", description = "Review changes") }
        every { createFile(any(), any(), any()) } returns "Created"
        every { editFile(any(), any(), any(), any(), any(), any()) } returns "Updated"
        every { deleteFile(any()) } returns "Deleted"
    }
    private val provider = SkillToolProvider(skillManager)
    private val enabled = GenerationContext(skillReadAccess = true, skillModifyAccess = true)

    @Test
    fun definitionsExposeExactlyFiveSavedSkillTools() {
        val definitions = provider.definitions(enabled)
        val names = definitions.map { it.function.name }
        assertEquals(
            listOf(
                "list_skill_files",
                "read_skill_file",
                "create_skill_file",
                "edit_skill_file",
                "delete_skill_file",
            ),
            names,
        )
        assertFalse(names.any { it.contains("active") })

        val edit = definitions.single { it.function.name == "edit_skill_file" }.function.parameters
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
    fun disabledAccessHidesAndRejectsTools() = runTest {
        val disabled = enabled.copy(skillReadAccess = false, skillModifyAccess = false)
        assertTrue(provider.definitions(disabled).isEmpty())
        assertTrue(
            provider.execute("list_skill_files", "{}", disabled)
                .contains("disabled", ignoreCase = true),
        )
    }

    @Test
    fun listReturnsOnlyCatalogMetadata() = runTest {
        val result = provider.execute("list_skill_files", "{}", enabled)
        assertTrue(result.contains("review.md"))
        assertFalse(result.contains("Review changes"))
        assertFalse(result.contains("skill body"))
    }

    @Test
    fun successfulErrorPrefixedReadAndAccessFailureHaveExplicitMetadata() = runTest {
        every { skillManager.document("review.md") } returns com.newoether.agora.skills.SkillDocument("review.md", "Error: documented example")
        val success = provider.executeEvents("read_skill_file", """{"name":"review.md"}""", enabled)
            .toList().single() as ToolExecutionEvent.Completed
        assertFalse(success.result.isError)
        assertTrue(success.result.text.endsWith("Error: documented example"))
        val failure = provider.executeEvents("read_skill_file", "{}", enabled.copy(skillReadAccess = false))
            .toList().single() as ToolExecutionEvent.Completed
        assertTrue(failure.result.isError)
        assertTrue(failure.result.text.contains("disabled"))
    }

    @Test
    fun readLoadsBodyOnDemand() = runTest {
        assertEquals(
            "--- review.md ---\nDescription: Review changes\nskill body",
            provider.execute(
                "read_skill_file",
                """{"name":"review.md"}""",
                enabled,
            ),
        )
    }

    @Test
    fun editSupportsFullReplacementIncludingEmptyContent() = runTest {
        assertEquals(
            "Updated",
            provider.execute(
                "edit_skill_file",
                """{"name":"review.md","operation":"replace","content":"replacement","old_string":"","new_string":"","new_name":"","description":""}""",
                enabled,
            ),
        )
        assertEquals(
            "Updated",
            provider.execute(
                "edit_skill_file",
                """{"name":"review.md","operation":"replace","content":"","old_string":"","new_string":"","new_name":"","description":""}""",
                enabled,
            ),
        )
        verify {
            skillManager.editFile(
                name = "review.md",
                content = "replacement",
                newName = null,
                description = null,
                oldString = null,
                newString = null,
            )
            skillManager.editFile(
                name = "review.md",
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
            "Updated",
            provider.execute(
                "edit_skill_file",
                """{"name":"review.md","operation":"patch","content":"","old_string":"old","new_string":"new","new_name":"","description":""}""",
                enabled,
            ),
        )
        assertEquals(
            "Updated",
            provider.execute(
                "edit_skill_file",
                """{"name":"review.md","operation":"patch","content":"","old_string":"remove","new_string":"","new_name":"","description":""}""",
                enabled,
            ),
        )
        verify {
            skillManager.editFile(
                name = "review.md",
                content = null,
                newName = null,
                description = null,
                oldString = "old",
                newString = "new",
            )
            skillManager.editFile(
                name = "review.md",
                content = null,
                newName = null,
                description = null,
                oldString = "remove",
                newString = "",
            )
        }
    }

    @Test
    fun editSupportsRename() = runTest {
        assertEquals(
            "Updated",
            provider.execute(
                "edit_skill_file",
                """{"name":"review.md","operation":"rename","content":"","old_string":"","new_string":"","new_name":"renamed.md","description":""}""",
                enabled,
            ),
        )
        verify {
            skillManager.editFile(
                name = "review.md",
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
            "Updated",
            provider.execute(
                "edit_skill_file",
                """{"name":"review.md","operation":"describe","content":"","old_string":"","new_string":"","new_name":"","description":"Review carefully"}""",
                enabled,
            ),
        )
        assertEquals(
            "Updated",
            provider.execute(
                "edit_skill_file",
                """{"name":"review.md","operation":"describe","content":"","old_string":"","new_string":"","new_name":"","description":""}""",
                enabled,
            ),
        )
        verify {
            skillManager.editFile(
                name = "review.md",
                content = null,
                newName = null,
                description = "Review carefully",
                oldString = null,
                newString = null,
            )
            skillManager.editFile(
                name = "review.md",
                content = null,
                newName = null,
                description = "",
                oldString = null,
                newString = null,
            )
        }
    }

    @Test
    fun invalidEditOperationsDoNotMutateSkill() = runTest {
        assertTrue(
            provider.execute(
                "edit_skill_file",
                """{"name":"review.md","operation":"","content":"","old_string":"","new_string":"","new_name":"","description":""}""",
                enabled,
            ).contains("operation", ignoreCase = true),
        )
        assertTrue(
            provider.execute(
                "edit_skill_file",
                """{"name":"review.md","operation":"patch","content":"","old_string":"","new_string":"","new_name":"","description":""}""",
                enabled,
            ).contains("old_string", ignoreCase = true),
        )
        assertTrue(
            provider.execute(
                "edit_skill_file",
                """{"name":"review.md","operation":"rename","content":"","old_string":"","new_string":"","new_name":"","description":""}""",
                enabled,
            ).contains("new_name", ignoreCase = true),
        )
        assertTrue(
            provider.execute(
                "edit_skill_file",
                """{"name":"review.md","operation":"move","content":"","old_string":"","new_string":"","new_name":"other.md","description":""}""",
                enabled,
            ).contains("operation", ignoreCase = true),
        )
        verify(exactly = 0) {
            skillManager.editFile(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun handlesOnlySkillTools() {
        assertTrue(provider.handles("read_skill_file"))
        assertFalse(provider.handles("update_active_memory"))
        assertFalse(provider.handles("web_search"))
    }
}
