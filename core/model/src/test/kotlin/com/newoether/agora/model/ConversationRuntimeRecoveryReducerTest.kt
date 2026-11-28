package com.newoether.agora.model

import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.CONVERSATION_ID
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.active
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.sendCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRuntimeRecoveryReducerTest {
    @Test
    fun `Room live Run snapshot produces one deterministic recovery effect`() {
        listOf(RunStatus.ACTIVE, RunStatus.STOPPING).forEach { priorStatus ->
            val snapshot = RunRecoverySnapshot(
                conversationId = CONVERSATION_ID,
                runId = "orphaned-run",
                pass = 4,
                status = priorStatus,
            )
            val command = ConversationCommand.Recover(snapshot)

            val first = ConversationRuntimeReducer.reduce(
                RunState.Idle(CONVERSATION_ID),
                command,
            )
            val replay = ConversationRuntimeReducer.reduce(
                RunState.Idle(CONVERSATION_ID),
                command,
            )

            assertEquals(first, replay)
            val effect = first.effects.filterIsInstance<RunEffect.RecoverDurableRun>().single()
            assertEquals("orphaned-run", effect.identity.runId)
            assertEquals(4, effect.identity.pass)
            assertEquals("recover-orphaned-run-4", effect.identity.effectId)
            assertEquals(priorStatus, effect.priorStatus)
            assertTrue(first.newState is RunState.Recovering)
            assertFalse(first.effects.any { it is RunEffect.StartProviderPass })
        }
    }

    @Test
    fun `recovery rejects stale and duplicate results and becomes Idle only on durable success`() {
        val snapshot = RunRecoverySnapshot(
            conversationId = CONVERSATION_ID,
            runId = "orphaned-run",
            pass = 2,
            status = RunStatus.ACTIVE,
        )
        val requested = ConversationRuntimeReducer.reduce(
            RunState.Idle(CONVERSATION_ID),
            ConversationCommand.Recover(snapshot),
        )
        val effect = requested.effects.filterIsInstance<RunEffect.RecoverDurableRun>().single()
        val duplicateRequest = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.Recover(snapshot),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicateRequest.rejection)

        val stale = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.RecoveryCompleted(
                effect.identity.copy(pass = 1),
                success = true,
            ),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, stale.rejection)

        val failed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.RecoveryCompleted(effect.identity, success = false),
        )
        assertEquals(listOf(RunEffect.RunRecoveryFailed(effect.identity)), failed.effects)
        assertTrue((failed.newState as RunState.Recovering).failureReported)
        val duplicateFailure = ConversationRuntimeReducer.reduce(
            failed.newState,
            ConversationCommand.RecoveryCompleted(effect.identity, success = false),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicateFailure.rejection)

        val recovered = ConversationRuntimeReducer.reduce(
            failed.newState,
            ConversationCommand.RecoveryCompleted(effect.identity, success = true),
        )
        assertEquals(RunState.Idle(CONVERSATION_ID), recovered.newState)
        assertTrue(recovered.effects.isEmpty())
    }


}
