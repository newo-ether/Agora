package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationForkShareServiceTest {
    @Test
    fun `interactive answers share only the readable fallback without executable content`() {
        val answer = com.newoether.agora.model.InteractiveAnswer("Chart", "A chart", "A is twice B",
            html = "<script>secretExecutableCode()</script>")
        val output = formatShareText("Chat", listOf(message(
            id = "model", text = "", participant = Participant.MODEL, sequence = 1,
            segments = listOf(MessageSegment(type = "tool", toolName = "render_interactive_answer",
                toolState = com.newoether.agora.model.ToolExecutionStates.SUCCEEDED,
                toolStructuredResult = answer.encode())),
        )))
        assertTrue(output.contains("A is twice B"))
        assertFalse(output.contains("secretExecutableCode"))
        assertFalse(output.contains("render_interactive_answer"))
    }

    @Test
    fun `share text excludes thinking and tool payloads while keeping public content`() {
        val messages = listOf(
            message(
                id = "user",
                text = "Tell me why",
                participant = Participant.USER,
                sequence = 0,
                attachmentMeta = AttachmentMeta(
                    listOf(AttachmentItem(type = "file", fileName = "notes.pdf")),
                ),
            ),
            message(
                id = "structured-model",
                text = "Fallback answer",
                participant = Participant.MODEL,
                sequence = 1,
                segments = listOf(
                    MessageSegment(type = "thought", content = "Private structured reasoning"),
                    MessageSegment(
                        type = "tool",
                        toolName = "web_search",
                        toolArgs = """{"query":"private query"}""",
                        toolResult = "Private tool result",
                    ),
                    MessageSegment(type = "answer", content = "Public structured answer"),
                    MessageSegment(type = "transcription", content = "Public transcription"),
                ),
            ),
            message(
                id = "legacy-model",
                text = "Public legacy answer",
                participant = Participant.MODEL,
                sequence = 2,
                thoughts = "Private legacy reasoning",
            ),
            message("error", "Public error", Participant.ERROR, 3),
            message("tool_hidden", "Synthetic protocol payload", Participant.MODEL, 4),
        )

        val text = formatShareText("Explanation", messages)

        assertTrue(text.contains("## User\n\nTell me why"))
        assertTrue(text.contains("Attachments: notes.pdf"))
        assertTrue(text.contains("## Assistant\n\nPublic structured answer"))
        assertTrue(text.contains("## Transcription\n\nPublic transcription"))
        assertTrue(text.contains("## Assistant\n\nPublic legacy answer"))
        assertTrue(text.contains("## Error\n\nPublic error"))
        assertFalse(text.contains("## Thinking"))
        assertFalse(text.contains("Private structured reasoning"))
        assertFalse(text.contains("Private legacy reasoning"))
        assertFalse(text.contains("## Tool"))
        assertFalse(text.contains("web_search"))
        assertFalse(text.contains("private query"))
        assertFalse(text.contains("Private tool result"))
        assertFalse(text.contains("Synthetic protocol payload"))
    }

    private fun message(
        id: String,
        text: String,
        participant: Participant,
        sequence: Long,
        thoughts: String? = null,
        segments: List<MessageSegment>? = null,
        attachmentMeta: AttachmentMeta? = null,
    ) = MessageEntity(
        id = id,
        conversationId = "conversation",
        text = text,
        thoughts = thoughts,
        status = MessageStatus.SUCCESS,
        participant = participant,
        timestamp = sequence,
        toolCallJson = segments?.let { Json.encodeToString(it) },
        attachmentMeta = attachmentMeta?.let { Json.encodeToString(it) },
        runId = "run",
        runSequence = sequence,
    )
}
