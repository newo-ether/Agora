package com.newoether.agora.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class InteractiveFollowupPromptTest {
    @get:Rule val compose = createComposeRule()

    @Test fun genericSelectionAcceptsExactNonMarkdownPathsButRejectsTraversalAndExcessFiles() {
        fun request(paths: List<String>) = InteractivePrompt.fromBridge(kotlinx.serialization.json.buildJsonObject {
            put("text", kotlinx.serialization.json.JsonPrimitive("Use selected files"))
            put("sourceFiles", kotlinx.serialization.json.JsonArray(paths.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })
        assertEquals(listOf("Data/menu.json", "cover.PNG", "README"), request(listOf("Data/menu.json", "cover.PNG", "README"))?.sourceFiles)
        assertNull(request(listOf("../private"))); assertNull(request(listOf("same.txt", "same.txt")))
        assertNull(request((1..17).map { "$it.custom" }))
    }
    @Test fun savedTemplateRunSendsOnceWithoutDialogAndAcknowledgesAcceptance() {
        var count = 0
        var finished: Boolean? = null
        var reply: Pair<Boolean, Boolean>? = null
        val request = PendingInteractivePrompt(1, "selected case", { accepted, cancelled -> reply = accepted to cancelled })
        val recompose = mutableStateOf(0)
        compose.setContent { MaterialTheme {
            recompose.value
            InteractiveFollowupPrompt(request, false, { assertEquals("selected case", it.text); count++; true }, { finished = it })
        } }
        compose.waitUntil(10000) { finished != null }
        compose.onNodeWithText("Send this follow-up?").assertDoesNotExist()
        compose.runOnIdle { recompose.value++ }
        compose.waitForIdle()
        assertEquals(1, count)
        assertEquals(true, finished)
        assertEquals(true to false, reply)
    }

    @Test fun generatedAnswerStillWaitsForExplicitConfirmation() {
        var count = 0
        var finished: Boolean? = null
        val request = PendingInteractivePrompt(1, "review this prompt", { _, _ -> })
        compose.setContent { MaterialTheme {
            InteractiveFollowupPrompt(request, true, { count++; true }, { finished = it })
        } }
        compose.onNodeWithText("Send this follow-up?").assertExists()
        assertEquals(0, count)
        compose.onNodeWithText("Send", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { finished != null }
        assertEquals(1, count)
    }

    @Test fun rejectedDirectRunReportsFailureAndDoesNotRetryOnRecomposition() {
        var count = 0
        var finished: Boolean? = null
        var reply: Pair<Boolean, Boolean>? = null
        val request = PendingInteractivePrompt(1, "case", { accepted, cancelled -> reply = accepted to cancelled })
        compose.setContent { MaterialTheme {
            InteractiveFollowupPrompt(request, false, { count++; error("Rejected by owner") }, { finished = it })
        } }
        compose.waitUntil(10000) { finished != null }
        assertEquals(false, finished)
        assertEquals(false to false, reply)
        assertEquals(1, count)
    }
}
