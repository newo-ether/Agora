package com.newoether.agora.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ThinkingControlPanelTest {
    @get:Rule val compose = createComposeRule()
    private val slider = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    private val switch = SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)

    private fun panel(
        budget: Boolean = false,
        tokens: Int = 4096,
        supported: Boolean = true,
        editable: Boolean = true,
        changes: MutableList<String> = mutableListOf(),
    ) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ThinkingControlPanel(
                        enabled = true, level = "high", budgetEnabled = budget, budgetTokens = tokens,
                        availableEfforts = listOf("low", "high"), controlsEnabled = editable,
                        budgetControlsEnabled = supported,
                        onEnabledChange = { changes += "enabled:$it" },
                        onLevelChange = { changes += "effort:$it" },
                        onBudgetEnabledChange = { changes += "budget:$it" },
                        onBudgetTokensChange = { changes += "tokens:$it" },
                    )
                }
            }
        }
    }

    @Test fun unsupportedBudgetRemainsDiscoverableButCannotWrite() {
        val changes = mutableListOf<String>()
        panel(supported = false, changes = changes)
        compose.onNodeWithText("Advanced").performClick()
        compose.onNodeWithText("Use Thinking Budget").assertExists()
        compose.onAllNodes(switch)[1].assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
    }

    @Test fun disabledPanelFencesBothBudgetControls() {
        val changes = mutableListOf<String>()
        panel(budget = true, editable = false, changes = changes)
        compose.onAllNodes(switch)[1].assertIsNotEnabled().performTouchInput { click() }
        compose.onAllNodes(slider)[1].performScrollTo().assertIsNotEnabled()
            .performTouchInput { click(Offset(width * .9f, height * .5f)) }
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
    }

    @Test fun budgetDragCommitsOnceOnReleaseAndDoesNotToggleThinking() {
        val changes = mutableListOf<String>()
        panel(budget = true, changes = changes)
        val budget = compose.onAllNodes(slider)[1].performScrollTo()
        budget.performTouchInput {
            down(Offset(width * .4f, height * .5f))
            moveTo(Offset(width * .9f, height * .5f))
        }
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
        budget.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, changes.size)
            assertTrue(changes.single().startsWith("tokens:"))
        }
    }

    @Test fun unchangedBudgetGestureDoesNotWrite() {
        val changes = mutableListOf<String>()
        panel(budget = true, tokens = 32768, changes = changes)
        compose.onAllNodes(slider)[1].performScrollTo().performTouchInput {
            click(Offset(width * .99f, height * .5f))
        }
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
    }

    @Test fun nonPresetBudgetUsesNearestSliderLabelWithoutRewritingSavedValue() {
        val changes = mutableListOf<String>()
        panel(budget = true, tokens = 6000, changes = changes)
        compose.onNodeWithText("6000 tokens").assertExists()
        compose.onNodeWithText("4096 tokens").assertExists()
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
    }

    @Test fun revisionResetsPendingBudgetSelectionToCurrentOwnerValue() {
        var revision by mutableStateOf(0L)
        var tokens by mutableStateOf(4096)
        val changes = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ThinkingControlPanel(
                        enabled = true, level = "high", budgetEnabled = true, budgetTokens = tokens,
                        settingsRevision = revision, onEnabledChange = {}, onLevelChange = {},
                        onBudgetEnabledChange = {}, onBudgetTokensChange = { changes += it },
                    )
                }
            }
        }
        compose.onAllNodes(slider)[1].performScrollTo().performTouchInput {
            click(Offset(width * .99f, height * .5f))
        }
        compose.runOnIdle {
            assertEquals(listOf(32768), changes)
            tokens = 2048
            revision++
        }
        compose.onAllNodesWithText("2048 tokens").assertCountEquals(2)
    }
}
