package com.newoether.agora.api.util

import com.newoether.agora.R
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.viewmodel.normalizePersistedGenerationErrorText
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationStatusMessagesTest {
    @Test
    fun standaloneError_usesTheExactGrayErrorFormatterResultOnce() {
        val rawError = """{"balance":0.57,"error":{"message":"raw provider failure"}}"""
        val displayedError = "Raw provider failure"
        val context = mockk<Context>()
        val error = ChatMessage(
            id = "error",
            text = "partial answer",
            images = listOf("/private/image.png"),
            thoughts = "private reasoning",
            thoughtTitle = "Thinking",
            tokenCount = 42,
            tokenUsage = TokenUsage(totalTokenCount = 42, outputTokenCount = 42),
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
            thoughtTimeMs = 100,
            modelName = "model",
            toolCall = ToolCallData("tool", "{}", "result"),
            segments = listOf(
                MessageSegment(type = "answer", content = "partial answer"),
                MessageSegment(type = "error", content = rawError),
            ),
            attachmentMeta = AttachmentMeta(),
            retryText = "retry",
        )

        val projected = projectGenerationStatusesForApi(listOf(error)) { raw ->
            normalizePersistedGenerationErrorText(context, raw)
        }.single()

        assertEquals(Participant.MODEL, projected.participant)
        assertEquals(MessageStatus.SUCCESS, projected.status)
        assertEquals(
            "partial answer\n\n" +
                "<generation_interrupted reason=\"error\">\n$displayedError\n</generation_interrupted>",
            projected.text,
        )
        assertFalse(projected.text.contains(rawError))
        assertEquals(1, Regex(Regex.escape(displayedError)).findAll(projected.text).count())
        assertTrue(projected.images.isEmpty())
        assertEquals(null, projected.thoughts)
        assertEquals(null, projected.toolCall)
        assertEquals(null, projected.segments)
        assertEquals(null, projected.attachmentMeta)
        assertEquals(null, projected.tokenUsage)
    }

    @Test
    fun multiSentenceVisibleError_isInjectedIntoContextInFullOnce() {
        val visibleError =
            "Server_error [server_error]: An error occurred while processing your request. " +
                "You can retry your request, or contact the help center if the error persists. " +
                "Please include the request ID test-request-id in your message."
        val context = mockk<Context>()
        val error = ChatMessage(
            id = "error",
            text = "",
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
            segments = listOf(MessageSegment(type = "error", content = visibleError)),
        )

        val projected = projectGenerationStatusesForApi(listOf(error)) { raw ->
            normalizePersistedGenerationErrorText(context, raw)
        }.single()

        assertEquals(
            "<generation_interrupted reason=\"error\">\n$visibleError\n</generation_interrupted>",
            projected.text,
        )
        assertEquals(1, Regex(Regex.escape(visibleError)).findAll(projected.text).count())
    }

    @Test
    fun legacyNetworkWrapper_usesTheSameLocalizedGrayErrorText() {
        val context = mockk<Context>()
        val localized = "Connection closed."
        every { context.getString(R.string.generation_error_connection_closed) } returns localized
        val rawError = "Network error (0): connection closed"
        val error = ChatMessage(
            id = "error",
            text = "",
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
            segments = listOf(MessageSegment(type = "error", content = rawError)),
        )

        val projected = projectGenerationStatusesForApi(listOf(error)) { raw ->
            normalizePersistedGenerationErrorText(context, raw)
        }.single()

        assertTrue(projected.text.endsWith("$localized\n</generation_interrupted>"))
        assertFalse(projected.text.contains(rawError))
    }

    @Test
    fun malformedAndAlreadyFormattedErrors_preserveTheGrayFormatterResult() {
        val context = mockk<Context>()
        val details = listOf(
            "  {bad  " to "{bad",
            "INSUFFICIENT_BALANCE [billing_error]: Balance too low" to
                "INSUFFICIENT_BALANCE [billing_error]: Balance too low",
        )

        details.forEachIndexed { index, (raw, displayed) ->
            val projected = projectGenerationStatusesForApi(
                listOf(
                    ChatMessage(
                        id = "error-$index",
                        text = "",
                        status = MessageStatus.ERROR,
                        participant = Participant.MODEL,
                        segments = listOf(MessageSegment(type = "error", content = raw)),
                    ),
                ),
            ) { detail ->
                normalizePersistedGenerationErrorText(context, detail)
            }.single()

            assertTrue(projected.text.endsWith("$displayed\n</generation_interrupted>"))
        }
    }

    @Test
    fun stoppedStatus_staysAsSeparateAssistantBeforeFollowingUserMessage() {
        val stopped = ChatMessage(
            id = "stopped",
            text = "partial answer",
            status = MessageStatus.STOPPED,
            participant = Participant.MODEL,
        )
        val followUp = ChatMessage(
            id = "follow-up",
            text = "continue",
            participant = Participant.USER,
        )

        val projected = projectGenerationStatusesForApi(listOf(stopped, followUp)) { it }

        assertEquals(2, projected.size)
        assertEquals("stopped", projected[0].id)
        assertEquals(Participant.MODEL, projected[0].participant)
        assertEquals(MessageStatus.SUCCESS, projected[0].status)
        assertEquals(
            "partial answer\n\n" +
                "<generation_interrupted reason=\"stopped\" />",
            projected[0].text,
        )
        assertSame(followUp, projected[1])
    }

    @Test
    fun emptyStoppedTurn_becomesSubstantiveAssistantText() {
        val stopped = ChatMessage(
            id = "stopped",
            text = "",
            status = MessageStatus.STOPPED,
            participant = Participant.MODEL,
        )

        val projected = projectGenerationStatusesForApi(listOf(stopped)) { it }.single()

        assertEquals(Participant.MODEL, projected.participant)
        assertEquals(
            "<generation_interrupted reason=\"stopped\" />",
            projected.text,
        )
    }

    @Test
    fun legacyErrorParticipant_becomesAssistantWithStoredRawDetail() {
        val projected = projectGenerationStatusesForApi(
            listOf(
                ChatMessage(
                    id = "legacy-error",
                    text = "legacy failure",
                    status = MessageStatus.SUCCESS,
                    participant = Participant.ERROR,
                ),
            ),
        ) { it }.single()

        assertEquals(Participant.MODEL, projected.participant)
        assertEquals(MessageStatus.SUCCESS, projected.status)
        assertEquals(
            "<generation_interrupted reason=\"error\">\nlegacy failure\n</generation_interrupted>",
            projected.text,
        )
    }

    @Test
    fun terminalProjection_isIdempotentAndFormatsOnce() {
        val rawError = """{"error":{"message":"once"}}"""
        val displayedError = "Once"
        var formatterCalls = 0
        val formatter: (String) -> String = {
            formatterCalls += 1
            displayedError
        }
        val error = ChatMessage(
            id = "error",
            text = "",
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
            segments = listOf(MessageSegment(type = "error", content = rawError)),
        )

        val once = projectGenerationStatusesForApi(listOf(error), formatter)
        val twice = projectGenerationStatusesForApi(once, formatter)

        assertSame(once, twice)
        assertEquals(1, formatterCalls)
        assertEquals(1, Regex(Regex.escape(displayedError)).findAll(twice.single().text).count())
        assertFalse(twice.single().text.contains(rawError))
        assertEquals(
            1,
            Regex(Regex.escape("<generation_interrupted reason=\"error\">"))
                .findAll(twice.single().text)
                .count(),
        )
    }

    @Test
    fun toolProtocolStatus_isNeverRewritten() {
        val tool = ChatMessage(
            id = "tool_call",
            text = "",
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
        )

        val projected = projectGenerationStatusesForApi(listOf(tool)) { it }

        assertSame(tool, projected.single())
        assertFalse(projected.single().text.contains("<generation_interrupted"))
    }

    @Test
    fun successfulMessages_areReturnedUnchanged() {
        val success = ChatMessage(
            id = "success",
            text = "answer",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
        )
        val messages = listOf(success)

        assertSame(messages, projectGenerationStatusesForApi(messages) { it })
    }
}
