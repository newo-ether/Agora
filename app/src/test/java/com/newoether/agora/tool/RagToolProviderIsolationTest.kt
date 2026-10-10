package com.newoether.agora.tool

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagToolProviderIsolationTest {
    private val conversations = mockk<ConversationRepository>()
    private val provider = RagToolProvider(conversations)
    private val context = GenerationContext(accessPastConversations = true)

    @Test
    fun listConversations_readsOnlySearchableConversationSource() = runTest {
        coEvery { conversations.getSearchableConversationCount() } returns 1
        coEvery {
            conversations.getSearchableConversationsPage(0, 20, descending = true)
        } returns listOf(
            ChatEntity(id = "visible", title = "Visible", lastUpdated = 123L)
        )

        val result = Json.parseToJsonElement(
            provider.execute("list_conversations", "{}", context)
        ).jsonObject

        assertEquals(1, result.getValue("total").jsonPrimitive.content.toInt())
        assertEquals(
            "visible",
            result.getValue("conversations").jsonArray.single().jsonObject
                .getValue("id").jsonPrimitive.content,
        )
        coVerify(exactly = 1) { conversations.getSearchableConversationCount() }
        coVerify(exactly = 1) {
            conversations.getSearchableConversationsPage(0, 20, descending = true)
        }
        coVerify(exactly = 0) { conversations.getAllConversationsList() }
    }

    @Test
    fun readConversation_rejectsHiddenConversationBeforeReadingMessages() = runTest {
        coEvery { conversations.getSearchableConversation("hidden") } returns null

        val result = Json.parseToJsonElement(
            provider.execute(
                "read_conversation",
                "{\"conversation_id\":\"hidden\"}",
                context,
            )
        ).jsonObject

        assertEquals("not_found", result.getValue("error").jsonPrimitive.content)
        coVerify(exactly = 0) { conversations.getMessageTopologySnapshot(any()) }
    }

    @Test
    fun keywordSearch_dropsStaleHiddenMatchBeforeWindowExpansion() = runTest {
        val hiddenMatch = MessageEntity(
            id = "message-hidden",
            conversationId = "hidden",
            text = "private task result",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = 123L,
            runId = "run-hidden",
            runSequence = 0,
        )
        coEvery { conversations.searchMessages("private", any()) } returns listOf(hiddenMatch)
        coEvery { conversations.getSearchableConversation("hidden") } returns null

        val result = Json.parseToJsonElement(
            provider.execute(
                "search_conversations",
                "{\"query\":\"private\"}",
                context,
            )
        ).jsonObject

        assertTrue(result.getValue("results").jsonArray.isEmpty())
        coVerify(exactly = 0) { conversations.getMessageTopologySnapshot(any()) }
    }

    @Test
    fun searchConversations_emitsCountField() = runTest {
        val match = MessageEntity(
            id = "message-1",
            conversationId = "conv",
            text = "hello target",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = 123L,
            runId = "run-1",
            runSequence = 0,
        )
        coEvery { conversations.searchMessages("target", any()) } returns listOf(match)
        coEvery { conversations.getSearchableConversation("conv") } returns ChatEntity(id = "conv", title = "Conv", lastUpdated = 123L)
        coEvery { conversations.getMessageTopologySnapshot("conv") } returns
            listOf(topology(match))
        coEvery { conversations.getMessagesByIds(listOf(match.id)) } returns listOf(match)

        val result = Json.parseToJsonElement(
            provider.execute("search_conversations", """{"query":"target"}""", context)
        ).jsonObject

        assertEquals(1, result.getValue("count").jsonPrimitive.content.toInt())
        assertEquals(1, result.getValue("results").jsonArray.size)
    }

    @Test
    fun readConversation_usesIdAsTheSiblingFallbackTieBreaker() = runTest {
        val laterId = MessageEntity(
            id = "z-message",
            conversationId = "conv",
            text = "deterministic fallback",
            status = MessageStatus.SUCCESS,
            participant = Participant.USER,
            timestamp = 123L,
            runId = "run-z",
            runSequence = 0,
        )
        val earlierId = laterId.copy(
            id = "a-message",
            text = "must not be selected",
            runId = "run-a",
        )
        coEvery { conversations.getSearchableConversation("conv") } returns ChatEntity(
            id = "conv",
            title = "Conv",
            lastUpdated = 123L,
        )
        coEvery { conversations.getMessageTopologySnapshot("conv") } returns listOf(
            topology(laterId),
            topology(earlierId),
        )
        coEvery { conversations.getMessagesByIds(listOf(laterId.id)) } returns listOf(laterId)

        val result = Json.parseToJsonElement(
            provider.execute(
                "read_conversation",
                "{\"conversation_id\":\"conv\"}",
                context,
            )
        ).jsonObject

        val messages = result.getValue("messages").jsonArray
        assertEquals(1, messages.size)
        assertEquals(
            laterId.text,
            messages.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun searchAndRead_excludeCompactContextWithoutBreakingBranchTraversal() = runTest {
        val first = MessageEntity(
            id = "user-1", conversationId = "conv", text = "hello target",
            status = MessageStatus.SUCCESS, participant = Participant.USER,
            timestamp = 1L, runId = "run", runSequence = 0,
        )
        val compact = first.copy(id = "compact_summary", parentId = first.id,
            text = "hidden summary", participant = Participant.MODEL, timestamp = 2L)
        val answer = first.copy(id = "answer-1", parentId = compact.id,
            text = "target answer", participant = Participant.MODEL, timestamp = 3L)
        coEvery { conversations.getSearchableConversation("conv") } returns
            ChatEntity(id = "conv", title = "Conv", lastUpdated = 123L)
        coEvery { conversations.searchMessages("target", any()) } returns listOf(answer)
        coEvery { conversations.getMessageTopologySnapshot("conv") } returns
            listOf(first, compact, answer).map(::topology)
        coEvery { conversations.getMessagesByIds(any()) } answers {
            val ids = firstArg<List<String>>()
            assertTrue(compact.id !in ids)
            listOf(first, compact, answer).filter { it.id in ids }
        }
        val search = Json.parseToJsonElement(provider.execute(
            "search_conversations", """{"query":"target","window_size":10}""", context,
        )).jsonObject
        assertTrue(!search.toString().contains("hidden summary"))
        assertTrue(search.toString().contains("target answer"))
        val read = Json.parseToJsonElement(provider.execute(
            "read_conversation", """{"conversation_id":"conv"}""", context,
        )).jsonObject
        assertEquals(2, read.getValue("total_messages").jsonPrimitive.content.toInt())
        assertEquals(listOf(first.text, answer.text), read.getValue("messages").jsonArray.map {
            it.jsonObject.getValue("text").jsonPrimitive.content
        })
    }

    private fun topology(message: MessageEntity) = MessageContextTopology(
        id = message.id,
        conversationId = message.conversationId,
        parentId = message.parentId,
        status = message.status,
        participant = message.participant,
        timestamp = message.timestamp,
        modelName = message.modelName,
        runId = message.runId,
        runSequence = message.runSequence,
        consumedAtPass = message.consumedAtPass,
    )
}
