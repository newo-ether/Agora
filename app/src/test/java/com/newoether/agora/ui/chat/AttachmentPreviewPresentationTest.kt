package com.newoether.agora.ui.chat.bottombar

import android.os.Trace
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.ui.chat.MediaLoadPresentation
import com.newoether.agora.ui.chat.rememberMediaLoadingVisible
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPreviewPresentationTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun loadingVisibilityWaitsForThresholdAndCancelsAcrossCompletionRetryIdentityAndDisposal() = runTest {
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } answers { }
        every { Trace.endSection() } answers { }
        val loading = mutableStateOf(false)
        val identity = mutableStateOf("image-a")
        val mounted = mutableStateOf(true)
        var visible = false
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(backgroundScope.coroutineContext + clock)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        var frame = 0L
        fun settle(expected: Boolean) {
            repeat(2) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(++frame * 16_000_000)
                runCurrent()
            }
            assertEquals(expected, visible)
        }
        try {
            composition.setContent {
                visible = mounted.value && rememberMediaLoadingVisible(identity.value, loading.value)
            }
            settle(false)
            loading.value = true
            settle(false)
            advanceTimeBy(199)
            settle(false)
            loading.value = false
            settle(false)
            advanceTimeBy(1_000)
            settle(false)

            loading.value = true
            settle(false)
            advanceTimeBy(199)
            settle(false)
            advanceTimeBy(1)
            settle(true)
            loading.value = false
            settle(false)
            loading.value = true
            settle(false)
            advanceTimeBy(100)
            identity.value = "image-b"
            settle(false)
            advanceTimeBy(100)
            settle(false)
            advanceTimeBy(100)
            settle(true)

            identity.value = "image-c"
            settle(false)
            advanceTimeBy(100)
            mounted.value = false
            settle(false)
            advanceTimeBy(300)
            settle(false)
            mounted.value = true
            settle(false)
            advanceTimeBy(200)
            settle(true)
        } finally {
            composition.dispose()
            recomposer.close()
            unmockkStatic(Trace::class)
        }
    }

    @Test
    fun notebookThumbnailDoesNotTurnAnOrdinaryZipIntoANoteOrAlterAiPages() {
        val note = com.newoether.agora.model.SelectedAttachment(uri="file:///note.zip",type="file",
            notesDocumentId="document",notesRevisionId="revision",preRenderedPaths=listOf("first.jpg","second.jpg"))
        assertEquals("first.jpg", notesAttachmentPreview(note))
        assertEquals(listOf("first.jpg","second.jpg"), note.preRenderedPaths)
        assertEquals(null, notesAttachmentPreview(note.copy(notesDocumentId=null,notesRevisionId=null)))
        assertEquals(null, notesAttachmentPreview(note.copy(preRenderedPaths=null)))
    }

    @Test
    fun notesUsePreviewWithBookFallbackAndPreserveImportStates() {
        fun presentation(media: MediaLoadPresentation, preview: Boolean = true,
                         state: AttachmentImportState = AttachmentImportState.READY,
                         unavailable: Boolean = false) = attachmentPreviewPresentation(
            unavailable, state, "file", false, media, isNotes = true, hasNotePreview = preview)
        assertEquals(AttachmentPreviewPresentation.MEDIA_SUCCESS, presentation(MediaLoadPresentation.LOADED))
        assertEquals(AttachmentPreviewPresentation.MEDIA_LOADING, presentation(MediaLoadPresentation.LOADING))
        assertEquals(AttachmentPreviewPresentation.READY_NOTE, presentation(MediaLoadPresentation.FAILED))
        assertEquals(AttachmentPreviewPresentation.READY_NOTE, presentation(MediaLoadPresentation.LOADED, preview = false))
        assertEquals(AttachmentPreviewPresentation.IMPORT_LOADING, presentation(MediaLoadPresentation.LOADED, state = AttachmentImportState.PROCESSING))
        assertEquals(AttachmentPreviewPresentation.IMPORT_FAILED, presentation(MediaLoadPresentation.LOADED, state = AttachmentImportState.FAILED))
        assertEquals(AttachmentPreviewPresentation.UNAVAILABLE, presentation(MediaLoadPresentation.LOADED, unavailable = true))
    }

    @Test
    fun reducerCoversEveryImportAndMediaOutcome() {
        val cases = listOf(
            Case(
                unavailable = true,
                importState = AttachmentImportState.READY,
                type = "image",
                media = MediaLoadPresentation.LOADED,
                expected = AttachmentPreviewPresentation.UNAVAILABLE,
            ),
            Case(
                importState = AttachmentImportState.PROCESSING,
                type = "image",
                media = MediaLoadPresentation.LOADING,
                expected = AttachmentPreviewPresentation.IMPORT_LOADING,
            ),
            Case(
                importState = AttachmentImportState.FAILED,
                type = "image",
                media = MediaLoadPresentation.FAILED,
                expected = AttachmentPreviewPresentation.IMPORT_FAILED,
            ),
            Case(
                type = "file",
                media = MediaLoadPresentation.LOADING,
                expected = AttachmentPreviewPresentation.READY_FILE,
            ),
            Case(
                type = "pdf",
                media = MediaLoadPresentation.LOADING,
                expected = AttachmentPreviewPresentation.READY_PDF,
            ),
            Case(
                type = "video",
                hasVideoFrame = false,
                media = MediaLoadPresentation.LOADING,
                expected = AttachmentPreviewPresentation.READY_VIDEO_PLACEHOLDER,
            ),
            Case(
                type = "video",
                hasVideoFrame = true,
                media = MediaLoadPresentation.LOADING,
                expected = AttachmentPreviewPresentation.MEDIA_LOADING,
            ),
            Case(
                type = "image",
                media = MediaLoadPresentation.LOADED,
                expected = AttachmentPreviewPresentation.MEDIA_SUCCESS,
            ),
            Case(
                type = "image",
                media = MediaLoadPresentation.FAILED,
                expected = AttachmentPreviewPresentation.MEDIA_ERROR,
            ),
        )

        assertEquals(
            AttachmentPreviewPresentation.entries.toSet() - AttachmentPreviewPresentation.INITIAL,
            cases.mapTo(linkedSetOf(), Case::expected) + AttachmentPreviewPresentation.READY_NOTE,
        )
        cases.forEach { case ->
            assertEquals(
                case.expected,
                attachmentPreviewPresentation(
                    unavailable = case.unavailable,
                    importState = case.importState,
                    type = case.type,
                    hasVideoFrame = case.hasVideoFrame,
                    mediaLoadState = case.media,
                ),
            )
        }
    }

    @Test
    fun canonicalCrossfadeCanRepresentEveryDirectedStateTransition() {
        val states = AttachmentPreviewPresentation.entries
        val directedEdges = states.flatMap { from -> states.map { to -> from to to } }

        assertEquals(states.size * states.size, directedEdges.size)
        assertTrue(directedEdges.contains(
            AttachmentPreviewPresentation.INITIAL to
                AttachmentPreviewPresentation.IMPORT_LOADING,
        ))
        assertTrue(directedEdges.contains(
            AttachmentPreviewPresentation.MEDIA_LOADING to
                AttachmentPreviewPresentation.MEDIA_SUCCESS,
        ))
        assertTrue(directedEdges.contains(
            AttachmentPreviewPresentation.MEDIA_LOADING to
                AttachmentPreviewPresentation.MEDIA_ERROR,
        ))
        assertTrue(directedEdges.contains(
            AttachmentPreviewPresentation.IMPORT_FAILED to
                AttachmentPreviewPresentation.IMPORT_LOADING,
        ))
    }

    private data class Case(
        val unavailable: Boolean = false,
        val importState: AttachmentImportState = AttachmentImportState.READY,
        val type: String,
        val hasVideoFrame: Boolean = false,
        val media: MediaLoadPresentation,
        val expected: AttachmentPreviewPresentation,
    )
}
