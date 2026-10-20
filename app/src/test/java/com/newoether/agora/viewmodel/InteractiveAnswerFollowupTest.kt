package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolExecutionStates
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InteractiveAnswerFollowupTest {
    private val answer = InteractiveAnswer("Tool", "A tool", "A readable answer", html = "<p>Answer</p>")
    private val complete = ChatMessage(id = "m", text = "", participant = Participant.MODEL,
        segments = listOf(MessageSegment(type = "tool", toolName = InteractiveAnswer.TOOL_NAME,
            toolCallId = "call", toolState = ToolExecutionStates.SUCCEEDED, toolStructuredResult = answer.encode())))
    private val selected = MutableStateFlow<String?>("c")
    // Selected topology deliberately has no heavy payload, just like lazy historical messages.
    private val path = MutableStateFlow(listOf(complete.copy(segments = null)))
    private val hydration = mockk<ConversationMessagePayloadHydration>()
    private val viewModel = mockk<ChatViewModel>().also {
        every { it.currentConversationId } returns selected
        every { it.messages } returns path
        every { it.messagePayloadHydration } returns hydration
    }

    @Test fun `selected summary admits one demand loaded exact artifact`() = runTest {
        coEvery { hydration.loadMessages("c", listOf("m"), any()) } returns listOf(complete)
        assertTrue(viewModel.verifyInteractiveAnswer("c", "m", "call", answer))
        assertFalse(viewModel.verifyInteractiveAnswer("c", "m", "foreign-call", answer))
        coVerify(exactly = 2) { hydration.loadMessages("c", listOf("m"), any()) }
    }
    @Test fun `foreign selection and removed branch never hydrate another conversation`() = runTest {
        selected.value = "other"
        assertFalse(viewModel.verifyInteractiveAnswer("c", "m", "call", answer))
        selected.value = "c"
        path.value = emptyList()
        assertFalse(viewModel.verifyInteractiveAnswer("c", "m", "call", answer))
        coVerify(exactly = 0) { hydration.loadMessages(any(), any(), any()) }
    }
    @Test fun `selection change while hydrating revokes the originating viewer`() = runTest {
        val result = CompletableDeferred<List<ChatMessage>>()
        coEvery { hydration.loadMessages("c", listOf("m"), any()) } coAnswers { result.await() }
        val pending = async { viewModel.verifyInteractiveAnswer("c", "m", "call", answer) }
        runCurrent()
        selected.value = "other"
        result.complete(listOf(complete))
        assertFalse(pending.await())
    }
    @Test fun `Skill review verifies the exact completed proposal and its draft`() = runTest {
        val proposed = answer.copy(skillDraft = com.newoether.agora.skills.SkillDocument("workflow.md", "Reviewed workflow"))
        val message = complete.copy(segments = listOf(complete.segments!!.single().copy(
            toolName = "propose_skill", toolStructuredResult = proposed.encode())))
        coEvery { hydration.loadMessages("c", listOf("m"), any()) } returns listOf(message)
        assertTrue(viewModel.verifyInteractiveAnswer("c", "m", "call", proposed))
        assertFalse(viewModel.verifyInteractiveAnswer("c", "m", "call", proposed.copy(
            skillDraft = proposed.skillDraft!!.copy(instructions = "Different draft"))))
    }

}
