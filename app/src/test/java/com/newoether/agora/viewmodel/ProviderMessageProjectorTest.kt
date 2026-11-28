package com.newoether.agora.viewmodel

import com.newoether.agora.api.util.ContextTokenEstimator
import com.newoether.agora.api.util.imageArtifactOccurrences
import com.newoether.agora.api.util.tokens.ContextCostModels
import com.newoether.agora.api.util.tokens.CostModelContextEstimator
import com.newoether.agora.api.util.tokens.ImageEstimateBasis
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.toMessageSegment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMessageProjectorTest {
    @Test
    fun `sandbox attachment projects path mime size and read instruction for every provider`() {
        val item = AttachmentItem(
            type = "file",
            fileName = "archive.apk",
            mimeType = "application/vnd.android.package-archive",
            storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME,
            sandboxPath = "/home/agora/attachments/id/archive.apk",
            fileSize = 4096L,
        )
        val entity = MessageEntity(
            id = "user",
            conversationId = "conversation",
            text = "inspect this",
            attachmentMeta = Json.encodeToString(AttachmentMeta(listOf(item))),
            status = MessageStatus.SUCCESS,
            participant = Participant.USER,
            timestamp = 1L,
            runId = "run",
        )

        val projected = projectProviderMessages(listOf(entity), false).single()

        assertEquals(
            "inspect this" + sandboxAttachmentInstruction(item),
            projected.text,
        )
        assertTrue(projected.text.contains(item.sandboxPath!!))
        assertTrue(projected.text.contains("4096 bytes"))
        assertTrue(projected.text.contains("file_read"))
    }

    @Test
    fun `stored transcription removes only its source image and keeps fresh tool images`() {
        val source = MessageEntity(
            id = "user",
            conversationId = "conversation",
            text = "describe",
            images = listOf("/private/source.png"),
            attachmentMeta = Json.encodeToString(
                AttachmentMeta(
                    items = listOf(
                        AttachmentItem(
                            type = "image",
                            imageIndex = 0,
                            transcription = "stored visual description",
                        ),
                    ),
                ),
            ),
            status = MessageStatus.SUCCESS,
            participant = Participant.USER,
            timestamp = 1L,
            runId = "run",
        )
        val toolResult = MessageEntity(
            id = "result_view_image",
            conversationId = "conversation",
            text = """{"ok":true}""",
            images = listOf("/private/tool-result.png"),
            status = MessageStatus.SUCCESS,
            participant = Participant.USER,
            timestamp = 2L,
            runId = "run",
        )

        val projected = projectProviderMessages(
            entities = listOf(source, toolResult),
            includeStoredTranscriptions = true,
        )

        assertEquals(emptyList<String>(), projected.first().images)
        assertEquals(listOf("/private/tool-result.png"), projected.last().images)
        assertTrue(projected.first().text.contains("stored visual description"))
    }

    @Test
    fun `partial transcription removes only the described attachment occurrences`() {
        val described = ImageArtifact("same.jpg", "image/jpeg", 100L, 200, 200)
        val frame = ImageArtifact("frame.jpg", "image/jpeg", 200L, 1_000, 1_000)
        val entity = MessageEntity(
            id = "user", conversationId = "conversation", text = "inspect",
            images = listOf(described.path, described.path, frame.path),
            attachmentMeta = Json.encodeToString(AttachmentMeta(listOf(
                AttachmentItem(type = "image", imageIndex = 0, transcription = "described",
                    imageArtifacts = listOf(described)),
                AttachmentItem(type = "image", imageIndex = 1,
                    imageArtifacts = listOf(described)),
                AttachmentItem(type = "video", imageIndex = 2, pageCount = 1,
                    imageArtifacts = listOf(frame)),
            ))),
            status = MessageStatus.SUCCESS, participant = Participant.USER,
            timestamp = 1L, runId = "run",
        )
        val projected = projectProviderMessages(listOf(entity), true).single()
        assertEquals(listOf(described.path, frame.path), projected.images)
        assertEquals(mapOf(described.path to described, frame.path to frame), projected.imageArtifacts)
        val items = projected.attachmentMeta!!.items
        assertNull(items[0].imageIndex)
        assertTrue(items[0].imageArtifacts.isEmpty())
        assertEquals("described", items[0].transcription)
        assertEquals(0, items[1].imageIndex)
        assertEquals(listOf(described), items[1].imageArtifacts)
        assertEquals(1, items[2].imageIndex)
        assertEquals(1, items[2].pageCount)
        assertEquals(listOf(frame), items[2].imageArtifacts)
        assertTrue(projected.text.contains("described"))
        assertEquals(entity.images, projectProviderMessages(listOf(entity), false).single().images)
    }

    @Test
    fun `transcribed pdf pages do not remove a following untranscribed image`() {
        val entity = MessageEntity(
            id = "user", conversationId = "conversation", text = "inspect",
            images = listOf("page1.jpg", "page2.jpg", "photo.jpg"),
            attachmentMeta = Json.encodeToString(AttachmentMeta(listOf(
                AttachmentItem(type = "pdf", imageIndex = 0, pageCount = 2,
                    transcription = "page descriptions"),
                AttachmentItem(type = "image", imageIndex = 2),
            ))),
            status = MessageStatus.SUCCESS, participant = Participant.USER,
            timestamp = 1L, runId = "run",
        )
        val projected = projectProviderMessages(listOf(entity), true).single()
        assertEquals(listOf("photo.jpg"), projected.images)
        assertNull(projected.attachmentMeta!!.items[0].imageIndex)
        assertEquals(0, projected.attachmentMeta!!.items[0].pageCount)
        assertEquals(0, projected.attachmentMeta!!.items[1].imageIndex)
    }

    @Test
    fun `unmapped transcription cannot discard unrelated images`() {
        val entity = MessageEntity(
            id = "user", conversationId = "conversation", text = "inspect",
            images = listOf("fresh.jpg"),
            attachmentMeta = Json.encodeToString(AttachmentMeta(listOf(
                AttachmentItem(type = "image", transcription = "legacy description"),
            ))),
            status = MessageStatus.SUCCESS, participant = Participant.USER,
            timestamp = 1L, runId = "run",
        )
        assertEquals(entity.images, projectProviderMessages(listOf(entity), true).single().images)
    }

    @Test
    fun `transcription remaps each remaining blank placeholder independently`() {
        val small = ImageArtifact("", "image/jpeg", 0L, 200, 200, unavailable = true)
        val large = small.copy(width = 1_000, height = 1_000)
        val entity = MessageEntity(
            id = "user", conversationId = "conversation", text = "inspect",
            images = listOf("described.jpg", "", ""),
            attachmentMeta = Json.encodeToString(AttachmentMeta(listOf(
                AttachmentItem(type = "image", imageIndex = 0, transcription = "description"),
                AttachmentItem(type = "pdf", imageIndex = 1, pageCount = 2,
                    imageArtifacts = listOf(small, large)),
            ))),
            status = MessageStatus.SUCCESS, participant = Participant.USER,
            timestamp = 1L, runId = "run",
        )
        val projected = projectProviderMessages(listOf(entity), true).single()
        assertEquals(listOf("", ""), projected.images)
        assertEquals(0, projected.attachmentMeta!!.items[1].imageIndex)
        assertEquals(listOf(small, large), projected.imageArtifactOccurrences())
        assertTrue(projected.imageArtifacts.isEmpty())
        assertTrue(CostModelContextEstimator(
            ContextCostModels.forModel("OpenAI:claude-sonnet-4-5"),
        ).imageEstimates(listOf(projected)).isEmpty())
    }

    @Test
    fun `partial artifact metadata cannot compress a later page into a missing slot`() {
        val second = ImageArtifact("page2.jpg", "image/jpeg", 100L, 1_000, 1_000)
        val entity = MessageEntity(
            id = "user", conversationId = "conversation", text = "inspect",
            images = listOf("described.jpg", "page1.jpg", second.path),
            attachmentMeta = Json.encodeToString(AttachmentMeta(listOf(
                AttachmentItem(type = "image", imageIndex = 0, transcription = "description"),
                AttachmentItem(type = "pdf", imageIndex = 1, pageCount = 2,
                    imageArtifacts = listOf(second)),
            ))),
            status = MessageStatus.SUCCESS, participant = Participant.USER,
            timestamp = 1L, runId = "run",
        )
        val projected = projectProviderMessages(listOf(entity), true).single()
        val artifacts = projected.attachmentMeta!!.items[1].imageArtifacts
        assertEquals(listOf("page1.jpg", "page2.jpg"), artifacts.map { it.path })
        assertNull(artifacts.first().width)
        assertEquals(second, artifacts.last())
        val estimates = CostModelContextEstimator(ContextCostModels.forModel("Gateway:claude-sonnet-4-5"))
            .imageEstimates(listOf(projected))
        assertEquals(ImageEstimateBasis.MISSING_DIMENSIONS, estimates.first().basis)
        assertEquals(1_296L, estimates.last().tokens)
    }

    @Test
    fun `complete tool descriptions replace only their mapped images and still reach context`() {
        val described = ImageArtifact("", "image/jpeg", 0L, 200, 200, unavailable = true)
        val pending = described.copy(width = 1_000, height = 1_000)
        val entity = MessageEntity(
            id = "result_images", conversationId = "conversation", text = "tool output",
            images = listOf("", ""),
            toolCallJson = Json.encodeToString(listOf(
                MessageSegment(type = "tool", toolName = "first", toolArgs = "{}", toolResult = "first",
                    toolCallId = "call-first", toolImages = listOf(described),
                    toolTranscription = "complete description"),
                MessageSegment(type = "tool", toolName = "second", toolArgs = "{}", toolResult = "second",
                    toolCallId = "call-second", toolImages = listOf(pending)),
            )),
            status = MessageStatus.SUCCESS, participant = Participant.USER,
            timestamp = 1L, runId = "run",
        )
        val projected = projectProviderMessages(listOf(entity), true)
        assertEquals(listOf(""), projected.single().images)
        assertEquals(listOf(pending), projected.single().imageArtifactOccurrences())
        val visual = projectGenerationInputMessages(projected, true, null, null).last()
        assertTrue(visual.images.isEmpty())
        assertTrue(visual.imageArtifactOccurrences().isEmpty())
        assertTrue(visual.text.contains("complete description"))
        assertEquals(listOf("", ""), projectProviderMessages(listOf(entity), false).single().images)
        val allDescribed = entity.copy(
            images = listOf(""),
            toolCallJson = Json.encodeToString(listOf(
                MessageSegment(type = "tool", toolName = "first", toolArgs = "{}", toolResult = "first",
                    toolCallId = "call-first", toolImages = listOf(described),
                    toolTranscription = "complete description"),
            )),
        )
        val textOnly = projectGenerationInputMessages(
            projectProviderMessages(listOf(allDescribed), true), true, null, null,
        ).last()
        assertTrue(textOnly.images.isEmpty())
        assertTrue(textOnly.text.contains("complete description"))
    }

    @Test
    fun `legacy multi image tool description cannot discard uncovered images`() {
        val first = ImageArtifact("first.jpg", "image/jpeg", 100L, 200, 200)
        val second = ImageArtifact("second.jpg", "image/jpeg", 100L, 1_000, 1_000)
        val entity = MessageEntity(
            id = "result_images", conversationId = "conversation", text = "tool output",
            images = listOf(first.path, second.path),
            toolCallJson = Json.encodeToString(listOf(MessageSegment(
                type = "tool", toolName = "view", toolArgs = "{}", toolResult = "images",
                toolCallId = "call-images", toolImages = listOf(first, second),
                toolTranscription = "description of the first image only",
            ))),
            status = MessageStatus.SUCCESS, participant = Participant.USER,
            timestamp = 1L, runId = "run",
        )
        val projected = projectProviderMessages(listOf(entity), true)
        assertEquals(entity.images, projected.single().images)
        assertEquals(listOf(first, second), projected.single().imageArtifactOccurrences())
        val visual = projectGenerationInputMessages(projected, true, null, null).last()
        assertEquals(entity.images, visual.images)
        assertEquals(listOf(first, second), visual.imageArtifactOccurrences())
        assertTrue(visual.text.contains("description of the first image only"))

        val legacy = entity.copy(toolCallJson = Json.encodeToString(listOf(MessageSegment(
            type = "tool", toolName = "view", toolArgs = "{}", toolResult = "images",
            toolCallId = "call-images", toolTranscription = "legacy first image description",
        ))))
        val fallback = projectProviderMessages(listOf(legacy), true)
        assertEquals(legacy.images, fallback.single().images)
        val fallbackVisual = projectGenerationInputMessages(fallback, true, null, null).last()
        assertEquals(legacy.images, fallbackVisual.images)
        assertTrue(fallbackVisual.text.contains("legacy first image description"))
        assertTrue(projectProviderMessages(listOf(legacy.copy(images = listOf(first.path))), true)
            .single().images.isEmpty())
    }

    @Test
    fun `provider history excludes citation metadata without changing the answer`() {
        val retained = MessageSegment(type = "answer", content = "answer")
        val citation = CitationRecord(
            sourceId = "citation_source",
            provider = "openai",
            kind = "url",
            title = "Private source",
            url = "https://example.com/source",
            providerSourceId = "turn0search0",
        ).toMessageSegment()
        val entity = MessageEntity(
            id = "model",
            conversationId = "conversation",
            text = "answer",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = 1L,
            toolCallJson = Json.encodeToString(listOf(retained, citation)),
            runId = "run",
        )

        val projected = projectProviderMessages(
            entities = listOf(entity),
            includeStoredTranscriptions = false,
        ).single()

        assertEquals("answer", projected.text)
        assertEquals(listOf(retained), projected.segments)
        assertNull(projected.toolCall)
    }
    @Test
    fun `provider history receives answer recovered from malformed thought segment`() {
        val segments = listOf(
            MessageSegment(type = "thought", content = "reason</thinking>answer"),
            MessageSegment(type = "error", content = "truncated"),
        )
        val entity = MessageEntity(
            id = "model",
            conversationId = "conversation",
            text = "",
            thoughts = "reason</thinking>answer",
            status = MessageStatus.ERROR,
            participant = Participant.MODEL,
            timestamp = 1L,
            toolCallJson = Json.encodeToString(segments),
            runId = "run",
        )

        val projected = projectProviderMessages(
            entities = listOf(entity),
            includeStoredTranscriptions = false,
        ).single()

        assertEquals("answer", projected.text)
        assertEquals("reason", projected.thoughts)
        assertEquals(
            listOf("thought", "answer", "error"),
            projected.segments?.map { it.type },
        )
    }

    @Test
    fun `file text and every media page remain visible to token accounting`() {
        val entity = MessageEntity(
            id = "user",
            conversationId = "conversation",
            text = "inspect attachments",
            images = listOf("page-1.jpg", "page-2.jpg", "video-frame.jpg"),
            attachmentMeta = Json.encodeToString(
                AttachmentMeta(
                    items = listOf(
                        AttachmentItem(
                            type = "file",
                            fileName = "notes.txt",
                            textContent = "important file content ".repeat(20),
                        ),
                        AttachmentItem(
                            type = "pdf",
                            fileName = "document.pdf",
                            imageIndex = 0,
                            pageCount = 2,
                        ),
                        AttachmentItem(
                            type = "video",
                            fileName = "clip.mp4",
                            imageIndex = 2,
                            pageCount = 1,
                        ),
                    ),
                ),
            ),
            status = MessageStatus.SUCCESS,
            participant = Participant.USER,
            timestamp = 1L,
            runId = "run",
        )

        val projected = projectProviderMessages(
            entities = listOf(entity),
            includeStoredTranscriptions = false,
        ).single()
        val withoutAttachments = projected.copy(
            text = "inspect attachments",
            images = emptyList(),
        )

        assertTrue(projected.text.contains("important file content"))
        assertEquals(3, projected.images.size)
        assertTrue(
            ContextTokenEstimator.estimate(listOf(projected)) >
                ContextTokenEstimator.estimate(listOf(withoutAttachments)),
        )
    }
}
