package com.newoether.agora.viewmodel

import com.newoether.agora.api.LOCAL_CONTEXT_CAPACITY_ERROR_CODE
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.model.citationRecords
import com.newoether.agora.model.toMessageSegment
import com.newoether.agora.ui.chat.message.assistantErrorContent
import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent

class UiMessageProjectionTest {
    @Test
    fun branchMutationProjectionRecoversDurableAnswerMissingFromSegments() {
        val segments = listOf(
            MessageSegment(
                type = "thought",
                content = "reasoning",
                durationMs = 42L,
            ),
            MessageSegment(
                type = "tool",
                toolName = "shell",
                toolArgs = """{"command":"pwd"}""",
                toolResult = "workspace",
                toolDisplayName = "Run shell",
                toolResultText = "workspace",
                toolStructuredResult = """{"path":"workspace"}""",
            ),
        )
        val entity = messageEntity(
            id = "assistant",
            text = "answer",
            toolCallJson = Json.encodeToString(segments),
        )

        val projected = entity.toUiChatMessage { value -> "formatted:$value" }

        assertEquals("formatted:answer", projected.text)
        assertEquals(segments + MessageSegment("answer", "formatted:answer"), projected.segments)
        assertEquals("shell", projected.toolCall?.toolName)
        assertEquals("""{"command":"pwd"}""", projected.toolCall?.arguments)
        assertEquals("formatted:workspace", projected.toolCall?.result)
        assertEquals("Run shell", projected.toolCall?.displayName)
        assertEquals("workspace", projected.toolCall?.resultText)
        assertEquals("""{"path":"workspace"}""", projected.toolCall?.structuredResult)
    }

    @Test
    fun roomHistoryReloadPreservesCitationSegments() {
        val answer = "Claim"
        val citation = requireNotNull(
            CitationPolicy.create(
                provider = "test",
                kind = "web",
                title = "Source",
                url = "https://example.com/source",
                anchors = listOf(CitationAnchor(0, answer.length, answer)),
                answerText = answer,
            ),
        )
        val segment = citation.toMessageSegment()
        val entity = messageEntity(
            id = "assistant",
            text = answer,
            toolCallJson = Json.encodeToString(listOf(segment)),
        )

        val projected = entity.toUiChatMessage { it }

        assertEquals(listOf(MessageSegment("answer", answer), segment), projected.segments)
        assertEquals(citation, projected.citationRecords().single())
    }

    @Test
    fun durableAnswerSurvivesLegacyThoughtOnlyFallback() {
        val projected = messageEntity(
            id = "assistant",
            text = "durable answer",
            thoughts = "reasoning",
            toolCallJson = null,
        ).toUiChatMessage { it }

        assertEquals(listOf("thought", "answer"), projected.segments?.map { it.type })
        assertEquals("durable answer", projected.segments?.last()?.content)
    }

    @Test
    fun structuredAnswerPreventsDurableTextDuplication() {
        val answer = MessageSegment(type = "answer", content = "structured")
        val projected = messageEntity(
            id = "assistant",
            text = "durable mirror",
            toolCallJson = Json.encodeToString(listOf(answer)),
        ).toUiChatMessage { it }

        assertEquals(listOf(answer), projected.segments)
    }

    @Test
    fun syntheticProtocolRowsRemainStructuralOnly() {
        val entity = messageEntity(
            id = Constants.TOOL_MSG_PREFIX + "call",
            text = "large provider payload",
            toolCallJson = Json.encodeToString(
                listOf(MessageSegment(type = "thought", content = "hidden"))
            ),
        )

        val projected = entity.toUiChatMessage { value -> "formatted:$value" }

        assertEquals("", projected.text)
        assertEquals(emptyList<String>(), projected.images)
        assertNull(projected.segments)
        assertNull(projected.toolCall)
    }

    @Test
    fun stoppedModelProjectionStopsLiveToolsAndIgnoresUnknownFields() {
        val raw =
            """[{"type":"tool","toolName":"shell","toolState":"running","future":{"v":1}},""" +
                """{"type":"tool","toolName":"background","toolState":"background_running","futureFlag":true}]"""
        val projected = messageEntity(
            id = "stopped-assistant",
            text = "",
            toolCallJson = raw,
            status = MessageStatus.STOPPED,
        ).toUiChatMessage { it }
        assertEquals(
            listOf(ToolExecutionStates.STOPPED, ToolExecutionStates.BACKGROUND_RUNNING),
            projected.segments?.map { it.toolState },
        )
    }
    @Test
    fun malformedPersistedSegmentsFailClosed() {
        val projected = messageEntity(
            id = "malformed-assistant",
            text = "partial",
            toolCallJson = "{not-json",
            status = MessageStatus.STOPPED,
        ).toUiChatMessage { it }
        assertNull(projected.segments)
        assertNull(projected.toolCall)
    }
    private fun messageEntity(
        id: String,
        text: String,
        toolCallJson: String?,
        thoughts: String? = null,
        status: MessageStatus = MessageStatus.SUCCESS,
        modelName: String? = null,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        parentId = "user",
        text = text,
        images = listOf("image"),
        thoughts = thoughts,
        status = status,
        participant = Participant.MODEL,
        timestamp = 1L,
        modelName = modelName,
        toolCallJson = toolCallJson,
        runId = "run",
    )
    @Test
    fun localContextHelpRejectsOtherProvidersAndErrorsAfterProjection() {
        val cases = listOf(
            "Remote: model" to LOCAL_CONTEXT_CAPACITY_ERROR_CODE,
            "Ollama: model" to LOCAL_CONTEXT_CAPACITY_ERROR_CODE,
            "Local: model.gguf" to "different_error",
            "Local: model.gguf" to null,
        )

        cases.forEachIndexed { index, (modelName, errorCode) ->
            val segments = listOf(
                MessageSegment(
                    type = "error",
                    content = "Context capacity reached",
                    errorCode = errorCode,
                )
            )
            val projected = messageEntity(
                id = "assistant-$index",
                text = "",
                toolCallJson = MessagePersistenceGuard.encodeSegmentsBounded(segments),
                status = MessageStatus.ERROR,
                modelName = modelName,
            ).toUiChatMessage { it }

            assertEquals(
                false,
                assistantErrorContent(
                    projected,
                    projected.segments.orEmpty(),
                    "Failed to generate",
                )?.showLocalContextHelp,
            )
        }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun unchangedDurableRowsSkipHydrationBeforeTransform() = kotlinx.coroutines.test.runTest {
        val rows = kotlinx.coroutines.flow.MutableSharedFlow<MessageEntity?>(extraBufferCapacity = 4)
        val repository = io.mockk.mockk<com.newoether.agora.data.repository.ConversationRepository>()
        io.mockk.every { repository.observeMessage("assistant") } returns rows
        val owner = ConversationMessagePayloadHydration(repository, io.mockk.mockk(relaxed = true),
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val emitted = mutableListOf<String>()
        var transformed = 0
        backgroundScope.launch {
            owner.observeMessage("assistant") { transformed++; it }.collect { emitted += it?.text.orEmpty() }
        }
        runCurrent()
        val row = messageEntity("assistant", "answer", null)
        for (value in listOf(row, row.copy(), row.copy(text = "changed"), null, null)) {
            rows.emit(value)
            runCurrent()
        }
        assertEquals(2, transformed)
        assertEquals(listOf("answer", "changed", ""), emitted)
    }
    @Test
    fun persistedImplicitThinkingCloseIsRecoveredForUi() {
        val segments = listOf(
            MessageSegment(type = "thought", content = "reason</thinking>answer"),
            MessageSegment(type = "error", content = "truncated"),
        )
        val entity = messageEntity(
            id = "assistant",
            text = "",
            thoughts = "reason</thinking>answer",
            toolCallJson = Json.encodeToString(segments),
        )

        val projected = entity.toUiChatMessage { value -> "formatted:$value" }

        assertEquals("formatted:answer", projected.text)
        assertEquals("reason", projected.thoughts)
        assertEquals(
            listOf("thought", "answer", "error"),
            projected.segments?.map { it.type },
        )
    }

}
