package com.newoether.agora.ui.chat

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.ui.chat.interaction.InteractionKind
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ShellConfirmationController
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DrawerInteractionsTest {
    @Test fun pendingChildQuestionsAndApprovalUseOnlyParentMetadataAndClearWithControllers() = runTest {
        val dao = mockk<ChatDao>()
        val parent = MutableStateFlow<String?>("parent")
        every { dao.observeParentConversationId("child") } returns parent
        val repository = ConversationRepository(dao, null, scheduleMaintenance = {})
        val questions = AskUserController()
        val shell = ShellConfirmationController(MutableStateFlow(true)) {}
        var latest = emptyMap<String?, InteractionKind>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeDrawerInteractions(questions.requests, shell.pendingShellCommand).collect { latest = it }
        }
        runCurrent()
        assertTrue(latest.isEmpty())
        confirmVerified(dao)
        val request = questions.open("child", "Choose", emptyList(), false, blocking = false)
        runCurrent()
        assertEquals(InteractionKind.Question, latest.drawerInteraction("parent"))
        assertEquals(InteractionKind.Question, latest.drawerInteraction("child"))
        assertNull(latest.drawerInteraction("unrelated"))
        val approval = async { shell.confirm("device", "command", "child") }
        runCurrent()
        assertEquals(InteractionKind.Approval, latest.drawerInteraction("parent"))
        shell.resolve(requireNotNull(shell.pendingShellCommand.value).id, false)
        runCurrent()
        assertFalse(approval.await())
        assertEquals(InteractionKind.Question, latest.drawerInteraction("parent"))
        parent.value = "replacement"
        runCurrent()
        assertNull(latest.drawerInteraction("parent"))
        assertEquals(InteractionKind.Question, latest.drawerInteraction("replacement"))
        questions.submit(request.id, emptyList(), "answer")
        runCurrent()
        assertTrue(latest.isEmpty())
        verify { dao.observeParentConversationId("child") }
        confirmVerified(dao)
    }

    @Test fun unownedRequestsNeedNoOwnerQueryAndExpiryAndCancellationRemoveStatus() = runTest {
        val dao = mockk<ChatDao>()
        val repository = ConversationRepository(dao, null, scheduleMaintenance = {})
        val questions = AskUserController()
        val shell = ShellConfirmationController(MutableStateFlow(true)) {}
        var latest = emptyMap<String?, InteractionKind>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeDrawerInteractions(questions.requests, shell.pendingShellCommand).collect { latest = it }
        }
        val question = questions.open(null, "Choose", emptyList(), false, blocking = true)
        runCurrent()
        assertEquals(InteractionKind.Question, latest.drawerInteraction("any"))
        val approval = async { shell.confirm("device", "command") }
        runCurrent()
        assertEquals(InteractionKind.Approval, latest.drawerInteraction("any"))
        approval.cancel()
        runCurrent()
        assertEquals(InteractionKind.Question, latest.drawerInteraction("any"))
        questions.awaitAnswers(listOf(question), 1)
        runCurrent()
        assertTrue(latest.isEmpty())
        confirmVerified(dao)
    }

    @Test fun interactionPrecedesSpinnerAndUnreadForSelectedAndUnselectedRows() {
        for (selected in listOf(false, true)) {
            assertEquals(DrawerConversationIndicator.APPROVAL,
                resolveDrawerConversationIndicator(true, selected, true, InteractionKind.Approval))
            assertEquals(DrawerConversationIndicator.QUESTION,
                resolveDrawerConversationIndicator(false, selected, true, InteractionKind.Question))
            assertEquals(DrawerConversationIndicator.GENERATING,
                resolveDrawerConversationIndicator(true, selected, true))
        }
        assertEquals(DrawerConversationIndicator.UNREAD, resolveDrawerConversationIndicator(false, false, true))
        assertEquals(DrawerConversationIndicator.NONE, resolveDrawerConversationIndicator(false, true, true))
        assertEquals(InteractionKind.Approval,
            mapOf<String?, InteractionKind>(null to InteractionKind.Approval, "c" to InteractionKind.Question).drawerInteraction("c"))
    }
}
