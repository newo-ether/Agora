package com.newoether.agora.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.api.util.projectGenerationStatusesForApi
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.ui.chat.message.assistantErrorContent
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
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GenerationRetryErrorPersistenceTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: ChatDatabase

    @Before fun setUp() = runBlocking {
        app.deleteDatabase(ChatDatabase.DB_NAME)
        database = ChatDatabase.build(app)
        database.chatDao().upsertConversation(ChatEntity("conversation", "Conversation"))
        database.chatDao().insertRun(RunEntity("run", "conversation", null,
            RunStatus.ACTIVE, 1, 1L, 1L))
        database.chatDao().insertMessage(MessageEntity(id = "model", conversationId = "conversation",
            text = "Partial answer", participant = Participant.MODEL, timestamp = 1L,
            status = MessageStatus.SENDING, runId = "run", runSequence = 0))
    }

    @After fun close() { database.close() }

    @Test fun stoppedRetryErrorSurvivesRoomReopenAndProviderProjection() = runBlocking {
        val state = ConversationGenerationState("conversation", reclaimQueuedAttachments = {})
        val token = requireNotNull(state.acquireForSend())
        state.bindRun(token, "run")
        val error = MessageSegment("error", "503 [server_error]: Last actual failure")
        state.streamUpdate(token, ChatMessage(id = "model", text = "Partial answer",
            participant = Participant.MODEL, status = MessageStatus.SENDING, runId = "run",
            runSequence = 0, retryText = "Retrying", segments = listOf(
                MessageSegment("answer", "Partial answer"), error)))
        val stopped = state.stop()
        val repository = ConversationRepository(database.chatDao(), database, scheduleMaintenance = {})
        assertTrue(repository.requestRunStop("run"))
        assertTrue(repository.finishStoppedGeneration(listOf(requireNotNull(stopped.stoppedMessage)), "run"))
        assertTrue(state.finishStopFinalization(stopped.completion(true)).accepted)
        database.close()
        database = ChatDatabase.build(app)

        val run = requireNotNull(database.chatDao().getRun("run"))
        assertEquals(RunStatus.STOPPED, run.status)
        assertEquals(RunEndReason.USER_STOPPED, run.endReason)
        assertNull(run.activeSlot)
        val reopened = requireNotNull(database.chatDao().getMessage("model")).toUiChatMessage { it }
        assertEquals(MessageStatus.STOPPED, reopened.status)
        assertEquals("Partial answer", reopened.text)
        assertEquals(error, reopened.segments!!.single { it.type == "error" })
        assertEquals(error.content, assistantErrorContent(reopened, reopened.segments!!, "Fallback")?.errorText)
        val projected = projectGenerationStatusesForApi(listOf(reopened)) { "display:$it" }.single()
        assertEquals(Participant.MODEL, projected.participant)
        assertEquals("Partial answer\n\n<generation_interrupted reason=\"stopped\">\n" +
            "display:${error.content}\n</generation_interrupted>", projected.text)
        assertEquals(MessageStatus.STOPPED, database.chatDao().getMessage("model")!!.status)
    }
}
