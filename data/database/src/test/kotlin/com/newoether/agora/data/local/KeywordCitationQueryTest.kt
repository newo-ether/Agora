package com.newoether.agora.data.local

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class KeywordCitationQueryTest {
    private lateinit var database: SQLiteDatabase

    @Before
    fun setUp() {
        database = SQLiteDatabase.create(null)
        database.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, taskId TEXT, parentConversationId TEXT)")
        database.execSQL("CREATE INDEX index_conversations_taskId ON conversations(taskId)")
        database.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, conversationId TEXT, participant TEXT, toolCallJson TEXT)")
        database.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
        database.execSQL("INSERT INTO conversations VALUES ('root', NULL, NULL), ('task', 'scheduled', NULL), ('child', NULL, 'root')")
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun productionQuerySeeksMessageIdsWithoutPerPageSort() {
        val plan = database.rawQuery(
            "EXPLAIN QUERY PLAN $CITATION_MESSAGE_PAGE_QUERY",
            arrayOf("m0100", "128"),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(3))
            }
        }
        // Android SQLite may spell the same indexed seek as SEARCH TABLE messages AS m.
        val messageSeek = Regex(
            """^SEARCH (?:TABLE messages AS )?m USING (?:COVERING )?INDEX \S+ \(id>\?\)$""",
        )
        val conversationLookup = Regex(
            """^SEARCH (?:TABLE conversations AS )?c USING (?:COVERING )?INDEX \S+ \(id=\?\)$""",
        )
        assertTrue(plan.toString(), plan.isNotEmpty() && messageSeek.matches(plan.first()))
        assertTrue(plan.toString(), plan.drop(1).any(conversationLookup::matches))
        assertFalse(plan.toString(), plan.any { it.startsWith("SCAN ") || it.contains("TEMP B-TREE") })

        val instructions = database.rawQuery(
            "EXPLAIN $CITATION_MESSAGE_PAGE_QUERY",
            arrayOf("m0100", "128"),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        assertTrue(instructions.toString(), "SeekGT" in instructions)
        assertFalse(instructions.toString(), instructions.any {
            it == "Sort" || it == "SorterOpen" || it == "SorterSort"
        })
    }

    @Test
    fun productionPagesPreserveEligibleRowsAndStrictCursor() {
        insert("a", "root", "MODEL", "[{\"type\":\"citation\",\"title\":\"A\"}]")
        insert("b", "task", "MODEL", "[{\"type\":\"citation\"}]")
        insert("child_match", "child", "MODEL", "[{\"type\":\"citation\"}]")
        insert("c", "root", "USER", "[{\"type\":\"citation\"}]")
        insert("d", "root", "MODEL", "[{\"type\":\"text\"}]")
        insert("e", "root", "MODEL", "[{\"type\":\"citation\",\"title\":\"\\u4e2d\\u6587\"}]")
        insert("f", "root", "MODEL", null)
        insert("result_x", "root", "MODEL", "[{\"type\":\"citation\"}]")
        insert("tool_x", "root", "MODEL", "[{\"type\":\"citation\"}]")
        insert("z", "root", "MODEL", "[{\"type\":\"citation\"}]")

        assertEquals(listOf("a", "e"), page("", 2))
        assertEquals(listOf("z"), page("e", 2))
        assertTrue(page("z", 2).isEmpty())
        assertTrue(page("", 0).isEmpty())
    }

    @Test
    fun productionQueryMatchesPriorEligibilityAcrossAllPages() {
        database.beginTransaction()
        try {
            repeat(2_000) { index ->
                insert(
                    id = "m%05d".format(index),
                    conversationId = if (index % 11 == 0) "task" else "root",
                    participant = if (index % 7 == 0) "USER" else "MODEL",
                    json = if (index % 3 == 0) "[{\"type\":\"citation\"}]" else "[]",
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        val previousSql = CITATION_MESSAGE_PAGE_QUERY
            .replace("CROSS JOIN conversations c", "INNER JOIN conversations c ON m.conversationId = c.id")
            .replace("WHERE m.conversationId = c.id\n      AND", "WHERE")
        var afterId = ""
        val seen = mutableListOf<String>()
        while (true) {
            val page = page(afterId, 17)
            val previous = ids(previousSql, afterId, 17)
            assertEquals(previous, page)
            if (page.isEmpty()) break
            assertTrue(page.all { it > afterId })
            seen += page
            afterId = page.last()
        }
        assertTrue(seen.isNotEmpty())
        assertEquals(seen.distinct(), seen)
    }

    private fun insert(id: String, conversationId: String, participant: String, json: String?) {
        database.execSQL("INSERT INTO messages VALUES (?, ?, ?, ?)", arrayOf(id, conversationId, participant, json))
    }

    private fun page(afterId: String, limit: Int): List<String> =
        ids(CITATION_MESSAGE_PAGE_QUERY, afterId, limit)

    private fun ids(sql: String, afterId: String, limit: Int): List<String> =
        database.rawQuery(sql, arrayOf(afterId, limit.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("id")))
            }
        }
}
