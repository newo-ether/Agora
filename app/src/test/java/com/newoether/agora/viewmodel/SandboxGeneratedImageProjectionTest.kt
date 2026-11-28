package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolCallData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxGeneratedImageProjectionTest {
    private val image = ImageArtifact("/private/sandbox-home/attachments/generated_id/image.png", "image/png", 123, 32, 32)
    private val descriptor = """{"type":"image_generation","status":"ok","images":[{"server":"Local Sandbox","path":"/home/agora/attachments/generated_id/image.png","mime_type":"image/png","size_bytes":123,"width":32,"height":32}]}"""
    private val call = ToolCallData(
        toolName = "image_generate", arguments = "{}", result = descriptor,
        toolCallId = "generated", resultImages = listOf(image),
    )

    @Test
    fun persistedAndCurrentRoundSendOnlyDescriptorsAndKeepDurablePreviews() {
        val round = GenerationToolRoundBuilder().build(
            previousMessageId = "user", conversationId = "owner", runId = "run",
            modelName = "model", providerName = "OpenAI", calls = listOf(call), completedSegments = emptyList(),
        )
        for (transcriptions in listOf(false, true)) {
            val projected = projectProviderMessages(round.entities, transcriptions)
            assertTrue(projected.all { it.images.isEmpty() && it.imageArtifacts.isEmpty() })
            assertTrue(projected.all { it.segments.orEmpty().all { segment -> segment.toolImages.isEmpty() } })
            assertTrue(projected.all { it.toolCall?.resultImages.orEmpty().isEmpty() })
            assertEquals(descriptor, projected.last().text)
            val request = projectGenerationInputMessages(projected, true, null, null)
            assertTrue(request.all { it.images.isEmpty() })
            assertTrue(request.any { it.text.contains("/home/agora/attachments/generated_id/image.png") })
            assertFalse(request.any { it.text.contains("/private/sandbox-home") })
        }
        val currentRequest = projectGenerationInputMessages(round.pathMessages, true, null, null)
        assertTrue(currentRequest.all { it.images.isEmpty() })
        assertTrue(currentRequest.all { it.toolCall?.resultImages.orEmpty().isEmpty() })
        assertEquals(listOf(image.path), round.entities.last().images)
        assertEquals(listOf(image), round.pathMessages.last().toolCall!!.resultImages)
    }

    @Test
    fun legacyGeneratedImagesAndExplicitFileReadsRetainTheirImageInputs() {
        val legacy = call.copy(result = """{"type":"image_generation","status":"ok"}""")
        for (retained in listOf(legacy, call.copy(toolName = "file_view_image"))) {
            val message = ChatMessage(
                id = "result_legacy", text = retained.result, images = listOf(image.path), toolCall = retained,
                participant = com.newoether.agora.model.Participant.USER,
            )
            assertEquals(message, projectSandboxGeneratedImages(message))
        }
    }

    @Test
    fun mixedToolImagesRemoveOnlyGeneratedOccurrencesEvenWhenPathsRepeat() {
        val generated = MessageSegment(
            type = "tool", toolName = call.toolName, toolArgs = "{}", toolResult = descriptor,
            toolCallId = "generated", toolImages = listOf(image),
        )
        val read = generated.copy(toolName = "file_view_image", toolCallId = "read", toolResult = "opened")
        val message = ChatMessage(
            id = "result_mixed", text = "results", images = listOf(image.path, image.path),
            participant = com.newoether.agora.model.Participant.USER,
            segments = listOf(generated, read),
        )
        val projected = projectSandboxGeneratedImages(message)
        assertEquals(listOf(image.path), projected.images)
        assertTrue(projected.segments!![0].toolImages.isEmpty())
        assertEquals(listOf(image), projected.segments!![1].toolImages)
        assertEquals(listOf(image), message.segments!![0].toolImages)
    }
}
