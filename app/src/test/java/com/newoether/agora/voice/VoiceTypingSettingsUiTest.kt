package com.newoether.agora.voice

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.VoiceTypingSettingsStore
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.ui.chat.bottombar.VoiceTypingButton
import com.newoether.agora.ui.settings.VoiceTypingSettingsGroup
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VoiceTypingSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val config = MutableStateFlow(VoiceTypingConfig())
    private fun settings(): SettingsRepository {
        val store = mockk<VoiceTypingSettingsStore>()
        every { store.config } returns config
        coEvery { store.update(any()) } coAnswers { config.value = firstArg<(VoiceTypingConfig) -> VoiceTypingConfig>()(config.value) }
        return mockk<SettingsRepository>().apply {
            every { voiceTyping } returns store
            every { apiKeys } returns MutableStateFlow(listOf(ApiKeyEntry("id", "Personal", "never-render-this-key", "OpenAI")))
            every { activeApiKeyIds } returns MutableStateFlow(mapOf("OpenAI" to "id"))
        }
    }

    @Test fun masterToggleAndProviderChoicePersistWithoutShowingCredentialValues() {
        val settings = settings()
        compose.setContent { MaterialTheme { Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
            VoiceTypingSettingsGroup(settings)
        } } }
        compose.onNode(isToggleable()).assertIsOff().performClick()
        compose.waitUntil(10000) { config.value.enabled }
        compose.onNodeWithText("Speech recognition: Android / Google Voice Typing").performClick()
        compose.onNodeWithText("OpenAI · GPT-4o mini transcribe", substring = false).performClick()
        compose.waitUntil(10000) { config.value.mode == VoiceTypingMode.OPENAI }
        assertEquals("gpt-4o-mini-transcribe", config.value.model)
        compose.onNodeWithText("never-render-this-key").assertDoesNotExist()
        compose.onNodeWithText("OpenAI · Personal").assertExists()
    }

    @Test fun disabledVoiceTypingHasNoMicrophoneAndStartsNoSession() {
        val settings = settings()
        var busy = false
        compose.setContent { MaterialTheme { VoiceTypingButton(settings, TextFieldState(), true, { busy = it }) } }
        compose.onNodeWithContentDescription("Start Voice Typing").assertDoesNotExist()
        compose.runOnIdle { assertFalse(busy) }
    }
}
