package com.newoether.agora.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DrawerGeneratingConversationIdsTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao
    private lateinit var repository: ConversationRepository
    private val foreground = MutableStateFlow<Set<String>>(emptySet())
    private val automation = MutableStateFlow<Set<String>>(emptySet())
    private val queries = Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(), ChatDatabase::class.java,
        ).allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() })
            .build()
        dao = database.chatDao()
        repository = ConversationRepository(dao, database, scheduleMaintenance = {})
        dao.upsertConversation(ChatEntity("parent", "Parent"))
        dao.upsertConversation(ChatEntity("other", "Other"))
        dao.upsertConversation(ChatEntity("child-a", "A", parentConversationId = "parent"))
        dao.upsertConversation(ChatEntity("child-b", "B", parentConversationId = "parent"))
        dao.upsertConversation(ChatEntity("other-child", "Other child", parentConversationId = "other"))
        queries.clear()
    }

    @After fun tearDown() = database.close()

    private suspend fun observe(block: suspend (Channel<Set<String>>) -> Unit) = coroutineScope {
        val states = Channel<Set<String>>(Channel.UNLIMITED)
        val observer = launch {
            repository.observeDrawerGeneratingConversationIds(foreground, automation).collect { states.send(it) }
        }
        try {
            states.expect(emptySet())
            block(states)
        } finally {
            observer.cancelAndJoin()
            states.close()
        }
    }

    private suspend fun Channel<Set<String>>.expect(expected: Set<String>) {
        assertEquals(expected, withTimeout(10_000) { receive() })
    }

    private fun applicationReads(): List<String> = synchronized(queries) {
        queries.filter { sql ->
            sql.trimStart().startsWith("SELECT") &&
                !sql.contains("room_table_modification_log") && sql.trim() != "SELECT changes()"
        }
    }

    @Test fun reservedWakeupSharesRunningStateWithListCapsulesAndParentLoading() = runBlocking {
        val coordinator = com.newoether.agora.automation.ConversationExecutionCoordinator()
        repository = ConversationRepository(dao, database, scheduleMaintenance = {},
            subagentGenerationIds = coordinator.admittedGenerationIds)
        observe { states ->
            val release = coordinator.reserveSubagentGeneration("parent", "child-a")
            states.expect(setOf("child-a", "parent"))
            assertEquals("running", repository.observeSubagents("parent").first().first { it.id == "child-a" }.status)
            assertEquals("idle", repository.observeSubagents("parent").first().first { it.id == "child-b" }.status)
            val retained = coordinator.reserveSubagentGeneration("parent", "child-a")
            release()
            assertEquals(1, coordinator.admittedChildCount("parent"))
            assertEquals("running", repository.observeSubagents("parent").first().first { it.id == "child-a" }.status)
            retained()
            states.expect(emptySet())
            assertEquals("idle", repository.observeSubagents("parent").first().first { it.id == "child-a" }.status)
        }
    }

    @Test fun idleDoesNotQueryUnopenedConversationsOrCreateRuntimeWork() = runBlocking {
        observe {
            val reads = applicationReads()
            assertTrue("Unexpected idle application reads: $reads", reads.isEmpty())
            assertTrue(repository.subagentJobs.value.isEmpty())
        }
    }

    @Test fun parentGenerationStillShowsAndClearsForBothExistingSources() = runBlocking {
        observe { states ->
            foreground.value = setOf("parent")
            states.expect(setOf("parent"))
            foreground.value = emptySet()
            states.expect(emptySet())
            automation.value = setOf("parent")
            states.expect(setOf("parent"))
            automation.value = emptySet()
            states.expect(emptySet())
        }
    }

    @Test fun childOnlyGenerationMarksParentWithoutAffectingAnotherFamily() = runBlocking {
        observe { states ->
            foreground.value = setOf("child-a")
            states.expect(setOf("child-a", "parent"))
            foreground.value = emptySet()
            states.expect(emptySet())
            automation.value = setOf("other-child")
            states.expect(setOf("other-child", "other"))
            automation.value = emptySet()
            states.expect(emptySet())
        }
    }

    @Test fun registeredDelegationCoversAdmissionAndGenerationHandoffs() = runBlocking {
        val delegation = Job()
        try {
            observe { states ->
                repository.subagentJobs.value = mapOf("child-a" to delegation)
                states.expect(setOf("child-a", "parent"))
                foreground.value = setOf("child-a", "other")
                states.expect(setOf("child-a", "parent", "other"))
                foreground.value = emptySet()
                states.expect(setOf("child-a", "parent"))
                delegation.complete()
                repository.subagentJobs.value = emptyMap()
                states.expect(emptySet())
            }
        } finally {
            delegation.cancel()
        }
    }

    @Test fun endingOneOfSeveralChildrenKeepsParentUntilTheLastChildEnds() = runBlocking {
        val first = Job()
        val second = Job()
        try {
            observe { states ->
                repository.subagentJobs.value = mapOf("child-a" to first, "child-b" to second)
                states.expect(setOf("child-a", "child-b", "parent"))
                first.complete()
                repository.subagentJobs.value = mapOf("child-b" to second)
                states.expect(setOf("child-b", "parent"))
                second.cancel()
                repository.subagentJobs.value = emptyMap()
                states.expect(emptySet())
            }
        } finally {
            first.cancel()
            second.cancel()
        }
    }

    @Test fun childCompletionPreservesParentOwnGeneration() = runBlocking {
        observe { states ->
            foreground.value = setOf("parent", "child-a")
            states.expect(setOf("parent", "child-a"))
            foreground.value = setOf("parent")
            states.expect(setOf("parent"))
            foreground.value = emptySet()
            states.expect(emptySet())
        }
    }

    @Test fun parentCompletionDoesNotHideRunningChildrenAcrossSources() = runBlocking {
        observe { states ->
            foreground.value = setOf("parent")
            states.expect(setOf("parent"))
            automation.value = setOf("child-a", "other-child")
            states.expect(setOf("parent", "child-a", "other-child", "other"))
            foreground.value = emptySet()
            automation.value = setOf("other-child")
            states.expect(setOf("other-child", "other"))
            automation.value = emptySet()
            states.expect(emptySet())
        }
    }

    @Test fun activeChildMembershipUpdatesReactivelyAndUsesOnlyNarrowIdReads() = runBlocking {
        observe { states ->
            foreground.value = setOf("new-child")
            states.expect(setOf("new-child"))
            dao.upsertConversation(ChatEntity("new-child", "New", parentConversationId = "parent"))
            states.expect(setOf("new-child", "parent"))
            dao.upsertConversation(ChatEntity("new-child", "New", parentConversationId = "other"))
            states.expect(setOf("new-child", "other"))
            foreground.value = emptySet()
            states.expect(emptySet())
            val reads = applicationReads()
            val metadataStateRead = "SELECT * FROM semantic_metadata_state WHERE id = 0"
            val conversationSummaryRead = "SELECT * FROM semantic_conversations WHERE conversationId = ?"
            // Each public write maintains its scalar summary in the same recorded SQL stream.
            assertEquals(2, reads.count { it.trim() == metadataStateRead })
            assertEquals(2, reads.count { it.trim() == conversationSummaryRead })
            val membershipReads = reads.filterNot {
                it.trim() == metadataStateRead || it.trim() == conversationSummaryRead
            }
            assertTrue(membershipReads.isNotEmpty())
            assertTrue("Unexpected observer reads: $membershipReads", membershipReads.all {
                it.contains("SELECT DISTINCT parentConversationId") && it.contains("id IN (")
            })
            assertFalse(reads.any { it.contains("messages") || it.contains("runs") || it.contains("draft") })
        }
    }
}
