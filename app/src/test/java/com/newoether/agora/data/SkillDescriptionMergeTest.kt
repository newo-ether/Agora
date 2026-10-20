package com.newoether.agora.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillDescriptionMergeTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun manager(): SkillManager {
        val context = mockk<Context>()
        every { context.filesDir } returns temporary.newFolder()
        return SkillManager(context)
    }
    @Test fun fillsMissingDescriptionsInAnExistingCatalogueWithoutReplacingMetadata() {
        val manager = manager()
        manager.createFile("existing.md", "Existing body", "My existing description")
        val body = "<!-- agora-skill/v1\n{\"shortcut\":\"learn-teach\"}\n-->\nTeach"
        manager.createFile("learn-teach.md", body)
        manager.mergeImportedMetadata(mapOf("existing.md" to "Imported label", "learn-teach.md" to "Build understanding through connected ideas"),
            mapOf("existing.md" to "Existing body", "learn-teach.md" to body))
        assertEquals("My existing description", manager.getDescription("existing.md"))
        assertEquals("Build understanding through connected ideas", manager.getDescription("learn-teach.md"))
        assertEquals("learn-teach", manager.listFiles().single { it.name == "learn-teach.md" }.shortcut)
        assertEquals(body, manager.readFile("learn-teach.md"))
        val revision = manager.catalogRevision.value
        val metadata = manager.getMetaJson()
        manager.mergeImportedMetadata(mapOf("learn-teach.md" to "Different import description"), mapOf("learn-teach.md" to body))
        assertEquals(metadata, manager.getMetaJson())
        assertEquals(revision, manager.catalogRevision.value)
    }
    @Test fun conflictingBodiesMissingFilesAndShortcutMetadataCannotChangeDescriptions() {
        val manager = manager()
        manager.createFile("same-name.md", "User's own body")
        val original = manager.getMetaJson()
        manager.mergeImportedMetadata(mapOf("same-name.md" to "Pack description", "missing.md" to "Missing label", "@shortcut:same-name.md" to "collision"),
            mapOf("same-name.md" to "Different body", "missing.md" to "Not imported"))
        assertEquals(original, manager.getMetaJson())
        assertEquals("User's own body", manager.readFile("same-name.md"))
    }
    @Test fun invalidDescriptionLeavesEveryMetadataEntryUnchanged() {
        val manager = manager()
        manager.createFile("first.md", "A")
        manager.createFile("second.md", "B")
        val before = manager.getMetaJson()
        assertThrows(IllegalArgumentException::class.java) {
            manager.mergeImportedMetadata(linkedMapOf("first.md" to "Valid short description", "second.md" to (1..11).joinToString(" ")),
                mapOf("first.md" to "A", "second.md" to "B"))
        }
        assertEquals(before, manager.getMetaJson())
    }
    @Test fun legacyShortcutsMergeWithoutReplacingAnExistingShortcutOrCreatingDuplicates() {
        val manager = manager()
        manager.createFile("first.md", "A")
        manager.createFile("second.md", "B")
        manager.mergeImportedMetadata(linkedMapOf("@shortcut:first.md" to "legacy", "@shortcut:second.md" to "legacy"),
            linkedMapOf("first.md" to "A", "second.md" to "B"))
        assertEquals("legacy", manager.listFiles().single { it.name == "first.md" }.shortcut)
        assertEquals("", manager.listFiles().single { it.name == "second.md" }.shortcut)
        manager.mergeImportedMetadata(mapOf("@shortcut:first.md" to "other"), mapOf("first.md" to "A"))
        assertEquals("legacy", manager.listFiles().single { it.name == "first.md" }.shortcut)
    }

}
