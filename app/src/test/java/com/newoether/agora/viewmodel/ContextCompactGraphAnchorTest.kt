package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.api.util.tokens.ContextCostModels
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.api.util.projectGenerationStatusesForApi
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactGraphAnchorTest {
    @Test
    fun persistedCompactTextFreezesRecentMessagesWithoutOpaqueSignatures() {
        val tool = entity("tool_round", "user", Participant.MODEL, 2)
            .toUiChatMessage { it }
            .copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "shell",
                        toolArgs = "{\"command\":\"echo hi\"}",
                        signature = "must-not-leak",
                    ),
                ),
            )
        val result = entity("result_round", tool.id, Participant.USER, 3)
            .toUiChatMessage { it }
            .copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "shell",
                        toolResult = "hi",
                        signature = "must-not-leak",
                    ),
                ),
            )

        val text = buildPersistedCompactText(" summary ", listOf(tool, result))

        assertEquals(
            "summary\n\n--- Recent messages (verbatim) ---\n\n" +
                "[Assistant tool request: shell]\n{\"command\":\"echo hi\"}\n\n" +
                "[Tool result: shell]\nhi",
            text,
        )
        assertFalse(text.contains("must-not-leak"))
    }

    @Test
    fun compactOutputNormalizationStripsCompleteApiWrapper() {
        assertEquals(
            "summary",
            normalizeContextCompactOutput(
                "\n<context_summary>\r\nsummary\r\n</context_summary>\n",
            ),
        )
    }

    @Test
    fun compactOutputNormalizationSuppressesPartialWrapperMarkers() {
        assertEquals("", normalizeContextCompactOutput("<context_sum"))
        assertEquals(
            "summary",
            normalizeContextCompactOutput("<context_summary>\nsummary\n</context_"),
        )
    }

    @Test
    fun compactOutputNormalizationLeavesPlainSummaryUnchanged() {
        val summary = "  plain summary\nwith <xml> content  "
        assertEquals(summary, normalizeContextCompactOutput(summary))
    }

    @Test
    fun automaticSplitExcludesCurrentEmptyPlaceholderButKeepsDurableUserBoundary() {
        val oldUser = entity("old-user", null, Participant.USER, 1).copy(text = "old")
        val oldModel = entity("old-model", "old-user", Participant.MODEL, 2).copy(text = "answer")
        val currentUser = entity("current-user", "old-model", Participant.USER, 3).copy(text = "new")
        val placeholder = entity("placeholder", "current-user", Participant.MODEL, 4).copy(
            status = MessageStatus.SENDING,
        )

        val split = com.newoether.agora.api.util.splitContextForCompactRetention(
            compactSplitMessages(
                listOf(oldUser, oldModel, currentUser, placeholder).map {
                    it.toUiChatMessage { text -> text }
                }
            ),
            retainMessages = 1,
        )

        assertEquals(listOf("old-user", "old-model"), split.prefix.map { it.id })
        assertEquals(listOf("current-user"), split.retained.map { it.id })
    }

    @Test
    fun automaticThresholdUsesCeilingBoundsAndLongArithmetic() {
        assertEquals(1, automaticCompactTokenThreshold(1, 50))
        assertEquals(2_500, automaticCompactTokenThreshold(5_000, 49))
        assertEquals(4_500, automaticCompactTokenThreshold(5_000, 90))
        assertEquals(5_000, automaticCompactTokenThreshold(5_000, 100))
        assertEquals(5_000, automaticCompactTokenThreshold(5_000, 101))
        assertEquals(
            1_932_735_283,
            automaticCompactTokenThreshold(Int.MAX_VALUE, 90),
        )
    }

    @Test
    fun automaticEligibilityDoesNotExposeCompactingBeforeThreshold() {
        val path = listOf(
            entity("old-user", null, Participant.USER, 1).copy(text = "old"),
            entity("old-model", "old-user", Participant.MODEL, 2).copy(text = "answer"),
            entity("current-user", "old-model", Participant.USER, 3).copy(text = "new"),
        ).map { it.toUiChatMessage { text -> text } }

        assertFalse(
            automaticCompactNeeded(
                path = path,
                contextLimit = Int.MAX_VALUE,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityRequiresARealCompactablePrefix() {
        val path = listOf(
            entity("current-user", null, Participant.USER, 1).copy(text = "new"),
        ).map { it.toUiChatMessage { text -> text } }

        assertFalse(
            automaticCompactNeeded(
                path = path,
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityStartsAboveThresholdWhenOlderPrefixExists() {
        val path = listOf(
            entity("old-user", null, Participant.USER, 1).copy(text = "old context"),
            entity("old-model", "old-user", Participant.MODEL, 2).copy(text = "old answer"),
            entity("current-user", "old-model", Participant.USER, 3).copy(text = "new request"),
        ).map { it.toUiChatMessage { text -> text } }

        assertTrue(
            automaticCompactNeeded(
                path = path,
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityCountsFrozenUserTemplatesUsedByDispatch() {
        val path = listOf(
            entity("old-user", null, Participant.USER, 1).copy(text = "old"),
            entity("old-model", "old-user", Participant.MODEL, 2).copy(text = "answer"),
            entity("current-user", "old-model", Participant.USER, 3).copy(text = "new"),
        ).map { it.toUiChatMessage { text -> text } }
        val rawUsage = com.newoether.agora.api.util.contextWindowUsage(
            path,
            tokenBudget = Int.MAX_VALUE,
        ).estimatedTokenCount
        val threshold = rawUsage + 1

        assertFalse(
            automaticCompactNeeded(
                path = path,
                contextLimit = threshold,
                retainLogicalMessages = 1,
            ),
        )
        assertTrue(
            automaticCompactNeeded(
                path = path,
                contextLimit = threshold,
                retainLogicalMessages = 1,
                userPrepend = "large provider-visible prefix ".repeat(20),
            ),
        )
    }

    @Test
    fun automaticEligibilityIgnoresHistoryBeforeNearestCompact() {
        val path = listOf(
            entity("old-user", null, Participant.USER, 1).copy(text = "very old context"),
            entity("compact_boundary", "old-user", Participant.MODEL, 2).copy(text = "summary"),
            entity("current-user", "compact_boundary", Participant.USER, 3).copy(text = "new request"),
        ).map { it.toUiChatMessage { text -> text } }

        assertFalse(
            automaticCompactNeeded(
                path = path,
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityKeepsCompleteToolRoundInRetainedSuffix() {
        val oldUser = entity("old-user", null, Participant.USER, 1)
            .copy(text = "old context")
            .toUiChatMessage { text -> text }
        val tool = entity("tool_round", "old-user", Participant.MODEL, 2)
            .toUiChatMessage { text -> text }
            .copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "test_tool",
                        toolArgs = "{}",
                        toolCallId = "call-1",
                    )
                )
            )
        val result = entity("result_round", "tool_round", Participant.USER, 3)
            .copy(text = "result")
            .toUiChatMessage { text -> text }
            .copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = "test_tool",
                        toolArgs = "{}",
                        toolResult = "result",
                        toolCallId = "call-1",
                    )
                )
            )
        val continuation = entity("continuation", "result_round", Participant.MODEL, 4)
            .copy(text = "answer")
            .toUiChatMessage { text -> text }

        val compactable = compactSplitMessages(listOf(oldUser, tool, result, continuation))
        val split = com.newoether.agora.api.util.splitContextForCompactRetention(
            compactable,
            retainMessages = 2,
        )

        assertEquals(listOf("old-user"), split.prefix.map { it.id })
        assertEquals(
            listOf("tool_round", "result_round", "continuation"),
            split.retained.map { it.id },
        )
        assertTrue(
            automaticCompactNeeded(
                path = listOf(oldUser, tool, result, continuation),
                contextLimit = 1,
                retainLogicalMessages = 1,
            ),
        )
    }

    @Test
    fun automaticEligibilityUsesFormattedTerminalErrorForTokenAccounting() {
        val rawError = "raw failure"
        val displayedError = "displayed provider failure ".repeat(80)
        val oldUser = entity("old-user", null, Participant.USER, 1).copy(text = "old")
        val failedModel = entity("failed-model", oldUser.id, Participant.MODEL, 2).copy(
            text = "partial answer",
            status = MessageStatus.ERROR,
            toolCallJson = Json.encodeToString(
                listOf(
                    MessageSegment(type = "answer", content = "partial answer"),
                    MessageSegment(type = "error", content = rawError),
                ),
            ),
        )
        val currentUser = entity("current-user", failedModel.id, Participant.USER, 3)
            .copy(text = "continue")
        val entities = listOf(oldUser, failedModel, currentUser)
        val selectedChildren = mapOf<String?, String>(
            null to oldUser.id,
            oldUser.id to failedModel.id,
            failedModel.id to currentUser.id,
        )
        val providerPath = projectProviderMessages(entities, includeStoredTranscriptions = false)
        val rawUsage = contextWindowUsage(
            projectGenerationInputMessages(
                messages = projectGenerationStatusesForApi(providerPath) { it },
                includeImages = true,
                userPrepend = null,
                userPostpend = null,
            ),
            tokenBudget = Int.MAX_VALUE,
        ).estimatedTokenCount
        val displayedUsage = contextWindowUsage(
            projectGenerationInputMessages(
                messages = projectGenerationStatusesForApi(providerPath) { displayedError },
                includeImages = true,
                userPrepend = null,
                userPostpend = null,
            ),
            tokenBudget = Int.MAX_VALUE,
        ).estimatedTokenCount
        val threshold = rawUsage + 1
        var formatterCalls = 0

        assertTrue(displayedUsage >= threshold)
        assertFalse(
            automaticCompactNeeded(
                entities = entities,
                selectedChildren = selectedChildren,
                contextLimit = threshold,
                retainLogicalMessages = 1,
                generationErrorFormatter = { it },
            ),
        )
        assertTrue(
            automaticCompactNeeded(
                entities = entities,
                selectedChildren = selectedChildren,
                contextLimit = threshold,
                retainLogicalMessages = 1,
                generationErrorFormatter = {
                    formatterCalls += 1
                    displayedError
                },
            ),
        )
        assertEquals(1, formatterCalls)
    }

    @Test
    fun automaticEligibilityUsesOnlySelectedConversationBranch() {
        val selectedRoot = entity("selected-root", null, Participant.USER, 1)
            .copy(text = "selected")
        val unselectedRoot = entity("unselected-root", null, Participant.USER, 2)
            .copy(text = "unselected old context")
        val unselectedAnswer = entity("unselected-answer", "unselected-root", Participant.MODEL, 3)
            .copy(text = "unselected answer")
        val unselectedCurrent = entity("unselected-current", "unselected-answer", Participant.USER, 4)
            .copy(text = "unselected new request")

        assertFalse(
            automaticCompactNeeded(
                entities = listOf(
                    selectedRoot,
                    unselectedRoot,
                    unselectedAnswer,
                    unselectedCurrent,
                ),
                selectedChildren = mapOf(null to selectedRoot.id),
                contextLimit = 1,
                retainLogicalMessages = 1,
                generationErrorFormatter = { it },
            ),
        )
    }

    @Test
    fun automaticEligibilityUsesRemainingImagesAndBothTemplatesAfterTranscription() {
        val image = ImageArtifact("pending.jpg", "image/jpeg", 100L, 1_000, 1_000)
        val root = entity("old-user", null, Participant.USER, 1).copy(
            text = "inspect", images = listOf("described.jpg", image.path),
            attachmentMeta = Json.encodeToString(AttachmentMeta(listOf(
                AttachmentItem(type = "image", imageIndex = 0, transcription = "description"),
                AttachmentItem(type = "image", imageIndex = 1, imageArtifacts = listOf(image)),
            ))),
        )
        val answer = entity("answer", root.id, Participant.MODEL, 2).copy(text = "answer")
        val current = entity("current", answer.id, Participant.USER, 3).copy(text = "continue")
        val entities = listOf(root, answer, current)
        val selection = mapOf<String?, String>(null to root.id, root.id to answer.id, answer.id to current.id)
        val costs = ContextCostModels.forModel("OpenAI:claude-sonnet-4-5")
        val providerPath = projectProviderMessages(entities, true)
        val userTemplate = "user prefix ".repeat(10)
        val assistantTemplate = "assistant suffix ".repeat(10)
        val fixedCost = 37
        val dispatch = projectGenerationInputMessages(
            providerPath, true, userTemplate, null, assistantPostpend = assistantTemplate,
        )
        val usage = contextWindowUsage(dispatch, Int.MAX_VALUE, fixedCost, costs = costs)
        assertEquals(listOf(1_296L), usage.imageEstimates.map { it.tokens })
        val threshold = usage.estimatedTokenCount
        assertTrue(automaticCompactNeeded(
            entities = entities, selectedChildren = selection, contextLimit = threshold,
            retainLogicalMessages = 1, generationErrorFormatter = { it },
            includeStoredTranscriptions = true, fixedTokenCost = fixedCost,
            userPrepend = userTemplate, assistantPostpend = assistantTemplate, costs = costs,
        ))
        assertFalse(automaticCompactNeeded(
            entities = entities, selectedChildren = selection, contextLimit = threshold + 1,
            retainLogicalMessages = 1, generationErrorFormatter = { it },
            includeStoredTranscriptions = true, fixedTokenCost = fixedCost,
            userPrepend = userTemplate, assistantPostpend = assistantTemplate, costs = costs,
        ))
        assertFalse(automaticCompactNeeded(
            path = providerPath, contextLimit = threshold, retainLogicalMessages = 1,
            fixedTokenCost = fixedCost, userPrepend = userTemplate,
            assistantPostpend = assistantTemplate, includeImages = false, costs = costs,
        ))
    }

    private fun entity(
        id: String,
        parentId: String?,
        participant: Participant,
        sequence: Long,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = parentId,
        text = "",
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        runId = "run",
        runSequence = sequence,
    )
}
