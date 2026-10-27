package com.newoether.agora.api.util

import com.newoether.agora.api.util.tokens.ContextCostModels
import com.newoether.agora.api.util.tokens.CostModelContextEstimator
import com.newoether.agora.api.util.tokens.ImageEstimateBasis
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.projectGenerationInputMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageArtifactProjectionTest {
    private fun artifact(path: String, edge: Int = 200) = ImageArtifact(
        path = path, mimeType = "image/png", sizeBytes = 100L, width = edge, height = edge,
    )

    private fun message(id: String, reference: String, artifact: ImageArtifact) = ChatMessage(
        id = id, text = "image", participant = Participant.USER,
        images = listOf(reference), imageArtifacts = mapOf(reference to artifact),
    )

    private fun estimates(message: ChatMessage) = CostModelContextEstimator(
        ContextCostModels.forModel("Gateway:claude-sonnet-4-5"),
    ).imageEstimates(listOf(message))

    @Test
    fun explicitFileAndContentAliasesRetainDimensionsAndCanonicalWireMetadata() {
        val image = artifact("/private/canonical.png")
        listOf("file:///private/canonical.png", "content://images/42").forEach { reference ->
            val input = message("user", reference, image)
            assertEquals(listOf(image), input.imageArtifactOccurrences())
            assertEquals(64L, estimates(input).single().tokens)
            assertEquals(ImageEstimateBasis.DOCUMENTED, estimates(input).single().basis)
            assertEquals(mapOf(reference to image, image.path to image), input.artifactsForImages())
            val indexed = input.copy(attachmentMeta = AttachmentMeta(listOf(
                AttachmentItem(type = "image", imageIndex = 0, imageArtifacts = listOf(image)),
            )))
            val projected = indexed.withProjectedImages(indexed.projectedImageOccurrences())
            assertEquals(listOf(image.path), projected.images)
            assertEquals(mapOf(image.path to image), projected.imageArtifacts)
            assertEquals(listOf(image), projected.imageArtifactOccurrences())
        }
    }

    @Test
    fun generatedAliasPrefixKeepsTheOriginalUserOccurrenceDimensionsAndIndices() {
        val generated = artifact("/private/generated.png")
        val attached = artifact("/private/attached.png", edge = 1_000)
        val assistant = message("assistant", "content://images/generated", generated).copy(
            participant = Participant.MODEL,
        )
        val user = message("user", attached.path, attached).copy(
            attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
                type = "image", imageIndex = 0, imageArtifacts = listOf(attached),
            ))),
        )
        val projected = projectAssistantImagesToLatestUserMessage(listOf(assistant, user), true)
        assertTrue(projected.first().images.isEmpty())
        val visual = projected.last()
        assertEquals(listOf(generated.path, attached.path), visual.images)
        assertEquals(listOf(generated, attached), visual.imageArtifactOccurrences())
        assertEquals(listOf(0, 1), visual.attachmentMeta!!.items.map { it.imageIndex })
        assertEquals(listOf(64L, 1_296L), estimates(visual).map { it.tokens })
    }

    @Test
    fun staleMeasurementsDoNotRejectAStablePathButWrongPathsStayUnavailable() {
        val image = artifact("/private/image.png")
        val stale = image.copy(width = 1_000, height = 300, mimeType = "image/jpeg", sizeBytes = 200L)
        val input = message("user", image.path, stale).copy(
            attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
                type = "image", imageIndex = 0, imageArtifacts = listOf(image),
            ))),
        )
        assertEquals(listOf(image), input.imageArtifactOccurrences())
        val mismatch = input.copy(attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
            type = "image", imageIndex = 0, imageArtifacts = listOf(image.copy(path = "/private/other.png")),
        ))))
        assertTrue(mismatch.imageArtifactOccurrences().single()!!.unavailable)
        val projected = projectGenerationInputMessages(listOf(mismatch), true, null, null).single()
        assertTrue(projected.images.isEmpty())
        assertEquals(mismatch.text, projected.text)
        assertEquals(listOf(image.path), mismatch.images)
    }

    @Test
    fun deduplicationUsesAvailablePathAndMeasurementsWithoutPolicyIdentity() {
        val image = artifact("/private/image.png")
        val differentSize = image.copy(width = 1_000)
        val occurrences = listOf(
            ProjectedImageOccurrence(image.path, image),
            ProjectedImageOccurrence(image.path, image.copy(policyVersion = 7)),
            ProjectedImageOccurrence(image.path, differentSize),
            ProjectedImageOccurrence("", null),
            ProjectedImageOccurrence(image.path, image.copy(unavailable = true)),
        ).distinctAvailableImages()
        assertEquals(listOf(image, differentSize), occurrences.map { it.artifact })
        val projected = message("user", image.path, image).withProjectedImages(occurrences)
        assertEquals(listOf(image, differentSize), projected.imageArtifactOccurrences())
    }

    @Test
    fun repeatedPathsKeepValidSlotsInEitherUnavailableOrder() {
        val image = artifact("/private/shared.png")
        for (badIndex in 0..1) {
            val artifacts = List(2) { image.copy(unavailable = it == badIndex) }
            val source = message("user", image.path, artifacts.last()).copy(
                images = List(2) { image.path },
                attachmentMeta = AttachmentMeta(artifacts.mapIndexed { index, value ->
                    AttachmentItem(type = "image", imageIndex = index,
                        imageArtifacts = listOf(value), unavailable = index == badIndex)
                }),
            )
            assertEquals(artifacts, source.imageArtifactOccurrences())
            assertFalse(source.artifactsForImages().getValue(image.path).unavailable)
            val projected = projectGenerationInputMessages(listOf(source), true, null, null).single()
            assertEquals(listOf(image.path), projected.images)
            assertEquals(listOf(image), projected.imageArtifactOccurrences())
            assertEquals(64L, estimates(projected).single().tokens)
            assertEquals(artifacts, source.imageArtifactOccurrences())
        }
    }

    @Test
    fun brokenPdfPageDoesNotHideItsReadableSiblingOrOrdinaryText() {
        val image = artifact("/private/shared.png")
        val bad = image.copy(unavailable = true)
        val source = message("user", image.path, bad).copy(
            images = List(2) { image.path },
            attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
                type = "pdf", imageIndex = 0, pageCount = 2, unavailable = true,
                imageArtifacts = listOf(bad, image),
            ))),
        )
        assertEquals(listOf(bad, image), source.imageArtifactOccurrences())
        val projected = projectGenerationInputMessages(listOf(source), true, null, null).single()
        assertEquals(source.text, projected.text)
        assertEquals(listOf(image), projected.imageArtifactOccurrences())
        assertEquals(2, source.images.size)
    }

    @Test
    fun toolBatchAliasesDeduplicateByCanonicalPathAndKeepSyntheticMetadata() {
        val image = artifact("/private/tool.png")
        val results = listOf("file:///private/tool.png", "content://images/tool").mapIndexed {
            index, reference ->
            message("result_$index", reference, image).copy(
                segments = listOf(MessageSegment(type = "tool", toolName = "file_view_image",
                    toolResult = "image", toolImages = listOf(image), toolCallId = "call-$index")),
            )
        }
        val visual = projectToolResultImagesToUserMessage(results, true).last()
        assertTrue(visual.id.startsWith("image_context_"))
        assertEquals(listOf(image.path), visual.images)
        assertEquals(listOf(image), visual.imageArtifactOccurrences())
    }
}
