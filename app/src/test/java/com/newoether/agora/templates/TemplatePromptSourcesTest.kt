package com.newoether.agora.templates

import com.newoether.agora.model.InteractiveAnswer
import org.junit.Assert.*
import org.junit.Test

class TemplatePromptSourcesTest {
    private val template = InteractiveTemplate(name = "Clinical exercise", linkedSkill = "examiner.md",
        resourceFiles = listOf("selected.md", "unselected.md"),
        answer = InteractiveAnswer(title = "Example", description = "Choose", fallback = "Choose", html = "<p>Choose</p>"))

    @Test fun attachesTheOriginalExaminerAndExactlyTheSelectedArticleWithoutListingTheLibrary() {
        val reads = mutableListOf<String>()
        val prompt = resolveTemplatePrompt(template, templateSkillPrefix(template.linkedSkill) + "Ask 12 questions",
            listOf("selected.md"), true) { name -> reads += name; "Full content of $name" }
        assertEquals(listOf("examiner.md", "selected.md"), reads)
        assertTrue(prompt.contains("Full content of examiner.md"))
        assertTrue(prompt.contains("Full content of selected.md"))
        assertTrue(prompt.contains("Ask 12 questions"))
        assertFalse(prompt.contains("unselected.md"))
        assertFalse(prompt.contains("Read the skill file"))
    }

    @Test fun undeclaredDisabledAndGeneratedRequestsCannotReadFiles() {
        fun rejected(t: InteractiveTemplate?, names: List<String>, enabled: Boolean) {
            var read = false
            assertThrows(IllegalArgumentException::class.java) {
                resolveTemplatePrompt(t, templateSkillPrefix(t?.linkedSkill) + "Go", names, enabled) {
                    read = true; "content"
                }
            }
            assertFalse(read)
        }
        rejected(template, listOf("other.md"), true)
        rejected(template, listOf("selected.md", "unselected.md"), true)
        rejected(template, listOf("selected.md"), false)
        rejected(null, listOf("selected.md"), true)
    }

    @Test fun missingOrOversizedContentFailsInsteadOfSendingAnIncompleteCase() {
        assertThrows(IllegalStateException::class.java) {
            resolveTemplatePrompt(template, templateSkillPrefix(template.linkedSkill) + "Go", listOf("selected.md"), true) {
                error("File missing")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveTemplatePrompt(template, templateSkillPrefix(template.linkedSkill) + "Go", listOf("selected.md"), true) {
                "x".repeat(128001)
            }
        }
    }
}
