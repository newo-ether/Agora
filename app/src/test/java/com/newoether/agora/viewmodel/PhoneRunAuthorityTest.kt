package com.newoether.agora.viewmodel

import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.phone.PhoneControlLease
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneRunAuthorityTest {
    @Test fun onlyExactExecutingBatchCanAuthorizePhoneEffects() = runBlocking<Unit> {
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val token = state.acquireForSend()!!
        state.bindRun(token, "run", pass = 2)
        val provider = RunEffectIdentity("conversation", token, "run", 2, "provider-2-0")
        assertFalse(state.acceptsToolEffect(provider))
        assertTrue(state.controlsRun("run"))
        val batch = state.commands.requestToolBatch(provider)!!
        assertTrue(state.acceptsToolEffect(batch.identity))
        for (stale in listOf(
            batch.identity.copy(conversationId = "other"), batch.identity.copy(ownerToken = token + 1),
            batch.identity.copy(runId = "old"), batch.identity.copy(pass = 1), batch.identity.copy(effectId = "old"),
        )) assertFalse(state.acceptsToolEffect(stale))
        state.commands.completeToolBatch(batch.identity)
        assertFalse(state.acceptsToolEffect(batch.identity))
        state.stop()
        assertFalse(state.controlsRun("run"))
        state.dispose()
    }

    @Test fun stopSettlementAndDisposalReleaseTheCanonicalRunsLease() = runBlocking {
        for (trigger in listOf("stop", "settle", "dispose")) {
            val lease = PhoneControlLease { 0 }
            val state = ConversationGenerationState("conversation", onRunControlEnded = { conversation, run ->
                lease.forget(PhoneControlLease.Owner(conversation, run))
            }, reclaimQueuedAttachments = {})
            val token = state.acquireForSend()!!
            state.bindRun(token, "run")
            lease.claim(RunEffectIdentity("conversation", token, "run", 0, "batch"), "Title")
            when (trigger) {
                "stop" -> state.stop()
                "settle" -> assertTrue(finalizeBoundRun(state, token, "run"))
                "dispose" -> state.dispose()
            }
            assertNull("Lease remained after $trigger", lease.held.value)
            assertFalse(state.controlsRun("run"))
            state.dispose()
        }
    }
}
