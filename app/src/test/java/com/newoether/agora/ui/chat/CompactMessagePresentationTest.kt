package com.newoether.agora.ui.chat

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageGenerationBoundaryResolver
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.bottombar.contextUsageAtCapacity
import com.newoether.agora.ui.chat.message.ContextCompactPillPresentation
import com.newoether.agora.ui.chat.message.SegmentSheetBackAction
import com.newoether.agora.ui.chat.message.contextCompactPillPresentation
import com.newoether.agora.ui.chat.message.segmentSheetBackAction
import com.newoether.agora.ui.chat.message.usesVirtualizedSegmentDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactMessagePresentationTest {
    @Test
    fun compactIsAStandaloneItemAfterAnOrdinaryTurnEvenWhenItsSummaryIsBlank() {
        val compact = message("compact_boundary", Participant.MODEL).copy(text = "")

        val turns = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
                compact,
            ),
        )

        assertEquals(
            listOf(
                listOf("user", "assistant"),
                listOf("compact_boundary"),
            ),
            turns.map { turn -> turn.messages.map { it.id } },
        )
        assertEquals("compact_boundary", turns.last().key)
    }

    @Test
    fun tailAnchorAndPhysicalHolderRemainSeparateAfterCompact() {
        val ordinaryTurns = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
            ),
        )
        val turnsEndingWithCompact = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
                message("compact_boundary", Participant.MODEL),
            ),
        )
        val turnsWithAssistantAfterCompact = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
                message("compact_boundary", Participant.MODEL),
                message("later-assistant", Participant.MODEL),
                message("later-error", Participant.ERROR),
            ),
        )

        assertEquals("user", messageListTailAnchorKey(ordinaryTurns))
        assertEquals("user", messageListTailHolderKey(ordinaryTurns))
        assertEquals("compact_boundary", messageListTailAnchorKey(turnsEndingWithCompact))
        assertEquals("compact_boundary", messageListTailHolderKey(turnsEndingWithCompact))
        assertEquals("compact_boundary", messageListTailAnchorKey(turnsWithAssistantAfterCompact))
        assertEquals("later-error", messageListTailHolderKey(turnsWithAssistantAfterCompact))
        assertEquals(
            null,
            messageListTailAnchorKey(
                buildMessageListTurns(listOf(message("assistant", Participant.MODEL))),
            ),
        )
    }

    @Test
    fun postCompactTurnsConsumeTheAnchorRegionBeforeTrailingSpace() {
        val turns = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
                message("compact_boundary", Participant.MODEL),
                message("later-assistant", Participant.MODEL),
                message("later-error", Participant.ERROR),
            ),
        )
        val heights = mapOf(
            "compact_boundary" to 80,
            "later-assistant" to 180,
            "later-error" to 40,
        )
        val holderMinimum = calculateTailHolderMinHeightPx(
            turns = turns,
            semanticAnchorKey = messageListTailAnchorKey(turns),
            baseMinimumHeightPx = 680,
            messageHeights = heights,
        )

        assertEquals(420, holderMinimum)
        assertEquals(680, 80 + 180 + calculateTailLayoutHeightPx(holderMinimum, 40))
        assertEquals(
            0,
            calculateTailHolderMinHeightPx(
                turns = turns,
                semanticAnchorKey = messageListTailAnchorKey(turns),
                baseMinimumHeightPx = 680,
                messageHeights = heights +
                    ("compact_boundary" to 300) +
                    ("later-assistant" to 500),
            ),
        )
    }

    @Test
    fun leadingAndConsecutiveCompactsRemainStandaloneForEveryStatus() {
        val compacts = MessageStatus.entries.map { status ->
            message("compact_${status.name.lowercase()}", Participant.MODEL).copy(
                status = status,
                text = "",
            )
        }

        val turns = buildMessageListTurns(compacts)

        assertEquals(
            compacts.map { compact -> listOf(compact.id) },
            turns.map { turn -> turn.messages.map { message -> message.id } },
        )
        assertEquals(compacts.map { it.id }, turns.map { it.key })
    }

    @Test
    fun legacyUserCompactIsStandaloneAndFollowingMessagesCannotJoinIt() {
        val legacyCompact = message("compact_boundary", Participant.USER)

        val turns = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
                legacyCompact,
                message("later-assistant", Participant.MODEL),
            ),
        )

        assertFalse(MessageGenerationBoundaryResolver.isRealUser(legacyCompact))
        assertEquals(
            listOf(
                listOf("user", "assistant"),
                listOf("compact_boundary"),
                listOf("later-assistant"),
            ),
            turns.map { turn -> turn.messages.map { it.id } },
        )
    }

    @Test
    fun compactOwnsAnIndependentIndexAndCacheIdentity() {
        val cache = MessageListTurnCache()
        val user = message("user", Participant.USER)
        val assistant = message("assistant", Participant.MODEL)
        val compact = message("compact_boundary", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
            text = "",
        )
        val laterAssistant = message("later-assistant", Participant.MODEL)
        val before = cache.update(listOf(user, assistant, compact, laterAssistant))

        val after = cache.update(
            listOf(
                user,
                assistant,
                compact.copy(status = MessageStatus.SUCCESS, text = "summary"),
                laterAssistant,
            ),
        )

        assertEquals(listOf("user", "compact_boundary", "later-assistant"), after.map { it.key })
        assertEquals(0, messageListTurnIndex(after, "assistant"))
        assertEquals(1, messageListTurnIndex(after, "compact_boundary"))
        assertEquals(2, messageListTurnIndex(after, "later-assistant"))
        assertSame(before.first(), after.first())
        assertNotSame(before[1], after[1])
        assertSame(before.last(), after.last())
    }

    @Test
    fun historicalTerminalThoughtCanUseTheVirtualizedDetailLoader() {
        assertTrue(
            usesVirtualizedSegmentDetail(
                selectedSegmentCount = 1,
                segmentType = "thought",
                segmentContentIsBlank = false,
                isStreaming = false,
                hasFooter = false,
            ),
        )
        assertFalse(
            usesVirtualizedSegmentDetail(
                selectedSegmentCount = 1,
                segmentType = "thought",
                segmentContentIsBlank = false,
                isStreaming = false,
                hasFooter = true,
            ),
        )
    }

    @Test
    fun compactUsesItsGraphPositionImmediatelyAfterThePrecedingMessage() {
        val renderedIds = buildMessageListTurns(
            listOf(
                message("user", Participant.USER),
                message("assistant", Participant.MODEL),
                message("compact_boundary", Participant.MODEL).copy(text = ""),
                message("later-assistant", Participant.MODEL),
            ),
        ).flatMap { it.messages }.map { it.id }

        assertEquals(
            listOf("user", "assistant", "compact_boundary", "later-assistant"),
            renderedIds,
        )
    }

    @Test
    fun contextProgressUsesTheSameCapacityThresholdInBothPresentations() {
        assertFalse(contextUsageAtCapacity(99, 100))
        assertTrue(contextUsageAtCapacity(100, 100))
        assertTrue(contextUsageAtCapacity(101, 100))
        assertFalse(contextUsageAtCapacity(1, 0))
    }

    @Test
    fun bottomSheetBackDismissesTheListAndReturnsDetailsToTheList() {
        assertEquals(
            SegmentSheetBackAction.DISMISS,
            segmentSheetBackAction(true, true, detailPageIndex = -1),
        )
        assertEquals(
            SegmentSheetBackAction.SHOW_LIST,
            segmentSheetBackAction(true, true, detailPageIndex = 0),
        )
        assertEquals(
            SegmentSheetBackAction.DISMISS,
            segmentSheetBackAction(false, true, detailPageIndex = 0),
        )
    }

    @Test
    fun compactActionsAreDisabledForEveryBusyConversationState() {
        assertTrue(compactMessageActionsEnabled(false, false, false))
        assertFalse(compactMessageActionsEnabled(true, false, false))
        assertFalse(compactMessageActionsEnabled(false, true, false))
        assertFalse(compactMessageActionsEnabled(false, false, true))
    }

    @Test
    fun newlyCreatedCompactUsesTheSharedOneShotEntrance() {
        val compact = message("compact_boundary", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
        )

        assertTrue(
            shouldAnimateMessageLifecycleEntrance(
                message = compact,
                isKnown = false,
                isLoading = true,
                isStreaming = true,
                lastUserMessageId = null,
                requestedTargetMessageId = null,
            ),
        )
        assertFalse(
            shouldAnimateMessageLifecycleEntrance(
                message = compact,
                isKnown = true,
                isLoading = true,
                isStreaming = true,
                lastUserMessageId = null,
                requestedTargetMessageId = null,
            ),
        )
    }

    @Test
    fun compactGenerationDoesNotOwnTheOrdinaryAssistantStreamingTail() {
        val compact = message("compact_boundary", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
        )
        val assistant = message("assistant", Participant.MODEL).copy(
            status = MessageStatus.SENDING,
        )

        assertFalse(
            shouldShowStreamingTailIndicator(
                isLoading = true,
                isStopping = false,
                message = compact,
            ),
        )
        assertTrue(
            shouldShowStreamingTailIndicator(
                isLoading = true,
                isStopping = false,
                message = assistant,
            ),
        )
    }

    @Test
    fun stoppedCompactUsesDedicatedStoppedPillPresentation() {
        assertEquals(
            ContextCompactPillPresentation.STOPPED,
            contextCompactPillPresentation(MessageStatus.STOPPED),
        )
    }

    private fun message(id: String, participant: Participant) = ChatMessage(
        id = id,
        text = id,
        participant = participant,
    )
}
