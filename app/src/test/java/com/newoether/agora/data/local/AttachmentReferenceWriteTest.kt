package com.newoether.agora.data.local

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
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

/** Uses the production builder: a Room-only fixture omits the attachment triggers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AttachmentReferenceWriteTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: ChatDatabase
    private val dao get() = database.chatDao()

    @Before fun open() = runBlocking {
        context.deleteDatabase(ChatDatabase.DB_NAME)
        database = ChatDatabase.build(context)
        dao.upsertConversation(ChatEntity("owner", "owner", lastUpdated = 1))
        dao.insertRun(RunEntity("run", "owner", parentRunId = null, status = RunStatus.ACTIVE,
            activeSlot = 1, startedAt = 1, lastCheckpointAt = 1))
    }

    @After fun close() {
        database.close()
        context.deleteDatabase(ChatDatabase.DB_NAME)
    }

    @Test fun realCheckpointAdapterUpdatesExistingOwnerAndNeverResurrectsDeletedMessage() = runBlocking {
        dao.insertMessage(message())
        val references = AttachmentReferenceIndex(database)
        references.refresh()
        val before = ownerToken(0, "message")
        val revision = references.prepareCheck("/missing")
        repeat(3) { pass ->
            assertEquals(1, dao.updateMessageCheckpoint(checkpoint(pass)))
            assertEquals("answer-$pass", dao.getMessage("message")?.text)
            assertNotEquals(before, ownerToken(0, "message"))
            database.withTransaction { assertFalse(references.isCurrent(revision)) }
            references.refresh()
        }
        dao.deleteMessagesByIds(listOf("message"))
        assertEquals(0, dao.updateMessageCheckpoint(checkpoint(4)))
        assertNull(dao.getMessage("message"))
        assertNull(ownerToken(0, "message"))
    }

    @Test fun checkpointRollbackRestoresPayloadOwnerTokenAndReferenceRevision() = runBlocking {
        dao.insertMessage(message())
        val references = AttachmentReferenceIndex(database)
        references.refresh()
        val before = ownerToken(0, "message")
        val revision = references.prepareCheck("/missing")
        val failure = runCatching {
            database.withTransaction {
                assertEquals(1, dao.updateMessageCheckpoint(checkpoint(1)))
                error("abort after checkpoint")
            }
        }.exceptionOrNull()
        assertEquals("abort after checkpoint", failure?.message)
        assertEquals("", dao.getMessage("message")?.text)
        assertEquals(before, ownerToken(0, "message"))
        database.withTransaction { assertTrue(references.isCurrent(revision)) }
    }


    @Test fun allOuterConflictPoliciesKeepOneOwnerAndInvalidateItsPreviousToken() = runBlocking {
        dao.insertMessage(message())
        dao.upsertNewChatPersist(NewChatPersistEntity(draftAttachments = "[]"))
        for ((table, kind, id, column) in listOf(
            listOf("messages", "0", "message", "toolCallJson"),
            listOf("conversations", "1", "owner", "draftAttachments"),
            listOf("new_chat_persist", "2", "0", "draftAttachments"),
        )) {
            for ((pass, policy) in listOf("ABORT", "FAIL", "IGNORE", "REPLACE", "ROLLBACK").withIndex()) {
                val before = ownerToken(kind.toInt(), id)
                assertNotNull(before)
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE OR $policy $table SET $column = ? WHERE id = ?",
                    arrayOf<Any>("[$pass]", id),
                )
                assertNotEquals(before, ownerToken(kind.toInt(), id))
                database.openHelper.readableDatabase.query(
                    "SELECT COUNT(*) FROM attachment_reference_owners WHERE kind = ? AND ownerId = ?",
                    arrayOf<Any>(kind.toInt(), id),
                ).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            }
        }
    }

    @Test fun installedVersion45TriggersAreReplacedWithoutLosingExistingReferences() = runBlocking {
        val path = java.io.File(context.filesDir, "img_preserved").canonicalPath
        dao.insertMessage(message().copy(images = listOf(path)))
        val index = AttachmentReferenceIndex(database)
        index.refresh()
        val token = ownerToken(0, "message")
        val revision = index.prepareCheck(path)
        val raw = database.openHelper.writableDatabase
        raw.beginTransaction()
        try {
            // Exact released-v45 trigger strategy, retained here to exercise an existing installation.
            listOf("messages", "conversations", "new_chat_persist").forEachIndexed { kind, table ->
                val columns = if (kind == 0) listOf("images", "attachmentMeta", "toolCallJson")
                    else listOf("draftAttachments")
                val mark = """
                    INSERT OR REPLACE INTO attachment_reference_owners(kind, ownerId, token, dirty, invalid)
                    VALUES($kind, CAST(NEW.id AS TEXT), lower(hex(randomblob(16))), 1, 0);
                    UPDATE attachment_reference_state SET revision = revision + 1 WHERE id = 0;
                """
                raw.execSQL("DROP TRIGGER attachment_refs_${table}_insert")
                raw.execSQL("DROP TRIGGER attachment_refs_${table}_update")
                raw.execSQL("CREATE TRIGGER attachment_refs_${table}_insert AFTER INSERT ON $table BEGIN $mark END")
                val changed = columns.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }
                raw.execSQL("CREATE TRIGGER attachment_refs_${table}_update AFTER UPDATE OF ${columns.joinToString(",")} ON $table WHEN $changed BEGIN $mark END")
            }
            raw.version = 45
            raw.setTransactionSuccessful()
        } finally { raw.endTransaction() }
        database.close()
        database = ChatDatabase.build(context)
        assertEquals(46, database.openHelper.readableDatabase.version)
        assertEquals(token, ownerToken(0, "message"))
        val reopened = AttachmentReferenceIndex(database)
        database.withTransaction {
            assertTrue(reopened.isCurrent(revision))
            assertTrue(reopened.contains(path))
        }
        assertEquals(1, dao.updateMessageCheckpoint(checkpoint(1).copy(images = listOf(path))))
        reopened.refresh()
        assertTrue(reopened.contains(path))
        database.close()
        database = ChatDatabase.build(context)
        assertEquals(1, dao.updateMessageCheckpoint(checkpoint(2).copy(images = listOf(path))))
        assertEquals("answer-2", dao.getMessage("message")?.text)
    }

    private fun message() = MessageEntity("message", "owner", text = "",
        participant = Participant.MODEL, timestamp = 1, runId = "run", runSequence = 0)

    private fun checkpoint(pass: Int) = MessageStreamCheckpoint(
        id = "message", text = "answer-$pass", images = emptyList(), thoughts = null,
        thoughtTitle = null, tokenCount = pass, inputTokenCount = null, cachedInputTokenCount = null,
        cacheWriteInputTokenCount = null, uncachedInputTokenCount = null, outputTokenCount = null,
        reasoningTokenCount = null, status = MessageStatus.THINKING, thoughtTimeMs = null,
        toolCallJson = """[{"type":"answer","content":"answer-$pass"}]""",
    )

    private fun ownerToken(kind: Int, id: String): String? =
        database.openHelper.readableDatabase.query(
            "SELECT token FROM attachment_reference_owners WHERE kind = ? AND ownerId = ?",
            arrayOf<Any>(kind, id),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
}
