package com.newoether.agora.templates

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.*
import com.newoether.agora.model.*
import com.newoether.agora.viewmodel.projectProviderMessages
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalTemplateAdmissionTest {
    private val template = InteractiveTemplate(name = "Any menu", answer = InteractiveAnswer(
        "Menu", "Options", "Choose an option.", html = "<button>Start</button>",
    ))
    private fun message() = MessageEntity(id = "card", conversationId = "c", text = "", timestamp = 1,
        participant = Participant.MODEL, runId = "local", runSequence = 0,
        toolCallJson = Json.encodeToString(listOf(template.segment())))
    private fun run() = RunEntity(id = "local", conversationId = "c", parentRunId = null,
        status = RunStatus.COMPLETED, activeSlot = null, startedAt = 1, lastCheckpointAt = 1,
        endedAt = 1, endReason = RunEndReason.LOCAL_TEMPLATE_OPENED)

    @Test fun localCardCommitsWithoutActiveSlotAndIsExcludedFromProviderContext() = runTest {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.chatDao()
            dao.upsertConversation(ChatEntity(id = "c", title = "Chat", modelId = "unchanged"))
            dao.createRunWithMessages(run(), listOf(message()), mapOf(null to "card"), null, 1, true)
            assertNull(dao.getLiveRun("c"))
            assertEquals(run(), dao.getRun("local"))
            assertEquals("unchanged", dao.getConversation("c")!!.modelId)
            val stored = dao.getMessage("card")!!
            assertEquals(template, Json.decodeFromString<List<MessageSegment>>(stored.toolCallJson!!).single().interactiveTemplate())
            assertTrue(projectProviderMessages(listOf(stored), false).isEmpty())
        } finally { database.close() }
    }

    @Test fun localCardCannotBypassLiveGenerationOrAcceptUnvalidatedContent() = runTest {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.chatDao()
            dao.upsertConversation(ChatEntity(id = "c", title = "Chat"))
            dao.insertRun(RunEntity("active", "c", null, RunStatus.ACTIVE, 1, 0, 0))
            try { dao.createRunWithMessages(run(), listOf(message()), mapOf(null to "card"), null, 1, true); fail() }
            catch (_: IllegalStateException) { }
            assertNull(dao.getMessage("card"))
            assertNull(dao.getRun("local"))
            assertThrows(IllegalArgumentException::class.java) {
                validateLocalOrActiveRunAdmission(run(), listOf(message().copy(text = "provider text")))
            }
        } finally { database.close() }
    }
}
