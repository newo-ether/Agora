package com.newoether.agora.ui.templates

import com.newoether.agora.data.SkillManager
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.templates.InteractiveTemplate
import com.newoether.agora.ui.chat.bottombar.*
import org.junit.Assert.*
import org.junit.Test

class ComposerCommandProjectionTest {
    @Test fun templateConflictsAndDuplicateSkillNamesAreShownOnlyOnce() {
        val template = InteractiveTemplate(name = "OSCE", shortcut = "osce",
            description = "Practice a focused clinical scenario", answer = InteractiveAnswer("OSCE", "Practice", "Text", html = "<p>Text</p>"))
        val skills = listOf(SkillManager.SkillFileInfo("osce.md", "Same workflow", "osce"),
            SkillManager.SkillFileInfo("reader.md", "Read selected clinical source files", "sources"),
            SkillManager.SkillFileInfo("reader.md", "Duplicate", "sources"))
        val entries = localComposerCommands("", skills)
        assertEquals(2, entries.size)
        assertEquals("skill:osce.md", entries.first().key)
        assertEquals("/sources", entries.last().label)
        assertEquals("Read selected clinical source files", entries.last().description)
        assertEquals("One two three four five…", conciseCommandDescription("One two three four five six seven"))
    }
    @Test fun setupNameAndSkillFilenameNormalizeToOneSkillAlongsideCreateSkill() {
        val template = InteractiveTemplate(name = "Zero to Finals OSCE",
            answer = InteractiveAnswer("Setup", "Choose", "Select", html = "<p>Setup</p>"))
        val skills = listOf(SkillManager.SkillFileInfo("zero-to-finals-osce.md", "Practise a selected condition", "osce"),
            SkillManager.SkillFileInfo("create-skill.md", "Create reusable instructions"))
        val entries = localComposerCommands("", skills)
        assertEquals(listOf("/create-skill", "/osce"), entries.map { it.label })
        assertTrue(entries.all { it.key.startsWith("skill:") })
        assertEquals(listOf("/osce"), localComposerCommands("osce", skills).map { it.label })
        assertTrue(localComposerCommands("", emptyList()).isEmpty())
    }

}
