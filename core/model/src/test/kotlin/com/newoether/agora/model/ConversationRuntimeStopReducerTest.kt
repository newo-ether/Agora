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

class ConversationRuntimeStopReducerTest {
    @Test
    fun `Stop emits identified cancellation and persistence effects`() {
        val active = active(ownerToken = 4, runId = "run", pass = 3)

        val transition = ConversationRuntimeReducer.reduce(
            active,
            stopCommand(active, effectId = "effect-1"),
        )

        assertTrue(transition.accepted)
        assertEquals(
            RunState.Stopping(
                identity = active.identity,
                finalizationEffectId = "effect-1",
                coroutineSettled = false,
                persistenceSettled = false,
            ),
            transition.newState,
        )
        assertEquals(
            listOf(
                RunEffect.CancelProviderPass(active.identity),
                RunEffect.FinalizeStop(effectIdentity(active.identity, "effect-1")),
            ),
            transition.effects,
        )
    }

    @Test
    fun `Stop releases only after both barriers in either order`() {
        val active = active(ownerToken = 1, runId = "run", pass = 2)
        val stopping = ConversationRuntimeReducer.reduce(
            active,
            stopCommand(active, effectId = "effect"),
        ).newState
        val persistence = ConversationCommand.PersistenceSettled(
            effectIdentity(active.identity, "effect"),
            success = true,
        )
        val coroutine = ConversationCommand.CoroutineSettled(active.identity)

        val persistenceFirst = ConversationRuntimeReducer.reduce(stopping, persistence)
        assertTrue(persistenceFirst.newState is RunState.Stopping)
        assertTrue((persistenceFirst.newState as RunState.Stopping).persistenceSettled)
        val persistenceThenCoroutine = ConversationRuntimeReducer.reduce(
            persistenceFirst.newState,
            coroutine,
        )

        val coroutineFirst = ConversationRuntimeReducer.reduce(stopping, coroutine)
        assertTrue(coroutineFirst.newState is RunState.Stopping)
        assertTrue((coroutineFirst.newState as RunState.Stopping).coroutineSettled)
        val coroutineThenPersistence = ConversationRuntimeReducer.reduce(
            coroutineFirst.newState,
            persistence,
        )

        val expectedEffect = RunEffect.ReleaseSlot(
            active.identity,
            SlotReleaseReason.STOP_BARRIERS_SETTLED,
        )
        for (completed in listOf(persistenceThenCoroutine, coroutineThenPersistence)) {
            assertEquals(RunState.Idle(CONVERSATION_ID), completed.newState)
            assertEquals(listOf(expectedEffect), completed.effects)
        }
    }

    @Test
    fun `stale and duplicate persistence results are rejected without effects`() {
        val active = active(ownerToken = 1, runId = "run", pass = 1)
        val stopping = ConversationRuntimeReducer.reduce(
            active,
            stopCommand(active, effectId = "expected"),
        ).newState
        val stale = ConversationRuntimeReducer.reduce(
            stopping,
            ConversationCommand.PersistenceSettled(
                effectIdentity(active.identity, "old-effect"),
                success = true,
            ),
        )

        assertEquals(CommandRejection.STALE_IDENTITY, stale.rejection)
        assertSame(stopping, stale.newState)
        assertTrue(stale.effects.isEmpty())

        val accepted = ConversationRuntimeReducer.reduce(
            stopping,
            ConversationCommand.PersistenceSettled(
                effectIdentity(active.identity, "expected"),
                success = true,
            ),
        )
        val duplicate = ConversationRuntimeReducer.reduce(
            accepted.newState,
            ConversationCommand.PersistenceSettled(
                effectIdentity(active.identity, "expected"),
                success = true,
            ),
        )

        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicate.rejection)
        assertSame(accepted.newState, duplicate.newState)
        assertTrue(duplicate.effects.isEmpty())

        val contradictoryFailure = ConversationRuntimeReducer.reduce(
            accepted.newState,
            ConversationCommand.PersistenceSettled(
                effectIdentity(active.identity, "expected"),
                success = false,
            ),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, contradictoryFailure.rejection)
        assertSame(accepted.newState, contradictoryFailure.newState)
        assertTrue(contradictoryFailure.effects.isEmpty())
    }

    @Test
    fun `failed persistence result remains pending and a later success can settle`() {
        val active = active(ownerToken = 1, runId = "run")
        val stopping = ConversationRuntimeReducer.reduce(
            active,
            stopCommand(active, effectId = "effect", coroutineSettled = true),
        ).newState
        val failedCommand = ConversationCommand.PersistenceSettled(
            effectIdentity(active.identity, "effect"),
            success = false,
        )

        val failed = ConversationRuntimeReducer.reduce(stopping, failedCommand)
        assertTrue(failed.accepted)
        assertEquals(
            listOf(RunEffect.StopPersistenceFailed(effectIdentity(active.identity, "effect"))),
            failed.effects,
        )
        assertTrue((failed.newState as RunState.Stopping).persistenceFailureReported)

        val duplicateFailure = ConversationRuntimeReducer.reduce(failed.newState, failedCommand)
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicateFailure.rejection)

        val recovered = ConversationRuntimeReducer.reduce(
            failed.newState,
            failedCommand.copy(success = true),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), recovered.newState)
        assertEquals(SlotReleaseReason.STOP_BARRIERS_SETTLED, releaseEffect(recovered).reason)
    }

    @Test
    fun `old effect result cannot release a later stopping Run`() {
        val first = active(ownerToken = 1, runId = "first")
        val firstStopping = ConversationRuntimeReducer.reduce(
            first,
            stopCommand(first, effectId = "first-effect", coroutineSettled = true),
        ).newState
        val firstCompletion = ConversationCommand.PersistenceSettled(
            effectIdentity(first.identity, "first-effect"),
            success = true,
        )
        val idle = ConversationRuntimeReducer.reduce(firstStopping, firstCompletion).newState

        val secondAcquired = ConversationRuntimeReducer.reduce(
            idle,
            ConversationCommand.AcquireSlot(identity(ownerToken = 2)),
        ).newState
        val second = ConversationRuntimeReducer.reduce(
            secondAcquired,
            ConversationCommand.BindRun(identity(ownerToken = 2, runId = "second")),
        ).newState as RunState.Active
        val secondStopping = ConversationRuntimeReducer.reduce(
            second,
            stopCommand(second, effectId = "second-effect", coroutineSettled = true),
        ).newState

        val stale = ConversationRuntimeReducer.reduce(secondStopping, firstCompletion)

        assertEquals(CommandRejection.STALE_IDENTITY, stale.rejection)
        assertSame(secondStopping, stale.newState)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun `bound coroutine completion remains occupied until a terminal result`() {
        val active = active(ownerToken = 1, runId = "run")
        val completion = ConversationCommand.CoroutineSettled(active.identity)

        val first = ConversationRuntimeReducer.reduce(active, completion)
        val duplicate = ConversationRuntimeReducer.reduce(first.newState, completion)

        assertEquals(active.copy(coroutineSettled = true), first.newState)
        assertTrue(first.effects.isEmpty())
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicate.rejection)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun `illegal state command matrix is rejected without state or effects`() {
        val active = active(ownerToken = 1, runId = "run")
        val stopping = ConversationRuntimeReducer.reduce(
            active,
            stopCommand(active, effectId = "effect"),
        ).newState
        val idle = RunState.Idle(CONVERSATION_ID)
        val cases = listOf(
            Triple(
                active,
                ConversationCommand.AcquireSlot(identity(ownerToken = 2)),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                stopping,
                ConversationCommand.AcquireSlot(identity(ownerToken = 2)),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                idle,
                ConversationCommand.BindRun(identity(ownerToken = 1, runId = "run")),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                stopping,
                ConversationCommand.BindRun(identity(ownerToken = 1, runId = "run", pass = 1)),
                CommandRejection.STALE_IDENTITY,
            ),
            Triple(
                RunState.Preparing(
                    ownerIdentity = identity(ownerToken = 1),
                    inputEffectIdentity = effectIdentity(identity(1, "run"), "send"),
                ),
                ConversationCommand.BindRun(identity(ownerToken = 1, runId = "run")),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                idle,
                stopCommand(active, effectId = "effect"),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                stopping,
                stopCommand(active, effectId = "effect"),
                CommandRejection.DUPLICATE_RESULT,
            ),
            Triple(
                idle,
                ConversationCommand.CoroutineSettled(active.identity),
                CommandRejection.ILLEGAL_STATE,
            ),
            Triple(
                active,
                ConversationCommand.PersistenceSettled(
                    effectIdentity(active.identity, "effect"),
                    success = true,
                ),
                CommandRejection.ILLEGAL_STATE,
            ),
        )

        cases.forEach { (state, command, expectedRejection) ->
            val transition = ConversationRuntimeReducer.reduce(state, command)
            assertEquals(expectedRejection, transition.rejection)
            assertSame(state, transition.newState)
            assertTrue(transition.effects.isEmpty())
        }
    }

    @Test
    fun `Bind Run rejects stale owner Run and pass identities`() {
        val active = active(ownerToken = 3, runId = "run", pass = 4)
        val staleCommands = listOf(
            ConversationCommand.BindRun(identity(ownerToken = 2, runId = "run", pass = 4)),
            ConversationCommand.BindRun(identity(ownerToken = 3, runId = "other", pass = 4)),
            ConversationCommand.BindRun(identity(ownerToken = 3, runId = "run", pass = 3)),
            ConversationCommand.BindRun(identity(ownerToken = 3, runId = "run", pass = 6)),
        )

        staleCommands.forEach { command ->
            val transition = ConversationRuntimeReducer.reduce(active, command)
            assertEquals(CommandRejection.STALE_IDENTITY, transition.rejection)
            assertSame(active, transition.newState)
            assertTrue(transition.effects.isEmpty())
        }

        val duplicate = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.BindRun(active.identity),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicate.rejection)
        assertSame(active, duplicate.newState)
    }

    @Test
    fun `unbound Stop releases immediately without a persistence effect`() {
        val active = RunState.Active(identity(ownerToken = 8))

        val transition = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.StopRequested(
                identity = active.identity,
                coroutineAlreadySettled = true,
                requiresPersistence = false,
                effectId = null,
            ),
        )

        assertEquals(RunState.Idle(CONVERSATION_ID), transition.newState)
        assertEquals(
            listOf(
                RunEffect.CancelProviderPass(active.identity),
                RunEffect.ReleaseSlot(active.identity, SlotReleaseReason.EMPTY_STOP),
            ),
            transition.effects,
        )
    }

}
