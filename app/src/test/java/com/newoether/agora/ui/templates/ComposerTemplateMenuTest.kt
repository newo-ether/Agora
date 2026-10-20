package com.newoether.agora.ui.templates

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.SkillManager
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.templates.*
import com.newoether.agora.ui.chat.bottombar.ComposerSkillMenu
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ComposerTemplateMenuTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun savedTemplatesNeverAppearInSlashWhileCreateSkillRemainsAvailable() {
        val repository = InteractiveTemplateRepository(folder.newFolder())
        repository.save(InteractiveTemplate(name = "Standalone form", shortcut = "form",
            answer = InteractiveAnswer("Form", "Choose", "Select", html = "<p>Form</p>")))
        val manager = SkillManager(context)
        manager.listFiles().forEach { manager.deleteFile(it.name) }
        var selected = ""
        compose.setContent { MaterialTheme {
            var text by remember { mutableStateOf("") }
            Column {
                ComposerSkillMenu(text, true, manager) { selected = it }
                BasicTextField(text, { text = it }, Modifier.testTag("input"))
            }
        } }
        compose.onNodeWithTag("input").performTextInput("/")
        compose.waitUntil(10000) { compose.onAllNodesWithText("/create-skill").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Standalone form").assertDoesNotExist()
        compose.onNodeWithText("/form").assertDoesNotExist()
        assertEquals("", selected)
        assertEquals("Standalone form", repository.list().single().name)
    }
    @Test fun disabledSkillsDoNotExposeCommands() {
        val manager = SkillManager(context)
        compose.setContent { MaterialTheme { ComposerSkillMenu("/", false, manager) {} } }
        compose.onNodeWithText("/create-skill").assertDoesNotExist()
    }
}
