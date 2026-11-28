package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.RunStatus
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

    @Test fun bothPageQueriesUseTimestampIndexAndBoundInnerRead() {
        for (query in listOf(KEYWORD_MESSAGE_FIRST_PAGE, KEYWORD_MESSAGE_NEXT_PAGE)) {
            // EXPLAIN runs the exact production query with fixed synthetic arguments.
            val sql = query.replace(":literalQuery", "NULL").replace(":query", "'needle'").replace(":pageSize", "128")
                .replace(":beforeTimestamp", "20").replace(":beforeId", "'before'")
            val details = database.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
            }
            assertTrue(details.toString(), details.any { "index_messages_timestamp_id" in it })
            assertFalse(details.toString(), details.any { "SCAN messages" in it && "INDEX" !in it })
        }
    }

    private suspend fun owner(id: String, title: String, parent: String? = null, task: String? = null) {
        dao.upsertConversation(ChatEntity(id, title, parentConversationId = parent, taskId = task))
        dao.upsertRun(RunEntity(id = "$id-run", conversationId = id, parentRunId = null,
            status = RunStatus.COMPLETED, activeSlot = null, startedAt = 0, lastCheckpointAt = 0,
            endedAt = 0, endReason = com.newoether.agora.model.RunEndReason.MODEL_COMPLETED))
    }
}
