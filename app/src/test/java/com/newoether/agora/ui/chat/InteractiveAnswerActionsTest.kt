package com.newoether.agora.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.chat.message.InteractiveAnswerActions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InteractiveAnswerActionsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun defaultViewOnlyOffersExpandAndCannotAccidentallySave() {
        var expanded = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(328.dp)) {
            InteractiveAnswerActions { expanded++ }
        } } }
        compose.onNodeWithText("Save as template").assertDoesNotExist()
        compose.onNodeWithText("Expand interactive view").performClick()
        compose.runOnIdle { assertEquals(1, expanded) }
    }

    @Test fun expandedSaveIsExplicitAndDisabledAfterSuccessfulSave() {
        var saved = 0
        compose.setContent { MaterialTheme { InteractiveAnswerSaveAction { saved++; true } } }
        assertEquals(0, saved)
        compose.onNodeWithText("Save as template").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Template saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Template saved").assertIsNotEnabled()
        compose.onNodeWithText("Template saved").performClick()
        compose.runOnIdle { assertEquals(1, saved) }
    }
}
