package com.newoether.agora.templates

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.*
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.*
import com.newoether.agora.viewmodel.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InteractiveTemplateControllerTest {
    @Test fun localNewChatUsesExistingGraphPersistenceWithoutCallingGeneration() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = database.chatDao()
            val repository = mockk<ConversationRepository>()
            val client = mockk<ChatClient>(relaxed = true)
            val render = mockk<ConversationRenderStore>(relaxed = true)
            every { client.renderStore } returns render
            every { client.openConversationId } returns "c"
            coEvery { client.publishAcceptedNewConversation(any(), any(), any()) } returns true
            coEvery { repository.createConversationRunWithMessages(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                dao.createConversationRunWithMessages(arg(0), arg(1), arg(2), arg(3), arg(4), arg(5), arg(6), arg(7))
            }
            val workspace = NewChatPersistEntity(modelId = "provider:model", draftText = "keep this")
            dao.upsertNewChatPersist(workspace)
            val target = ForegroundSendTarget(NEW_CHAT_WORKSPACE_ID, "c", "local", true, 1, "provider:model",
                NewChatWorkspaceSnapshot.fromPersisted(workspace))
            val template = com.newoether.agora.skills.bindSkillTemplate(InteractiveTemplate(name = "Generic interface",
                answer = InteractiveAnswer("Menu", "Options", "Choose", html = "<button>Start</button>")),
                com.newoether.agora.skills.SkillTemplateFlow("examiner.md", "before"))
            val coordinator = ConversationExecutionCoordinator()
            val controller = InteractiveTemplateController(context, repository, client, coordinator, { null }, { false })
            val opened = controller.open(template, target)
            assertTrue(opened.acceptance is SendAcceptance.Direct)
            assertEquals(RunEndReason.LOCAL_TEMPLATE_OPENED, dao.getRun("local")!!.endReason)
            assertNull(dao.getLiveRun("c"))
            val restored = InteractiveTemplate.decode(template.segment().content)
            assertEquals(template.skillFlow, restored.skillFlow)
            assertEquals("examiner.md", restored.skillFlow?.skill)
            assertEquals("keep this", dao.getNewChatPersist()!!.draftText)
            assertTrue(coordinator.activeConversationIds.value.isEmpty())
            verify(exactly = 1) { render.commitGraph(any(), any(), null, any()) }
        } finally { database.close() }
    }
}
