package com.newoether.agora.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class AgoraOptionHighlightTest {
    @Test
    fun optionRowsShareTheDropdownHighlight() {
        assertEquals(
            24f,
            OPTION_HIGHLIGHT_SHAPE.topStart.toPx(Size(300f, 72f), Density(1f)),
            0.001f,
        )
        assertEquals(8.dp, SHEET_OPTION_INSET)
    }

    @Test
    fun knownOptionRowsKeepUsingTheSharedHighlight() {
        val ui = File("src/main/java/com/newoether/agora/ui")
        // Scoped call-site regressions; completeness is established by the modal-owner audit.
        val expected = mapOf(
            "chat/AdvancedSettingsDialog.kt" to (2 to 0),
            "components/SystemPromptPickerDialog.kt" to (2 to 0),
            "settings/datacontrol/SettingsDataControlPage.kt" to (2 to 0),
            "settings/datacontrol/NativeDataSelectionDialogs.kt" to (1 to 0),
            "settings/SettingsContextPage.kt" to (1 to 0),
            "settings/SettingsSubagentPage.kt" to (3 to 0),
            "settings/SettingsSearchPage.kt" to (0 to 1),
            "settings/SettingsImageGenPage.kt" to (2 to 0),
            "settings/SettingsModelsPage.kt" to (1 to 0),
            "settings/SettingsTitleGenPage.kt" to (2 to 0),
            "settings/SettingsTranscriptionPage.kt" to (2 to 0),
            "settings/SettingsWebSearchPage.kt" to (1 to 0),
            // The Tasks model picker's ChoiceRow joins the existing option row (UI-21).
            "tasks/TaskEditorSupportingComponents.kt" to (2 to 0),
            "chat/ImageActions.kt" to (0 to 1),
            "settings/SettingsPromptsPage.kt" to (0 to 2),
            "settings/SettingsSkillsPage.kt" to (0 to 0), // Skills now uses the approved full-page editor, not an Add sheet.
            "settings/SystemPromptEditorPage.kt" to (0 to 1),
        )
        expected.forEach { (path, counts) ->
            val source = File(ui, path).readLines().filterNot { it.startsWith("import ") }.joinToString("\n")
            assertEquals(path, counts.first, Regex("""\.optionClickable\b""").findAll(source).count())
            assertEquals(path, counts.second, Regex("""\.sheetOptionClickable\b""").findAll(source).count())
        }
    }
}
