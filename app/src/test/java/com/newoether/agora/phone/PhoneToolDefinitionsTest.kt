package com.newoether.agora.phone

import com.newoether.agora.viewmodel.GenerationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolDefinitionsTest {
    private fun definitions(mode: String = "accessibility") = phoneDefinitions(
        GenerationContext(phoneEnabled = true, phoneReady = true, phoneMode = mode),
    ).associateBy { it.function.name }

    @Test
    fun sharedRulesAppearOnlyOnTheirOwningTools() {
        val definitions = definitions()
        val descriptions = definitions.mapValues { it.value.function.description }
        val secureOwners = descriptions.filterValues { it.contains("FLAG_SECURE") }

        assertEquals(setOf("phone_screenshot"), secureOwners.keys)
        assertTrue(descriptions.getValue("phone_claim").contains("Required before every other phone_* tool"))
        assertTrue(descriptions.getValue("phone_claim").contains("border"))
        assertTrue(descriptions.getValue("phone_screenshot").contains("never physical pixels"))
        assertTrue(descriptions.getValue("phone_ui_tree").contains("Prefer node indices"))
        descriptions.filterKeys { it != "phone_claim" }.forEach { (name, description) ->
            assertFalse(name, description.contains("Requires phone_claim first"))
        }
        definitions.getValue("phone_tap").function.parameters.properties.getValue("node")
            .description.contains("x/y are ignored").let(::assertTrue)
    }

    @Test
    fun captureAndTreeLimitsFollowTheSelectedMode() {
        val accessibility = definitions("accessibility")
        assertTrue(accessibility.getValue("phone_screenshot").function.description.contains("rate limited"))
        assertFalse(accessibility.getValue("phone_ui_tree").function.description.contains("uiautomator dump"))

        val shizuku = definitions("shizuku")
        assertFalse(shizuku.getValue("phone_screenshot").function.description.contains("rate limited"))
        assertTrue(shizuku.getValue("phone_ui_tree").function.description.contains("uiautomator dump"))
    }
}
