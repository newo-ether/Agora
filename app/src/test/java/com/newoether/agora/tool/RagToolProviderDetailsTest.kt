package com.newoether.agora.tool

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.HistorySearchFilter
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

    @Test fun readIgnoresFormerDetailArgumentsWithoutLoadingToolRounds() = runTest {
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
        )).copy(thoughts = "legacy thought")
        val next = message("next", parent = answer.id)
        seed(listOf(user, round, result, answer, next))

        val plain = read(""", "offset":1,"limit":1""")
        val formerArguments = read(""", "offset":1,"limit":1,"show_thinking":true,"show_tool_calls":true,"tool_offset":1,"tool_limit":1""")
        assertEquals(plain, formerArguments)
        assertEquals(listOf(answer.id), ids(plain))
        assertEquals("3", plain.getValue("total_messages").jsonPrimitive.content)
        assertEquals("2", plain.getValue("next_offset").jsonPrimitive.content)
        assertEquals(answer.id, plain.getValue("next_after_message_id").jsonPrimitive.content)
        assertEquals(setOf(
            "message_id", "participant", "text", "status", "run_id", "run_sequence", "timestamp",
        ), row(plain).keys)
        assertFalse(plain.toString().contains("stored reasoning"))
        assertFalse(plain.toString().contains("legacy thought"))
        assertFalse(plain.toString().contains("opaque-"))
        assertFalse(plain.toString().contains("hidden result row"))
        coVerify(exactly = 0) { repository.getMessagesByIds(listOf(round.id)) }
    }

    @Test fun readExposesStructuredTerminalErrorWhenPresent() = runTest {
        val failed = message(
            id = "failed",
            text = "partial answer",
            segments = listOf(MessageSegment("error", "Connection closed", errorCode = "E_CLOSED")),
        ).copy(status = MessageStatus.ERROR)
        seed(listOf(failed))
        val page = read()
        assertEquals("partial answer", row(page).getValue("text").jsonPrimitive.content)
        assertEquals("Connection closed", row(page).getValue("error").jsonPrimitive.content)
        assertEquals("E_CLOSED", row(page).getValue("error_code").jsonPrimitive.content)
    }

    @Test fun cursorSurvivesDeletionBeforeAnchorAndRejectsRemovedBranchAnchor() = runTest {
        val first = message("first")
        val anchor = message("anchor", parent = first.id)
        val last = message("last", parent = anchor.id)
        seed(listOf(first, anchor, last))
        val page = read(""", "limit":2""")
        assertEquals(anchor.id, page.getValue("next_after_message_id").jsonPrimitive.content)
        seed(listOf(anchor.copy(parentId = null), last))
        val continued = read(""", "after_message_id":"anchor","offset":999""")
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

    @Test fun readPropagatesCancellationDuringPageHydration() = runTest {
        seed(listOf(message("answer")))
        coEvery { repository.getMessagesByIds(listOf("answer")) } throws CancellationException("cancelled")
        try {
            read()
            fail("Cancellation must escape")
        } catch (_: CancellationException) { }
    }

    @Test fun historyToolSchemasOmitDetailFlagsAndToolPagination() {
        val definitions = provider.definitions(context).associateBy { it.function.name }
        assertEquals(
            setOf("query", "limit", "conversation_id", "exclude_current", "after", "before", "participant"),
            definitions.getValue("conversation_search").function.parameters.properties.keys,
        )
        assertEquals(
            setOf("conversation_id", "offset", "after_message_id", "limit"),
            definitions.getValue("conversation_read").function.parameters.properties.keys,
        )
    }

    @Test fun searchIgnoresFormerDetailArgumentsWithoutLoadingToolRounds() = runTest {
        val user = message("user", text = "target")
        val round = message("tool_round", segments = listOf(tool("referenced")))
        val blank = message("blank", parent = user.id, text = "", segments = listOf(
            MessageSegment("thought", "stored reasoning"), tool("inline"),
            MessageSegment("tool", toolCallId = "referenced", toolRoundId = round.id),
        )).copy(thoughts = "legacy thought")
        val malformed = message("malformed", parent = blank.id).copy(toolCallJson = "invalid json")
        seed(listOf(user, blank, malformed), listOf(round))
        coEvery { repository.searchMessages("target", any()) } returns listOf(user)
        suspend fun search(flags: String): JsonObject = Json.parseToJsonElement(provider.execute(
            "conversation_search", """{"query":"target"$flags}""", context,
        )).jsonObject
        val plain = search("")
        val formerArguments = search(""", "show_thinking":true,"show_tool_calls":true,"tool_offset":1,"tool_limit":1""")
        assertEquals(plain, formerArguments)
        val window = plain.getValue("results").jsonArray.single().jsonObject
        assertEquals(listOf(user.id, blank.id, malformed.id), ids(window))
        assertEquals("1", window.getValue("match_count").jsonPrimitive.content)
        assertEquals("1.0", window.getValue("top_score").jsonPrimitive.content)
        for (element in window.getValue("messages").jsonArray) {
            assertEquals(setOf(
                "message_id", "participant", "text", "status", "run_id", "run_sequence", "timestamp",
            ), element.jsonObject.keys)
        }
        coVerify(exactly = 2) { repository.searchMessages("target", any()) }
        coVerify(exactly = 2) { repository.getMessagesByIds(listOf(user.id, blank.id, malformed.id)) }
        coVerify(exactly = 2) { repository.getMessagesByIds(any()) }
        coVerify(exactly = 0) { repository.getMessagesByIds(listOf(round.id)) }
    }

    @Test fun readResolvesCurrentAliasAndReportsRealId() = runTest {
        seed(listOf(message("answer")))
        coEvery { repository.getSearchableConversation("conv", "conv") } returns ChatEntity("conv", "Conversation")
        val page = Json.parseToJsonElement(provider.execute(
            "conversation_read", """{"conversation_id":"current"}""", context.copy(conversationId = "conv"),
        )).jsonObject
        assertEquals("conv", page.getValue("conversation_id").jsonPrimitive.content)
        assertEquals(listOf("answer"), ids(page))
        val orphan = Json.parseToJsonElement(provider.execute(
            "conversation_read", """{"conversation_id":"current"}""", context,
        )).jsonObject
        assertEquals("current_unavailable", orphan.getValue("error").jsonPrimitive.content)
    }

    @Test fun listMarksCurrentConversation() = runTest {
        coEvery { repository.getSearchableConversationCount() } returns 2
        coEvery { repository.getSearchableConversationsPage(0, 20, true) } returns listOf(
            ChatEntity("conv", "Current"), ChatEntity("other", "Other"),
        )
        val list = Json.parseToJsonElement(provider.execute(
            "conversation_list", "{}", context.copy(conversationId = "conv"),
        )).jsonObject
        assertEquals("conv", list.getValue("current_conversation_id").jsonPrimitive.content)
        assertEquals(
            listOf("true", "false"),
            list.getValue("conversations").jsonArray.map { it.jsonObject.getValue("is_current").jsonPrimitive.content },
        )
    }

    @Test fun searchForwardsParsedFiltersToTheDatabaseSearch() = runTest {
        val user = message("user", text = "target")
        seed(listOf(user))
        val expected = HistorySearchFilter(
            conversationId = "conv", participant = "USER",
            afterMs = java.time.Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(),
            beforeMs = java.time.Instant.parse("2026-11-02T12:00:00Z").toEpochMilli(),
        )
        coEvery { repository.searchMessages("target", any(), expected) } returns listOf(user)
        val result = Json.parseToJsonElement(provider.execute(
            "conversation_search",
            """{"query":"target","conversation_id":"current","participant":"user",""" +
                """"after":"2026-11-01T00:00:00Z","before":"2026-11-02T20:00:00+08:00"}""",
            context.copy(conversationId = "conv"),
        )).jsonObject
        assertEquals("1", result.getValue("count").jsonPrimitive.content)
        assertEquals("conv", result.getValue("filters").jsonObject.getValue("conversation_id").jsonPrimitive.content)
        coVerify(exactly = 1) { repository.searchMessages("target", any(), expected) }
    }

    @Test fun searchRejectsMalformedFiltersWithoutSearching() = runTest {
        suspend fun error(args: String): String = Json.parseToJsonElement(provider.execute(
            "conversation_search", """{"query":"target"$args}""", context,
        )).jsonObject.getValue("error").jsonPrimitive.content
        assertEquals("invalid_time", error(""","after":"yesterday""""))
        assertEquals("invalid_participant", error(""","participant":"tool""""))
        assertEquals("current_unavailable", error(""","exclude_current":true"""))
        coVerify(exactly = 0) { repository.searchMessages(any(), any(), any()) }
    }

    @Test fun historyTimeAcceptsDatesLocalTimesAndOffsets() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val start = java.time.Instant.parse("2026-11-20T16:00:00Z").toEpochMilli()
        assertEquals(start, parseHistoryTime("2026-11-21", zone))
        assertEquals(start, parseHistoryTime("2026-11-21 00:00", zone))
        assertEquals(start, parseHistoryTime("2026-11-21T00:00:00+08:00", zone))
        assertEquals(start, parseHistoryTime("2026-11-20T16:00:00Z", zone))
        assertNull(parseHistoryTime("21/11/2026", zone))
    }

    @Test fun malformedSegmentsDoNotBreakReadPagination() = runTest {
        seed(listOf(message("answer").copy(toolCallJson = "invalid json")))
        val page = read()
        assertEquals(listOf("answer"), ids(page))
        assertFalse(row(page).containsKey("details_error"))
        assertEquals("false", page.getValue("has_more").jsonPrimitive.content)
    }

    @Test fun offsetAtIntegerMaximumReturnsAnEmptyPageWithoutOverflow() = runTest {
        seed(listOf(message("answer")))
        val page = read(""", "offset":2147483647""")
        assertTrue(ids(page).isEmpty())
        assertEquals("false", page.getValue("has_more").jsonPrimitive.content)
    }

    @Test fun searchCapsALongMergedWindowInsideItsOwnRange() = runTest {
        val branch = (0 until 60).map { index ->
            message("m$index", parent = if (index == 0) null else "m${index - 1}", text = "body $index")
        }
        seed(branch)
        // A high-scoring match far outside the merged window used to supply the centre, which
        // produced an inverted range and failed inside branch.subList.
        coEvery { repository.searchMessages("target", any(), any()) } returns
            (listOf(branch[40]) + (0..18 step 2).map { branch[it] })
        val result = Json.parseToJsonElement(provider.execute(
            "conversation_search",
            """{"query":"target","context_window":4,"limit":30}""",
            context,
        )).jsonObject
        assertNull(result["error"])
        assertTrue(result.getValue("count").jsonPrimitive.content.toInt() > 0)
    }

    @Test fun searchReportsAReadableFailureInsteadOfTheRawException() = runTest {
        seed(listOf(message("m0")))
        coEvery { repository.searchMessages("target", any(), any()) } throws
            IllegalStateException("fromIndex(32) > toIndex(15)")
        val result = Json.parseToJsonElement(provider.execute(
            "conversation_search", """{"query":"target"}""", context,
        )).jsonObject
        assertEquals("search_error", result.getValue("error").jsonPrimitive.content)
        val message = result.getValue("message").jsonPrimitive.content
        assertTrue(message.startsWith("conversation_search failed:"))
        assertFalse(message.contains("fromIndex"))
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
