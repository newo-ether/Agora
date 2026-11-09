package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class ExecutionMessageSummaryQueryTest {
    @Test
    fun executionRowsReadOnlyModelAndErrorSummariesWithABoundedPreview() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.chatDao()
            dao.upsertConversation(ChatEntity(id = "exec", title = "run", taskId = "task"))
            dao.upsertConversation(ChatEntity(id = "chat", title = "other"))
            val run = RunEntity(
                id = "r", conversationId = "exec", parentRunId = null,
                status = RunStatus.ACTIVE, activeSlot = 1, startedAt = 1L, lastCheckpointAt = 1L,
            )
            // Larger than one CursorWindow (about 2 MB) once read as a full row.
            val huge = "x".repeat(3 * 1024 * 1024)
            val messages = listOf(
                message("u", "exec", Participant.USER, "prompt", timestamp = 1L),
                message("m", "exec", Participant.MODEL, huge, timestamp = 2L, parent = "u"),
            )
            dao.upsertRun(run)
            messages.forEach { dao.upsertMessage(it) }

            val rows = dao.observeExecutionMessagesForTask("task").first()

            assertEquals(listOf("m"), rows.map { it.id })
            val row = rows.single()
            assertEquals("exec", row.conversationId)
            assertEquals(Participant.MODEL, row.participant)
            assertEquals(MessageStatus.SUCCESS, row.status)
            assertEquals(2L, row.timestamp)
            assertEquals(huge.take(EXECUTION_PREVIEW_MAX_CHARS), row.preview)
        } finally {
            database.close()
        }
    }

    private fun message(
        id: String,
        conversationId: String,
        participant: Participant,
        text: String,
        timestamp: Long,
        parent: String? = null,
    ) = MessageEntity(
        id = id, conversationId = conversationId, parentId = parent, text = text,
        participant = participant, status = MessageStatus.SUCCESS, timestamp = timestamp,
        runId = "r", runSequence = timestamp - 1,
    )
}
