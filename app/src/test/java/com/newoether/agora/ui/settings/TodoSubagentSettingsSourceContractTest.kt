package com.newoether.agora.ui.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class TodoSubagentSettingsSourceContractTest {
    @Test
    fun toolsExposeSeparateTodoAndSubagentDestinations() {
        val screen = sourceFile("ui/settings/SettingsScreen.kt").readText()
        val tools = screen.substringAfter("titleRes = R.string.settings_group_tools")
            .substringBefore("titleRes = R.string.settings_group_network")
        val destinations = sourceFile("ui/settings/SettingsTwoPane.kt").readText()

        assertTrue(
            tools.contains(
                "SettingsCategory(\"todo\", R.string.settings_todo, R.string.settings_todo_desc, Icons.Default.Checklist)",
            ),
        )
        assertTrue(
            tools.contains(
                "SettingsCategory(\"subagents\", R.string.settings_subagents, R.string.settings_subagents_desc, Icons.Default.AccountTree)",
            ),
        )
        assertTrue(destinations.contains("\"todo\" -> SettingsTodoPage(viewModel, onBack)"))
        assertTrue(destinations.contains("\"subagents\" -> SettingsSubagentPage(viewModel, onBack)"))
    }

    @Test
    fun todoUsesOneImmediateWholeRowSwitch() {
        assertTogglePage(
            fileName = "SettingsTodoPage.kt",
            property = "todoEnabled",
            setter = "setTodoEnabled",
            title = "settings_todo",
            label = "todo_enable",
            description = "todo_enable_desc",
            icon = "Checklist",
        )
    }

    @Test
    fun settingsCopyIsLocalizedWithoutDuplicateKeys() {
        val keys = setOf(
            "settings_todo", "settings_todo_desc", "todo_enable", "todo_enable_desc",
            "settings_subagents", "settings_subagents_desc", "subagents_enable", "subagents_enable_desc",
        )
        val resources = mainFile("res/values/strings.xml").parentFile.parentFile
        val defaults = readStrings(File(resources, "values/strings.xml"), keys)
        assertEquals("Todo List", defaults["settings_todo"])
        assertEquals("Enable Todo List", defaults["todo_enable"])
        assertEquals("Subagents", defaults["settings_subagents"])
        assertEquals("Enable Subagents", defaults["subagents_enable"])
        assertTrue(defaults.getValue("todo_enable_desc").contains("read and update"))
        assertTrue(defaults.getValue("todo_enable_desc").contains("keeps existing lists"))
        assertTrue(defaults.getValue("subagents_enable_desc").contains("isolated conversations"))
        assertTrue(defaults.getValue("subagents_enable_desc").contains("does not stop existing subagents"))

        listOf(
            "values-ar", "values-de", "values-es", "values-fr", "values-ja", "values-ko",
            "values-pt-rBR", "values-ru", "values-vi", "values-zh", "values-zh-rTW",
        ).forEach { locale ->
            val localized = readStrings(File(resources, "$locale/strings.xml"), keys)
            keys.forEach { key ->
                assertFalse("Untranslated $key in $locale", defaults.getValue(key) == localized.getValue(key))
            }
        }
    }

    private fun assertTogglePage(
        fileName: String,
        property: String,
        setter: String,
        title: String,
        label: String,
        description: String,
        icon: String,
    ) {
        val page = sourceFile("ui/settings/$fileName").readText()
        assertTrue(page.contains("viewModel.settings.$property.collectAsState()"))
        assertTrue(page.contains("CollapsingSettingsScaffold("))
        assertTrue(page.contains("title = stringResource(R.string.$title)"))
        assertTrue(page.contains("onBack = onBack"))
        assertTrue(page.contains("SettingsGroupColumn {"))
        assertTrue(page.contains("SettingsGroup("))
        assertEquals(1, Regex("""\bSettingsItem\(""").findAll(page).count())
        assertTrue(page.contains("headlineContent = { Text(stringResource(R.string.$label)) }"))
        assertTrue(page.contains("supportingContent = { Text(stringResource(R.string.$description)) }"))
        assertTrue(page.contains("Icons.Default.$icon"))
        assertEquals(1, Regex("""\bSwitch\(""").findAll(page).count())
        assertTrue(page.contains("Switch(checked = $property, onCheckedChange = null)"))
        assertTrue(page.contains("modifier = Modifier.toggleable("))
        assertTrue(page.contains("value = $property"))
        assertTrue(page.contains("role = Role.Switch"))
        assertTrue(page.contains("onValueChange = viewModel.settings::$setter"))
        assertFalse(page.contains("R.string.save"))
        assertFalse(page.contains("rememberUnsavedChangesBack"))
        assertFalse(page.contains("AlertDialog("))
    }

    private fun readStrings(file: File, keys: Set<String>): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val values = mutableMapOf<String, String>()
        repeat(nodes.length) { index ->
            val element = nodes.item(index) as Element
            val name = element.getAttribute("name")
            if (name in keys) {
                assertFalse("Duplicate $name in ${file.path}", values.containsKey(name))
                values[name] = element.textContent
            }
        }
        keys.forEach { key -> assertTrue("Missing $key in ${file.path}", !values[key].isNullOrBlank()) }
        return values
    }

    private fun sourceFile(path: String): File = mainFile("java/com/newoether/agora/$path")

    private fun mainFile(path: String): File {
        var cursor: File? = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val directory = checkNotNull(cursor)
            val candidates = listOf(
                File(directory, "app/src/main/$path"),
                File(directory, "src/main/$path"),
            )
            candidates.firstOrNull(File::isFile)?.let { return it }
            cursor = directory.parentFile
        }
        error("Unable to locate Agora source: $path")
    }
}
