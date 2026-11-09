package com.newoether.agora.model

import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.active
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.effectIdentity
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.identity
import com.newoether.agora.model.ConversationRuntimeReducerTestFixture.stopCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRuntimeToolReducerTest {
    @Test
    fun `validated tool batch must commit before continuation is authorized`() {
        val active = active(ownerToken = 9, runId = "run", pass = 2)
        val providerIdentity = effectIdentity(active.identity, "provider-2-1")

        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ToolBatchRequested(providerIdentity),
        )
        val batchEffect = requested.effects.filterIsInstance<RunEffect.ExecuteToolBatch>().single()
        assertEquals("tool-batch-provider-2-1", batchEffect.identity.effectId)
        assertEquals(
            RunToolPhase.Executing(batchEffect.identity),
            (requested.newState as RunState.Active).toolPhase,
        )

        val completed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ToolBatchCompleted(batchEffect.identity),
        )
        val commitEffect = completed.effects.filterIsInstance<RunEffect.CommitToolRound>().single()
        assertEquals("tool-round-tool-batch-provider-2-1", commitEffect.identity.effectId)
        assertTrue((completed.newState as RunState.Active).toolPhase is RunToolPhase.Committing)

        val committed = ConversationRuntimeReducer.reduce(
            completed.newState,
            ConversationCommand.ToolRoundCommitted(commitEffect.identity, success = true),
        )
        assertEquals(RunState.Active(active.identity), committed.newState)
        assertEquals(
            listOf(RunEffect.ContinueProviderPass(commitEffect.identity)),
            committed.effects,
        )
    }

    @Test
    fun `duplicate and stale tool results cannot advance the active Run`() {
        val active = active(ownerToken = 10, runId = "run", pass = 3)
        val providerIdentity = effectIdentity(active.identity, "provider-3-0")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ToolBatchRequested(providerIdentity),
        )
        val batch = requested.effects.filterIsInstance<RunEffect.ExecuteToolBatch>().single()

        val duplicateRequest = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ToolBatchRequested(providerIdentity),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicateRequest.rejection)

        val passAdvance = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.BindRun(identity(ownerToken = 10, runId = "run", pass = 4)),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, passAdvance.rejection)

        val staleBatch = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ToolBatchCompleted(
                batch.identity.copy(effectId = "tool-batch-old-provider"),
            ),
        )
        assertEquals(CommandRejection.STALE_IDENTITY, staleBatch.rejection)
        assertSame(requested.newState, staleBatch.newState)
    }

    @Test
    fun `tool commit failure is recorded once and never authorizes continuation`() {
        val active = active(ownerToken = 11, runId = "run")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ToolBatchRequested(
                effectIdentity(active.identity, "provider-0-0"),
            ),
        )
        val batch = requested.effects.filterIsInstance<RunEffect.ExecuteToolBatch>().single()
        val completed = ConversationRuntimeReducer.reduce(
            requested.newState,
            ConversationCommand.ToolBatchCompleted(batch.identity),
        )
        val commit = completed.effects.filterIsInstance<RunEffect.CommitToolRound>().single()

        val failed = ConversationRuntimeReducer.reduce(
            completed.newState,
            ConversationCommand.ToolRoundCommitted(commit.identity, success = false),
        )
        assertEquals(listOf(RunEffect.ToolRoundCommitFailed(commit.identity)), failed.effects)
        assertTrue(
            ((failed.newState as RunState.Active).toolPhase as RunToolPhase.Committing)
                .failureReported,
        )

        val duplicate = ConversationRuntimeReducer.reduce(
            failed.newState,
            ConversationCommand.ToolRoundCommitted(commit.identity, success = false),
        )
        assertEquals(CommandRejection.DUPLICATE_RESULT, duplicate.rejection)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun `Stop invalidates a late tool result`() {
        val active = active(ownerToken = 12, runId = "run")
        val requested = ConversationRuntimeReducer.reduce(
            active,
            ConversationCommand.ToolBatchRequested(
                effectIdentity(active.identity, "provider-0-0"),
            ),
        )
        val batch = requested.effects.filterIsInstance<RunEffect.ExecuteToolBatch>().single()
        val stopping = ConversationRuntimeReducer.reduce(
            requested.newState,
            stopCommand(requested.newState as RunState.Active, effectId = "stop"),
        )

        val late = ConversationRuntimeReducer.reduce(
            stopping.newState,
            ConversationCommand.ToolBatchCompleted(batch.identity),
        )
        assertEquals(CommandRejection.ILLEGAL_STATE, late.rejection)
        assertSame(stopping.newState, late.newState)
    }

}
