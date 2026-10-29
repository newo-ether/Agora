package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.TokenUsage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MessageInfoDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun measuredFirstTokenWaitReadsAsALatencyFigure() {
        show(tokenUsage = TokenUsage(
            totalTokenCount = 42,
            inputTokenCount = 30,
            outputTokenCount = 12,
            generationDurationMs = 1_500L,
            timeToFirstTokenMs = 620L,
        ))
        compose.onNodeWithText(label("620 ms")).assertIsDisplayed()
        compose.onNodeWithText(label("1.2 s")).assertDoesNotExist()
    }

    @Test
    fun longFirstTokenWaitReadsInSeconds() {
        show(tokenUsage = TokenUsage(totalTokenCount = 42, outputTokenCount = 12, timeToFirstTokenMs = 1_500L))
        compose.onNodeWithText(label("1.5 s")).assertIsDisplayed()
    }

    @Test
    fun unmeasuredFirstTokenWaitKeepsTheDashesThePhoneUsesForNoFigure() {
        show(tokenUsage = TokenUsage(totalTokenCount = 42, outputTokenCount = 12))
        compose.onNodeWithText(
            ApplicationProvider.getApplicationContext<Application>()
                .getString(R.string.time_to_first_token_with_label, "—"),
        ).assertIsDisplayed()
    }

    @Test
    fun measuredTotalGenerationTimeReadsBesideTheFirstTokenWait() {
        show(tokenUsage = TokenUsage(
            totalTokenCount = 42,
            outputTokenCount = 12,
            timeToFirstTokenMs = 620L,
            totalGenerationMs = 4_200L,
        ))
        compose.onNodeWithText(totalLabel("4.2 s")).assertIsDisplayed()
    }

    @Test
    fun unmeasuredTotalGenerationTimeKeepsTheDash() {
        show(tokenUsage = TokenUsage(totalTokenCount = 42, outputTokenCount = 12))
        compose.onNodeWithText(totalLabel("—")).assertIsDisplayed()
    }

    private fun label(figure: String) =
        ApplicationProvider.getApplicationContext<Application>()
            .getString(R.string.time_to_first_token_with_label, figure)

    private fun totalLabel(figure: String) =
        ApplicationProvider.getApplicationContext<Application>()
            .getString(R.string.total_generation_time_with_label, figure)

    private fun show(tokenUsage: TokenUsage) {
        val message = ChatMessage(
            id = "model-message",
            text = "Measured answer",
            tokenCount = tokenUsage.totalTokenCount,
            tokenUsage = tokenUsage,
            participant = Participant.MODEL,
            timestamp = 1_700_000_000_000L,
            modelName = "provider:model",
        )
        compose.setContent {
            MaterialTheme {
                MessageInfoDialog(
                    message = message,
                    modelAliases = emptyMap(),
                    showProviderName = true,
                    onDismiss = {},
                )
            }
        }
    }
}
