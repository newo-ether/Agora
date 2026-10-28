package com.newoether.agora.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class ImageQualityResourceContractTest {
    @Test
    fun everyLocaleDefinesImageQualityCopyAndPreservesNumericPlaceholders() {
        val resources = listOf(File("src/main/res"), File("app/src/main/res"))
            .first { it.isDirectory }
        val locales = listOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh", "values-zh-rTW",
        )
        val expected = setOf(
            "image_quality_title", "image_quality_mode", "image_quality_mode_desc",
            "image_quality_light", "image_quality_balanced", "image_quality_high", "image_quality_custom",
            "image_quality_long_edge", "image_quality_long_edge_desc", "image_quality_jpeg",
            "image_quality_jpeg_desc", "image_quality_pixels",
        )
        val placeholders = Regex("""%\d+\$[a-zA-Z]""")
        locales.forEach { locale ->
            val file = File(resources, "$locale/image_quality_strings.xml")
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("string")
            val strings = buildMap {
                repeat(nodes.length) { index ->
                    val node = nodes.item(index)
                    val key = node.attributes.getNamedItem("name").nodeValue
                    assertFalse("$locale duplicate $key", containsKey(key))
                    put(key, node.textContent)
                }
            }
            assertEquals(locale, expected, strings.keys)
            assertTrue(strings.values.all { it.isNotBlank() })
            assertEquals(listOf("%1\$d", "%2\$d"), placeholders.findAll(strings.getValue("image_quality_mode_desc"))
                .map { it.value }.toList())
            assertEquals(listOf("%1\$d"), placeholders.findAll(strings.getValue("image_quality_pixels"))
                .map { it.value }.toList())
        }
    }
}
