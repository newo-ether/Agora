package com.newoether.agora.skills

import org.junit.Assert.*
import org.junit.Test

class SkillDocumentTest {
    @Test fun legacyMarkdownRemainsLiteralAndHasNoHiddenIdentity() {
        val source = "# Examiner\nUse file_glob on /home/agora/notes, then invoke reader.md.\n"
        val document = SkillDocument.decode("examiner.md", source)
        assertEquals(source, document.instructions)
        assertEquals(source, document.encode())
        assertFalse(document.encode().contains("id"))
    }
    @Test fun onlyShortcutMetadataRoundTrips() {
        val source = SkillDocument("examiner.md", "# Instructions\nLiteral --> and <tag> text\n", SkillConfiguration("osce"))
        assertEquals(source, SkillDocument.decode(source.name, source.encode()))
    }
    @Test fun oldDraftConfigurationIsReadWithoutRetainingRemovedCustomizations() {
        val doc = SkillDocument.decode("x.md", "<!-- agora-skill/v1\n{\"id\":\"old\",\"shortcut\":\"test\",\"folders\":[]}\n-->\nInstructions")
        assertEquals("test", doc.configuration.shortcut)
        assertFalse(doc.encode().contains("folders"))
        assertFalse(doc.encode().contains("id"))
        assertThrows(IllegalArgumentException::class.java) { SkillDocument.decode("x.md", "<!-- agora-skill/v1\n{}") }
    }
    @Test fun reservedShortcutsAndLongDescriptionsFailValidation() {
        assertThrows(IllegalArgumentException::class.java) { SkillConfiguration("skills").validate() }
        assertThrows(IllegalArgumentException::class.java) {
            SkillDocument("x.md", "Instructions", description = (1..11).joinToString(" ")).encode()
        }
        SkillDocument("x.md", "Instructions", description = "Turn a discussion into reusable Skill instructions").encode()
    }
    @Test fun explicitCommandsSupportOrdinaryAndQuotedFilenames() {
        assertNull(skillCommand("Explain /skill examiner.md"))
        assertNull(skillCommand("/osce"))
        assertEquals("examiner.md" to "case input", skillCommand("/skill examiner.md case input"))
        assertEquals("clinical examiner.md" to "case input", skillCommand("/skill \"clinical examiner.md\" case input"))
    }
}
