package com.newoether.agora.remote

import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.ui.chat.InteractivePrompt
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.viewmodel.ScrollRequestCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class RemoteInteractiveFollowupTest : RemoteViewModelFixture() {
    private val answer = InteractiveAnswer("Figure", "Model", "Fallback", html = "<svg/>")
    private val tool = RemoteMessage("figure", "turn", null, "assistant", "", 1, groupId = "figure",
        activity = RemoteActivity("tool", InteractiveAnswer.TOOL_NAME, state = "succeeded",
            structuredResult = Json.parseToJsonElement(answer.encode()).jsonObject))

    @Test fun followupUsesExistingSendWithoutSubmittingComposerText() = runTest(dispatcher) {
        coEvery { client.conversation(any(), any()) } returns bodyPage(listOf(tool), null, emptyList())
        coEvery { client.send(any(), any(), any(), any()) } returns "followup-turn"
        val vm = RemoteViewModel(connections, projectionDispatcher = dispatcher) { _, _ -> client }
        runCurrent(); saveAndSelect(vm); vm.setVisible(true); vm.selectSession(session); runCurrent()
        val owner = requireNotNull(vm.state.value.owner)
        vm.editDraft(owner, "Unsent draft")
        assertTrue(vm.hasInteractiveAnswer(owner, "figure", "figure", answer))
        assertFalse(vm.sendInteractiveFollowup(owner, "figure", "figure", answer,
            InteractivePrompt("Read a device file", listOf("notes/private.md"))))
        assertTrue(vm.sendInteractiveFollowup(owner, "figure", "figure", answer, InteractivePrompt("Explain the mechanism")))
        coVerify(exactly = 1) { client.send(session.id, "Explain the mechanism", any(), emptyList()) }
        assertEquals("Unsent draft", vm.state.value.drafts[owner])
        assertTrue(vm.state.value.attempts[owner]!!.preserveComposer)
        assertFalse(vm.sendInteractiveFollowup("another-owner", "figure", "figure", answer, InteractivePrompt("Wrong chat")))
        assertFalse(vm.sendInteractiveFollowup(owner, "figure", "figure", answer.copy(html = "changed"), InteractivePrompt("Stale artifact")))
        coVerify(exactly = 1) { client.send(any(), any(), any(), any()) }
        vm.setVisible(false)
    }

    @Test fun nativeReceiptPreservesDraftAndAttachmentsForArtifactFollowup() {
        val owner = "device/session"
        val attachment = SelectedAttachment(uri = "private://draft", type = "file")
        val attempt = RemoteAttempt("client", "Explain", RemoteDelivery.ACCEPTED, preserveComposer = true)
        val state = MutableStateFlow(RemoteState(deviceId = "device", session = session,
            drafts = mapOf(owner to "Keep my draft"), attachments = mapOf(owner to listOf(attachment)),
            attempts = mapOf(owner to attempt)))
        val node = RemoteMessageNode("ack", "turn", "client", "user", 1, "a".repeat(64), 7)
        confirmRemoteDelivery(state, null, ScrollRequestCoordinator(), owner, attempt, listOf(node))
        assertEquals("Keep my draft", state.value.drafts[owner])
        assertEquals(listOf(attachment), state.value.attachments[owner])
        assertEquals(RemoteDelivery.DELIVERED, state.value.attempts[owner]?.delivery)
    }

    @Test fun structuredArtifactsCountTowardTheRemoteRecordBudget() = runTest(dispatcher) {
        val node = bodyPage(listOf(tool), null, emptyList()).nodes.single()
        val groups = projectRemoteTopology(listOf(node), RemoteRuntime("idle"))
        val state = MutableStateFlow(RemoteState(deviceId = "device", session = session, messageGroups = groups))
        val hydration = RemoteMessageHydration(state, { _, _ -> error("No network expected") }, { throw it },
            maxRecordBytes = 500, projectionDispatcher = dispatcher)
        hydration.accept(requireNotNull(state.value.owner), bodyPage(listOf(tool), null, emptyList()), groups, live = false)
        assertTrue(hydration.retainedRecordBytes <= 500)
    }
    @Test fun offPageReceiptPreservesArtifactComposerAndRejectsWrongTurn() {
        val owner = "device/session"
        val attachment = SelectedAttachment(uri = "private://draft", type = "file")
        val attempt = RemoteAttempt("client", "Explain", RemoteDelivery.ACCEPTED,
            turnId = "expected-turn", preserveComposer = true)
        val state = MutableStateFlow(RemoteState(deviceId = "device", session = session,
            drafts = mapOf(owner to "Keep my draft"), attachments = mapOf(owner to listOf(attachment)),
            attempts = mapOf(owner to attempt)))
        val scroll = ScrollRequestCoordinator()
        confirmRemoteDelivery(state, null, scroll, owner, attempt, emptyList(),
            listOf(RemoteInputReceipt("client", "wrong-turn", "message")))
        assertEquals(RemoteDelivery.ACCEPTED, state.value.attempts[owner]?.delivery)
        confirmRemoteDelivery(state, null, scroll, owner, attempt, emptyList(),
            listOf(RemoteInputReceipt("client", "expected-turn", "message")))
        assertEquals(RemoteDelivery.DELIVERED, state.value.attempts[owner]?.delivery)
        assertEquals("Keep my draft", state.value.drafts[owner])
        assertEquals(listOf(attachment), state.value.attachments[owner])
    }

    @Test fun offPageReceiptClearsOnlyTheUnchangedOrdinaryDraft() {
        val owner = "device/session"
        val attempt = RemoteAttempt("client", "Submitted", RemoteDelivery.ACCEPTED, turnId = "turn")
        val state = MutableStateFlow(RemoteState(deviceId = "device", session = session,
            drafts = mapOf(owner to "New writing"), attempts = mapOf(owner to attempt)))
        confirmRemoteDelivery(state, null, ScrollRequestCoordinator(), owner, attempt, emptyList(),
            listOf(RemoteInputReceipt("client", "turn", "message")))
        assertEquals(RemoteDelivery.DELIVERED, state.value.attempts[owner]?.delivery)
        assertEquals("New writing", state.value.drafts[owner])
    }

}
