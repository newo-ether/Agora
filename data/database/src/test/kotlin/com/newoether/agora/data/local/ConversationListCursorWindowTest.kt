package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationListCursorWindowTest {
    @Test fun globalListNeverMaterializesBranchPayloadAcrossCursorWindowBoundaries() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.chatDao()
            val selections = buildString {
                append('{')
                repeat(32_000) { index ->
                    if (index > 0) append(',')
                    append('"').append("parent-$index-".padEnd(48, 'p')).append("\":\"")
                    append("child-$index-".padEnd(48, 'c')).append('"')
                }
                append('}')
            }
            assertTrue(selections.length > 3 * 1024 * 1024)
            repeat(240) { index ->
                dao.upsertConversation(ChatEntity(
                    id = "root-$index", title = "Conversation $index", lastUpdated = (240 - index).toLong(),
                    selectedBranchesJson = if (index == 193) selections else "{\"null\":\"message-$index\"}",
                    modelId = "provider:model", isPinned = index == 193,
                    hasUnreadGeneration = index == 193,
                ))
            }
            dao.upsertConversation(ChatEntity("child", "Hidden child", parentConversationId = "root-193"))

            val rows = dao.getAllConversations().first()

            assertEquals((0 until 240).map { "root-$it" }, rows.map { it.id })
            assertTrue("The global list must not read any owner's branch payload",
                rows.all { it.selectedBranchesJson == null })
            assertEquals("Conversation 193", rows[193].title)
            assertEquals("provider:model", rows[193].modelId)
            assertTrue(rows[193].isPinned)
            assertTrue(rows[193].hasUnreadGeneration)
            assertEquals("root-193", dao.observeDrawerConversations(1).first().single().id)
            database.openHelper.readableDatabase.query(
                "SELECT length(selectedBranchesJson) FROM conversations WHERE id = 'root-193'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Stored branches remain complete", selections.length, cursor.getInt(0))
            }
            val ordinary = dao.getConversation("root-0")
            assertEquals("{\"null\":\"message-0\"}", ordinary?.selectedBranchesJson)
            assertNull(rows.first().parentConversationId)
        } finally {
            database.close()
        }
    }
}
