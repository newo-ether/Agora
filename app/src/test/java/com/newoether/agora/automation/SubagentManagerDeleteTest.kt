package com.newoether.agora.automation

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.model.RunStatus
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubagentManagerDeleteTest {
    private fun SubagentManagerTest.Fixture.idleChild(
        id: String = "child", resultId: String? = null, deliveredId: String? = resultId,
        parent: String = "parent",
    ) {
        children[id] = ChatEntity(id, "Worker", parentConversationId = parent,
            subagentResultId = resultId, subagentDeliveredResultId = deliveredId)
    }

    private val SubagentManagerTest.Fixture.deleteContext get() = context.copy(subagentDeleteEnabled = true)

    @Test fun idleDeliveredChildIsDeletedThroughLifecycleOwner() = runTest {
        val f = SubagentManagerTest.Fixture(backgroundScope)
        f.idleChild(resultId = "result")
        f.manager.delete("child", f.deleteContext)
        assertEquals(listOf("child" to "parent"), f.deletedChildren)
        assertTrue("child" !in f.children)
    }

    @Test fun disabledSettingStaleIdentityAndForeignChildNeverReachDeletion() = runTest {
        val f = SubagentManagerTest.Fixture(backgroundScope)
        f.idleChild()
        f.idleChild(id = "foreign", parent = "other")
        assertTrue(runCatching { f.manager.delete("child", f.context) }.isFailure)
        assertTrue(runCatching { f.manager.delete("child", f.deleteContext.copy(
            toolEffectIdentity = f.identity.copy(pass = 1))) }.isFailure)
        assertTrue(runCatching { f.manager.delete("foreign", f.deleteContext) }.isFailure)
        assertTrue(f.deletedChildren.isEmpty())
    }

    @Test fun undeliveredResultIsRejectedSoTheParentNeverLosesIt() = runTest {
        val f = SubagentManagerTest.Fixture(backgroundScope)
        f.idleChild(resultId = "result", deliveredId = null)
        val error = runCatching { f.manager.delete("child", f.deleteContext) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("agent_wait"))
        assertTrue(f.deletedChildren.isEmpty())
        assertTrue("child" in f.children)
    }

    @Test fun runningChildIsRejectedWithoutStoppingIt() = runTest {
        val f = SubagentManagerTest.Fixture(backgroundScope)
        f.idleChild()
        coEvery { f.dao.getLiveRun("child") } returns
            RunEntity("live", "child", null, RunStatus.ACTIVE, 1, 1L, 1L)
        assertTrue(runCatching { f.manager.delete("child", f.deleteContext) }.isFailure)
        assertTrue(f.deletedChildren.isEmpty())
        runCurrent()
        assertTrue("child" in f.children)
    }

    @Test fun lifecycleRejectionSurfacesAsAnError() = runTest {
        val f = SubagentManagerTest.Fixture(backgroundScope)
        f.idleChild()
        f.deleteAccepted = false
        assertTrue(runCatching { f.manager.delete("child", f.deleteContext) }.isFailure)
        assertTrue("child" in f.children)
    }
}
