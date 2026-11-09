package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactTest {
    private fun message(id: String, text: String, participant: Participant) = ChatMessage(
        id = id,
        text = text,
        participant = participant,
        status = MessageStatus.SUCCESS,
    )

    @Test
    fun nearestCompactIsTheOnlyBoundary() {
        val projected = applyNearestContextCompact(
            listOf(
                message("u0", "old", Participant.USER),
                message("compact_first", "summary one", Participant.MODEL),
                message("u1", "middle", Participant.USER),
                message("compact_second", "summary two", Participant.MODEL),
                message("u2", "new", Participant.USER),
            )
        )
        assertEquals(listOf("summary two", "new"), projected.map { it.text })
        assertEquals(Participant.USER, projected.first().participant)
    }

    @Test
    fun genericContextKeepsRawSummaryAndSeparatesFollowingUserInput() {
        val canonical = canonicalContextMessages(
            listOf(
                message("u0", "old", Participant.USER),
                message("compact_boundary", "summary", Participant.MODEL),
                message("u1", "new request", Participant.USER),
            )
        )

        assertEquals(listOf("context_summary_compact_boundary", "u1"), canonical.map { it.id })
        assertEquals(listOf("summary", "new request"), canonical.map { it.text })
        assertTrue(canonical.none { it.text.contains("<context_summary>") })
    }

    @Test
    fun apiRequestMarksSummaryAndSeparatesFollowingUserInput() {
        val source = listOf(
            message("u0", "old", Participant.USER),
            message("compact_boundary", "summary", Participant.MODEL),
            message("u1", "new request", Participant.USER),
        )

        val prepared = prepareMessages(source, contextTokenBudget = 4_096)

        assertEquals(listOf("context_summary_compact_boundary", "u1"), prepared.map { it.id })
        assertEquals(
            listOf("<context_summary>\nsummary\n</context_summary>", "new request"),
            prepared.map { it.text },
        )
        assertTrue(prepared.none { it.text.contains("Please continue.") })
        assertEquals("summary", source[1].text)
        assertTrue(source.none { it.text.contains("<context_summary>") })
    }

    @Test
    fun compactOnlyApiRequestAppendsContinuation() {
        val prepared = prepareMessages(
            listOf(
                message("u0", "old", Participant.USER),
                message("compact_boundary", " summary ", Participant.MODEL),
            ),
            contextTokenBudget = 4_096,
        )

        assertEquals(1, prepared.size)
        assertEquals(Participant.USER, prepared.single().participant)
        assertEquals(
            "<context_summary>\nsummary\n</context_summary>\n\nPlease continue.",
            prepared.single().text,
        )
    }

    @Test
    fun compactContinuationStaysOnCompactWhenLaterUserIsNotAdjacent() {
        val compact = message("compact_boundary", "summary", Participant.MODEL).copy(
            parentId = "u0",
            timestamp = 123L,
        )
        val prepared = prepareMessages(
            listOf(
                message("u0", "old", Participant.USER),
                compact,
                message("a1", "later assistant", Participant.MODEL),
                message("u2", "later user", Participant.USER),
            ),
            contextTokenBudget = 4_096,
        )

        assertEquals(
            listOf("context_summary_compact_boundary", "a1", "u2"),
            prepared.map { it.id },
        )
        assertEquals(
            "<context_summary>\nsummary\n</context_summary>\n\nPlease continue.",
            prepared[0].text,
        )
        assertEquals(Participant.USER, prepared[0].participant)
        assertEquals(compact.parentId, prepared[0].parentId)
        assertEquals(compact.timestamp, prepared[0].timestamp)
        assertEquals("later assistant", prepared[1].text)
        assertEquals("later user", prepared[2].text)
        assertTrue(prepared.drop(1).none { it.text.contains("Please continue.") })
    }

    @Test
    fun compactContinuationNeverMovesIntoFollowingToolRound() {
        val compact = message("compact_boundary", "summary", Participant.MODEL).copy(
            timestamp = 123L,
        )
        val toolCall = message("tool_call", "", Participant.MODEL).copy(
            segments = listOf(
                com.newoether.agora.model.MessageSegment(
                    type = "tool",
                    toolName = "file_read",
                    toolArgs = "{}",
                    toolCallId = "call-1",
                )
            ),
        )
        val toolResult = message("result_call", "result", Participant.USER).copy(
            segments = listOf(
                com.newoether.agora.model.MessageSegment(
                    type = "tool",
                    toolName = "file_read",
                    toolResult = "result",
                    toolCallId = "call-1",
                )
            ),
        )

        val prepared = prepareMessages(
            listOf(compact, toolCall, toolResult),
            contextTokenBudget = 4_096,
        )

        assertEquals(
            listOf("context_summary_compact_boundary", "tool_call", "result_call"),
            prepared.map { it.id },
        )
        assertEquals(
            "<context_summary>\nsummary\n</context_summary>\n\nPlease continue.",
            prepared[0].text,
        )
        assertEquals(compact.timestamp, prepared[0].timestamp)
        assertTrue(prepared.drop(1).none { it.text.contains("Please continue.") })
        assertEquals("{}", prepared[1].segments?.single()?.toolArgs)
        assertEquals("result", prepared[2].segments?.single()?.toolResult)
    }

    @Test
    fun initialApiUserPromptSuppressesCompactContinuation() {
        val compact = message("compact_boundary", "summary", Participant.MODEL)
        val prompt = message(
            "api_initial_user_${compact.id}",
            "Create the compact context summary now.",
            Participant.USER,
        ).copy(parentId = compact.id)

        val prepared = prepareMessages(
            listOf(message("u0", "old", Participant.USER), compact, prompt),
            contextTokenBudget = 4_096,
        )

        assertEquals(2, prepared.size)
        assertEquals("<context_summary>\nsummary\n</context_summary>", prepared[0].text)
        assertEquals("Create the compact context summary now.", prepared[1].text)
        assertTrue(prepared.none { it.text.contains("Please continue.") })
    }

    @Test
    fun nonTerminalInitialApiUserPromptSuppressesCompactContinuation() {
        val compact = message("compact_boundary", "summary", Participant.MODEL)
        val prompt = message(
            "api_initial_user_${compact.id}",
            "Create the compact context summary now.",
            Participant.USER,
        ).copy(parentId = compact.id)

        val prepared = prepareMessages(
            listOf(compact, prompt, message("a1", "later assistant", Participant.MODEL)),
            contextTokenBudget = 4_096,
        )

        assertTrue(prepared.any { it.text.contains("Create the compact context summary now.") })
        assertTrue(prepared.none { it.text.contains("Please continue.") })
    }

    @Test
    fun terminalInitialApiUserPromptDoesNotSuppressWhenNotAdjacent() {
        val compact = message("compact_boundary", "summary", Participant.MODEL)
        val model = message("a1", "later assistant", Participant.MODEL)
        val prompt = message(
            "api_initial_user_${model.id}",
            "Create the compact context summary now.",
            Participant.USER,
        ).copy(parentId = model.id)

        val prepared = prepareMessages(
            listOf(compact, model, prompt),
            contextTokenBudget = 4_096,
        )

        assertEquals(
            "<context_summary>\nsummary\n</context_summary>\n\nPlease continue.",
            prepared[0].text,
        )
        assertEquals("later assistant", prepared[1].text)
        assertEquals("Create the compact context summary now.", prepared[2].text)
        assertTrue(prepared.drop(1).none { it.text.contains("Please continue.") })
    }

    @Test
    fun deletingNewestCompactNaturallyRevealsPreviousBoundary() {
        val withoutNewest = listOf(
            message("u0", "old", Participant.USER),
            message("compact_first", "summary one", Participant.MODEL),
            message("u1", "middle", Participant.USER),
            message("u2", "new", Participant.USER),
        )
        assertEquals(
            listOf("summary one", "middle", "new"),
            applyNearestContextCompact(withoutNewest).map { it.text },
        )
    }

    @Test
    fun logicalSplitMergesSameRolesAndKeepsToolRoundAtomic() {
        val history = listOf(
            message("u0", "one", Participant.USER),
            message("u1", "two", Participant.USER),
            message("a0", "answer", Participant.MODEL),
            message("tool_call", "", Participant.MODEL),
            message("result_call", "result", Participant.USER),
            message("a1", "continuation", Participant.MODEL),
        )
        val split = splitLogicalContext(history, 1)
        assertEquals(2, split.logicalMessageCount)
        assertEquals(listOf("a0", "tool_call", "result_call", "a1"), split.suffix.map { it.id })
        assertEquals(listOf("u0", "u1"), split.prefix.map { it.id })
    }

    @Test
    fun logicalSplitWithLargeRetentionLeavesNoPrefix() {
        val history = listOf(
            message("u0", "one", Participant.USER),
            message("a0", "answer", Participant.MODEL),
        )
        val split = splitLogicalContext(history, 2)
        assertTrue(split.prefix.isEmpty())
        assertEquals(history, split.suffix)
    }

    @Test
    fun failedCompactNeverBecomesProviderContextOrABoundary() {
        val failed = message("compact_failed", "failed summary", Participant.MODEL).copy(
            status = MessageStatus.ERROR,
        )
        val history = listOf(
            message("u0", "old", Participant.USER),
            failed,
            message("u1", "new", Participant.USER),
        )

        assertEquals(listOf("u0", "u1"), applyNearestContextCompact(history).map { it.id })
        assertTrue(!contextWindowUsage(history, 4_096).hasCompactBoundary)
    }

    @Test
    fun failedCompactTextNeverLeaksIntoAnOrdinaryUserTurn() {
        val history = listOf(
            message("u0", "old", Participant.USER),
            message("compact_failed", "private partial summary", Participant.MODEL).copy(
                status = MessageStatus.ERROR,
            ),
            message("u1", "ordinary follow-up", Participant.USER),
        )

        val canonical = canonicalContextMessages(history)
        val prepared = prepareMessages(history, contextTokenBudget = 4_096)

        assertEquals(listOf("u0", "u1"), canonical.map { it.id })
        assertEquals(listOf("old", "ordinary follow-up"), canonical.map { it.text })
        assertEquals(canonical, prepared)
        assertTrue(canonical.none { it.text.contains("private partial summary") })
        assertTrue(prepared.none { it.text.contains("<context_summary>") })
        assertTrue(prepared.none { it.text.contains("Please continue.") })
    }

    @Test
    fun stoppedCompactNeverBecomesProviderContextOrABoundary() {
        val stopped = message("compact_stopped", "partial summary", Participant.MODEL).copy(
            status = MessageStatus.STOPPED,
        )
        val history = listOf(
            message("u0", "old", Participant.USER),
            stopped,
            message("u1", "new", Participant.USER),
        )

        assertEquals(listOf("u0", "u1"), applyNearestContextCompact(history).map { it.id })
        assertTrue(!contextWindowUsage(history, 4_096).hasCompactBoundary)
    }

    @Test
    fun failedCompactIsNotMarkedAsRetainedProviderContext() {
        val failed = message("compact_failed", "partial", Participant.MODEL).copy(
            status = MessageStatus.ERROR,
        )
        val history = listOf(
            message("u0", "old", Participant.USER),
            failed,
            message("u1", "new", Participant.USER),
        )

        assertEquals(
            linkedSetOf("u0", "u1"),
            contextWindowRetainedMessageIds(history, tokenBudget = 4_096),
        )
    }

    @Test
    fun physicalRetentionCountsEveryOrdinaryMessage() {
        val history = listOf(
            message("u0", "one", Participant.USER),
            message("u1", "two", Participant.USER),
            message("a0", "answer", Participant.MODEL),
        )

        val split = splitContextForCompactRetention(history, retainMessages = 2)

        assertEquals(listOf("u0"), split.prefix.map { it.id })
        assertEquals(listOf("u1", "a0"), split.retained.map { it.id })
        assertEquals(2, split.retainedMessageCount)
    }

    @Test
    fun physicalRetentionNeverSplitsToolRound() {
        val history = listOf(
            message("u0", "old", Participant.USER),
            message("tool_call", "", Participant.MODEL),
            message("result_one", "one", Participant.USER),
            message("result_two", "two", Participant.USER),
            message("a0", "answer", Participant.MODEL),
        )

        val split = splitContextForCompactRetention(history, retainMessages = 2)

        assertEquals(listOf("u0"), split.prefix.map { it.id })
        assertEquals(
            listOf("tool_call", "result_one", "result_two", "a0"),
            split.retained.map { it.id },
        )
        assertEquals(4, split.retainedMessageCount)
    }

    @Test
    fun noCompactLeavesHistoryUntouched() {
        val history = listOf(message("u0", "old", Participant.USER))
        assertTrue(applyNearestContextCompact(history) === history)
    }

    @Test
    fun contextUsageSharesCanonicalRoleAndToolRoundAccounting() {
        val history = listOf(
            message("u0", "one", Participant.USER),
            message("u1", "two", Participant.USER),
            message("a0", "answer", Participant.MODEL),
            message("tool_call", "", Participant.MODEL).copy(
                segments = listOf(
                    com.newoether.agora.model.MessageSegment(
                        type = "tool",
                        toolName = "test_tool",
                        toolArgs = "{}",
                        toolCallId = "call-1",
                    )
                )
            ),
            message("result_call", "result", Participant.USER).copy(
                segments = listOf(
                    com.newoether.agora.model.MessageSegment(
                        type = "tool",
                        toolName = "test_tool",
                        toolArgs = "{}",
                        toolResult = "result",
                        toolCallId = "call-1",
                    )
                )
            ),
            message("a1", "continuation", Participant.MODEL),
        )

        val usage = contextWindowUsage(history, tokenBudget = 4_096)

        assertEquals(2, usage.logicalMessageCount)
        assertEquals(4_096, usage.tokenBudget)
        assertTrue(usage.estimatedTokenCount > 0)
        assertEquals(
            usage.estimatedTokenCount.toFloat() / usage.tokenBudget,
            usage.progress,
        )
        assertTrue(!usage.hasCompactBoundary)
    }

    @Test
    fun retainedContextIdsMatchProviderUserLedSuffix() {
        val history = listOf(
            message("u0", "one", Participant.USER),
            message("u1", "two", Participant.USER),
            message("a0", "answer", Participant.MODEL),
            message("u2", "latest", Participant.USER),
        )

        val retained = contextWindowRetainedMessageIds(history, tokenBudget = 20)

        assertEquals(linkedSetOf("u2"), retained)
    }

    @Test
    fun contextUsageStartsAtNearestCompactBoundary() {
        val history = listOf(
            message("u0", "old", Participant.USER),
            message("compact_boundary", "summary", Participant.MODEL),
            message("u1", "new", Participant.USER),
        )

        val usage = contextWindowUsage(history, tokenBudget = 4_096)

        assertEquals(1, usage.logicalMessageCount)
        assertTrue(usage.hasCompactBoundary)
    }

    @Test
    fun retainedContextIdsKeepCompactBoundaryAndVerbatimSuffixVisible() {
        val history = listOf(
            message("u0", "old", Participant.USER),
            message("compact_boundary", "summary", Participant.MODEL),
            message("u1", "new", Participant.USER),
            message("a1", "answer", Participant.MODEL),
        )

        val retained = contextWindowRetainedMessageIds(history, tokenBudget = 4_096)

        assertEquals(
            linkedSetOf("compact_boundary", "u1", "a1"),
            retained,
        )
    }

    @Test
    fun selectedPathExpansionIncludesEveryParallelToolResultExactlyOnce() {
        val user = message("u", "start", Participant.USER).copy(runId = "run", runSequence = 0)
        val model = message("m", "", Participant.MODEL).copy(
            parentId = "u",
            runId = "run",
            runSequence = 1,
        )
        val tool = message("tool_round", "", Participant.MODEL).copy(
            parentId = "m",
            runId = "run",
            runSequence = 2,
        )
        val resultOne = message("result_one", "one", Participant.USER).copy(
            parentId = "tool_round",
            runId = "run",
            runSequence = 3,
        )
        val resultTwo = message("result_two", "two", Participant.USER).copy(
            parentId = "tool_round",
            runId = "run",
            runSequence = 4,
        )

        assertEquals(
            listOf("u", "tool_round", "result_one", "result_two", "m"),
            expandSelectedToolProtocolRows(
                selectedPath = listOf(user, model, tool, resultTwo),
                allMessages = listOf(user, model, tool, resultOne, resultTwo),
            ).map { it.id },
        )
    }
}
