package com.newoether.agora.model

import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.CONVERSATION_ID
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.active
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.effectIdentity
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.identity
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.releaseEffect
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.stopCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRuntimeProviderFinalizationReducerTest {
    @Test
    fun `Provider pass must be authorized and its exact result accepted once`() {
        val active = active(ownerToken = 13, runId = "run", pass = 2)
        val identity = effectIdentity(active.identity, "provider-2-0")

        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ProviderPassRequested(identity),
        )
        assertEquals(listOf(RunEffect.StartProviderPass(identity)), requested.effects)
        assertEquals(
            RunProviderPhase.Running(identity),
            (requested.newState as RunState.Active).providerPhase,
        )
        val duplicateRequest = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ProviderPassRequested(identity),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicateRequest.rejection)
        val wrongPassRequest = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ProviderPassRequested(identity.copy(pass = 1)),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, wrongPassRequest.rejection)

        val completed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ProviderPassCompleted(
                identity,
                ProviderPassResult.COMPLETED_TOOL_CALLS,
            ),
        )
        assertEquals(RunState.Active(active.identity), completed.newState)
        assertEquals(
            listOf(
                RunEffect.ProviderPassAccepted(
                    identity,
                    ProviderPassResult.COMPLETED_TOOL_CALLS,
                ),
            ),
            completed.effects,
        )

        val duplicate = ConversationRuntimeReducer.reduce(
            completed.newState,
            ConversationCommand.ProviderPassCompleted(
                identity,
                ProviderPassResult.COMPLETED_TOOL_CALLS,
            ),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, duplicate.rejection)
    }

    @Test
    fun `stale Provider outcome and Provider result after Stop cannot mutate the Run`() {
        val active = active(ownerToken = 14, runId = "run", pass = 4)
        val currentIdentity = effectIdentity(active.identity, "provider-4-1")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ProviderPassRequested(currentIdentity),
        )
        val stale = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ProviderPassCompleted(
                currentIdentity.copy(effectId = "provider-4-0"),
                ProviderPassResult.COMPLETED_TEXT,
            ),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, stale.rejection)
        assertSame(requested.newState, stale.newState)

        val stopping = ConversationRuntimeReducer.reduce(
            requested.newState,
            stopCommand(requested.newState as RunState.Active, effectId = "stop"),
        )
        val late = ConversationRuntimeReducer.reduce(
            stopping.newState,
            ConversationCommand.ProviderPassCompleted(
                currentIdentity,
                ProviderPassResult.COMPLETED_TEXT,
            ),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, late.rejection)
        assertSame(stopping.newState, late.newState)
    }

    @Test
    fun `normal finalization releases only after coroutine and persistence settle in either order`() {
        val active = active(ownerToken = 15, runId = "run", pass = 3)
        val identity = effectIdentity(active.identity, "finalize-run-3")
        val request = ConversationCommand.FinalizationRequested(
            identity,
            RunStatus.COMPLETED,
            RunEndReason.MODEL_COMPLETED,
            markConversationUnread = true,
        )
        val finalizing = ConversationRuntimeReducer.reduce(active, request)
        assertEquals(
            listOf(
                RunEffect.FinalizeRun(
                    identity,
                    RunStatus.COMPLETED,
                    RunEndReason.MODEL_COMPLETED,
                    markConversationUnread = true,
                ),
            ),
            finalizing.effects,
        )

        val persistenceFirst = ConversationRuntimeReducer.reduce(
            finalizing.newState,
            ConversationCommand.FinalizationCompleted(identity, success = true),
        )
        assertTrue((persistenceFirst.newState as RunState.Finalizing).persistenceSettled)
        val persistenceThenCoroutine = ConversationRuntimeReducer.reduce(
            persistenceFirst.newState,
            ConversationCommand.CoroutineSettled(active.identity),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), persistenceThenCoroutine.newState)
        assertEquals(
            SlotReleaseReason.NORMAL_FINALIZATION_SETTLED,
            releaseEffect(persistenceThenCoroutine).reason,
        )

        val finalizingAgain = ConversationRuntimeReducer.reduce(active, request)
        val coroutineFirst = ConversationRuntimeReducer.reduce(
            finalizingAgain.newState,
            ConversationCommand.CoroutineSettled(active.identity),
        )
        assertTrue((coroutineFirst.newState as RunState.Finalizing).coroutineSettled)
        val coroutineThenPersistence = ConversationRuntimeReducer.reduce(
            coroutineFirst.newState,
            ConversationCommand.FinalizationCompleted(identity, success = true),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), coroutineThenPersistence.newState)
        assertEquals(
            SlotReleaseReason.NORMAL_FINALIZATION_SETTLED,
            releaseEffect(coroutineThenPersistence).reason,
        )
    }

    @Test
    fun `failed normal finalization stays occupied and permits Stop recovery`() {
        val active = active(ownerToken = 16, runId = "run")
        val identity = effectIdentity(active.identity, "finalize-run-0")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.FinalizationRequested(
                identity,
                RunStatus.FAILED,
                RunEndReason.PROVIDER_ERROR,
                markConversationUnread = true,
            ),
        )
        val failed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.FinalizationCompleted(identity, success = false),
        )
        assertEquals(listOf(RunEffect.RunFinalizationFailed(identity)), failed.effects)
        assertTrue((failed.newState as RunState.Finalizing).persistenceFailureReported)

        val settled = ConversationRuntimeReducer.reduce(
            failed.newState,
            ConversationCommand.CoroutineSettled(active.identity),
        )
        assertTrue((settled.newState as RunState.Finalizing).coroutineSettled)
        assertTrue(settled.effects.isEmpty())

        val stop = ConversationRuntimeReducer.reduce(
            settled.newState,
            ConversationCommand.StopRequested(
                identity = active.identity,
                coroutineAlreadySettled = true,
                requiresPersistence = true,
                effectId = "stop-recovery",
            ),
        )
        assertTrue(stop.accepted)
        assertTrue(stop.newState is RunState.Stopping)
        assertTrue(stop.effects.any { it is RunEffect.FinalizeStop })
    }

    @Test
    fun `coroutine completion before finalization cannot release a bound durable Run`() {
        val active = active(ownerToken = 18, runId = "run", pass = 1)
        val settled = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.CoroutineSettled(active.identity),
        )
        assertEquals(active.copy(coroutineSettled = true), settled.newState)
        assertTrue(settled.effects.isEmpty())

        val finalizationIdentity = effectIdentity(active.identity, "finalize-run-1")
        val requested = ConversationRuntimeReducer.reduce(
            settled.newState,
            ConversationCommand.FinalizationRequested(
                finalizationIdentity,
                RunStatus.COMPLETED,
                RunEndReason.MODEL_COMPLETED,
                markConversationUnread = true,
            ),
        )
        val staleResult = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.FinalizationCompleted(
                finalizationIdentity.copy(effectId = "old-finalization"),
                success = true,
            ),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, staleResult.rejection)

        val completed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.FinalizationCompleted(finalizationIdentity, success = true),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), completed.newState)
        assertEquals(
            SlotReleaseReason.NORMAL_FINALIZATION_SETTLED,
            releaseEffect(completed).reason,
        )
        val duplicate = ConversationRuntimeReducer.reduce(
            completed.newState,
            ConversationCommand.FinalizationCompleted(finalizationIdentity, success = true),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, duplicate.rejection)
    }

    @Test
    fun `Stop and normal finalization use first accepted command as the terminal owner`() {
        val active = active(ownerToken = 17, runId = "run")
        val finalizationIdentity = effectIdentity(active.identity, "finalize-run-0")
        val finalizing = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.FinalizationRequested(
                finalizationIdentity,
                RunStatus.COMPLETED,
                RunEndReason.MODEL_COMPLETED,
                markConversationUnread = true,
            ),
        )
        val lateStop = ConversationRuntimeReducer.reduce(
            finalizing.newState,
            stopCommand(active, effectId = "stop"),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, lateStop.rejection)

        val stopping = ConversationRuntimeReducer.reduce(
            active,
            stopCommand(active, effectId = "stop"),
        )
        val lateFinalization = ConversationRuntimeReducer.reduce(
            stopping.newState,
            ConversationCommand.FinalizationRequested(
                finalizationIdentity,
                RunStatus.COMPLETED,
                RunEndReason.MODEL_COMPLETED,
                markConversationUnread = true,
            ),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, lateFinalization.rejection)
    }

}
