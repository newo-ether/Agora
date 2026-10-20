package com.newoether.agora.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPreviewSourceContractTest {
    @Test
    fun fileCaptionIsOutsideTheRoundedThumbnailMask() {
        val fileBranch = source("ui/chat/AttachmentThumbnail.kt")
            .substringAfter('"' + "file" + '"' + " -> {")
            .substringBefore('"' + "pdf" + '"' + " -> {")
        assertFalse(fileBranch.contains(".clip(shape)"))
        assertTrue(fileBranch.contains(".clickable {"))
        assertTrue(fileBranch.contains("shape = shape"))
        assertTrue(fileBranch.contains("overflow = TextOverflow.Ellipsis"))
    }

    @Test
    fun everyAttachmentOutcomeUsesTheCanonicalCrossfadePresentation() {
        val source = source("ui/chat/bottombar/AttachmentPreviewRow.kt")
        val mediaState = source("ui/chat/MediaLoadPresentation.kt")

        assertTrue(source.contains("mutableStateOf(AttachmentPreviewPresentation.INITIAL to false)"))
        assertTrue(source.contains("targetState = presentedState"))
        assertTrue(source.contains("label = \"attachmentPresentation\""))
        assertTrue(source.contains("label = \"attachmentCaption\""))
        assertTrue(source.contains("tween(ATTACHMENT_STATUS_CROSSFADE_MS)"))
        assertTrue(source.contains("mediaPainter.state.toMediaLoadPresentation()"))
        assertTrue(mediaState.contains("this is AsyncImagePainter.State.Success"))
        assertTrue(mediaState.contains("this is AsyncImagePainter.State.Error"))
        assertTrue(mediaState.contains("MEDIA_STATE_CROSSFADE_MILLIS = 200"))
        assertTrue(mediaState.contains("MEDIA_LOADING_INDICATOR_STROKE_WIDTH = 3.dp"))
        assertTrue(source.contains("AttachmentPreviewPresentation.IMPORT_LOADING"))
        assertTrue(source.contains("AttachmentPreviewPresentation.IMPORT_FAILED"))
        assertTrue(source.contains("AttachmentPreviewPresentation.MEDIA_LOADING"))
        assertTrue(source.contains("AttachmentPreviewPresentation.MEDIA_SUCCESS"))
        assertTrue(source.contains("AttachmentPreviewPresentation.MEDIA_ERROR"))
        assertTrue(source.contains("strokeWidth = MEDIA_LOADING_INDICATOR_STROKE_WIDTH"))
        assertFalse(source.contains("strokeWidth = 2.dp"))
        assertFalse(source.contains("label = \"attachmentProcessingOverlay\""))
        assertFalse(source.contains("label = \"attachmentStatusIndicator\""))
        assertFalse(source.contains("coil.compose.AsyncImage("))
        // A successfully loaded notebook image must never be overlaid with a failure label.
        val success = source.substringAfter("AttachmentPreviewPresentation.MEDIA_SUCCESS -> {")
            .substringBefore("@Composable\nprivate fun AttachmentTypePlaceholder")
        assertFalse(success.contains("NoteAttachmentPlaceholder()"))
    }

    private fun source(relativePath: String): String =
        File(mainSourceRoot(), "com/newoether/agora/$relativePath")
            .readText()
            .replace("\r\n", "\n")

    private fun mainSourceRoot(): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            val candidate = File(directory, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile ?: error("Unable to locate app/src/main/java")
        }
    }
}
