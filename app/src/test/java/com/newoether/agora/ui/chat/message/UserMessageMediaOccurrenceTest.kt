package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentStorage
import com.newoether.agora.model.ImageArtifact
import org.junit.Assert.assertEquals
import org.junit.Test

class UserMessageMediaOccurrenceTest {
    @Test
    fun duplicateImageAndVideoValuesRetainFirstAndSecondOccurrenceIdentity() {
        val duplicateImage = "/ready/duplicate.jpg"
        val duplicateVideo = "file:///ready/duplicate.mp4"
        val projection = projectStoredMediaOccurrences(
            listOf(
                duplicateImage to AttachmentItem(type = "image", imageIndex = 0),
                duplicateImage to AttachmentItem(type = "image", imageIndex = 1),
                "/ready/frame-a.jpg" to AttachmentItem(
                    type = "video",
                    originalUri = duplicateVideo,
                    imageIndex = 2,
                ),
                "/ready/frame-b.jpg" to AttachmentItem(
                    type = "video",
                    originalUri = duplicateVideo,
                    imageIndex = 3,
                ),
                "/missing.jpg" to AttachmentItem(
                    type = "image",
                    imageIndex = 4,
                    unavailable = true,
                ),
                "" to AttachmentItem(type = "file"),
            ),
        )

        assertEquals(
            listOf(duplicateImage, duplicateImage, duplicateVideo, duplicateVideo),
            projection.urls,
        )
        assertEquals(listOf(0, 1, 2, 3, null, null), projection.indexByDisplayItem)
    }

    @Test
    fun sandboxVideoViewerUsesReadableSnapshotFramesWithoutOriginalFallback() {
        val frames = listOf(
            ImageArtifact("/private/vid_first.png", "image/png", 1, 20, 30, unavailable = true),
            ImageArtifact("/private/vid_second.png", "image/png", 1, 20, 30),
        )
        val item = AttachmentItem(type = "video", imageIndex = 0, originalUri = "file:///sandbox/clip.mp4",
            storage = AttachmentStorage.LOCAL_SANDBOX_RUNTIME, imageArtifacts = frames)
        val projection = projectStoredMediaOccurrences(listOf(frames.first().path to item))
        assertEquals(listOf(frames.last().path), projection.urls)
        assertEquals(listOf(0), projection.indexByDisplayItem)
        val missing = projectStoredMediaOccurrences(listOf(frames.first().path to item.copy(
            imageArtifacts = frames.map { it.copy(unavailable = true) })))
        assertEquals(emptyList<String>(), missing.urls)
        assertEquals(listOf<Int?>(null), missing.indexByDisplayItem)
    }

    @Test
    fun legacyImageOccurrencesRemainOrderedWithoutMetadata() {
        val projection = projectStoredMediaOccurrences(
            listOf(
                "/legacy/same.jpg" to null,
                "/legacy/same.jpg" to null,
            ),
        )

        assertEquals(listOf("/legacy/same.jpg", "/legacy/same.jpg"), projection.urls)
        assertEquals(listOf(0, 1), projection.indexByDisplayItem)
    }
}
