package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.repository.searchConversationMessages
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.toMessageSegment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KeywordSearchRoomTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.chatDao()
        owner("root", "Conversation title")
        owner("child", "Conversation title", parent = "root")
        owner("task", "Conversation title", task = "scheduled")
    }

    @After fun tearDown() = database.close()

    @Test fun filtersHiddenOwnersRolesAndSyntheticRowsBeforeReturningPayload() = runBlocking {
        add("visible", "needle", 1)
        add("child-message", "needle", 100, owner = "child")
        add("task-message", "needle", 100, owner = "task")
        add("tool_hidden", "needle", 100)
        add("result_hidden", "needle", 100)
        add("compact_hidden", "needle", 100)
        add("system", "needle", 100, participant = Participant.ERROR)
        add("empty", "", 100)
        val page = dao.getKeywordSearchFirstPage("needle", 128)
        assertEquals(listOf("visible"), page.filter { it.directMatch }.map { it.id })
        assertTrue(page.filter { it.id != "visible" }.all { !it.directMatch && it.toolCallJson == null })
        assertEquals(listOf("visible"), search("needle", 1))
        assertEquals(listOf("visible"), search("Conversation title", 1))
    }

    @Test fun preservesChineseCaseWildcardsCitationTitlesAndNewestTieOrder() = runBlocking {
        add("body", "中文关键词 NeEdLe 100% a_b C:\\folder", 5)
        add("plain", "unrelated", 6)
        add("citation-a", "answer", 8, citation = "中文来源 NEEDLE")
        add("citation-z", "answer", 8, citation = "中文来源 needle")
        add("citation-older", "answer", 1, citation = "中文来源 needle")
        add("compact_summary", "answer", 99, citation = "中文来源 needle")
        assertEquals(listOf("body"), search("关键词"))
        assertEquals(listOf("body"), search("关键词 needle"))
        assertEquals(listOf("body"), search("100%"))
        assertEquals(listOf("body"), search("a_b"))
        assertEquals(listOf("body"), search("C:\\folder"))
        assertEquals(listOf("citation-z", "citation-a", "body", "citation-older"), search("needle"))
        assertEquals(listOf("citation-z", "citation-a"), search("中文来源", 2))
        assertTrue(search("private.example").isEmpty())
        assertTrue(search("turn0search7").isEmpty())
        assertTrue(search("missing").isEmpty())
        assertTrue(search(" ").isEmpty())
    }

    @Test fun missesAndTiesAdvanceAcrossPhysicalPagesAndHydrationRechecksVisibility() = runBlocking {
        repeat(260) { add("row-%04d".format(it), "other", 10) }
        add("a-target", "needle", 10)
        assertEquals(listOf("a-target"), search("needle", 1))
        dao.upsertConversation(dao.getConversation("root")!!.copy(parentConversationId = "parent"))
        assertTrue(dao.getSearchableMessagesByIds(listOf("a-target")).isEmpty())
        assertTrue(search("needle", 1).isEmpty())
    }

    private suspend fun search(query: String, limit: Int = 30) =
        searchConversationMessages(dao, query, limit).map { it.id }

    private suspend fun owner(id: String, title: String, parent: String? = null, task: String? = null) {
        dao.upsertConversation(ChatEntity(id, title, parentConversationId = parent, taskId = task))
        dao.upsertRun(RunEntity(id = "$id-run", conversationId = id, parentRunId = null,
            status = RunStatus.COMPLETED, activeSlot = null, startedAt = 0, lastCheckpointAt = 0,
            endedAt = 0, endReason = com.newoether.agora.model.RunEndReason.MODEL_COMPLETED))
    }

    private suspend fun add(id: String, text: String, time: Long, owner: String = "root",
        participant: Participant = Participant.MODEL, citation: String? = null) {
        val json = citation?.let { title -> MessagePersistenceGuard.encodeSegmentsBounded(
            listOf(requireNotNull(CitationPolicy.create(provider = "test", kind = "url", title = title,
                url = "https://private.example/source", providerSourceId = "turn0search7")).toMessageSegment()),
        ) }
        dao.insertMessage(MessageEntity(id = id, conversationId = owner, text = text,
            participant = participant, timestamp = time, runId = "$owner-run", toolCallJson = json))
    }
}
