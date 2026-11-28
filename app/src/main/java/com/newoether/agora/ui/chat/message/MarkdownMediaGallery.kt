package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.ui.chat.FullScreenMediaViewer

/** One opened gallery: the answer's media in reading order and the tapped page. */
internal data class MarkdownMediaPages(val urls: List<String>, val index: Int)

/** One rendered picture or video card of an answer. */
internal class MarkdownMediaEntry internal constructor(private val gallery: MarkdownMediaGallery) {
    internal var uri: String? = null
    internal var position: Offset? = null

    /** Tracks where the card sits, which is its reading order among the answer's media. */
    val modifier: Modifier = Modifier.onGloballyPositioned { position = it.positionInRoot() }

    /** Opens the answer's media viewer on this card, whose viewer [uri] is now known. */
    fun open(uri: String) {
        this.uri = uri
        gallery.open(this)
    }
}

/**
 * Every picture and video one answer renders, so a tap on any of them opens one viewer that pages
 * through all of them in reading order, like the WebUI gallery. Entries only hold layout data and
 * are read at tap time, so registering never recomposes the answer.
 */
@Stable
internal class MarkdownMediaGallery {
    private val entries = LinkedHashSet<MarkdownMediaEntry>()
    var opened by mutableStateOf<MarkdownMediaPages?>(null)

    internal fun add(entry: MarkdownMediaEntry) { entries += entry }
    internal fun remove(entry: MarkdownMediaEntry) { entries -= entry }

    internal fun open(entry: MarkdownMediaEntry) {
        opened = markdownMediaPages(entries.toList(), entry)
    }
}

/**
 * Orders the placed entries that have a viewer URI top to bottom, then start to end. An entry
 * that was never placed is shown alone rather than guessed into the order.
 */
internal fun markdownMediaPages(entries: List<MarkdownMediaEntry>, tapped: MarkdownMediaEntry): MarkdownMediaPages? {
    val uri = tapped.uri ?: return null
    val ordered = entries
        .filter { it.uri != null && it.position != null }
        .sortedWith(compareBy<MarkdownMediaEntry>({ it.position!!.y }, { it.position!!.x }))
    val index = ordered.indexOf(tapped)
    return if (index < 0) MarkdownMediaPages(listOf(uri), 0)
    else MarkdownMediaPages(ordered.map { it.uri!! }, index)
}

internal val LocalMarkdownMediaGallery = staticCompositionLocalOf<MarkdownMediaGallery?> { null }

/** Gives [content] one shared media gallery and hosts its full-screen viewer. */
@Composable
internal fun MarkdownMediaGalleryHost(content: @Composable () -> Unit) {
    val gallery = remember { MarkdownMediaGallery() }
    CompositionLocalProvider(LocalMarkdownMediaGallery provides gallery, content = content)
    val pages = gallery.opened ?: return
    Dialog(
        onDismissRequest = { gallery.opened = null },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            FullScreenMediaViewer(
                urls = pages.urls,
                initialIndex = pages.index,
                pdfPages = emptyList(),
                onClose = { gallery.opened = null },
                onNavigate = {},
            )
        }
    }
}

/**
 * Joins the surrounding answer's gallery with the viewer [uri] this card opens, or null where no
 * gallery is provided and the card keeps its own viewer.
 */
@Composable
internal fun rememberMarkdownMediaEntry(uri: String?): MarkdownMediaEntry? {
    val gallery = LocalMarkdownMediaGallery.current ?: return null
    val entry = remember(gallery) { MarkdownMediaEntry(gallery) }
    DisposableEffect(gallery, entry) {
        gallery.add(entry)
        onDispose { gallery.remove(entry) }
    }
    SideEffect { entry.uri = uri }
    return entry
}
