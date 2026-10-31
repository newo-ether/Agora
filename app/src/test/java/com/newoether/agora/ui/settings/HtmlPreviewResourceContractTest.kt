package com.newoether.agora.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlPreviewResourceContractTest {
    private val resources = listOf(File("src/main/res"), File("app/src/main/res"))
        .first { it.isDirectory }
    private val locales = listOf(
        "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
        "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh", "values-zh-rTW",
    )
    private val expected = setOf(
        "html_preview_run", "html_preview_source", "html_preview_preview", "html_preview_reload",
        "html_preview_fullscreen", "html_preview_save", "html_preview_share", "html_preview_failed",
        "html_preview_loading", "html_preview_insert", "html_preview_export_failed",
        "html_preview_webview_required",
    )

    @Test
    fun everyLocaleDefinesTheCompleteHtmlPreviewCopyWithoutDuplicateKeys() {
        val actualLocales = resources.listFiles().orEmpty().filter { directory ->
            directory.isDirectory && File(directory, "strings.xml").isFile
        }.map { it.name }.toSet()
        assertEquals(locales.toSet(), actualLocales)
        locales.forEach { locale ->
            val strings = mergedStrings(File(resources, locale))
            assertEquals(locale, expected, strings.keys.filter { it.startsWith("html_preview_") }.toSet())
            expected.forEach { key ->
                assertTrue("$locale/$key", strings.getValue(key).isNotBlank())
            }
            val update = strings.getValue("html_preview_webview_required")
            assertTrue("$locale WebView name", update.contains("WebView"))
            assertFalse("$locale must not promise a minimum version", update.any { it.isDigit() })
            assertEquals("$locale uses the existing localized Preview", "@string/template_preview",
                strings.getValue("html_preview_preview"))
            assertTrue("$locale Preview alias must resolve locally",
                strings.getValue("template_preview").isNotBlank())
            assertFalse(strings.getValue("template_preview").startsWith("@"))
        }
        assertEquals("Update Android System WebView to run HTML previews",
            mergedStrings(File(resources, "values")).getValue("html_preview_webview_required"))
    }

    private fun mergedStrings(directory: File): Map<String, String> = buildMap {
        directory.listFiles().orEmpty().filter { it.extension == "xml" }.sortedBy { it.name }.forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("string")
            repeat(nodes.length) { index ->
                val node = nodes.item(index)
                val key = node.attributes.getNamedItem("name").nodeValue
                if (key in expected || key == "template_preview") {
                    assertFalse("${directory.name} duplicate $key in ${file.name}", containsKey(key))
                }
                put(key, node.textContent)
            }
        }
    }
}
