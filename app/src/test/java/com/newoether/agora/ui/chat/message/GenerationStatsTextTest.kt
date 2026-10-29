package com.newoether.agora.ui.chat.message

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GenerationStatsTextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settledReplyPrintsTheRateAndTheTotalTime() {
        show(rate = 12.3, totalMs = 4_200)
        compose.onNodeWithText("12.3 tokens/s · 4.2 s total").assertIsDisplayed()
    }

    @Test
    fun aRateWithoutATotalPrintsAlone() {
        show(rate = 12.3, totalMs = null)
        compose.onNodeWithText("12.3 tokens/s").assertIsDisplayed()
    }

    @Test
    fun aTotalWithoutARatePrintsAlone() {
        show(rate = null, totalMs = 420)
        compose.onNodeWithText("420 ms total").assertIsDisplayed()
    }

    @Test
    fun unmeasuredReplyPrintsNothing() {
        show(rate = null, totalMs = null)
        compose.onAllNodesWithText("total").assertCountEquals(0)
    }

    private fun show(rate: Double?, totalMs: Long?) {
        compose.setContent { Text(generationStatsText(rate, totalMs) ?: "") }
    }
}
