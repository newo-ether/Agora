package com.newoether.agora.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.updateConversationModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationModelWriteTest {
    @Test
    fun compactNeverEntersCacheAndLegacyCacheIsReclaimed() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.chatDao()
            val semantic = database.semanticIndexDao()
            dao.upsertConversation(ChatEntity(id = "cache", title = "Cache", lastUpdated = 1L))
            dao.upsertRun(RunEntity(id = "cache-run", conversationId = "cache", parentRunId = null,
                status = com.newoether.agora.model.RunStatus.ACTIVE, activeSlot = 1,
                startedAt = 1L, lastCheckpointAt = 1L))
            val ordinary = MessageEntity(id = "ordinary", conversationId = "cache", text = "ordinary body",
                participant = com.newoether.agora.model.Participant.USER, timestamp = 1L, runId = "cache-run")
            val compact = ordinary.copy(id = "compact_summary", text = "summary body")
            dao.insertMessage(ordinary)
            dao.insertMessage(compact)
            val ledger = semantic.admitModel("embedding", 1L)
            assertEquals(null, semantic.getSearchableMessageText(compact.id))
            assertEquals(listOf(ordinary.id), dao.getSearchableMessagesPage(null, 10).map { it.id })
            assertEquals(listOf(ordinary.id), dao.getUnembeddedMessagesPage("embedding", null, 10).map { it.id })
            assertEquals(listOf(ordinary.id), semantic.getReconcileMessagesPage(
                "embedding", ledger.reconcileRevision, null, 10,
            ).map { it.id })
            fun embedding(message: MessageEntity) = EmbeddingEntity(
                messageId = message.id, modelId = "embedding", embedding = ByteArray(4),
                chunkText = message.text, dimension = 1,
            )
            assertFalse(database.commitSemanticEmbedding(embedding(compact),
                semanticSourceFingerprint(compact.text), 2L, completePendingWork = false))
            assertEquals(null, dao.getEmbedding(compact.id))
            assertTrue(database.commitSemanticEmbedding(embedding(ordinary),
                semanticSourceFingerprint(ordinary.text), 2L, completePendingWork = false))
            dao.insertEmbeddings(listOf(embedding(compact)))
            assertEquals(1, dao.getIndexableMessageCount())
            assertEquals(1, dao.getEmbeddingCountByModel("embedding"))
            assertEquals(listOf(ordinary.id), dao.getEmbeddingSearchPage("embedding", 0L, 1, 10).map { it.messageId })
            val maintenance = database.maintenanceDebtDao()
            val stale = maintenance.getOrphanEmbeddingIdsPage(0L, 10)
            assertEquals(1, stale.size)
            assertEquals(1, maintenance.deleteOrphanEmbeddingsByIds(stale))
            assertEquals(null, dao.getEmbedding(compact.id))
            assertTrue(dao.getEmbedding(ordinary.id) != null)
        } finally {
            database.close()
        }
    }

    @Test
    fun modelWriteKeepsEveryOtherFieldAndNeverRecreatesADeletedOwner() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.chatDao()
            val repository = ConversationRepository(dao, database)
            val before = ChatEntity(
                id = "conversation", title = "latest title", modelId = "old:model",
                draftText = "later draft", draftAttachments = "[]", selectedBranchesJson = "{}",
                lastUpdated = 42L, dataChangedAt = Long.MAX_VALUE - 100,
            )
            dao.upsertConversation(before)
            assertTrue(repository.updateConversationModel(before.id, "new:model"))
            val after = requireNotNull(dao.getConversation(before.id))
            assertEquals(before.copy(modelId = "new:model", dataChangedAt = before.dataChangedAt + 1), after)
            assertFalse(repository.updateConversationModel("deleted", "new:model"))
            assertTrue(repository.updateConversationSystemPrompt(before.id, "prompt"))
            val prompted = requireNotNull(dao.getConversation(before.id))
            assertEquals(after.copy(systemPromptId = "prompt", dataChangedAt = after.dataChangedAt + 1), prompted)
            assertTrue(repository.updateConversationSystemPrompt(before.id, null))
            assertEquals(prompted.copy(systemPromptId = null, dataChangedAt = prompted.dataChangedAt + 1), dao.getConversation(before.id))
            assertFalse(repository.updateConversationSystemPrompt("deleted", "prompt"))
            assertEquals(null, dao.getConversation("deleted"))
        } finally {
            database.close()
        }
    }
}
