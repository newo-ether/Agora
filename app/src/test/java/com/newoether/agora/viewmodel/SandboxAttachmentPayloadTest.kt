package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.SelectedAttachment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SandboxAttachmentPayloadTest {
    @Test
    fun sandboxTextPersistsPreviewButProjectsOnlyPathAndMetadata() {
        val source = selected("file", "notes.txt").copy(
            mimeType = "text/plain", fileSize = 8192L,
            sourceText = "complete pasted source that must never enter a request",
            preparedText = "private preview text",
        )
        val payload = MessagePayloadBuilder().buildComposerPayload(listOf(source))
        val item = requireNotNull(payload.attachmentMeta).items.single()
        assertTrue(payload.allImages.isEmpty())
        assertNull(item.originalUri)
        assertEquals(source.preparedText, item.textContent)
        assertEquals(AttachmentStorage.LOCAL_SANDBOX_RUNTIME, item.storage)
        assertEquals(source.sandboxPath, item.sandboxPath)
        val entity = entity(payload)
        for (transcriptions in listOf(false, true)) {
            val projected = projectProviderMessages(listOf(entity), transcriptions).single()
            assertEquals("inspect" + sandboxAttachmentInstruction(item), projected.text)
            assertFalse(projected.text.contains("private preview"))
            assertFalse(projected.text.contains("complete pasted"))
            val apiItem = requireNotNull(projected.attachmentMeta).items.single()
            assertNull(apiItem.textContent)
            assertNull(apiItem.originalUri)
            assertEquals(source.sandboxPath, apiItem.sandboxPath)
            assertEquals(source.fileSize, apiItem.fileSize)
        }
        assertEquals(source.preparedText, item.textContent)
    }

    @Test
    fun sandboxMediaKeepsDurablePreviewSlotsWithoutAutomaticProviderImages() {
        val image = artifact("/private/img_snapshot.png")
        val pages = listOf(artifact("/private/pdf_1.png"), artifact("/private/pdf_2.png"))
        val frames = listOf(artifact("/private/vid_1.png"), artifact("/private/vid_2.png"))
        val selected = listOf(
            selected("image", "photo.png").copy(imageArtifacts = listOf(image)),
            selected("pdf", "document.pdf").copy(preRenderedPaths = pages.map { it.path }, imageArtifacts = pages),
            selected("video", "clip.mp4").copy(processedFrames = frames.map { it.path }, imageArtifacts = frames),
        )
        val payload = MessagePayloadBuilder().buildComposerPayload(selected)
        val paths = listOf(image.path) + pages.map { it.path } + frames.map { it.path }
        assertEquals(paths, payload.allImages)
        val items = requireNotNull(payload.attachmentMeta).items
        assertEquals(listOf(0, 1, 3), items.map { it.imageIndex })
        assertEquals(listOf(null, 2, 2), items.map { it.pageCount })
        assertTrue(items.all { it.storage == AttachmentStorage.LOCAL_SANDBOX_RUNTIME })
        assertTrue(items.all { !it.originalUri.isNullOrBlank() })
        assertEquals("file://${selected.first().localPath}", items.first().originalUri)
        assertNotEquals(image.path, selected.first().localPath)
        for (transcriptions in listOf(false, true)) {
            val projected = projectProviderMessages(listOf(entity(payload)), transcriptions).single()
            assertTrue(projected.images.isEmpty())
            assertTrue(projected.imageArtifacts.isEmpty())
            assertEquals("inspect" + items.joinToString("") { sandboxAttachmentInstruction(it) }, projected.text)
            assertTrue(requireNotNull(projected.attachmentMeta).items.all {
                it.imageIndex == null && it.imageArtifacts.isEmpty() && it.originalUri == null
            })
        }
        assertEquals(paths, payload.allImages)
        assertEquals(listOf(image), items.first().imageArtifacts)
    }

    @Test
    fun duplicatePathsRemoveOnlySandboxOccurrencesAndLeaveExplicitToolReads() {
        val image = artifact("/private/shared.png")
        val ordinary = selected("image", "ordinary.png").copy(
            localPath = image.path, storage = AttachmentStorage.APP_PRIVATE, sandboxPath = null,
            imageArtifacts = listOf(image),
        )
        val sandbox = selected("pdf", "document.pdf").copy(
            preRenderedPaths = listOf(image.path, image.path), imageArtifacts = listOf(image, image),
        )
        val payload = MessagePayloadBuilder().buildComposerPayload(listOf(ordinary, sandbox, ordinary))
        val tool = entity(MessagePayloadBuilder.MessagePayload(listOf(image.path), null)).copy(
            id = "result_file_view_image", text = "explicit tool read",
        )
        for (transcriptions in listOf(false, true)) {
            val projected = projectProviderMessages(listOf(entity(payload), tool), transcriptions)
            assertEquals(listOf(image.path, image.path), projected.first().images)
            assertEquals(listOf(0, null, 1), requireNotNull(projected.first().attachmentMeta).items.map { it.imageIndex })
            assertEquals(listOf(image.path), projected.last().images)
        }
        assertEquals(List(4) { image.path }, payload.allImages)
    }

    @Test
    fun missingSandboxPathNeverFallsBackToStoredPrivateText() {
        val payload = MessagePayloadBuilder().buildComposerPayload(listOf(selected("file", "notes.txt").copy(
            preparedText = "private", sandboxPath = null,
        )))
        assertNull(payload.attachmentMeta)
        val metadata = com.newoether.agora.model.AttachmentMeta(listOf(com.newoether.agora.model.AttachmentItem(
            type = "file", storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME, textContent = "private",
            transcription = "private transcription",
        )))
        val source = entity(payload).copy(attachmentMeta = Json.encodeToString(metadata))
        assertEquals("inspect", projectProviderMessages(listOf(source), true).single().text)
    }

    private fun selected(type: String, name: String) = SelectedAttachment(
        uri = "content://picker/$name", type = type, fileName = name,
        localPath = "/private/sandbox-home/attachments/id/$name",
        storage = AttachmentStorage.LOCAL_SANDBOX_PENDING,
        sandboxPath = "/home/agora/attachments/id/$name",
    )

    private fun artifact(path: String) = ImageArtifact(path, "image/png", 32L, 31, 47)

    private fun entity(payload: MessagePayloadBuilder.MessagePayload) = MessageEntity(
        id = "user", conversationId = "conversation", text = "inspect", images = payload.allImages,
        attachmentMeta = payload.attachmentMeta?.let { Json.encodeToString(it) },
        status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = 1L, runId = "run",
    )
}
