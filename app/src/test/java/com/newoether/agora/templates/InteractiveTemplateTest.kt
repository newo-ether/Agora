package com.newoether.agora.templates

import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.model.interactiveAnswer
import com.newoether.agora.tool.InteractiveTemplateToolProvider
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
class InteractiveTemplateTest {
    @get:Rule val folder = TemporaryFolder()
    private fun template(shortcut: String? = "study") = InteractiveTemplate(
        name = "Study", shortcut = shortcut, linkedSkill = "examiner.md",
        answer = InteractiveAnswer("Menu", "Choose settings", "Choose a topic and start.", html = "<button>Start</button>"),
    )

    @Test fun commandsAreUserDefinedExactAndDuplicateIsUnassigned() {
        val template = template("anything")
        assertEquals(template, resolveTemplateShortcut("  /anything\n", listOf(template)))
        listOf("/osce", "please /anything", "/anything topic", "/Anything").forEach {
            assertNull(resolveTemplateShortcut(it, listOf(template)))
        }
        assertNull(template.duplicate().shortcut)
        assertNotEquals(template.id, template.duplicate().id)
    }

    @Test fun invalidPayloadAndUnsafeMetadataAreRefused() {
        val template = template()
        listOf("../escape", "/study", "two words", "Upper", "1study", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { template.copy(shortcut = it).validate() }
        }
        listOf("../skill.md", "a/b.md", "a\\b.md", "a\nb.md").forEach {
            assertThrows(IllegalArgumentException::class.java) { template.copy(linkedSkill = it).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { template.copy(answer = template.answer.copy(html = "漢".repeat(30000))).validate() }
        assertThrows(IllegalArgumentException::class.java) { template.copy(version = 2).validate() }
        assertThrows(IllegalArgumentException::class.java) { template.copy(id = "../escape").validate() }
    }

    @Test fun saveReplaceDeleteAndReloadPreserveImmutableSnapshots() {
        val directory = folder.newFolder()
        val repository = InteractiveTemplateRepository(directory)
        val original = template()
        repository.save(original)
        val card = original.segment()
        val updated = original.copy(name = "Updated", shortcut = "new", answer = original.answer.copy(html = "<p>new</p>"))
        repository.save(updated, replace = true)
        assertEquals(listOf(updated), InteractiveTemplateRepository(directory).list())
        assertEquals(original.answer, card.interactiveAnswer())
        repository.delete(original.id)
        assertTrue(repository.list().isEmpty())
        assertEquals(original.answer, card.interactiveAnswer())
    }

    @Test fun shortcutCollisionAndMalformedReplaceLeaveCatalogueUntouched() {
        val repository = InteractiveTemplateRepository(folder.newFolder())
        val original = template()
        repository.save(original)
        assertThrows(IllegalArgumentException::class.java) { repository.save(template()) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.restoreCatalogue(Json.encodeToString(listOf(original, original)), replace = true)
        }
        assertEquals(listOf(original), repository.list())
        assertThrows(IllegalArgumentException::class.java) { repository.save(original.copy(answer = original.answer.copy(html = "")), replace = true) }
        assertEquals(listOf(original), repository.list())
    }

    @Test fun catalogueMergeReportsConflictsAndReplaceIsScoped() {
        val repository = InteractiveTemplateRepository(folder.newFolder())
        val original = template()
        repository.save(original)
        val conflict = template().copy(name = "Conflict")
        val additional = template("second")
        val warnings = repository.restoreCatalogue(Json.encodeToString(listOf(conflict, additional)), replace = false)
        assertEquals(1, warnings.size)
        assertEquals(setOf(original, additional), repository.list().toSet())
        val exported = repository.exportCatalogue()
        val restored = InteractiveTemplateRepository(folder.newFolder())
        assertTrue(restored.restoreCatalogue(exported, replace = true).isEmpty())
        assertEquals(repository.list(), restored.list())
        restored.restoreCatalogue("[]", replace = true)
        assertTrue(restored.list().isEmpty())
        assertEquals(2, repository.list().size)
    }

    @Test fun corruptCatalogueFailsClosedWithoutDeletingFiles() {
        val directory = folder.newFolder()
        val repository = InteractiveTemplateRepository(directory)
        repository.save(template())
        val file = java.io.File(directory, "catalog.json")
        file.writeText("invalid")
        assertThrows(Exception::class.java) { repository.list() }
        assertEquals("invalid", file.readText())
        assertThrows(Exception::class.java) { repository.save(template("other")) }
        assertEquals("invalid", file.readText())
    }

    @Test fun readBoundIsEnforcedForStreamWithoutTrustedSize() {
        assertThrows(IllegalArgumentException::class.java) { ByteArray(11).inputStream().readBytesBounded(10) }
        assertEquals(10, ByteArray(10).inputStream().readBytesBounded(10).size)
    }

    @Test fun localAndToolCardsUseSameRendererButFailedResultsNeverExecute() {
        val template = template()
        assertEquals(template.answer, template.segment().interactiveAnswer())
        val tool = MessageSegment(type = "tool", toolName = InteractiveTemplateToolProvider.OPEN,
            toolState = ToolExecutionStates.SUCCEEDED, toolStructuredResult = template.encode())
        assertEquals(template.answer, tool.interactiveAnswer())
        assertNull(tool.copy(toolState = ToolExecutionStates.FAILED).interactiveAnswer())
        assertNull(tool.copy(toolStructuredResult = "bad").interactiveAnswer())
    }

    @Test fun staleEditorCannotOverwriteNewerTemplate() {
        val repository = InteractiveTemplateRepository(folder.newFolder())
        val original = template()
        repository.save(original)
        val newer = original.copy(name = "Newer")
        repository.save(newer, replace = true, expected = original)
        assertThrows(IllegalArgumentException::class.java) {
            repository.save(original.copy(name = "Stale"), replace = true, expected = original)
        }
        assertEquals(newer, repository.get(original.id))
    }

    @Test fun archiveAndForkPreserveLocalSnapshotAndLinkedSkill() {
        val original = template()
        val raw = Json.encodeToString(listOf(original.segment()))
        val exported = com.newoether.agora.data.NativeBackupMediaPolicy.rewriteToolImagePathsForExport(raw) { null }
        val restored = com.newoether.agora.data.NativeBackupMediaPolicy.restoreToolImagePaths(exported, 6) { null }
        assertEquals(original, Json.decodeFromString<List<MessageSegment>>(restored!!).single().interactiveTemplate())
        val session = com.newoether.agora.data.MessageAttachmentCloneSession(folder.newFolder())
        val row = com.newoether.agora.data.local.MessageEntity(id = "card", conversationId = "c", text = "",
            timestamp = 1, participant = com.newoether.agora.model.Participant.MODEL, runId = "local", toolCallJson = raw)
        val copied = session.cloneMessage(row.copy(id = "fork-card", conversationId = "fork", runId = "fork-run"))
        assertEquals(raw, copied.toolCallJson)
        session.rollback()
    }

    @Test fun modelToolsAreOptInAndReturnMetadataWithoutExecutableCodeInText() = runTest {
        val repository = InteractiveTemplateRepository(folder.newFolder())
        val template = template()
        repository.save(template)
        val provider = InteractiveTemplateToolProvider(repository)
        val disabled = GenerationContext()
        val enabled = GenerationContext(interactiveAnswersEnabled = true)
        assertTrue(provider.definitions(disabled).isEmpty())
        assertEquals(2, provider.definitions(enabled).size)
        val list = provider.executeOneShotResult(InteractiveTemplateToolProvider.LIST, "{}", enabled)
        assertTrue(list.text.contains(template.id))
        assertFalse(list.text.contains("button"))
        val result = provider.executeOneShotResult(InteractiveTemplateToolProvider.OPEN, """{"template_id":"${template.id}"}""", enabled)
        assertEquals(template, InteractiveTemplate.decode(result.structuredContent!!))
        assertFalse(result.text.contains("<button>"))
        try { provider.executeOneShotResult(InteractiveTemplateToolProvider.OPEN, "{}", disabled); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
