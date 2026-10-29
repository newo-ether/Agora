package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlPreviewGuidanceTest {
    @Test
    fun guidanceIsOrdinaryEditableTextWithIndependentItemIdentity() {
        val first = HtmlPreviewGuidance.item()
        val second = HtmlPreviewGuidance.item()
        assertEquals(PromptItemType.CUSTOM, first.type)
        assertEquals(HtmlPreviewGuidance.text, first.value)
        assertNotEquals(first.id, second.id)
        val edited = first.copy(value = "My HTML instructions")
        assertEquals("My HTML instructions", PredefinedVariables.compile(listOf(edited), emptyMap()))
        assertEquals("", PredefinedVariables.compile(emptyList(), emptyMap()))
        assertFalse(HtmlPreviewGuidance.isPresent(listOf(edited)))
    }

    @Test
    fun duplicateDetectionAcceptsEditedHeadingBlockAndAllowsReinsertionAfterRemoval() {
        val guidance = HtmlPreviewGuidance.item()
        val edited = guidance.copy(value = "My rules\n  ## Interactive HTML preview  \nUse inline data.")
        val surrounding = PromptTemplateItem(type = PromptItemType.CUSTOM, value = "Other rules")
        assertTrue(HtmlPreviewGuidance.isPresent(listOf(surrounding, guidance)))
        assertTrue(HtmlPreviewGuidance.isPresent(listOf(edited)))
        assertFalse(HtmlPreviewGuidance.isPresent(listOf(surrounding)))
        assertFalse(HtmlPreviewGuidance.isPresent(listOf(guidance.copy(type = PromptItemType.PREDEFINED))))
        assertFalse(HtmlPreviewGuidance.isPresent(listOf(guidance.copy(value = "Mention ## Interactive HTML preview inline"))))
    }

    @Test
    fun guidanceDescribesCompletedFencesRestrictedResourcesAndResettableState() {
        val text = HtmlPreviewGuidance.text
        listOf(
            "`html-preview`", "closing fence", "`html`", "manual Run", "inline HTML, CSS, JavaScript, and data",
            "cdnjs.cloudflare.com", "cdn.jsdelivr.net", "unpkg.com", "esm.sh",
            ".js", ".mjs", ".css", ".woff", ".woff2", ".ttf",
            "versioned URLs", "without query strings or redirects",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "Do not use fetch, API requests, WebSockets, or other network access",
            "<img src=\"shell://SERVER/absolute/path.png\">", "Percent-encode spaces",
            "Do not construct shell URLs in JavaScript", "Video is unsupported",
            "reload, re-entry, fullscreen transitions, or process restart", "persistent storage",
        ).forEach { assertTrue("Missing guidance: $it", text.contains(it)) }
        assertFalse(text.contains("152"))
        assertFalse(text.contains("360 dp"))
    }
}
