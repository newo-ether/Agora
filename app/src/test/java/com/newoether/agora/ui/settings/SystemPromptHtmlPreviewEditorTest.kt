package com.newoether.agora.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.data.DefaultSystemPrompt
import com.newoether.agora.data.HtmlPreviewGuidance
import com.newoether.agora.data.PredefinedVariables
import com.newoether.agora.data.PromptItemType
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.data.migrateSystemPromptsOnStartup
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w400dp-h900dp-mdpi")
class SystemPromptHtmlPreviewEditorTest {
    @get:Rule val compose = createComposeRule()
    private val saves = mutableListOf<SystemPromptEntry>()
    private var backs = 0
    private val action = "Add HTML Preview Guidance"

    @Test
    fun insertionAtSelectedPositionChangesOnlyDraftUntilExplicitSave() {
        val entry = entry(listOf(text("first"), text("last")))
        showEditor(entry)
        openInsert(1)
        compose.onNodeWithText(action).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(saves.isEmpty())
            assertEquals(listOf("first", "last"), entry.systemItems.map { it.value })
        }
        compose.onNodeWithContentDescription("Save").performClick()
        compose.runOnIdle {
            val saved = saves.single()
            assertEquals(listOf("first", HtmlPreviewGuidance.text, "last"), saved.systemItems.map { it.value })
            assertTrue(saved.systemItems.all { it.type == PromptItemType.CUSTOM })
            assertEquals(entry.resolvedUserItems, saved.userItems)
            assertEquals(entry.resolvedAssistantItems, saved.assistantItems)
        }
    }

    @Test
    fun existingEditedGuidanceDisablesDuplicateInsertion() {
        showEditor(entry(listOf(HtmlPreviewGuidance.item().copy(
            value = "## Interactive HTML preview\nMy edited guidance",
        ))))
        openInsert()
        compose.onNodeWithText(action).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(saves.isEmpty()) }
    }

    @Test
    fun deletingGuidanceEnablesReinsertionAndNormalTextEditing() {
        showEditor(entry(listOf(HtmlPreviewGuidance.item().copy(
            value = "## Interactive HTML preview\nMy edited guidance",
        ))))
        compose.onNodeWithContentDescription("Delete").performScrollTo().performClick()
        openInsert()
        compose.onNodeWithText(action).assertIsEnabled().performClick()
        compose.onNode(hasSetTextAction() and hasText(HtmlPreviewGuidance.text))
            .performTextReplacement("My replacement guidance")
        compose.onNodeWithContentDescription("Save").performClick()
        compose.runOnIdle {
            assertEquals(listOf("My replacement guidance"), saves.single().systemItems.map { it.value })
        }
    }

    @Test
    fun deletingGuidanceFromDefaultAndSavingDoesNotResurrectItAfterRestart() {
        val original = DefaultSystemPrompt.create(Locale.ENGLISH)
        showEditor(original)
        compose.onAllNodesWithContentDescription("Delete")[1].performScrollTo().performClick()
        compose.onNodeWithContentDescription("Save").performClick()
        compose.runOnIdle {
            var restored = saves.single()
            val historical = DefaultSystemPrompt.versionBeforeHtmlPreviewForMigration(Locale.ENGLISH)
            assertEquals(historical.systemItems.map { it.type to it.value },
                restored.systemItems.map { it.type to it.value })
            repeat(2) {
                restored = migrateSystemPromptsOnStartup(
                    Json.decodeFromString<List<SystemPromptEntry>>(Json.encodeToString(listOf(restored))),
                    Locale.ENGLISH,
                ).single()
                assertEquals(saves.single(), restored)
                assertFalse(HtmlPreviewGuidance.isPresent(restored.systemItems))
            }
        }
    }

    @Test
    fun backDiscardDoesNotSaveInsertedGuidance() {
        showEditor(entry(emptyList()))
        openInsert()
        compose.onNodeWithText(action).performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle {
            assertEquals(1, backs)
            assertTrue(saves.isEmpty())
        }
    }

    @Test
    fun userTabDoesNotOfferSystemGuidanceInsertion() {
        showEditor(entry(emptyList()))
        compose.onNodeWithText("User").performClick()
        openInsert()
        compose.onNodeWithText("Add Text").assertExists()
        compose.onNodeWithText(action).assertDoesNotExist()
    }

    @Test
    fun assistantTabDoesNotOfferSystemGuidanceInsertion() {
        showEditor(entry(emptyList()))
        compose.onNodeWithText("Assistant").performClick()
        openInsert()
        compose.onNodeWithText("Add Text").assertExists()
        compose.onNodeWithText(action).assertDoesNotExist()
    }

    private fun openInsert(index: Int = 0) {
        compose.onAllNodesWithContentDescription("Insert Item")[index].performScrollTo().performClick()
    }

    private fun showEditor(entry: SystemPromptEntry) = compose.setContent {
        CompositionLocalProvider(LocalAgoraMotionPolicy provides AgoraMotionPolicy(reduceMotion = true)) {
            MaterialTheme {
                SystemPromptEditorPage(
                    entry = entry,
                    onSave = { title, system, user, assistant ->
                        saves += entry.copy(title = title, systemItems = system, userItems = user, assistantItems = assistant)
                    },
                    onBack = { backs += 1 },
                    showDocFab = false,
                )
            }
        }
    }

    private fun entry(items: List<PromptTemplateItem>) = SystemPromptEntry(
        title = "My prompt",
        systemItems = items,
        userItems = listOf(text("user prefix"), PredefinedVariables.promptItem()),
        assistantItems = listOf(PredefinedVariables.promptItem(), text("assistant suffix")),
    )

    private fun text(value: String) = PromptTemplateItem(type = PromptItemType.CUSTOM, value = value)
}
