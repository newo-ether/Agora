package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ImageArtifact
import com.newoether.agora.model.MarkdownImage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMediaGalleryTest {
    private fun artifact(path: String) = ImageArtifact(
        path = path,
        mimeType = "image/png",
        sizeBytes = 10,
    )

    @Test
    fun galleryCollectsEveryMessageImageInOrderAndDropsPdfPages() {
        val messages = listOf(
            ChatMessage(
                text = "user",
                participant = Participant.USER,
                images = listOf("/pdf-page-1.png", "/pdf-page-2.png", "/photo.jpg"),
                attachmentMeta = AttachmentMeta(
                    items = listOf(
                        AttachmentItem(type = "pdf", imageIndex = 0, pageCount = 2),
                        AttachmentItem(type = "image", imageIndex = 2),
                    ),
                ),
            ),
            ChatMessage(
                text = "tool",
                participant = Participant.MODEL,
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolImages = listOf(artifact("/tool.png"), artifact("")),
                    ),
                ),
                markdownImages = mapOf("shot" to MarkdownImage(attachment = artifact("/md.png"))),
            ),
            ChatMessage(
                text = "answer",
                participant = Participant.MODEL,
                images = listOf("/generated.png", "/photo.jpg"),
            ),
        )

        assertEquals(
            listOf("/photo.jpg", "/tool.png", "/md.png", "/generated.png"),
            chatMediaGallery(messages),
        )
    }

    @Test
    fun galleryIsEmptyForTextOnlyChat() {
        assertEquals(emptyList<String>(), chatMediaGallery(listOf(ChatMessage(text = "hi", participant = Participant.USER))))
    }
}
