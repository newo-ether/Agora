package com.newoether.agora.viewmodel

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.api.util.imageArtifactOccurrences
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.SelectedAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePayloadBuilderTest {
    private val builder = MessagePayloadBuilder()

    @Test
    fun mixedReadyArtifactsPreserveFrozenComposerOrderAndExactIndices() {
        val attachments = listOf(
            attachment("image-a", "image", localPath = "/ready/image-a.jpg"),
            attachment(
                "file",
                "file",
                localPath = "/ready/notes.txt",
                preparedText = "prepared text",
            ),
            attachment(
                "video",
                "video",
                localPath = "/ready/video.mp4",
                processedFrames = listOf("/ready/frame-1.jpg", "/ready/frame-2.jpg"),
            ),
            attachment(
                "pdf",
                "pdf",
                localPath = "/ready/document.pdf",
                preRenderedPaths = listOf("/ready/page-1.jpg", "/ready/page-2.jpg"),
            ),
            attachment("image-b", "image", localPath = "/ready/image-b.jpg"),
            attachment(
                "failed",
                "image",
                localPath = "/must-not-send.jpg",
                state = AttachmentImportState.FAILED,
            ),
            attachment(
                "unavailable",
                "image",
                localPath = "/must-not-send-either.jpg",
                unavailable = true,
            ),
        )

        val payload = builder.buildComposerPayload(attachments)
        val items = requireNotNull(payload.attachmentMeta).items

        assertEquals(
            listOf("/ready/image-a.jpg", "/ready/frame-1.jpg", "/ready/frame-2.jpg",
                "/ready/page-1.jpg", "/ready/page-2.jpg", "/ready/image-b.jpg", "/must-not-send-either.jpg"),
            payload.allImages,
        )
        assertEquals(listOf("image", "file", "video", "pdf", "image", "image"), items.map { it.type })
        assertEquals(listOf(0, null, 1, 3, 5, 6), items.map { it.imageIndex })
        assertEquals(listOf(null, null, 2, 2, null, null), items.map { it.pageCount })
        assertTrue(items.last().unavailable)
        assertEquals("prepared text", items[1].textContent)
    }

    @Test
    fun duplicateArtifactPathsKeepDistinctOccurrenceIndices() {
        val payload = builder.buildComposerPayload(
            listOf(
                attachment("first", "image", localPath = "/ready/duplicate.jpg"),
                attachment("second", "image", localPath = "/ready/duplicate.jpg"),
            ),
        )
        val items = requireNotNull(payload.attachmentMeta).items

        assertEquals(
            listOf("/ready/duplicate.jpg", "/ready/duplicate.jpg"),
            payload.allImages,
        )
        assertEquals(listOf(0, 1), items.map { it.imageIndex })
    }

    @Test
    fun incompleteReadyArtifactsAreDroppedWithoutPickerUriFallback() {
        val payload = builder.buildComposerPayload(
            listOf(
                attachment("image", "image"),
                attachment("video", "video", localPath = "/ready/video.mp4"),
                attachment("pdf", "pdf", localPath = "/ready/document.pdf"),
                attachment("file", "file", localPath = "/ready/file.txt"),
            ),
        )

        assertEquals(emptyList<String>(), payload.allImages)
        assertNull(payload.attachmentMeta)
    }

    @Test
    fun imageDescriptorsFollowFinalFrameAndPageOrderWithTheirOwnMeasuredFacts() {
        val still = artifact("/ready/still.png", 211, 97, "image/png", 101)
        val frameA = artifact("/ready/frame-a.jpg", 2048, 1152, "image/jpeg", 202)
        val frameB = artifact("/ready/frame-b.png", 901, 1601, "image/png", 303)
        val pageA = artifact("/ready/page-a.jpg", 1448, 2048, "image/jpeg", 404)
        val pageB = artifact("/ready/page-b.jpg", 2048, 1448, "image/jpeg", 505)
        val unused = artifact("/ready/unused.jpg", 1, 1, "image/jpeg", 1)
        val payload = builder.buildComposerPayload(
            listOf(
                attachment(
                    "image", "image", localPath = still.path,
                    imageArtifacts = listOf(unused, still),
                ),
                attachment(
                    "video", "video", localPath = "/ready/video.mp4",
                    processedFrames = listOf(frameA.path, frameB.path),
                    imageArtifacts = listOf(frameB, unused, frameA),
                ),
                attachment(
                    "pdf", "pdf", localPath = "/ready/document.pdf",
                    preRenderedPaths = listOf(pageB.path, pageA.path),
                    imageArtifacts = listOf(pageA, pageB),
                ),
            ),
        )
        val items = requireNotNull(payload.attachmentMeta).items

        assertEquals(listOf(still.path, frameA.path, frameB.path, pageB.path, pageA.path), payload.allImages)
        assertEquals(listOf(still), items[0].imageArtifacts)
        assertEquals(listOf(frameA, frameB), items[1].imageArtifacts)
        assertEquals(listOf(pageB, pageA), items[2].imageArtifacts)
    }

    @Test
    fun mixedReadyPagesAndFramesKeepBrokenUiSlotsAndSendTheirValidSiblings() {
        val good = artifact("/ready/shared.jpg", 200, 100, "image/jpeg", 20)
        val bad = good.copy(unavailable = true)
        for (type in listOf("pdf", "video")) for (badIndex in 0..1) {
            val records = List(2) { if (it == badIndex) bad else good }
            val mixed = attachment("mixed", type, localPath = "/ready/source.$type",
                processedFrames = List(2) { good.path }.takeIf { type == "video" },
                preRenderedPaths = List(2) { good.path }.takeIf { type == "pdf" },
                imageArtifacts = records, unavailable = true)
            assertTrue(mixed.hasCanonicalReadyArtifact())
            val payload = builder.buildComposerPayload(listOf(
                mixed, attachment("text", "file", localPath = "/ready/notes.txt", preparedText = "notes"),
            ))
            val items = payload.attachmentMeta!!.items
            assertEquals(List(2) { good.path }, payload.allImages)
            assertEquals(records, items.first().imageArtifacts)
            assertEquals("notes", items.last().textContent)
            val source = ChatMessage(id = "user", text = "ordinary text", participant = Participant.USER,
                images = payload.allImages, attachmentMeta = payload.attachmentMeta)
            val sent = projectGenerationInputMessages(listOf(source), true, null, null).single()
            assertEquals(listOf(good), sent.imageArtifactOccurrences())
            assertEquals(source.text, sent.text)
            assertEquals("notes", sent.attachmentMeta!!.items.first().textContent)
        }
    }

    @Test
    fun missingPageSlotsRemainInHistoryWithoutShiftingFollowingImages() {
        val good = artifact("/ready/page.jpg", 100, 200, "image/jpeg", 10)
        for (type in listOf("pdf", "video")) {
            val paths = listOf("", good.path, "")
            val mixed = attachment("mixed", type, localPath = "/ready/source.$type",
                processedFrames = paths.takeIf { type == "video" },
                preRenderedPaths = paths.takeIf { type == "pdf" }, imageArtifacts = listOf(good))
            val payload = builder.buildComposerPayload(listOf(
                mixed, attachment("after", "image", localPath = good.path, imageArtifacts = listOf(good)),
            ))
            assertTrue(mixed.hasCanonicalReadyArtifact())
            assertEquals(paths + good.path, payload.allImages)
            assertEquals(listOf(0, 3), payload.attachmentMeta!!.items.map { it.imageIndex })
            assertEquals(listOf(true, false, true),
                payload.attachmentMeta.items.first().imageArtifacts.map { it.unavailable })
        }
    }

    @Test
    fun readyUnavailableStillKeepsItsUiSlotAndContributesNoProviderImage() {
        val bad = artifact("/ready/bad.jpg", 100, 200, "image/jpeg", 10).copy(unavailable = true)
        val ready = attachment("bad", "image", localPath = bad.path,
            imageArtifacts = listOf(bad), unavailable = true)
        assertTrue(ready.hasCanonicalReadyArtifact())
        val payload = builder.buildComposerPayload(listOf(ready))
        assertEquals(listOf(bad.path), payload.allImages)
        val source = ChatMessage(id = "user", text = "keep text", participant = Participant.USER,
            images = payload.allImages, attachmentMeta = payload.attachmentMeta)
        val sent = projectGenerationInputMessages(listOf(source), true, null, null).single()
        assertTrue(sent.images.isEmpty())
        assertEquals(source.text, sent.text)
        assertEquals(listOf(bad), source.imageArtifactOccurrences())
    }

    @Test
    fun legacyReadyImageWithoutDescriptorsKeepsItsExistingRecoveryBoundary() {
        val legacy = attachment("legacy", "image", localPath = "/ready/legacy.jpg")
        val payload = builder.buildComposerPayload(listOf(legacy))

        assertTrue(legacy.hasCanonicalReadyArtifact())
        assertEquals(listOf("/ready/legacy.jpg"), payload.allImages)
        assertEquals(emptyList<ImageArtifact>(), requireNotNull(payload.attachmentMeta).items.single().imageArtifacts)
    }

    private fun artifact(path: String, width: Int, height: Int, mimeType: String, bytes: Long) =
        ImageArtifact(path, mimeType, bytes, width, height, policyVersion = 1)

    private fun attachment(
        id: String,
        type: String,
        localPath: String? = null,
        processedFrames: List<String>? = null,
        preRenderedPaths: List<String>? = null,
        preparedText: String? = null,
        state: AttachmentImportState = AttachmentImportState.READY,
        unavailable: Boolean = false,
        imageArtifacts: List<ImageArtifact> = emptyList(),
    ) = SelectedAttachment(
        localId = id,
        uri = "content://picker/$id",
        type = type,
        fileName = "$id.bin",
        localPath = localPath,
        processedFrames = processedFrames,
        preRenderedPaths = preRenderedPaths,
        preparedText = preparedText,
        importState = state,
        unavailable = unavailable,
        imageArtifacts = imageArtifacts,
    )
}
