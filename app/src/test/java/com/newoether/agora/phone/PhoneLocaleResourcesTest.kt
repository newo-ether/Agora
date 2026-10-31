package com.newoether.agora.phone

import com.newoether.agora.readLocaleStringResourceSources
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class PhoneLocaleResourcesTest {
    private fun resourceRoot(): File = sequenceOf(File("src/main/res"), File("app/src/main/res"))
        .first { it.isDirectory }

    @Test fun everyLocaleHasOneNonemptyTranslationForEveryPhoneString() {
        val root = resourceRoot()
        val directories = listOf("values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh", "values-zh-rTW")
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val expected = parser.parse(File(root, "values/phone.xml")).getElementsByTagName("string")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue }.toSet() }
        assertEquals(69, expected.size)
        for (directory in directories) {
            val files = File(root, directory).listFiles { file -> file.extension == "xml" }!!
            val translations = files.flatMap { file ->
                val nodes = parser.parse(file).getElementsByTagName("string")
                (0 until nodes.length).mapNotNull { index ->
                    val node = nodes.item(index)
                    val key = node.attributes.getNamedItem("name").nodeValue
                    if (key.startsWith("phone_")) key to node.textContent else null
                }
            }
            assertEquals(directory, expected, translations.map { it.first }.toSet())
            assertEquals("Duplicate phone keys in $directory", expected.size, translations.size)
            assertTrue(directory, translations.all { it.second.isNotBlank() })
            assertTrue("Active summaries in $directory", translations.filter { it.first.startsWith("phone_running_") }
                .all { it.second.endsWith("…") })
            assertTrue("Terminal summaries in $directory", translations.filter { it.first.startsWith("phone_done_") }
                .all { !it.second.endsWith(".") && !it.second.endsWith("。") && !it.second.endsWith("…") })
            val aggregate = File(root, "$directory/strings.xml").readLocaleStringResourceSources()
            assertTrue(directory, aggregate.contains("name=\"phone_title\""))
            assertTrue(directory, translations.single { it.first == "phone_choose_apps_description" }.second.contains("%1\$d"))
        }
    }
}
