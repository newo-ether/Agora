package com.newoether.agora.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
class ConversationPinTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: ChatDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(ChatDatabase.DB_NAME)
        database = ChatDatabase.build(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(ChatDatabase.DB_NAME)
    }

    @Test
    fun pinWriteAdvancesBackupWatermarkWithoutTouchingRecencyOrUnread() = runBlocking {
        val dao = database.chatDao()
        withContext(Dispatchers.IO) {
            dao.upsertConversation(
                ChatEntity(
                    id = "c",
                    title = "Conversation",
                    lastUpdated = 100L,
                    dataChangedAt = 500L,
                    draftText = "draft",
                    hasUnreadGeneration = true,
                ),
            )
            assertEquals(1, dao.setConversationPinned("c", pinned = true, at = 200L))
            // Repeating the same state is a no-op and does not advance the watermark again.
            assertEquals(0, dao.setConversationPinned("c", pinned = true, at = 900L))
        }
        val pinned = withContext(Dispatchers.IO) { dao.getConversation("c") }!!
        assertTrue(pinned.isPinned)
        assertEquals(501L, pinned.dataChangedAt)
        assertEquals(100L, pinned.lastUpdated)
        assertEquals("draft", pinned.draftText)
        assertTrue(pinned.hasUnreadGeneration)
        assertTrue(withContext(Dispatchers.IO) { dao.getAllConversations().first() }.single().isPinned)

        withContext(Dispatchers.IO) { dao.setConversationPinned("c", pinned = false, at = 1_000L) }
        val unpinned = withContext(Dispatchers.IO) { dao.getConversation("c") }!!
        assertFalse(unpinned.isPinned)
        assertEquals(1_000L, unpinned.dataChangedAt)
        assertEquals(100L, unpinned.lastUpdated)
    }

    @Test
    fun taskExecutionsCannotBePinned() = runBlocking {
        val dao = database.chatDao()
        withContext(Dispatchers.IO) {
            dao.upsertTask(TaskEntity(id = "t", name = "T", prompt = "P", cronExpr = "", nextRunAt = 0L))
            dao.upsertConversation(ChatEntity(id = "e", title = "Execution", taskId = "t", origin = "task"))
            assertEquals(0, dao.setConversationPinned("e", pinned = true, at = 1L))
        }
        assertFalse(withContext(Dispatchers.IO) { dao.getConversation("e") }!!.isPinned)
    }

    @Test
    fun migration35To36KeepsRowsAndDefaultsToUnpinned() {
        val name = "conversation-pin-migration"
        context.deleteDatabase(name)
        val migration = ChatDatabase.ALL_MIGRATIONS.single { it.startVersion == 35 }
        assertEquals(36, migration.endVersion)
        fun open(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, title TEXT NOT NULL)")
                        db.execSQL("INSERT INTO conversations VALUES ('kept', 'Old')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        migration.migrate(db)
                    }
                }).build(),
        )
        try {
            open(35).use { it.writableDatabase }
            open(36).use { helper ->
                helper.readableDatabase.query("SELECT id, title, isPinned FROM conversations").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("kept", it.getString(0))
                    assertEquals("Old", it.getString(1))
                    assertEquals(0, it.getInt(2))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
