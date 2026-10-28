package com.newoether.agora.phone

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.ui.chat.interaction.QuestionBody
import com.newoether.agora.ui.chat.interaction.QuestionDraft
import com.newoether.agora.ui.chat.message.toolDisplayName
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.viewmodel.AskUserController
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneOverlayControlsTest {
    @get:Rule val compose = createComposeRule()
    private val lease = PhoneControlLease { 0L }
    private val action = MutableStateFlow<String?>(null)
    private val questions = AskUserController()
    private val runtime = mockk<PhoneUseRuntime> {
        every { lease } returns this@PhoneOverlayControlsTest.lease
        every { askUser } returns questions
        every { actionName } returns action
    }
    private val bounds = mutableMapOf<String, Rect>()
    private var frameWidth by mutableStateOf(360.dp)
    private var frameHeight by mutableStateOf(500.dp)
    private var direction by mutableStateOf(LayoutDirection.Ltr)

    private fun start() {
        lease.claim(RunEffectIdentity("a", 1, "run", 0, "batch"), "A")
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                AgoraTheme {
                    Box(Modifier.size(frameWidth, frameHeight)) {
                        PhoneControls(runtime, lease.held.value!!.epoch, {}, { key, rect ->
                            if (rect == null) bounds.remove(key) else bounds[key] = rect
                        })
                    }
                }
            }
        }
    }

    @Test fun actualControlBoundsMoveAndClampOnDragAndFrameResize() {
        start()
        val initial = bounds.getValue("strip")
        compose.onNodeWithText("0:00").performTouchInput {
            down(center); moveBy(Offset(80f, 90f), delayMillis = 100); up()
        }
        compose.waitForIdle()
        val moved = bounds.getValue("strip")
        assertTrue(moved.left > initial.left && moved.top > initial.top)
        compose.onNodeWithText("0:00").performTouchInput {
            down(center); moveBy(Offset(1000f, 1000f), delayMillis = 100); up()
        }
        compose.waitForIdle()
        val edge = bounds.getValue("strip")
        assertTrue(edge.right <= 348f && edge.bottom <= 494f)
        val restDuration = compose.onNodeWithText("0:00").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("0:00").performTouchInput { down(center) }
        compose.waitForIdle()
        val pressedStop = compose.onNodeWithContentDescription("Stop").fetchSemanticsNode().boundsInRoot
        val pressedDuration = compose.onNodeWithText("0:00").fetchSemanticsNode().boundsInRoot
        assertEquals(restDuration.width * 1.05f, pressedDuration.width, 0.3f)
        assertTrue(pressedStop.right <= 348f && pressedStop.bottom <= 494f)
        compose.onNodeWithText("0:00").performTouchInput { cancel() }
        compose.waitForIdle()
        compose.runOnIdle { frameWidth = 300.dp; frameHeight = 260.dp; action.value = "Swipe" }
        compose.waitForIdle()
        val resized = bounds.getValue("strip")
        assertTrue(resized.left >= 12f && resized.top >= 6f)
        assertTrue(resized.right <= 288f && resized.bottom <= 254f)
        assertEquals(setOf("strip"), bounds.keys)
    }

    @Test fun sharedAskPagesKeepDraftsWhenAQuestionArrivesAndSubmitOneOrderedAnswerSet() = runBlocking {
        val set = questions.newSetId()
        val first = questions.open("a", "Choose one", listOf("A", "B"), false, false, set, "run")
        questions.open("a", "Choose several", listOf("X", "Y"), true, false, set, "run")
        val deliveries = mutableListOf<AskUserController.DeferredAnswer>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            questions.deferredAnswers.collect { deliveries += it }
        }
        try {
            start()
            compose.onNodeWithText("Ask User").assertExists()
            compose.onNodeWithText("1 / 2").assertExists()
            compose.onNodeWithText("A").performClick()
            compose.onNodeWithText("B").performClick()
            click(R.string.ask_user_next)
            compose.onNodeWithText("X").performClick()
            compose.onNodeWithText("Y").performClick()
            compose.runOnIdle { questions.open("a", "Open answer", emptyList(), false, false, set, "run") }
            compose.onNodeWithText("2 / 3").assertExists()
            click(R.string.ask_user_next)
            compose.onNode(hasSetTextAction()).performTextInput("free answer")
            compose.waitForIdle()
            assertTrue(bounds.keys.any { it.startsWith("text-") })
            click(R.string.back)
            click(R.string.back)
            compose.onNodeWithText("Choose one").assertExists()
            click(R.string.ask_user_next)
            compose.onNodeWithText("Choose several").assertExists()
            click(R.string.ask_user_next)
            compose.onNode(hasSetTextAction()).assertTextContains("free answer")
            click(R.string.ask_user_send)
            questions.submit(first.id, listOf("A"))
            kotlinx.coroutines.yield()
            assertTrue(questions.requests.value.isEmpty())
            assertEquals(1, deliveries.size)
            assertEquals("Choose one\nB\n\nChoose several\nX, Y\n\nOpen answer\nfree answer", deliveries.single().text)
            assertEquals(setOf("strip"), bounds.keys)
        } finally {
            collector.cancel()
        }
    }

    @Test fun foldReleasesWindowFocusWithoutAnsweringAndSkipSettlesTheWholeSet() {
        val set = questions.newSetId()
        questions.open("a", "Keep this draft?", emptyList(), false, true, set, "run")
        questions.open("a", "Another question", listOf("Yes"), false, true, set, "run")
        var focused = false
        compose.setContent {
            AgoraTheme {
                val waiting by questions.requests.collectAsState()
                if (waiting.isNotEmpty()) PhoneAskInteraction(waiting, questions, { focused = it },
                    { key, rect -> if (rect == null) bounds.remove(key) else bounds[key] = rect }, true)
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("draft stays")
        compose.waitForIdle()
        assertTrue(focused)
        compose.onNodeWithContentDescription(text(R.string.interaction_minimize)).performClick()
        compose.waitForIdle()
        assertFalse(focused)
        assertEquals(2, questions.requests.value.size)
        compose.onNodeWithContentDescription(text(R.string.interaction_expand)).performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("draft stays")
        click(R.string.ask_user_skip)
        assertTrue(questions.requests.value.isEmpty())
        assertFalse(focused)
        assertTrue(bounds.isEmpty())
    }

    @Test fun sharedOwnAnswerWaitsForHostFocusPreparationAndDisabledInputPreservesTheDraft() {
        val request = questions.open("a", "Pick or type", listOf("A"), false, false, runId = "run")
        val draft = QuestionDraft(hasOptions = true)
        val ready = CompletableDeferred<Unit>()
        var preparations = 0
        var enabled by mutableStateOf(true)
        compose.setContent {
            AgoraTheme {
                QuestionBody(request, draft, prepareTextInput = {
                    preparations++
                    ready.await()
                }, enabled = enabled)
            }
        }
        compose.onNodeWithText(text(R.string.ask_user_custom_answer)).performClick()
        compose.waitForIdle()
        assertEquals(1, preparations)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle { ready.complete(Unit) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("own answer")
        compose.runOnIdle { enabled = false }
        compose.onNodeWithText("own answer").assertIsNotEnabled()
        compose.onNodeWithText("A").assertIsNotEnabled()
        assertEquals("own answer", draft.typed)
        assertEquals(emptySet<String>(), draft.selected)
        compose.runOnIdle { enabled = true }
        compose.onNodeWithText("A").performClick()
        assertFalse(draft.ownAnswer)
        assertEquals(listOf("A"), draft.answerFor(request).choices)
        assertNull(draft.answerFor(request).text)
    }

    @Test fun sharedMultipleChoiceKeepsTypedAnswerAndOrdersChoicesAsAsked() {
        val request = questions.open("a", "Pick several or type", listOf("X", "Y"), true, false, runId = "run")
        val draft = QuestionDraft(hasOptions = true)
        compose.setContent { AgoraTheme { QuestionBody(request, draft) } }
        compose.onNodeWithText("Y").performClick()
        compose.onNodeWithText("X").performClick()
        compose.onNodeWithText(text(R.string.ask_user_custom_answer)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("  also this  ")
        assertEquals(listOf("X", "Y"), draft.answerFor(request).choices)
        assertEquals("also this", draft.answerFor(request).text)
    }

    @Test fun inactiveSharedAskCannotSubmitSkipOrChangeItsSelectedAnswer() {
        val request = questions.open("a", "Outgoing question", listOf("A", "B"), false, true, runId = "run")
        var enabled by mutableStateOf(true)
        compose.setContent {
            AgoraTheme { PhoneAskInteraction(listOf(request), questions, {}, { _, _ -> }, enabled) }
        }
        compose.onNodeWithText("A").performClick()
        compose.runOnIdle { enabled = false }
        compose.onNodeWithText("A").assertDoesNotExist()
        compose.onNodeWithText("B", useUnmergedTree = true).onParent().performClick()
        compose.onNodeWithText(text(R.string.ask_user_send), useUnmergedTree = true).onParent().performClick()
        compose.onNodeWithText(text(R.string.ask_user_skip), useUnmergedTree = true).onParent().performClick()
        assertEquals(listOf(request), questions.requests.value)
        compose.runOnIdle { enabled = true }
        click(R.string.ask_user_send)
        val answer = runBlocking { withTimeout(1_000) { questions.awaitAnswer(request) } }
        assertEquals(listOf("A"), answer.choices)
    }

    @Test fun everyToolActionUsesItsOwnLabelAndOnlyIdleShowsThinking() {
        val resources = RuntimeEnvironment.getApplication().resources
        fun resolved(name: String, title: String? = null) = checkNotNull(PhoneActionStatus.activity(
            ChatMessage(id = "message", text = "", participant = Participant.MODEL, runId = "run",
                segments = listOf(MessageSegment("tool", toolName = name, toolCallId = "call",
                    toolState = ToolExecutionStates.RUNNING, toolDisplayName = title))),
            "run", resources,
        )).label
        start()
        compose.onNodeWithText(text(R.string.phone_thinking)).assertExists()
        listOf(resolved("shell_exec"), resolved("web_search"), "Custom tool display").forEach { name ->
            compose.runOnIdle { action.value = name }
            compose.onNodeWithText(name).assertExists()
            compose.onNodeWithText(text(R.string.phone_thinking)).assertDoesNotExist()
        }
        compose.runOnIdle { action.value = resolved("phone_type") }
        compose.onNodeWithText(text(R.string.phone_typing)).assertExists()
        compose.onNodeWithText("phone_type").assertDoesNotExist()
        compose.runOnIdle { action.value = null }
        compose.onNodeWithText(text(R.string.phone_thinking)).assertExists()
    }

    private fun click(resource: Int) {
        compose.onNodeWithText(text(resource)).performClick()
        compose.waitForIdle()
    }

    private fun text(resource: Int) = RuntimeEnvironment.getApplication().getString(resource)

    @Test fun physicalDragDirectionAndInitialTopLeftAreStableInRtl() {
        direction = LayoutDirection.Rtl
        start()
        val initial = bounds.getValue("strip")
        assertEquals(12f, initial.left, 0.1f)
        compose.onNodeWithText("0:00").performTouchInput {
            down(center); moveBy(Offset(60f, 40f), delayMillis = 100); up()
        }
        compose.waitForIdle()
        val moved = bounds.getValue("strip")
        assertTrue(moved.left > initial.left && moved.top > initial.top)
    }
}
