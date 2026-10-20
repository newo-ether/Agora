package com.newoether.agora.data

import android.app.Application
import android.content.Context
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.skills.SkillDocument
import com.newoether.agora.templates.InteractiveTemplate
import com.newoether.agora.templates.InteractiveTemplateRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeResourceUpdateTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun managers(): Pair<MemoryManager, SkillManager> {
        val context = mockk<Context>()
        every { context.filesDir } returns temporary.newFolder()
        return MemoryManager(context) to SkillManager(context)
    }
    private fun template(name: String) = InteractiveTemplate(name = name,
        answer = InteractiveAnswer(name, "Example interface", "Readable fallback", html = "<p>Old</p>"))

    @Test fun updatesOnePackAndKeepsUnrelatedSkillsNotesActiveMemoryAndTemplates() {
        val (memories, skills) = managers()
        skills.createFile("unrelated.md", "Keep instructions", "Keep this label")
        skills.createFile("learn.md", "Old instructions", "Old label")
        memories.createFile("my-notes.md", "Keep my notes", "Notes label")
        memories.updateActiveMemory("Keep active memory")
        val repository = InteractiveTemplateRepository(temporary.newFolder())
        val old = template("Learning setup")
        val unrelated = template("Unrelated template")
        repository.save(old); repository.save(unrelated)
        val revised = old.copy(answer = old.answer.copy(html = "<button type='button'>Start</button>"))
        val added = template("Learning review")
        val body = "<!-- agora-skill/v1\n{\"shortcut\": \"learn\", \"beforeTemplates\": [\"${old.id}\"]}\n-->\nNew instructions"
        val entries = listOf("memories/skill_db/learn.md" to body,
            "memories/skill_db/extra.md" to "Extra instructions",
            "memories/skill_db/skill_meta.json" to "{\"learn.md\":\"Build understanding through connected ideas\"}")
        assertEquals(2, updateArchivedResources(entries, memories, skills))
        assertTrue(repository.restoreCatalogue(Json.encodeToString(listOf(revised, added)), false, updateExisting = true).isEmpty())
        assertEquals(body, skills.readFile("learn.md"))
        assertEquals("Build understanding through connected ideas", skills.getDescription("learn.md"))
        assertEquals("learn", skills.listFiles().single { it.name == "learn.md" }.shortcut)
        assertEquals(old.id, SkillDocument.decode("learn.md", skills.readFile("learn.md")).configuration.beforeSteps.single())
        assertEquals("Keep instructions", skills.readFile("unrelated.md"))
        assertEquals("Keep this label", skills.getDescription("unrelated.md"))
        assertEquals("Keep my notes", memories.readFile("my-notes.md"))
        assertEquals("Keep active memory", memories.getActiveMemory())
        assertEquals(revised, repository.get(old.id))
        assertEquals(unrelated, repository.get(unrelated.id))
        updateArchivedResources(entries, memories, skills)
        repository.restoreCatalogue(Json.encodeToString(listOf(revised, added)), false, updateExisting = true)
        assertEquals(3, skills.listFiles().size)
        assertEquals(3, repository.list().size)
    }

    @Test fun updateCanRemoveOldConfigurationWithoutChangingOrdinaryInstructionEdits() {
        val (memories, skills) = managers()
        val original = "<!-- agora-skill/v1\n{\"shortcut\":\"old\"}\n-->\nOld"
        skills.createFile("learn.md", original)
        skills.editFile("learn.md", content = "Ordinary edited instructions")
        assertEquals("old", SkillDocument.decode("learn.md", skills.readFile("learn.md")).configuration.shortcut)
        updateArchivedResources(listOf("memories/skill_db/learn.md" to "New plain instructions"), memories, skills)
        assertEquals("New plain instructions", skills.readFile("learn.md"))
        assertEquals("", skills.listFiles().single().shortcut)
    }

    @Test fun malformedIncomingSkillMetadataIsRejectedBeforeUpdatingBodies() {
        val (memories, skills) = managers()
        skills.createFile("learn.md", "Original")
        assertThrows(IllegalArgumentException::class.java) {
            updateArchivedResources(listOf("memories/skill_db/learn.md" to "Changed",
                "memories/skill_db/skill_meta.json" to "{\"learn.md\":\"${(1..11).joinToString(" ")}\"}"), memories, skills)
        }
        assertEquals("Original", skills.readFile("learn.md"))
    }

    @Test fun templateUpdateRejectsMalformedCatalogueAndReportsShortcutConflicts() {
        val repository = InteractiveTemplateRepository(temporary.newFolder())
        val first = template("First")
        val other = template("Other").copy(shortcut = "occupied")
        repository.save(first); repository.save(other)
        val original = repository.exportCatalogue()
        assertThrows(IllegalArgumentException::class.java) {
            repository.restoreCatalogue(Json.encodeToString(listOf(first, first)), false, updateExisting = true)
        }
        assertEquals(original, repository.exportCatalogue())
        assertEquals(1, repository.restoreCatalogue(Json.encodeToString(listOf(first.copy(shortcut = "occupied"))), false, updateExisting = true).size)
        assertEquals(original, repository.exportCatalogue())
    }
}
