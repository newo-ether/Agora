package com.newoether.agora.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SettingsResourceContractTest {
    @Test
    fun localizedStringKeysMatchDefaultResources() {
        val resources = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "app/src/main/res") }
            .first { it.isDirectory }
        val defaults = stringKeys(File(resources, "values"))
        val localePattern = Regex("""values-[a-z]{2,3}(?:-r[A-Z]{2})?""")
        val locales = resources.listFiles().orEmpty()
            .filter { it.isDirectory && localePattern.matches(it.name) }
        assertTrue("No localized resource directories found", locales.isNotEmpty())
        locales.forEach { locale ->
            val missing = defaults - stringKeys(locale)
            assertTrue("${locale.name} is missing: ${missing.sorted().joinToString()}", missing.isEmpty())
        }
    }

    private fun stringKeys(directory: File): Set<String> = buildSet {
        directory.listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = document.getElementsByTagName("string")
            repeat(strings.length) { add((strings.item(it) as Element).getAttribute("name")) }
        }
    }
}
