package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.AttachmentItem
import org.junit.Assert.*
import org.junit.Test

class AttachmentSourcePreviewTest {
    @Test fun historicalCoverAndPageFallbackRemainReadableWithoutAnEditor() {
        val note = AttachmentItem(type = "file", mimeType = "application/vnd.kalamos.notes-document+zip",
            imageIndex = 0, notesThumbnailPath = "cover.jpg")
        assertEquals("cover.jpg", attachmentSourcePreviewPath(note, listOf("page.jpg")))
        assertEquals("page.jpg", attachmentSourcePreviewPath(note.copy(notesThumbnailPath = null), listOf("page.jpg")))
        assertNull(attachmentSourcePreviewPath(note.copy(mimeType = "application/zip"), listOf("page.jpg")))
        assertNull(attachmentSourcePreviewPath(note.copy(notesThumbnailPath = null, imageIndex = 8), listOf("page.jpg")))
        assertEquals("source.jpg", attachmentSourcePreviewPath(
            AttachmentItem(type = "file", sourceThumbnailPath = "source.jpg"), emptyList()))
    }
}
