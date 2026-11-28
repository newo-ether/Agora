package com.newoether.agora.webui

import com.newoether.agora.data.local.DrawerConversationRow
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual connection events use the same narrow activity projection as the native drawer. */
@OptIn(ExperimentalCoroutinesApi::class)
class WebUiDrawerSyncTest {
    private val fixture = WebUiSyncTest()
    private val foreground = MutableStateFlow<Set<String>>(emptySet())
    private val parents = MutableStateFlow(mapOf("a1" to "a", "a2" to "a", "b1" to "b"))

    init {
        every { fixture.registry.activeConversationIds } returns foreground
        every { fixture.conversations.subagentGenerationIds } returns fixture.executionCoordinator.admittedGenerationIds
        every { fixture.todoDao.observeSubagentParentIds(any()) } answers {
            val active = firstArg<Set<String>>()
            parents.map { membership -> active.mapNotNull(membership::get).distinct() }
        }
    }

    @Test fun idleDrawerLoadsOnlyItsBoundedListWithoutObservingChildOwners() = fixture.sync { _, received ->
        assertActivity(received(), emptySet())
        verify(exactly = 0) { fixture.todoDao.observeSubagentParentIds(any()) }
        verifyNoOwnerLoads()
    }

    @Test fun foregroundChildrenKeepTheirParentActiveUntilTheLastChildEnds() = fixture.sync { _, received ->
        assertActivity(received(), emptySet())
        foreground.value = setOf("a1", "a2")
        assertActivity(received(), setOf("a"))
        foreground.value = setOf("a2")
        // The parent's serialized row does not change when only one child ends.
        assertTrue(received().none { it.type == "conversations" })
        foreground.value = emptySet()
        assertActivity(received(), emptySet())
        foreground.value = setOf("b1")
        assertActivity(received(), setOf("b"))
        foreground.value = emptySet()
        assertActivity(received(), emptySet())
        verifyNoOwnerLoads()
    }

    @Test fun workerHandoffsAndParentOwnGenerationDoNotClearTheIndicatorEarly() = fixture.sync { _, received ->
        received()
        val first = Job()
        val second = Job()
        try {
            fixture.conversations.subagentJobs.value = mapOf("a1" to first, "a2" to second)
            assertActivity(received(), setOf("a"))
            foreground.value = setOf("a1")
            assertTrue(received().none { it.type == "conversations" })
            foreground.value = emptySet()
            assertTrue(received().none { it.type == "conversations" })
            first.complete()
            fixture.conversations.subagentJobs.value = mapOf("a2" to second)
            assertTrue(received().none { it.type == "conversations" })
            foreground.value = setOf("a")
            received()
            second.cancel()
            fixture.conversations.subagentJobs.value = emptyMap()
            assertTrue(received().none { it.type == "conversations" })
            foreground.value = emptySet()
            assertActivity(received(), emptySet())
            verifyNoOwnerLoads()
        } finally {
            first.cancel()
            second.cancel()
        }
    }

    @Test fun reservedChildWakeupRemainsActiveUntilItsLastAdmissionReferenceEnds() = fixture.sync { _, received ->
        received()
        val release = fixture.executionCoordinator.reserveSubagentGeneration("a", "a1")
        val retained = fixture.executionCoordinator.reserveSubagentGeneration("a", "a1")
        try {
            assertActivity(received(), setOf("a"))
            release()
            assertTrue(received().none { it.type == "conversations" })
            retained()
            assertActivity(received(), emptySet())
            verifyNoOwnerLoads()
        } finally {
            release()
            retained()
        }
    }

    @Test fun automationActivitySharesTheDrawerAndClearsAfterCancellation() = fixture.sync { _, received ->
        received()
        val automation = backgroundScope.launch {
            fixture.executionCoordinator.withAutomationConversationLock("b1") {
                CompletableDeferred<Unit>().await()
            }
        }
        runCurrent()
        assertActivity(received(), setOf("b"))
        foreground.value = setOf("a")
        assertActivity(received(), setOf("a", "b"))
        automation.cancelAndJoin()
        assertActivity(received(), setOf("a"))
        foreground.value = emptySet()
        assertActivity(received(), emptySet())
        verifyNoOwnerLoads()
    }

    @Test fun activeMembershipChangesUpdateOnlyTheCurrentDurableParent() = fixture.sync { _, received ->
        received()
        foreground.value = setOf("a1")
        assertActivity(received(), setOf("a"))
        parents.value = parents.value + ("a1" to "b")
        assertActivity(received(), setOf("b"))
        parents.value = parents.value - "a1"
        assertActivity(received(), emptySet())
        foreground.value = emptySet()
        assertTrue(received().none { it.type == "conversations" })
        verify(exactly = 1) { fixture.todoDao.observeSubagentParentIds(setOf("a1")) }
        verifyNoOwnerLoads()
    }

    @Test fun drawerPagesAreBoundedAndDuplicateLoadRequestsDoNotAdvanceTwice() {
        val rows = MutableStateFlow((0 until 200).map {
            DrawerConversationRow("c$it", "Conversation $it", false, it == 0)
        })
        every { fixture.conversations.observeDrawerConversations(any()) } answers {
            val limit = firstArg<Int>()
            rows.map { it.take(limit) }
        }
        fixture.sync { send, received ->
            val first = received().single { it.type == "conversations" }
            assertEquals(80, first["items"]!!.jsonArray.size)
            assertEquals("true", first.string("hasMore"))
            assertEquals("true", first["items"]!!.jsonArray.first().jsonObject.string("isPinned"))
            send("""{"type":"list_more","tokens":80}""")
            assertEquals(160, received().single { it.type == "conversations" }["items"]!!.jsonArray.size)
            send("""{"type":"list_more","tokens":80}""")
            assertTrue(received().none { it.type == "conversations" })
            send("""{"type":"list_more","tokens":160}""")
            val last = received().single { it.type == "conversations" }
            assertEquals(200, last["items"]!!.jsonArray.size)
            assertFalse(last["hasMore"]?.jsonPrimitive?.content?.toBoolean() == true)
            verifyNoOwnerLoads()
        }
    }

    private fun assertActivity(events: List<JsonObject>, expected: Set<String>) {
        val rows = events.single { it.type == "conversations" }["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("a", "b"), rows.map { it.string("id") })
        assertEquals(expected, rows.filter { it["generating"]?.jsonPrimitive?.content == "true" }
            .map { it.string("id") }.toSet())
    }

    private fun verifyNoOwnerLoads() {
        verify(exactly = 0) { fixture.conversations.getAllConversations() }
        verify(exactly = 0) { fixture.conversations.observeConversation(any()) }
        verify(exactly = 0) { fixture.conversations.observeMessageTopology(any()) }
        verify(exactly = 0) { fixture.todoDao.observeSubagents(any()) }
        verify(exactly = 0) { fixture.registry.getOrCreate(any()) }
        coVerify(exactly = 0) { fixture.conversations.recoverConversationRuntime(any(), any()) }
    }

    private val JsonObject.type: String get() = string("type")
    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content
}
