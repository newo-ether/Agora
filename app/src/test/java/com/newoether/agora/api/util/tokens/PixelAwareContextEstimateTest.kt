package com.newoether.agora.api.util.tokens

import com.newoether.agora.api.util.contextWindowUsage
import com.newoether.agora.api.util.imageArtifactOccurrences
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.projectGenerationInputMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Accounting consumes each transmitted artifact, never source-byte or first-page metadata. */
class PixelAwareContextEstimateTest {
    private val text = "look at this"

    private fun artifact(path: String, width: Int?, height: Int?, bytes: Long = 100L) = ImageArtifact(
        path = path, mimeType = "image/jpeg", sizeBytes = bytes, width = width, height = height,
    )

    private fun message(artifacts: List<ImageArtifact>, images: List<String> = artifacts.map { it.path }) =
        ChatMessage(id = "u1", text = text, participant = Participant.USER, images = images,
            attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
                type = "pdf", imageIndex = 0, pageCount = artifacts.size, imageArtifacts = artifacts,
            ))))

    private fun estimator(model: String = "Gateway:claude-sonnet-4-5") =
        CostModelContextEstimator(ContextCostModels.forModel(model))

    @Test
    fun mixedPagesAndFramesKeepTheirOwnDimensionsWhenReordered() {
        val page = artifact("page.jpg", 1_000, 1_000)
        val frame = artifact("frame.jpg", 200, 200)
        val input = message(listOf(frame, page))
        assertEquals(listOf(64L, 1_296L), estimator().imageEstimates(listOf(input)).map { it.tokens })
        assertTrue(estimator().imageEstimates(listOf(input)).all { it.basis == ImageEstimateBasis.DOCUMENTED })
    }

    @Test
    fun repeatedPathsAreChargedForEveryVisibleOccurrence() {
        val page = artifact("page.jpg", 1_000, 1_000)
        val input = message(listOf(page), listOf(page.path, page.path))
        assertEquals(listOf(1_296L, 1_296L), estimator().imageEstimates(listOf(input)).map { it.tokens })
        assertEquals(SafetyMargin.Default.apply(EnvelopeCost.Default.perMessage +
            ContextCostModels.forModel("Gateway:claude-sonnet-4-5").text.count(text) + 2_592L),
            estimator().estimate(listOf(input)))
    }

    @Test
    fun encodedLengthAndLegacyItemDimensionsCannotReplaceArtifactDimensions() {
        val page = artifact("page.jpg", 200, 200)
        val input = message(listOf(page))
        val alteredSource = input.copy(attachmentMeta = AttachmentMeta(listOf(AttachmentItem(
            type = "pdf", imageIndex = 0, pageCount = Int.MAX_VALUE,
            pixelWidth = 10_000, pixelHeight = 10_000, fileSize = Long.MAX_VALUE,
            imageArtifacts = listOf(page.copy(sizeBytes = Long.MAX_VALUE)),
        ))))
        assertEquals(estimator().estimate(listOf(input)), estimator().estimate(listOf(alteredSource)))
        assertEquals(64L, estimator().imageEstimates(listOf(alteredSource)).single().tokens)
    }

    @Test
    fun absentDimensionsStayExplicitUntilOwnerHydrationMeasuresReadableBytes() {
        val input = message(emptyList(), listOf("legacy.jpg"))
        val usage = contextWindowUsage(listOf(input), 32_768,
            costs = ContextCostModels.forModel("Gateway:claude-sonnet-4-5"))
        assertEquals(ImageEstimateBasis.MISSING_DIMENSIONS, usage.imageEstimates.single().basis)
        assertTrue(usage.hasMissingImageDimensions)
        assertFalse(usage.hasUnavailableImages)
    }

    @Test
    fun directArtifactMapIgnoresStaleEntriesAndSkipsWrongPathDescriptors() {
        val visible = artifact("visible.jpg", 200, 200)
        val stale = artifact("stale.jpg", 1_000, 1_000)
        val input = message(emptyList(), listOf(visible.path)).copy(
            imageArtifacts = mapOf(visible.path to visible, stale.path to stale))
        assertEquals(listOf(64L), estimator().imageEstimates(listOf(input)).map { it.tokens })
        assertTrue(estimator().imageEstimates(listOf(input.copy(
            imageArtifacts = mapOf(visible.path to stale)))).isEmpty())
    }

    @Test
    fun unavailableAndBlankImagesContributeZeroImageTokensWithoutLosingUiSlots() {
        val missing = artifact("", null, null, 0L).copy(unavailable = true)
        val known = artifact("lost.jpg", 1_000, 1_000).copy(unavailable = true)
        val input = message(listOf(missing, known), listOf("", known.path))
        val usage = contextWindowUsage(listOf(input), 32_768,
            costs = ContextCostModels.forModel("Gateway:claude-sonnet-4-5"))
        assertTrue(usage.imageEstimates.isEmpty())
        assertFalse(usage.hasMissingImageDimensions)
        assertFalse(usage.hasUnavailableImages)
        assertEquals(estimator().estimate(listOf(input.copy(images = emptyList()))),
            estimator().estimate(listOf(input)))
        assertEquals(listOf(missing, known), input.imageArtifactOccurrences())
    }

    @Test
    fun mixedRepeatedPathsChargeOnlyTheSentOccurrencesInEitherOrder() {
        val good = artifact("shared.jpg", 200, 200)
        for (badIndex in 0..2) {
            val artifacts = List(3) { good.copy(unavailable = it == badIndex) }
            val input = message(artifacts).copy(imageArtifacts = mapOf(good.path to artifacts.last()))
            val sent = projectGenerationInputMessages(listOf(input), true, null, null).single()
            assertEquals(List(2) { good.path }, sent.images)
            assertEquals(listOf(64L, 64L), estimator().imageEstimates(listOf(input)).map { it.tokens })
            assertEquals(estimator().estimate(listOf(sent)), estimator().estimate(listOf(input)))
            assertEquals(artifacts, input.imageArtifactOccurrences())
        }
    }

    @Test
    fun blankPdfPagesKeepTheirOwnDisplayDimensionsAndHaveNoImageCost() {
        val small = artifact("", 200, 200, 0L).copy(unavailable = true)
        val large = small.copy(width = 1_000, height = 1_000)
        val input = message(listOf(small, large)).copy(imageArtifacts = mapOf("" to large))
        assertEquals(listOf(small, large), input.imageArtifactOccurrences())
        assertTrue(estimator().imageEstimates(listOf(input)).isEmpty())
    }

    @Test
    fun identicalArtifactsFollowTheActualModelIdentityAcrossTransports() {
        val input = message(listOf(artifact("shot.jpg", 1_000, 1_000)))
        assertEquals(1_296L, estimator("OpenAI:claude-sonnet-4-5").imageEstimates(listOf(input)).single().tokens)
        assertEquals(765L, estimator("Gateway:gpt-4o").imageEstimates(listOf(input)).single().tokens)
        assertEquals(602L, estimator("OpenAI:deepseek-flash").imageEstimates(listOf(input)).single().tokens)
        assertEquals(1_120L, estimator("Gateway:gemini-3-pro").imageEstimates(listOf(input)).single().tokens)
        assertEquals(ImageEstimateBasis.HEURISTIC,
            estimator("OpenAI:private-model").imageEstimates(listOf(input)).single().basis)
    }
}
