package com.newoether.agora.skills

import android.content.Context
import com.newoether.agora.data.SkillManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillEditorPersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun manager(): SkillManager {
        val context = mockk<Context>()
        every { context.filesDir } returns temp.root
        return SkillManager(context)
    }
    @Test fun saveReopenRenameAndStaleSaveUseTheFilename() {
        val manager = manager()
        val doc = SkillDocument("a.md", "original", SkillConfiguration(shortcut = "example"))
        manager.saveDocument(doc, "description")
        val raw = manager.readFile("a.md")
        manager.saveDocument(doc.copy(name = "renamed.md", instructions = "changed"), "description", "a.md", raw)
        assertEquals("renamed.md", manager.document("renamed.md").name)
        assertThrows(IllegalArgumentException::class.java) {
            manager.saveDocument(doc.copy(name = "renamed.md", instructions = "stale"), "", "renamed.md", raw)
        }
        assertEquals("changed", manager.document("renamed.md").instructions)
    }
    @Test fun duplicateShortcutDoesNotMutateEitherSkill() {
        val manager = manager()
        val a = SkillDocument("a.md", "first", SkillConfiguration(shortcut = "same"))
        manager.saveDocument(a, "")
        assertThrows(IllegalArgumentException::class.java) {
            manager.saveDocument(SkillDocument("b.md", "second", SkillConfiguration(shortcut = "same")), "")
        }
        assertEquals(listOf("a.md"), manager.listFiles().map { it.name })
        assertEquals(a, manager.document("a.md"))
    }
    @Test fun interruptedBodyReplacementRecoversThePreviousCompleteFile() {
        val manager = manager()
        manager.createFile("a.md", "previous")
        val file = File(temp.root, "skill_db/a.md")
        assertTrue(file.renameTo(File(file.path + ".bak")))
        File(file.path + ".tmp").writeText("incomplete")
        assertEquals("previous", manager.readFile("a.md"))
    }
    @Test fun encodedMarkdownUsesTheSameArchiveReadCreateBoundary() {
        val manager = manager()
        val original = SkillDocument("source.md", "instructions", SkillConfiguration())
        manager.saveDocument(original, "")
        val exported = manager.readFile("source.md")
        manager.deleteFile("source.md")
        manager.createFile("source.md", exported)
        assertEquals(original, manager.document("source.md"))
    }
    @Test fun renamedReferencesFailRatherThanFollowingAHiddenIdentity() {
        val manager = manager()
        manager.createFile("legacy.md", "Legacy instructions")
        manager.editFile("legacy.md", newName = "renamed.md")
        assertThrows(IllegalArgumentException::class.java) { manager.document("legacy.md") }
        assertEquals("Legacy instructions", manager.document("renamed.md").instructions)
        assertFalse(manager.getMetaJson().contains("@skill-id"))
    }
    @Test fun instructionOnlyToolEditsPreserveTheShortcut() {
        val manager = manager()
        val doc = SkillDocument("configured.md", "Old", SkillConfiguration(shortcut = "example"))
        manager.saveDocument(doc, "")
        manager.editFile(doc.name, content = "New instructions")
        assertEquals(doc.configuration, manager.document(doc.name).configuration)
        assertEquals("New instructions", manager.document(doc.name).instructions)
    }

    @Test fun discoveryAndSavingDoNotDecodeUnrelatedSkillBodies() {
        val manager = manager()
        File(temp.root, "skill_db/unrelated.md").writeText("<!-- agora-skill/v1\nmalformed")
        val selected = SkillDocument("selected.md", "Read the selected source", SkillConfiguration("chosen"))
        manager.saveDocument(selected, "Read one selected source from the terminal")
        assertEquals(listOf("selected.md", "unrelated.md"), manager.listFiles().map { it.name })
        assertEquals("chosen", manager.listFiles().first().shortcut)
        assertFalse(manager.catalog().contains("Read one selected"))
        assertEquals(selected.instructions, manager.document("selected.md").instructions)
    }

    @Test fun authoringGuidanceIsPackagedAndUserOverrideWinsWithoutSeedingFiles() {
        val manager = manager()
        assertTrue(manager.listFiles().isEmpty())
        assertEquals(SkillAuthoring.document, manager.document(SkillAuthoring.NAME))
        assertTrue(manager.listFiles().isEmpty())
        manager.createFile(SkillAuthoring.NAME, "My authoring instructions")
        assertEquals("My authoring instructions", manager.document(SkillAuthoring.NAME).instructions)
    }

}
