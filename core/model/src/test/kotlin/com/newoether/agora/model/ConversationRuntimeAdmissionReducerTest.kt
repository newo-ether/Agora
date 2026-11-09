package com.newoether.agora.model

import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.CONVERSATION_ID
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.active
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.effectIdentity
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.identity
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.releaseEffect
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.sendCommand
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.stopCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRuntimeAdmissionReducerTest {
    @Test
    fun `slot acquire and Run bind are reducer-owned transitions`() {
        val acquired = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            ConversationCommand.AcquireSlot(identity(ownerToken = 1)),
        )

        assertTrue(acquired.accepted)
        assertEquals(RunState.Active(identity(ownerToken = 1)), acquired.newState)
        assertEquals(
            listOf(RunEffect.SlotActivated(identity(ownerToken = 1))),
            acquired.effects,
        )

        val bound = ConversationRuntimeReducer.reduce(
            acquired.newState,
            ConversationCommand.BindRun(identity(ownerToken = 1, runId = "run", pass = 2)),
        )

        assertTrue(bound.accepted)
        assertEquals(
            RunState.Active(identity(ownerToken = 1, runId = "run", pass = 2)),
            bound.newState,
        )
    }

    @Test
    fun `foreground Send prepares one identified persistence effect before binding the Run`() {
        val requested = sendCommand(ownerToken = 3, runId = "run", effectId = "send-1")

        val preparing = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            requested,
        )

        assertEquals(
            RunState.Preparing(
                ownerIdentity = identity(ownerToken = 3),
                inputEffectIdentity = requested.identity,
            ),
            preparing.newState,
        )
        assertEquals(
            listOf(RunEffect.PersistAcceptedInput(requested.identity)),
            preparing.effects,
        )

        val persisted = ConversationRuntimeReducer.reduce(
            preparing.newState,
            ConversationCommand.InputPersisted(requested.identity),
        )

        assertEquals(
            RunState.Active(identity(ownerToken = 3, runId = "run")),
            persisted.newState,
        )
        assertTrue(persisted.effects.isEmpty())
    }

    @Test
    fun `durable Run arriving after pre-bind Stop receives one identified finalization effect`() {
        val send = sendCommand(ownerToken = 3, runId = "run", effectId = "send-1")
        val preparing = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            send,
        ).newState
        val stopping = ConversationRuntimeReducer.reduce(
            preparing,
            ConversationCommand.StopRequested(
                identity = identity(ownerToken = 3),
                coroutineAlreadySettled = false,
                requiresPersistence = false,
                effectId = null,
            ),
        ).newState

        val persisted = ConversationRuntimeReducer.reduce(
            stopping,
            ConversationCommand.InputPersisted(send.identity),
        )
        val expectedIdentity = effectIdentity(
            identity(ownerToken = 3, runId = "run"),
            "stop-3",
        )

        assertEquals(
            RunState.Stopping(
                identity = identity(ownerToken = 3, runId = "run"),
                finalizationEffectId = "stop-3",
                coroutineSettled = false,
                persistenceSettled = false,
            ),
            persisted.newState,
        )
        assertEquals(listOf(RunEffect.FinalizeStop(expectedIdentity)), persisted.effects)

        val duplicate = ConversationRuntimeReducer.reduce(
            persisted.newState,
            ConversationCommand.InputPersisted(send.identity),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicate.rejection)

        val staleBind = ConversationRuntimeReducer.reduce(
            stopping,
            ConversationCommand.BindRun(identity(ownerToken = 4, runId = "other")),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, staleBind.rejection)
    }

    @Test
    fun `replacement Run bind after Stop uses the same late finalization transition`() {
        val unbound = RunState.Active(identity(ownerToken = 9))
        val stopping = ConversationRuntimeReducer.reduce(
            unbound,
            ConversationCommand.StopRequested(
                identity = unbound.identity,
                coroutineAlreadySettled = false,
                requiresPersistence = false,
                effectId = null,
            ),
        ).newState
        val durableIdentity = identity(ownerToken = 9, runId = "replacement", pass = 0)

        val bound = ConversationRuntimeReducer.reduce(
            stopping,
            ConversationCommand.BindRun(durableIdentity),
        )

        assertEquals(
            RunState.Stopping(
                identity = durableIdentity,
                finalizationEffectId = "stop-9",
                coroutineSettled = false,
                persistenceSettled = false,
            ),
            bound.newState,
        )
        assertEquals(
            listOf(
                RunEffect.FinalizeStop(effectIdentity(durableIdentity, "stop-9")),
            ),
            bound.effects,
        )
    }

    @Test
    fun `active Send accepts guidance only for the currently bound Run`() {
        val active = active(ownerToken = 4, runId = "active-run", pass = 2)
        val request = sendCommand(ownerToken = 99, runId = "unused", effectId = "guidance")

        val transition = ConversationRuntimeReducer.reduce(active, request)

        assertSame(active, transition.newState)
        assertEquals(
            listOf(
                RunEffect.AcceptGuidance(
                    RunEffectIdentity(
                        conversationId = CONVERSATION_ID,
                        ownerToken = 4,
                        runId = "active-run",
                        pass = 2,
                        effectId = "guidance",
                    ),
                ),
            ),
            transition.effects,
        )
    }

    @Test
    fun `Send accepts memory guidance during preparation while stopping waits and direct-only is busy`() {
        val request = sendCommand(ownerToken = 1, runId = "run", effectId = "send")
        val preparing = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            request,
        ).newState
        val stoppingActive = active(ownerToken = 8, runId = "stopping")
        val stopping = ConversationRuntimeReducer.reduce(
            stoppingActive,
            stopCommand(stoppingActive, effectId = "stop"),
        ).newState

        val preparingGuidance = ConversationRuntimeReducer.reduce(
            preparing,
            request.copy(
                identity = request.identity.copy(
                    ownerToken = 99,
                    runId = "later",
                    effectId = "guidance",
                ),
            ),
        )
        assertSame(preparing, preparingGuidance.newState)
        assertEquals(
            RunEffect.AcceptGuidance(
                request.identity.copy(runId = "run", effectId = "guidance"),
            ),
            preparingGuidance.effects.single(),
        )

        val stoppingWait = ConversationRuntimeReducer.reduce(
            stopping,
            request.copy(directOnly = false),
        )
        assertSame(stopping, stoppingWait.newState)
        assertTrue(stoppingWait.effects.single() is RunEffect.AwaitRunRelease)

        for (state in listOf(preparing, stopping)) {
            val busy = ConversationRuntimeReducer.reduce(state, request.copy(directOnly = true))
            assertSame(state, busy.newState)
            assertTrue(busy.effects.single() is RunEffect.RejectSendBusy)
        }
    }

    @Test
    fun `pending guidance is drained before a newer idle Send can claim the slot`() {
        val state = RunState.Idle(CONVERSATION_ID)
        val request = sendCommand(
            ownerToken = 1,
            runId = "new-run",
            effectId = "send",
            hasPendingGuidance = true,
        )

        val drain = ConversationRuntimeReducer.reduce(state, request)
        assertSame(state, drain.newState)
        assertEquals(
            listOf(RunEffect.DrainGuidanceFirst(request.identity)),
            drain.effects,
        )

        val directOnly = ConversationRuntimeReducer.reduce(
            state,
            request.copy(directOnly = true),
        )
        assertSame(state, directOnly.newState)
        assertEquals(
            listOf(RunEffect.RejectSendBusy(request.identity)),
            directOnly.effects,
        )
    }

    @Test
    fun `stale persistence and abandonment results cannot mutate another Send`() {
        val first = sendCommand(ownerToken = 1, runId = "first", effectId = "first-effect")
        val second = sendCommand(ownerToken = 2, runId = "second", effectId = "second-effect")
        val firstPreparing = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            first,
        ).newState

        val staleInput = ConversationRuntimeReducer.reduce(
            firstPreparing,
            ConversationCommand.InputPersisted(second.identity),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, staleInput.rejection)
        assertSame(firstPreparing, staleInput.newState)

        val staleAbandonment = ConversationRuntimeReducer.reduce(
            firstPreparing,
            ConversationCommand.SendLaunchAbandoned(second.identity),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, staleAbandonment.rejection)
        assertSame(firstPreparing, staleAbandonment.newState)

        val abandoned = ConversationRuntimeReducer.reduce(
            firstPreparing,
            ConversationCommand.SendLaunchAbandoned(first.identity),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), abandoned.newState)
        assertEquals(
            SlotReleaseReason.SEND_LAUNCH_ABANDONED,
            releaseEffect(abandoned).reason,
        )
    }

    @Test
    fun `input persistence failure is identified idempotent and releases on coroutine settlement`() {
        val request = sendCommand(ownerToken = 6, runId = "run", effectId = "input")
        val preparing = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            request,
        ).newState
        val failureCommand = ConversationCommand.InputPersistenceFailed(request.identity)

        val failed = ConversationRuntimeReducer.reduce(preparing, failureCommand)
        assertTrue((failed.newState as RunState.Preparing).inputFailureReported)
        assertTrue(failed.effects.isEmpty())

        val duplicate = ConversationRuntimeReducer.reduce(failed.newState, failureCommand)
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicate.rejection)
        assertSame(failed.newState, duplicate.newState)

        val settled = ConversationRuntimeReducer.reduce(
            failed.newState,
            ConversationCommand.CoroutineSettled(identity(ownerToken = 6)),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), settled.newState)
        assertEquals(SlotReleaseReason.NORMAL_COMPLETION, releaseEffect(settled).reason)
    }

    @Test
    fun `Stop before input persistence adopts the late durable Run and waits for both barriers`() {
        val request = sendCommand(ownerToken = 5, runId = "run", effectId = "input")
        val preparing = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            request,
        ).newState as RunState.Preparing
        val stop = ConversationCommand.StopRequested(
            identity = preparing.ownerIdentity,
            coroutineAlreadySettled = false,
            requiresPersistence = false,
            effectId = null,
        )

        val stopping = ConversationRuntimeReducer.reduce(preparing, stop)
        assertEquals(
            RunState.Stopping(
                identity = preparing.ownerIdentity,
                finalizationEffectId = null,
                coroutineSettled = false,
                persistenceSettled = true,
            ),
            stopping.newState,
        )
        assertTrue(stopping.effects.none { it is RunEffect.FinalizeStop })

        val lateInput = ConversationRuntimeReducer.reduce(
            stopping.newState,
            ConversationCommand.InputPersisted(request.identity),
        )
        val stopIdentity = effectIdentity(request.identity.runIdentity(), "stop-5")
        assertTrue(lateInput.accepted)
        assertEquals(listOf(RunEffect.FinalizeStop(stopIdentity)), lateInput.effects)

        val coroutineSettled = ConversationRuntimeReducer.reduce(
            lateInput.newState,
            ConversationCommand.CoroutineSettled(request.identity.runIdentity()),
        )
        assertTrue(coroutineSettled.newState is RunState.Stopping)
        assertTrue(coroutineSettled.effects.isEmpty())

        val settled = ConversationRuntimeReducer.reduce(
            coroutineSettled.newState,
            ConversationCommand.PersistenceSettled(stopIdentity, success = true),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), settled.newState)
        assertEquals(SlotReleaseReason.STOP_BARRIERS_SETTLED, releaseEffect(settled).reason)
    }

}
