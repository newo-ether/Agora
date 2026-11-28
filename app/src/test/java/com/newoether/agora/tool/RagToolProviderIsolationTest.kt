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
        assertEquals("false", result.getValue("conversations").jsonArray.single().jsonObject
            .getValue("is_generating").jsonPrimitive.content)
        coVerify(exactly = 1) { conversations.getSearchableConversationCount() }
        coVerify(exactly = 1) {
            conversations.getSearchableConversationsPage(0, 20, descending = true)
        }
        coVerify(exactly = 0) { conversations.getAllConversationsList() }
    }

    @Test
    fun listConversationsSnapshotsLiveGenerationStateOncePerPageAndRefreshesOnEachCall() = runTest {
        val registry = com.newoether.agora.viewmodel.ConversationStateRegistry { }
        var automation = setOf("background", "hidden-child")
        var snapshotReads = 0
        val liveProvider = RagToolProvider(conversations) {
            snapshotReads++
            registry.activeConversationIds.value + automation
        }
        coEvery { conversations.getSearchableConversationCount() } returns 3
        coEvery { conversations.getSearchableConversationsPage(0, 20, true) } returns
            listOf("foreground", "background", "idle").map { ChatEntity(id = it, title = it) }
        suspend fun states(): List<Boolean> = Json.parseToJsonElement(
            liveProvider.execute("list_conversations", "{}", context),
        ).jsonObject.getValue("conversations").jsonArray.map {
            val value = it.jsonObject.getValue("is_generating").jsonPrimitive
            assertTrue(!value.isString)
            value.content.toBooleanStrict()
        }
        registry.markActive("foreground")
        assertEquals(listOf(true, true, false), states())
        registry.markIdle("foreground")
        automation = emptySet()
        registry.markActive("idle")
        assertEquals(listOf(false, false, true), states())
        registry.markIdle("idle")
        assertEquals(listOf(false, false, false), states())
        assertEquals(3, snapshotReads)
        coVerify(exactly = 0) { conversations.getMessageTopologySnapshot(any()) }
        coVerify(exactly = 0) { conversations.getMessagesByIds(any()) }
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
    fun keywordSearchPropagatesCancellationInsteadOfReturningSearchError() = runTest {
        coEvery { conversations.searchMessages("cancel", any()) } throws
            kotlinx.coroutines.CancellationException("cancelled search")
        try {
            provider.execute("search_conversations", """{"query":"cancel"}""", context)
            org.junit.Assert.fail("Cancellation must escape tool execution")
        } catch (_: kotlinx.coroutines.CancellationException) { }
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

    @Test fun readConversationReturnsTerminalErrorAndExactIdentityAlongsidePartialAnswer() = runTest {
        val message = MessageEntity(id = "failed-model", conversationId = "conv", text = "Partial answer",
            participant = Participant.MODEL, status = MessageStatus.ERROR, timestamp = 123L,
            runId = "run", runSequence = 4L,
            toolCallJson = """[{"type":"answer","content":"Partial answer"},{"type":"error","content":"Connection closed","errorCode":"connection_closed"}]""")
        coEvery { conversations.getSearchableConversation("conv") } returns ChatEntity("conv", "Conversation")
        coEvery { conversations.getMessageTopologySnapshot("conv") } returns listOf(topology(message))
        coEvery { conversations.getMessagesByIds(listOf(message.id)) } returns listOf(message)
        val result = Json.parseToJsonElement(provider.execute("read_conversation",
            """{"conversation_id":"conv"}""", context)).jsonObject
        val row = result.getValue("messages").jsonArray.single().jsonObject
        assertEquals("Partial answer", row.getValue("text").jsonPrimitive.content)
        assertEquals("Connection closed", row.getValue("error").jsonPrimitive.content)
        assertEquals("connection_closed", row.getValue("error_code").jsonPrimitive.content)
        assertEquals("ERROR", row.getValue("status").jsonPrimitive.content)
        assertEquals(message.id, row.getValue("message_id").jsonPrimitive.content)
        assertEquals("run", row.getValue("run_id").jsonPrimitive.content)
        assertEquals("4", row.getValue("run_sequence").jsonPrimitive.content)
    }

    @Test
    fun childReadPassesExactCallerAndRejectsBeforeLoadingMessageTopology() = runTest {
        val parentContext = context.copy(conversationId = "parent")
        coEvery { conversations.getSearchableConversation("foreign-child", "parent") } returns null
        val result = Json.parseToJsonElement(provider.execute(
            "read_conversation", """{"conversation_id":"foreign-child"}""", parentContext,
        )).jsonObject
        assertEquals("not_found", result.getValue("error").jsonPrimitive.content)
        coVerify(exactly = 1) { conversations.getSearchableConversation("foreign-child", "parent") }
        coVerify(exactly = 0) { conversations.getMessageTopologySnapshot(any()) }
        coVerify(exactly = 0) { conversations.getMessagesByIds(any()) }
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
