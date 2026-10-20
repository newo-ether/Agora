package com.newoether.agora.ui.templates

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.templates.InteractiveTemplate
import com.newoether.agora.templates.InteractiveTemplateRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TemplatesScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val folder = TemporaryFolder()
    private fun template() = InteractiveTemplate(name = "Reference menu",
        answer = InteractiveAnswer("Menu", "Options", "Choose", html = "<p>Menu</p>"))

    @Test fun browsingAndBackNeverPreparesOrChangesTheTemplate() {
        val repository = InteractiveTemplateRepository(folder.newFolder())
        val template = template()
        repository.save(template)
        var prepared = false
        compose.setContent { MaterialTheme { TestTemplatesScreen(repository, { prepared = true; it.answer }, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText(template.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(template.name).performClick()
        compose.onNodeWithText("Alpine source folder (optional)").performTextInput("/mnt/shared/References")
        assertFalse(prepared)
        assertEquals(template, repository.get(template.id))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText(template.name).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(template, repository.get(template.id))
    }

    @Test fun savingChangesSourceFolderWithoutOfferingASlashShortcut() {
        val repository = InteractiveTemplateRepository(folder.newFolder())
        val template = template().copy(shortcut = "legacy")
        repository.save(template)
        compose.setContent { MaterialTheme { TestTemplatesScreen(repository, { it.answer }, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText(template.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(template.name).performClick()
        compose.onNodeWithText("Shortcut (optional)").assertDoesNotExist()
        compose.onNodeWithText("Alpine source folder (optional)").performTextInput("/mnt/shared/References")
        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitUntil(10000) { repository.get(template.id).sourceDirectory == "/mnt/shared/References" }
        assertNull(repository.get(template.id).shortcut)
        assertEquals(template.answer, repository.get(template.id).answer)
    }

    @Test fun previewFailureStaysInEditorAndDoesNotAlterCatalogue() {
        val repository = InteractiveTemplateRepository(folder.newFolder())
        val template = template()
        repository.save(template)
        val reason = "Enable shared storage first"
        compose.setContent { MaterialTheme { TestTemplatesScreen(repository, { throw IllegalStateException(reason) }, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText(template.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(template.name).performClick()
        compose.onAllNodesWithText("Preview template").onLast().performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText(reason).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(template), repository.list())
        compose.onNodeWithContentDescription("Save").assertExists()
    }

    @Test fun invalidCodeDisablesSaveAndKeepsTheOriginal() {
        val repository = InteractiveTemplateRepository(folder.newFolder())
        val template = template()
        repository.save(template)
        compose.setContent { MaterialTheme { TestTemplatesScreen(repository, { it.answer }, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText(template.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(template.name).performClick()
        compose.onAllNodesWithText("Interface code (JSON)").filter(hasSetTextAction()).onFirst()
            .performScrollTo().performTextReplacement("invalid JSON")
        compose.onNodeWithContentDescription("Save").assertIsNotEnabled()
        assertEquals(template, repository.get(template.id))
    }

    @Test fun clipboardRoundTripSavesRevisedCodeUnderTheSameIdentity() {
        val repository = InteractiveTemplateRepository(folder.newFolder())
        val template = template()
        repository.save(template)
        lateinit var clipboard: androidx.compose.ui.platform.ClipboardManager
        compose.setContent { MaterialTheme {
            clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            TestTemplatesScreen(repository, { it.answer }, {})
        } }
        compose.waitUntil(10000) { compose.onAllNodesWithText(template.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(template.name).performClick()
        compose.onNodeWithText("Copy code").performScrollTo().performClick()
        val revised = template.answer.copy(html = "<p>Revised by chat</p>", css = "p { color: black; }")
        compose.runOnIdle {
            assertEquals(template.answer, InteractiveAnswer.decode(clipboard.getText()!!.text))
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(revised.encode()))
        }
        compose.onNodeWithText("Paste code").performClick()
        assertEquals(template, repository.get(template.id))
        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitUntil(10000) { repository.get(template.id).answer == revised }
        assertEquals(listOf(template.copy(answer = revised)), repository.list())
    }

    @Test fun codeRoundTripPreservesIdentityResourcesAndAllInterfaceFields() {
        val original = template().copy(sourceDirectory = "/mnt/shared/References", resourceFiles = listOf("Topic.md"))
        val improved = original.answer.copy(html = "<button>Improved</button>", css = "button { color: black; }",
            jsFunctions = "function select() {}", jsExpressions = listOf("select();"), eInkAware = true)
        val edited = editedTemplate(original, original.name, "Updated menu", original.sourceDirectory!!, improved.encode())
        assertEquals(original.id, edited.id)
        assertEquals(original.resourceFiles, edited.resourceFiles)
        assertEquals(original.sourceDirectory, edited.sourceDirectory)
        assertEquals(improved, edited.answer)
        assertTrue(runCatching { editedTemplate(original, original.name, "", "", "bad") }.isFailure)
        assertTrue(runCatching { editedTemplate(original, original.name, "", "", original.answer.copy(skillDraft =
            com.newoether.agora.skills.SkillDocument("Bad", "Instructions", com.newoether.agora.skills.SkillConfiguration())).encode()) }.isFailure)
    }
}

/** Temporary-file operations run synchronously; Compose's virtual clock cannot advance real IO. */
@androidx.compose.runtime.Composable
private fun TestTemplatesScreen(repository: InteractiveTemplateRepository,
    preparePreview: suspend (InteractiveTemplate) -> InteractiveAnswer, onBack: () -> Unit) {
    TemplatesScreen(repository, preparePreview, onBack, ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
}
