package com.newoether.agora.ui.chat.message

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownMediaGalleryTest {
    private val gallery = MarkdownMediaGallery()

    private fun entry(uri: String?, x: Float?, y: Float?) = MarkdownMediaEntry(gallery).apply {
        this.uri = uri
        position = if (x != null && y != null) Offset(x, y) else null
    }

    @Test
    fun picturesAndVideosPageInReadingOrder() {
        val video = entry("content://agora/clip.mp4", 0f, 400f)
        val first = entry("/data/a.png", 0f, 100f)
        val besideFirst = entry("/data/b.png", 320f, 100f)
        val pages = markdownMediaPages(listOf(video, besideFirst, first), video)!!
        assertEquals(listOf("/data/a.png", "/data/b.png", "content://agora/clip.mp4"), pages.urls)
        assertEquals(2, pages.index)
    }

    @Test
    fun mediaWithoutViewerUriOrPlacementStaysOut() {
        val waiting = entry(null, 0f, 50f)
        val picture = entry("/data/a.png", 0f, 100f)
        val unplaced = entry("content://agora/clip.mp4", null, null)
        assertEquals(listOf("/data/a.png"), markdownMediaPages(listOf(waiting, picture, unplaced), picture)!!.urls)
        // A tapped card that was never placed still opens alone.
        val alone = markdownMediaPages(listOf(waiting, picture, unplaced), unplaced)!!
        assertEquals(listOf("content://agora/clip.mp4"), alone.urls)
        assertEquals(0, alone.index)
        assertNull(markdownMediaPages(listOf(waiting), waiting))
    }
}
