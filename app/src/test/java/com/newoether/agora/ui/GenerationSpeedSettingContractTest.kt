package com.newoether.agora.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class GenerationSpeedSettingContractTest : UiSourceContractFixture() {

    @Test
    fun `Generation speed is opt in portable and only paints settled replies`() {
        val appearance = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAppearancePage.kt",
        )
        val presentation = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAppearancePresentation.kt",
        )
        val chatApp = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt")
        val messageList = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt")
        val messageItem = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt",
        )
        val assistant = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/AssistantMessageContent.kt",
        )
        val settings = sourceFile(
            "app/src/main/java/com/newoether/agora/data/SettingsDisplayPreferenceStore.kt",
        ) + sourceFile(
            "app/src/main/java/com/newoether/agora/data/PortableSettingsReset.kt",
        )
        val repository = sourceFile(
            "app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt",
        )
        val archive = sourceFile(
            "app/src/main/java/com/newoether/agora/data/PortableSettingsArchive.kt",
        )

        assertTrue(presentation.contains("R.string.show_generation_speed"))
        assertTrue(presentation.contains("onEnabledChange(!enabled)"))
        assertTrue(appearance.contains("GenerationSpeedSettingRow("))
        assertTrue(appearance.contains("viewModel.settings::setShowGenerationSpeed"))
        assertTrue(settings.contains("it[SHOW_GENERATION_SPEED] ?: false"))
        assertTrue(settings.contains("saveShowGenerationSpeed"))
        assertTrue(settings.contains("prefs.remove(SHOW_GENERATION_SPEED)"))
        assertTrue(repository.contains(
            "publication.hot(settingsManager.displayPreferenceStore.showGenerationSpeed, false)",
        ))
        assertTrue(repository.contains("fun setShowGenerationSpeed"))
        assertTrue(repository.contains(
            "settingsManager.displayPreferenceStore.saveShowGenerationSpeed(enabled)",
        ))
        assertTrue(archive.contains("\"showGenerationSpeed\""))
        assertTrue(archive.contains("displayPreferenceStore.showGenerationSpeed"))
        assertTrue(archive.contains("displayPreferenceStore.saveShowGenerationSpeed"))
        assertTrue(chatApp.contains("viewModel.settings.showGenerationSpeed.collectAsState()"))
        assertTrue(messageList.contains("showGenerationSpeed = showGenerationSpeed,"))
        assertTrue(messageItem.contains("showGenerationSpeed = showGenerationSpeed,"))
        assertTrue(assistant.contains("if (showGenerationSpeed)"))
        assertTrue(assistant.contains("generationStatsText("))
        assertTrue(assistant.contains("graphicsLayer { alpha = informationActionsAlpha }"))
        listOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh",
            "values-zh-rTW",
        ).forEach { locale ->
            val strings = sourceFile("app/src/main/res/$locale/settings_strings.xml")
            assertTrue("$locale show_generation_speed", strings.contains("name=\"show_generation_speed\""))
            assertTrue(
                "$locale show_generation_speed_desc",
                strings.contains("name=\"show_generation_speed_desc\""),
            )
            val metadata = sourceFile("app/src/main/res/$locale/message_metadata.xml")
            assertTrue("$locale token_speed_value", metadata.contains("name=\"token_speed_value\""))
            assertTrue(
                "$locale total_generation_time_with_label",
                metadata.contains("name=\"total_generation_time_with_label\""),
            )
            assertTrue(
                "$locale total_generation_time_row",
                metadata.contains("name=\"total_generation_time_row\""),
            )
            assertFalse(
                "$locale keeps the dropped bullet string",
                metadata.contains("generation_speed_inline"),
            )
        }
    }
}
