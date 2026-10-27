package com.newoether.agora.tool

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class RagToolProviderDetailsTest {
    private val repository = mockk<ConversationRepository>()
    private val provider = RagToolProvider(repository)
    private val context = GenerationContext(accessPastConversations = true)

    @Test fun flagsOnlyExpandMessagesAndLoadReferencedToolsOnDemand() = runTest {
        val user = message("user", text = "question")
        val round = message("tool_round", parent = user.id, segments = listOf(
            tool("a").copy(signature = "opaque-signature", responseOutputItems = listOf(
                Json.parseToJsonElement("""{"encrypted":"opaque-continuation"}""").jsonObject,
            )), tool("b"),
        ))
        val result = message("result_a", parent = round.id, text = "hidden result row")
        val answer = message("answer", parent = result.id, segments = listOf(
            MessageSegment("thought", "stored reasoning"),
            MessageSegment("tool", toolCallId = "b", toolRoundId = round.id),
            MessageSegment("tool", toolCallId = "a", toolRoundId = round.id),
        ))
        val next = message("next", parent = answer.id)
        seed(listOf(user, round, result, answer, next))

        val plain = read(""", "offset":1,"limit":1""")
        val thinking = read(""", "offset":1,"limit":1,"show_thinking":true""")
        coVerify(exactly = 0) { repository.getMessagesByIds(listOf(round.id)) }
        val expanded = read(""", "offset":1,"limit":1,"show_thinking":true,"show_tool_calls":true""")
        for (page in listOf(plain, thinking, expanded)) {
            assertEquals(listOf(answer.id), ids(page))
            assertEquals("3", page.getValue("total_messages").jsonPrimitive.content)
            assertEquals("2", page.getValue("next_offset").jsonPrimitive.content)
            assertEquals(answer.id, page.getValue("next_after_message_id").jsonPrimitive.content)
        }
        assertFalse(row(plain).containsKey("thinking"))
        assertFalse(row(plain).containsKey("tool_calls"))
        assertEquals("stored reasoning", row(thinking).getValue("thinking").jsonArray.single()
            .jsonObject.getValue("text").jsonPrimitive.content)
        val calls = row(expanded).getValue("tool_calls").jsonArray
        assertEquals(listOf("b", "a"), calls.map { it.jsonObject.getValue("tool_call_id").jsonPrimitive.content })
        assertEquals("result a", calls[1].jsonObject.getValue("result").jsonPrimitive.content)
        assertEquals("round_commit", calls[0].jsonObject.getValue("timestamp_source").jsonPrimitive.content)
        assertFalse(expanded.toString().contains("opaque-"))
        assertFalse(expanded.toString().contains("hidden result row"))
    }

    @Test fun toolPagesHydrateOnlyRequestedRoundsAndDoNotMoveMessagePage() = runTest {
        val rounds = (0..2).map { index -> message("tool_$index", segments = listOf(tool("$index"))) }
        val answer = message("answer", segments = rounds.mapIndexed { index, round ->
            MessageSegment("tool", toolCallId = "$index", toolRoundId = round.id)
        })
        seed(listOf(answer), rounds)
        val first = read(""", "show_tool_calls":true,"tool_limit":1""")
        val second = read(""", "show_tool_calls":true,"tool_offset":1,"tool_limit":1""")
        assertEquals(ids(first), ids(second))
        assertEquals("1", row(first).getValue("tool_calls_next_offset").jsonPrimitive.content)
        assertEquals("2", row(second).getValue("tool_calls_next_offset").jsonPrimitive.content)
        assertEquals("3", row(second).getValue("tool_calls_total").jsonPrimitive.content)
        coVerify(exactly = 1) { repository.getMessagesByIds(listOf("tool_0")) }
        coVerify(exactly = 1) { repository.getMessagesByIds(listOf("tool_1")) }
        coVerify(exactly = 0) { repository.getMessagesByIds(listOf("tool_2")) }
    }

    @Test fun legacyInlineToolsAndThoughtFallbackRemainReadable() = runTest {
        val answer = message("answer", segments = listOf(tool("legacy"))).copy(thoughts = "legacy thought")
        seed(listOf(answer))
        val page = read(""", "show_thinking":true,"show_tool_calls":true""")
        assertEquals("legacy thought", row(page).getValue("thinking").jsonArray.single()
            .jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("legacy", row(page).getValue("tool_calls").jsonArray.single()
            .jsonObject.getValue("tool_call_id").jsonPrimitive.content)
        coVerify(exactly = 1) { repository.getMessagesByIds(any()) }
    }

    @Test fun invalidToolReferenceFailsClosedForForeignMissingAndAmbiguousBodies() = runTest {
        val answer = message("answer", segments = listOf(MessageSegment("tool", toolCallId = "a", toolRoundId = "tool_a")))
        val validRound = message("tool_a", segments = listOf(tool("a")))
        val variants = listOf(
            emptyList(),
            listOf(validRound.copy(conversationId = "foreign")),
            listOf(validRound.copy(runId = "foreign-run")),
            listOf(validRound.copy(status = MessageStatus.ERROR)),
            listOf(validRound.copy(toolCallJson = Json.encodeToString(listOf(tool("a"), tool("a"))))),
        )
        for (rounds in variants) {
            seed(listOf(answer), rounds)
            val page = read(""", "show_tool_calls":true""")
            assertEquals("read_error", page.getValue("error").jsonPrimitive.content)
            assertFalse(page.containsKey("messages"))
        }
    }

    @Test fun cursorSurvivesDeletionBeforeAnchorAndRejectsRemovedBranchAnchor() = runTest {
        val first = message("first")
        val anchor = message("anchor", parent = first.id)
        val last = message("last", parent = anchor.id)
        seed(listOf(first, anchor, last))
        val page = read(""", "limit":2""")
        assertEquals(anchor.id, page.getValue("next_after_message_id").jsonPrimitive.content)
        seed(listOf(anchor.copy(parentId = null), last))
        val continued = read(""", "after_message_id":"anchor","offset":999,"show_thinking":true""")
        assertEquals(listOf(last.id), ids(continued))
        assertEquals("1", continued.getValue("offset").jsonPrimitive.content)
        seed(listOf(first, last.copy(parentId = first.id)))
        assertEquals("cursor_not_found", read(""", "after_message_id":"anchor" """)
            .getValue("error").jsonPrimitive.content)
    }

    @Test fun hydrationDeletionDoesNotSilentlyAdvanceThePage() = runTest {
        seed(listOf(message("deleted")))
        coEvery { repository.getMessagesByIds(any()) } returns emptyList()
        val page = read()
        assertEquals("history_changed", page.getValue("error").jsonPrimitive.content)
        assertFalse(page.containsKey("next_offset"))
    }

    @Test fun readPropagatesCancellationDuringDetailHydration() = runTest {
        val answer = message("answer", segments = listOf(MessageSegment("tool", toolCallId = "a", toolRoundId = "tool_a")))
        seed(listOf(answer))
        coEvery { repository.getMessagesByIds(listOf("tool_a")) } throws CancellationException("cancelled")
        try {
            read(""", "show_tool_calls":true""")
            fail("Cancellation must escape")
        } catch (_: CancellationException) { }
    }

    @Test fun searchKeepsBlankAssistantMembershipAndMatchingUnchangedAcrossFlags() = runTest {
        val user = message("user", text = "target")
        val blank = message("blank", parent = user.id, text = "", segments = listOf(
            MessageSegment("thought", "stored reasoning"), tool("inline"),
        ))
        seed(listOf(user, blank))
        coEvery { repository.searchMessages("target", any()) } returns listOf(user)
        suspend fun search(flags: String): JsonObject = Json.parseToJsonElement(provider.execute(
            "conversation_search", """{"query":"target"$flags}""", context,
        )).jsonObject.getValue("results").jsonArray.single().jsonObject
        val plain = search("")
        val expanded = search(""", "show_thinking":true,"show_tool_calls":true""")
        assertEquals(listOf(user.id, blank.id), ids(plain))
        assertEquals(ids(plain), ids(expanded))
        assertEquals(plain["match_count"], expanded["match_count"])
        assertEquals(plain["top_score"], expanded["top_score"])
        coVerify(exactly = 2) { repository.searchMessages("target", any()) }
    }

    @Test fun malformedSegmentsAreExplicitAndDoNotChangeMessagePagination() = runTest {
        seed(listOf(message("answer").copy(toolCallJson = "invalid json")))
        val page = read(""", "show_tool_calls":true""")
        assertEquals(listOf("answer"), ids(page))
        assertEquals("invalid_stored_segments", row(page).getValue("details_error").jsonPrimitive.content)
        assertEquals("false", page.getValue("has_more").jsonPrimitive.content)
    }

    @Test fun offsetAtIntegerMaximumReturnsAnEmptyPageWithoutOverflow() = runTest {
        seed(listOf(message("answer")))
        val page = read(""", "offset":2147483647""")
        assertTrue(ids(page).isEmpty())
        assertEquals("false", page.getValue("has_more").jsonPrimitive.content)
    }

    private suspend fun read(extra: String = ""): JsonObject = Json.parseToJsonElement(provider.execute(
        "conversation_read", """{"conversation_id":"conv"$extra}""", context,
    )).jsonObject

    private fun ids(page: JsonObject) = page.getValue("messages").jsonArray.map {
        it.jsonObject.getValue("message_id").jsonPrimitive.content
    }
    private fun row(page: JsonObject) = page.getValue("messages").jsonArray.single().jsonObject

    private fun seed(branch: List<MessageEntity>, extra: List<MessageEntity> = emptyList()) {
        coEvery { repository.getSearchableConversation("conv") } returns ChatEntity("conv", "Conversation")
        coEvery { repository.getMessageTopologySnapshot("conv") } returns branch.map { message ->
            MessageContextTopology(
                id = message.id, conversationId = message.conversationId, parentId = message.parentId,
                status = message.status, participant = message.participant, timestamp = message.timestamp,
                modelName = message.modelName, runId = message.runId, runSequence = message.runSequence,
                consumedAtPass = message.consumedAtPass,
            )
        }
        coEvery { repository.getMessagesByIds(any()) } answers {
            val ids = firstArg<List<String>>()
            (branch + extra).filter { it.id in ids }
        }
    }

    private fun message(
        id: String,
        parent: String? = null,
        text: String = "answer",
        segments: List<MessageSegment> = emptyList(),
    ) = MessageEntity(
        id = id, conversationId = "conv", parentId = parent, text = text,
        participant = Participant.MODEL, timestamp = 123L, runId = "run", runSequence = 1L,
        toolCallJson = Json.encodeToString(segments),
    )

    private fun tool(id: String) = MessageSegment(
        type = "tool", toolName = "test_tool", toolArgs = "{}", toolCallId = id,
        toolResult = "result $id", toolState = "succeeded",
    )
}
