package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.automation.ConversationExecutionCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationLifecycleControllerTest {
    @Test
    fun admittedDeletionHoldsTheCoordinatorUntilStorageCompletes() = runTest {
        val fixture = Fixture(this, controllerScope = backgroundScope)
        val releaseDeletion = CompletableDeferred<Unit>()
        coEvery { fixture.conversations.deleteConversation("conversation") } coAnswers {
            fixture.events += "delete-start"
            releaseDeletion.await()
            fixture.events += "delete-end"
        }
        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()
        backgroundScope.launch {
            fixture.executionCoordinator.withAutomationConversationLock("conversation") {
                fixture.events += "generation"
            }
        }
        runCurrent()
        assertTrue("delete-start" in fixture.events)
        assertTrue("generation" !in fixture.events)

        releaseDeletion.complete(Unit)
        runCurrent()

        assertTrue(fixture.events.indexOf("generation") > fixture.events.indexOf("delete-end"))
        coVerify(exactly = 1) { fixture.conversations.deleteConversation("conversation") }
    }

    @Test
    fun staleConfirmationIsRejectedBeforeStoppingGenerationOrDeletingStorage() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.getMessageTopologySnapshot("conversation") } returns emptyList()
        val results = mutableListOf<Boolean>()

        fixture.controller.delete(fixture.origin, "conversation", setOf("confirmed-message"), results::add)
        runCurrent()

        assertEquals(listOf(false), results)
        assertTrue("abort:7" in fixture.events)
        assertTrue("stop" !in fixture.events && "stop-loop" !in fixture.events)
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
    }

    @Test
    fun occupiedCoordinatorAfterStopRejectsDeletionWithoutTouchingStorage() = runTest {
        val fixture = Fixture(this, controllerScope = backgroundScope)
        val releaseGeneration = CompletableDeferred<Unit>()
        val generation = backgroundScope.launch {
            fixture.executionCoordinator.withAutomationConversationLock("conversation") {
                releaseGeneration.await()
            }
        }
        runCurrent()
        val results = mutableListOf<Boolean>()

        assertTrue(fixture.controller.delete(fixture.origin, "conversation", onResult = results::add))
        runCurrent()

        assertEquals(listOf(false), results)
        assertTrue(generation.isActive)
        assertTrue("abort:7" in fixture.events)
        assertEquals(listOf("conversation"), fixture.stoppedTargets)
        assertTrue("stop-loop" in fixture.events)
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
        releaseGeneration.complete(Unit)
    }

    @Test
    fun cancelledDeletionCompletesItsFailureCallbackEvenWhileTheScopeIsCancelled() = runTest {
        val controllerJob = SupervisorJob()
        val controllerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + controllerJob)
        val fixture = Fixture(this, controllerScope = controllerScope)
        fixture.onOverlay = { CompletableDeferred<Unit>().await() }
        val results = mutableListOf<Boolean>()
        fixture.controller.delete(fixture.origin, "conversation", onResult = results::add)
        runCurrent()

        controllerJob.cancel()
        runCurrent()

        assertEquals(listOf(false), results)
        assertTrue("abort:null" in fixture.events)
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
    }

    @Test
    fun renamePersistsTheExactTitle() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.updateConversationTitle(any(), any()) } returns true

        fixture.controller.rename("conversation", "New title")
        runCurrent()

        coVerify(exactly = 1) {
            fixture.conversations.updateConversationTitle("conversation", "New title")
        }
    }

    @Test
    fun visibleDeletionPreservesStopLockCleanupAndSelectionSettlementOrder() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }

        fixture.controller.delete(fixture.origin, "conversation")
        assertTrue(fixture.events.isEmpty())
        runCurrent()

        assertEquals(
            listOf(
                "overlay:conversation",
                "stop-loop",
                "stop",
                "lock-start",
                "delete",
                "lock-end",
                "remove",
                "settle:conversation",
            ),
            fixture.events,
        )
    }

    @Test
    fun selectedChildDeletionCapturesItsParentUnderTheLockBeforeStorageDisappears() = runTest {
        val fixture = Fixture(this)
        val viewer = FakeChatClient(open = "conversation")
        fixture.clients.attach(viewer)
        coEvery { fixture.conversations.getConversation("conversation") } coAnswers {
            assertTrue("lock-start" in fixture.events)
            assertTrue("delete" !in fixture.events)
            ChatEntity("conversation", "Child", parentConversationId = "parent")
        }
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }

        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()

        assertEquals(listOf("conversation" to "parent"), fixture.origin.deletedDestinations)
        assertEquals(fixture.origin.deletedDestinations, viewer.deletedDestinations)
        assertEquals(listOf("deleted elsewhere"), viewer.snackbars)
    }

    @Test
    fun deletionDoesNotStopOrReplaceAnotherVisibleConversation() = runTest {
        val fixture = Fixture(this, currentConversationId = "other")
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }

        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()

        assertEquals(listOf("stop-loop", "stop", "lock-start", "delete", "lock-end", "remove"), fixture.events)
        assertEquals(listOf("conversation"), fixture.stoppedTargets)
        assertTrue(fixture.events.none { it.startsWith("settle:") })
    }

    @Test
    fun selectionChangeBeforeFinalLockDoesNotStopTheNewVisibleConversation() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }
        fixture.onLockStart = { fixture.origin.open = "other" }

        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()

        assertEquals(listOf("conversation"), fixture.stoppedTargets)
        assertTrue(fixture.events.none { it.startsWith("settle:") })
        assertTrue("delete" in fixture.events)
    }

    @Test
    fun lifecycleSettlesTheOriginallySelectedDeletionAfterCleanup() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }
        fixture.onRemove = { fixture.origin.open = "other" }

        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()

        assertTrue("stop" in fixture.events)
        assertEquals("settle:conversation", fixture.events.last())
    }

    @Test
    fun frozenSubmissionRejectsDeletionBeforeAnySideEffect() = runTest {
        val fixture = Fixture(this, deleteLocked = true)

        assertFalse(fixture.controller.delete(fixture.origin, "conversation"))
        runCurrent()

        assertTrue(fixture.events.isEmpty())
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
    }

    @Test
    fun failedDurableDeletionDoesNotCleanupOrSettleSelection() = runTest {
        val failures = mutableListOf<Throwable>()
        val controllerScope = CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> failures += error },
        )
        val fixture = Fixture(this, controllerScope = controllerScope)
        coEvery { fixture.conversations.deleteConversation("conversation") } throws
            IllegalStateException("delete failed")

        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()

        assertTrue(failures.isEmpty())
        assertTrue("remove" !in fixture.events)
        assertTrue(fixture.events.none { it.startsWith("settle:") })
        assertTrue("abort:7" in fixture.events)
    }

    @Test
    fun otherClientsShowingTheDeletedConversationAreToldAndLeaveIt() = runTest {
        val fixture = Fixture(this)
        val viewer = FakeChatClient(open = "conversation").apply {
            onSettleDeleted = { conversationId -> fixture.events += "viewer-settle:$conversationId" }
        }
        val elsewhere = FakeChatClient(open = "other")
        fixture.clients.attach(viewer)
        fixture.clients.attach(elsewhere)
        coEvery { fixture.conversations.deleteConversation("conversation") } answers {
            fixture.events += "delete"
        }

        fixture.controller.delete(fixture.origin, "conversation")
        runCurrent()

        assertEquals(listOf("deleted elsewhere"), viewer.snackbars)
        assertTrue("viewer-settle:conversation" in fixture.events)
        assertTrue("settle:conversation" in fixture.events)
        assertTrue(fixture.origin.snackbars.isEmpty())
        assertTrue(elsewhere.snackbars.isEmpty())
    }

    @Test
    fun anotherClientSubmittingIntoTheConversationLocksDeletion() = runTest {
        val fixture = Fixture(this)
        fixture.clients.attach(FakeChatClient(open = "conversation").apply { frozen = true })

        assertFalse(fixture.controller.delete(fixture.origin, "conversation"))
        runCurrent()

        assertTrue(fixture.events.isEmpty())
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
    }

    @Test
    fun runningDeletionWaitsForStopSettlementForViewedAndBackgroundOwners() = runTest {
        for (selected in listOf("conversation", "other")) {
            val fixture = Fixture(this, currentConversationId = selected, controllerScope = backgroundScope)
            val stopSettled = CompletableDeferred<Unit>()
            val generation = backgroundScope.launch {
                fixture.executionCoordinator.withAutomationConversationLock("conversation") {
                    CompletableDeferred<Unit>().await()
                }
            }
            runCurrent()
            fixture.onStop = {
                generation.cancelAndJoin()
                stopSettled.await()
            }
            coEvery { fixture.conversations.deleteConversation("conversation") } answers {
                fixture.events += "delete"
            }
            val results = mutableListOf<Boolean>()

            assertTrue(fixture.controller.delete(fixture.origin, "conversation", onResult = results::add))
            runCurrent()

            assertEquals(listOf("conversation"), fixture.stoppedTargets)
            assertTrue("stop-loop" in fixture.events)
            assertTrue("lock-start" !in fixture.events)
            assertTrue(results.isEmpty())
            coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }

            stopSettled.complete(Unit)
            runCurrent()

            assertEquals(listOf(true), results)
            assertTrue(fixture.events.indexOf("delete") > fixture.events.indexOf("stop"))
            coVerify(exactly = 1) { fixture.conversations.deleteConversation("conversation") }
            if (selected == "other") {
                assertTrue(fixture.events.none { it.startsWith("overlay:") || it.startsWith("settle:") })
            }
        }
    }

    @Test
    fun duplicatePendingDeleteIsRejectedAndFailedStopCanBeRetried() = runTest {
        val fixture = Fixture(this)
        val releaseStop = CompletableDeferred<Unit>()
        fixture.onStop = {
            releaseStop.await()
            error("stop failed")
        }
        val results = mutableListOf<Boolean>()
        assertTrue(fixture.controller.delete(fixture.origin, "conversation", onResult = results::add))
        runCurrent()
        assertFalse(fixture.controller.delete(fixture.origin, "conversation"))
        assertEquals(listOf("conversation"), fixture.stoppedTargets)
        releaseStop.complete(Unit)
        runCurrent()
        assertEquals(listOf(false), results)
        assertTrue("abort:7" in fixture.events)
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }

        fixture.onStop = {}
        coEvery { fixture.conversations.deleteConversation("conversation") } returns Unit
        assertTrue(fixture.controller.delete(fixture.origin, "conversation", onResult = results::add))
        runCurrent()
        assertEquals(listOf(false, true), results)
        coVerify(exactly = 1) { fixture.conversations.deleteConversation("conversation") }
    }

    @Test
    fun submissionStartingDuringCoverOrStopPreventsDurableDeletion() = runTest {
        for (duringCover in listOf(true, false)) {
            val fixture = Fixture(this)
            if (duringCover) fixture.onOverlay = { fixture.origin.frozen = true }
            else fixture.onStop = { fixture.origin.frozen = true }
            val results = mutableListOf<Boolean>()

            assertTrue(fixture.controller.delete(fixture.origin, "conversation", onResult = results::add))
            runCurrent()

            assertEquals(listOf(false), results)
            assertTrue("abort:7" in fixture.events)
            assertTrue("remove" !in fixture.events)
            coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
            if (duringCover) assertTrue(fixture.stoppedTargets.isEmpty())
        }
    }

    @Test
    fun confirmationIsRevalidatedAfterStopBeforeDurableDeletion() = runTest {
        val fixture = Fixture(this)
        var changed = false
        coEvery { fixture.conversations.getMessageTopologySnapshot("conversation") } answers {
            if (changed) listOf(mockk { every { id } returns "new-message" }) else emptyList()
        }
        fixture.onStop = { changed = true }
        val results = mutableListOf<Boolean>()

        fixture.controller.delete(fixture.origin, "conversation", emptySet(), results::add)
        runCurrent()

        assertEquals(listOf(false), results)
        assertTrue("abort:7" in fixture.events)
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
    }

    @Test
    fun newLiveRunAfterStopRejectsDeletionWithoutCleanup() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.getLiveRun("conversation") } returns mockk()
        val results = mutableListOf<Boolean>()

        fixture.controller.delete(fixture.origin, "conversation", onResult = results::add)
        runCurrent()

        assertEquals(listOf(false), results)
        assertTrue("abort:7" in fixture.events)
        assertTrue("remove" !in fixture.events)
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
    }

    @Test
    fun idleSubagentDeletionNeverStopsAndNotifiesEveryViewer() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.getConversation("conversation") } returns
            com.newoether.agora.data.local.ChatEntity("conversation", "Child", parentConversationId = "parent")
        coEvery { fixture.conversations.deleteConversation("conversation") } returns Unit

        assertTrue(fixture.controller.deleteIdleSubagent("conversation", "parent"))

        assertTrue("stop" !in fixture.events && "stop-loop" !in fixture.events)
        assertTrue("remove" in fixture.events)
        assertTrue("settle:conversation" in fixture.events)
        assertEquals(listOf("deleted by parent"), fixture.origin.snackbars)
        coVerify(exactly = 1) { fixture.conversations.deleteConversation("conversation") }
    }

    @Test
    fun idleSubagentDeletionRejectsLiveRunAndChangedOwner() = runTest {
        val fixture = Fixture(this)
        coEvery { fixture.conversations.getConversation("conversation") } returns
            com.newoether.agora.data.local.ChatEntity("conversation", "Child", parentConversationId = "other")
        assertFalse(fixture.controller.deleteIdleSubagent("conversation", "parent"))
        coEvery { fixture.conversations.getLiveRun("conversation") } returns mockk()
        assertFalse(fixture.controller.deleteIdleSubagent("conversation", "other"))
        assertTrue("remove" !in fixture.events && "stop" !in fixture.events)
        coVerify(exactly = 0) { fixture.conversations.deleteConversation(any()) }
    }

    private class Fixture(
        testScope: kotlinx.coroutines.test.TestScope,
        currentConversationId: String? = "conversation",
        controllerScope: CoroutineScope = testScope,
        deleteLocked: Boolean = false,
    ) {
        val conversations = mockk<ConversationRepository>()
        val executionCoordinator = ConversationExecutionCoordinator()
        val events = mutableListOf<String>()
        val stoppedTargets = mutableListOf<String>()
        var onStop: suspend () -> Unit = {}
        var onRemove: () -> Unit = {}
        var onLockStart: () -> Unit = {}
        var onOverlay: suspend () -> Unit = {}
        val origin = FakeChatClient(open = currentConversationId).apply {
            frozen = deleteLocked
            onBeginTreeMutation = { conversationId, _ ->
                events += "overlay:$conversationId"
                onOverlay()
                7L
            }
            onFailTreeMutation = { requestId -> events += "abort:$requestId" }
            onSettleDeleted = { conversationId -> events += "settle:$conversationId" }
        }
        val clients = ChatClients().also { it.attach(origin) }
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        init {
            coEvery { conversations.getLiveRun(any()) } returns null
            coEvery { conversations.getConversation(any()) } returns null
        }
        val controller = ConversationLifecycleController(
            conversations = conversations,
            scope = controllerScope,
            clients = clients,
            stopLoop = { events += "stop-loop" },
            tryWithConversationLock = { id, block ->
                executionCoordinator.tryWithConversationLock(id) {
                    events += "lock-start"
                    onLockStart()
                    block()
                    events += "lock-end"
                }
            },
            removeRuntime = {
                events += "remove"
                onRemove()
            },
            stopGeneration = { id, _ ->
                stoppedTargets += id
                events += "stop"
                onStop()
            },
            deletedElsewhereText = { "deleted elsewhere" },
            deletedByParentText = { "deleted by parent" },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
    }
}