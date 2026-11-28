package com.newoether.agora.ui.chat

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.newoether.agora.ProcessTextRequest
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProcessTextComposerEffectTest {
    @get:Rule val compose = createComposeRule()

    @Test fun waitsForRestoredDraftAndSettledOwnerThenAppendsWithoutOverwritingEdits() {
        val field = TextFieldState("existing draft")
        var loaded by mutableStateOf(false)
        var switching by mutableStateOf(true)
        val consumed = mutableListOf<String>()
        val writes = mutableListOf<String>()
        compose.setContent {
            ProcessTextComposerEffect("owner", loaded, switching,
                ProcessTextRequest("request", "selected text"), field, consumed::add,
                persistText = { _, text -> writes += text; true })
        }
        compose.runOnIdle {
            assertEquals("existing draft", field.text.toString())
            assertTrue(consumed.isEmpty())
            loaded = true
        }
        compose.runOnIdle {
            assertTrue(consumed.isEmpty())
            field.edit { append(" typed before ingress") }
            switching = false
        }
        compose.runOnIdle {
            val expected = "existing draft typed before ingress\n\nselected text"
            assertEquals(expected, field.text.toString())
            assertEquals(listOf(expected), writes)
            assertEquals(listOf("request"), consumed)
        }
    }

    @Test fun repeatedSelectionAppendsOncePerInvocationAndNeverReplaysOnRecreation() {
        val field = TextFieldState()
        var request by mutableStateOf(ProcessTextRequest("first", "selection"))
        val consumed = mutableListOf<String>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ProcessTextComposerEffect("owner", true, false, request, field, consumed::add,
                persistText = { _, _ -> true })
        }
        compose.runOnIdle {
            assertEquals("selection", field.text.toString())
            assertEquals(listOf("first"), consumed)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals("selection", field.text.toString()) }
        compose.runOnIdle { request = ProcessTextRequest("second", "selection") }
        compose.runOnIdle {
            assertEquals("selection\n\nselection", field.text.toString())
            assertEquals(1, consumed.count { it == "second" })
        }
    }

    @Test fun waitsForDurableWriteAndPreservesTypingWhileItIsSuspended() {
        val field = TextFieldState("draft")
        val committed = CompletableDeferred<Boolean>()
        val writes = mutableListOf<String>()
        val consumed = mutableListOf<String>()
        compose.setContent {
            ProcessTextComposerEffect("owner", true, false,
                ProcessTextRequest("request", "selection"), field, consumed::add,
                persistText = { _, text -> writes += text; committed.await() })
        }
        compose.runOnIdle {
            assertEquals(listOf("draft\n\nselection"), writes)
            assertTrue(consumed.isEmpty())
            field.edit { append(" typed during save") }
            committed.complete(true)
        }
        compose.runOnIdle {
            assertEquals("draft\n\nselection typed during save", field.text.toString())
            assertEquals(listOf("request"), consumed)
        }
    }

    @Test fun failedWriteKeepsRequestPendingAndNextEditRetriesWithoutAppendingAgain() {
        val field = TextFieldState("draft")
        var failWrites = true
        val consumed = mutableListOf<String>()
        val writes = mutableListOf<String>()
        compose.setContent {
            ProcessTextComposerEffect("owner", true, false,
                ProcessTextRequest("request", "selection"), field, consumed::add,
                persistText = { _, text -> writes += text; !failWrites })
        }
        compose.runOnIdle {
            assertTrue(consumed.isEmpty())
            assertEquals("draft\n\nselection", field.text.toString())
            failWrites = false
            field.edit { append(" retry edit") }
        }
        compose.runOnIdle {
            assertEquals(listOf("draft\n\nselection", "draft\n\nselection retry edit"), writes)
            assertEquals(listOf("request"), consumed)
        }
    }

    @Test fun replacingTextFieldForSamePendingRequestRestartsItsPersistenceObserver() {
        var field by mutableStateOf(TextFieldState("old disk draft"))
        val consumed = mutableListOf<String>()
        val writes = mutableListOf<String>()
        var failWrites = true
        compose.setContent {
            ProcessTextComposerEffect("owner", true, false,
                ProcessTextRequest("request", "selection"), field, consumed::add,
                persistText = { _, text -> writes += text; !failWrites })
        }
        compose.runOnIdle {
            assertEquals(listOf("old disk draft\n\nselection"), writes)
            assertTrue(consumed.isEmpty())
            failWrites = false
            field = TextFieldState("old disk draft")
        }
        compose.runOnIdle {
            assertEquals("old disk draft\n\nselection", field.text.toString())
            assertEquals(listOf("old disk draft\n\nselection", "old disk draft\n\nselection"), writes)
            assertEquals(listOf("request"), consumed)
        }
    }

    @Test fun selectedTextWaitsForTheNewChatOwnerInsteadOfJoiningTheOpenConversation() {
        val field = TextFieldState()
        val consumed = mutableListOf<String>()
        var owner by mutableStateOf("conversation")
        compose.setContent {
            ProcessTextComposerEffect(owner, true, false,
                ProcessTextRequest("request", "selected text"), field, consumed::add,
                persistText = { _, _ -> true }, newChatOnly = true)
        }
        compose.runOnIdle {
            assertEquals("", field.text.toString())
            assertTrue(consumed.isEmpty())
            owner = com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
        }
        compose.runOnIdle {
            assertEquals("selected text", field.text.toString())
            assertEquals(listOf("request"), consumed)
        }
    }

    @Test fun appendingPreservesAllDraftAndSelectionWhitespace() {
        assertEquals("  selection  ", appendSelectedText("", "  selection  "))
        assertEquals("draft\n\nselection", appendSelectedText("draft", "selection"))
        assertEquals("draft\n\nselection", appendSelectedText("draft\n", "selection"))
        assertEquals("draft\n\nselection", appendSelectedText("draft\n\n", "selection"))
    }
}
