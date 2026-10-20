package com.newoether.agora.templates

import com.newoether.agora.model.InteractiveAnswer
import org.junit.Assert.*
import org.junit.Test

class TemplateCommandsTest {
    private fun item(name: String, shortcut: String? = null) = InteractiveTemplate(name = name, shortcut = shortcut,
        answer = InteractiveAnswer("Menu", "Options", "Choose", html = "<p>Menu</p>"))

    @Test fun sendingTheExampleNameOpensItsSavedInterfaceWithoutAnAssignedShortcut() {
        val osce = item("Zero to Finals OSCE")
        val items = listOf(osce, item("Revision planner"))
        assertEquals(listOf(osce), templateCommandMatches("/osce", items))
        assertEquals(listOf(osce), templateCommandMatches(" /OSCE\n", items))
        assertTrue(isTemplateCommand("/OSCE"))
        assertTrue(templateCommandMatches("Please run /osce", items).isEmpty())
        assertTrue(templateCommandMatches("/osce topic", items).isEmpty())
    }

    @Test fun explicitShortcutWinsAndAmbiguousMatchesRemainLocalChoices() {
        val exam = item("General OSCE")
        val specialist = item("Advanced OSCE")
        assertEquals(2, templateCommandMatches("/osce", listOf(exam, specialist)).size)
        assertEquals(2, templateCommandMatches("/", listOf(exam, specialist)).size)
        val assigned = item("My examiner", "osce")
        assertEquals(listOf(assigned), templateCommandMatches("/osce", listOf(exam, specialist, assigned)))
        assertTrue(templateCommandMatches("/unknown", listOf(exam)).isEmpty())
    }
}
