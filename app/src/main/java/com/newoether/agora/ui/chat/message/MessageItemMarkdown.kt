package com.newoether.agora.ui.chat.message

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert

import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.theme.MonoFamily
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.components.*
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.MarkdownText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

// ── Streaming Markdown rendering (extracted from MessageItem.kt) ──────────────
// Pure code-motion: these were `private` members of MessageItem.kt; entry points
// used by MessageItem.kt / the timeline section are `internal`. Behavior unchanged.

@Stable
internal class ChatMarkdownRenderContext(
    val colors: MarkdownColors,
    val typography: MarkdownTypography,
    val padding: MarkdownPadding,
    val components: MarkdownComponents,
    val annotator: MarkdownAnnotator,
    val imageTransformer: ImageTransformer,
    val flavour: MarkdownFlavourDescriptor,
    val plainTextStyle: TextStyle,
    val parseInlineDollarMath: Boolean,
    val preparedMarkdown: Map<String, State.Success> = emptyMap(),
)

internal const val MarkdownLinkPressAnimationMillis = 180

/** Resolves a completed tap in the initial pass; movement past touch slop is a scroll, not a tap. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitTapUp(
    down: androidx.compose.ui.input.pointer.PointerInputChange,
    touchSlop: Float,
): androidx.compose.ui.input.pointer.PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed) return change
        if ((change.position - down.position).getDistance() > touchSlop) return null
    }
}

internal const val MarkdownLinkPressedAlpha = 0.72f

@Composable
internal fun AnimatedMarkdownText(
    content: AnnotatedString,
    node: ASTNode,
    modifier: Modifier = Modifier,
    style: TextStyle,
    sourceContent: String,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    var layoutResult by remember(content) { mutableStateOf<TextLayoutResult?>(null) }
    var activeLink by remember(content) {
        mutableStateOf<AnnotatedString.Range<LinkAnnotation>?>(null)
    }
    var isPressed by remember(content) { mutableStateOf(false) }
    val linkColor = MaterialTheme.colorScheme.primary
    val animatedColor by animateColorAsState(
        targetValue = if (isPressed) linkColor.copy(alpha = MarkdownLinkPressedAlpha) else linkColor,
        animationSpec = tween(MarkdownLinkPressAnimationMillis, easing = FastOutSlowInEasing),
        finishedListener = { if (!isPressed) activeLink = null },
        label = "markdownLinkPressColor",
    )
    val rendered = remember(content, activeLink, animatedColor) {
        activeLink?.let { link ->
            buildAnnotatedString {
                append(content)
                addStyle(SpanStyle(color = animatedColor), link.start, link.end)
            }
        } ?: content
    }
    val linkOpener = rememberMarkdownLinkOpener()
    MarkdownText(
        content = rendered,
        node = node,
        modifier = modifier.pointerInput(content, linkOpener) {
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(false, PointerEventPass.Initial)
                    val offset = layoutResult?.getOffsetForPosition(down.position)
                    val destination = offset?.let { markdownLinkAt(content, it) }
                    if (destination != null) {
                        activeLink = if (content.isEmpty()) null else {
                            val start = offset.coerceIn(0, content.lastIndex)
                            content.getLinkAnnotations(start, start + 1).firstOrNull()
                        }
                        isPressed = true
                        // Take the tap before the platform text link handler so one tap opens once.
                        val up = awaitTapUp(down, viewConfiguration.touchSlop)
                        isPressed = false
                        if (up != null) {
                            up.consume()
                            linkOpener.activate(destination)
                        }
                    }
                }
            } finally {
                isPressed = false
            }
        },
        style = style,
        onTextLayout = { result, _ ->
            layoutResult = result
            onTextLayout?.invoke(result)
        },
        sourceContent = sourceContent,
    )
}

@Composable
internal fun MarkdownTextContent(
    text: String,
    renderContext: ChatMarkdownRenderContext,
    immediate: Boolean = false,
    includeFirstSpacer: Boolean = true,
    onReady: () -> Unit = {}
) {
    val markdownText = remember(text, renderContext.parseInlineDollarMath) {
        text.toRenderableMarkdownText(renderContext.parseInlineDollarMath)
    }
    MarkdownPreparedTextContent(
        text = markdownText,
        renderContext = renderContext,
        immediate = immediate,
        includeFirstSpacer = includeFirstSpacer,
        onReady = onReady
    )
}

/**
 * Detail pages can contain tens of thousands of Markdown characters. Parsing already happens off
 * the main thread; this variant also virtualizes top-level AST nodes so only visible blocks are
 * composed and measured.
 */
@Composable
internal fun LazyMarkdownTextContent(
    text: String,
    renderContext: ChatMarkdownRenderContext,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    includeFirstSpacer: Boolean = true,
    onReady: () -> Unit = {},
    imageDocument: String = LocalMarkdownImageDocument.current,
) {
    val markdownText = remember(text, renderContext.parseInlineDollarMath) {
        text.toRenderableMarkdownText(renderContext.parseInlineDollarMath)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalMarkdownImageDocument provides imageDocument) {
        MarkdownPreparedTextContent(
            text = markdownText,
            renderContext = renderContext,
            immediate = false,
            includeFirstSpacer = includeFirstSpacer,
            onReady = onReady,
            modifier = modifier,
            lazyListState = listState,
            lazyContentPadding = contentPadding,
        )
    }
}

@Composable
private fun MarkdownPreparedTextContent(
    text: String,
    renderContext: ChatMarkdownRenderContext,
    immediate: Boolean = false,
    includeFirstSpacer: Boolean = true,
    onReady: () -> Unit = {},
    modifier: Modifier = Modifier.fillMaxWidth(),
    lazyListState: LazyListState? = null,
    lazyContentPadding: PaddingValues = PaddingValues(),
) {
    val inlineContent = LocalMarkdownInlineContent.current
    val markdownText = text
    val markdownParser = remember(markdownText, renderContext.flavour) {
        MarkdownParser(renderContext.flavour)
    }
    val referenceLinkHandler = remember(markdownText) { ReferenceLinkHandlerImpl() }
    val state = renderContext.preparedMarkdown[markdownText] ?: rememberMarkdownState(
        content = markdownText,
        flavour = renderContext.flavour,
        parser = markdownParser,
        referenceLinkHandler = referenceLinkHandler,
        immediate = immediate
    ).state.collectAsState().value
    val documentState = remember(state) {
        if (state is State.Success) {
            State.Success(
                node = state.node,
                content = state.content,
                linksLookedUp = true,
                referenceLinkHandler = MarkdownDocumentReferenceHandler(
                    markdownReferenceDefinitions(state.node, state.content),
                ),
            )
        } else state
    }
    val currentOnReady by rememberUpdatedState(onReady)

    LaunchedEffect(state) {
        // Error is terminal too: reveal the renderer's error UI instead of leaving a loading
        // indicator over it forever.
        if (state is State.Success || state is State.Error) currentOnReady()
    }

    val document = LocalMarkdownImageDocument.current
    val ordinalOffset = LocalMarkdownImageOrdinalOffset.current
    val occurrences = remember(documentState, document, ordinalOffset) {
        (documentState as? State.Success)?.let {
            markdownImageOccurrences(it.content, it.node, it.referenceLinkHandler, document, ordinalOffset)
        }.orEmpty()
    }
    LaunchedEffect(occurrences, renderContext.imageTransformer) {
        (documentState as? State.Success)?.let { parsed ->
            (renderContext.imageTransformer as? com.newoether.agora.ui.components.LatexImageTransformer)
                ?.loadParsedImages(parsed.content, occurrences, parsed.referenceLinkHandler)
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalMarkdownImageOccurrences provides occurrences) {
    com.mikepenz.markdown.compose.Markdown(
        state = documentState,
        modifier = modifier,
        colors = renderContext.colors,
        typography = renderContext.typography,
        padding = renderContext.padding,
        components = renderContext.components,
        annotator = renderContext.annotator,
        imageTransformer = renderContext.imageTransformer,
        inlineContent = inlineContent,
        animations = markdownAnimations { this },
        success = { successState, components, modifier ->
            if (lazyListState == null) {
                MarkdownSuccessWithSpacing(
                    state = successState,
                    components = components,
                    modifier = modifier,
                    includeFirstSpacer = includeFirstSpacer,
                )
            } else {
                LazyMarkdownSuccessWithSpacing(
                    state = successState,
                    components = components,
                    listState = lazyListState,
                    modifier = modifier,
                    contentPadding = lazyContentPadding,
                    includeFirstSpacer = includeFirstSpacer,
                )
            }
        }
    )
    }
}

@Composable
private fun MarkdownSuccessWithSpacing(
    state: State.Success,
    components: MarkdownComponents,
    modifier: Modifier = Modifier,
    includeFirstSpacer: Boolean = true
) {
    val visibleNodes = remember(state.node) { markdownVisibleBlocks(state.node) }
    Column(modifier) {
        visibleNodes.forEachIndexed { index, node ->
            MarkdownElement(
                node = node,
                components = components,
                content = state.content,
                includeSpacer = includeFirstSpacer || index > 0
            )
        }
    }
}

@Composable
private fun LazyMarkdownSuccessWithSpacing(
    state: State.Success,
    components: MarkdownComponents,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    includeFirstSpacer: Boolean = true,
) {
    val visibleNodes = remember(state.node) { markdownVisibleBlocks(state.node) }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = contentPadding,
    ) {
        itemsIndexed(
            items = visibleNodes,
            key = { index, node ->
                "${node.startOffset}:${node.endOffset}:${node.type}:$index"
            },
        ) { index, node ->
            MarkdownElement(
                node = node,
                components = components,
                content = state.content,
                includeSpacer = includeFirstSpacer || index > 0,
            )
        }
    }
}

internal fun String.toRenderableMarkdownText(parseInlineDollarMath: Boolean = false): String {
    val spans = parseLatexSpans(this, parseInlineDollarMath)
    val markdown = if (spans.all { !it.isLatex }) {
        this
    } else {
        spans.joinToString("") { span ->
            if (span.isLatex) latexToMarkdown(span.content, span.display, span.source)
            else span.content
        }
    }
    return markdown.escapeForMarkdown().openTableBlocks().embedHtmlVideoTags().embedStandaloneVideoPaths()
}

/**
 * A line that is only a video destination becomes the same Markdown image node as an embedded
 * image, so a path written directly renders as the inline video card instead of a text link.
 * Fenced/indented code, inline code and existing links or images keep their text.
 */
internal fun String.embedStandaloneVideoPaths(): String {
    if (indexOf('/') < 0) return this
    var fence: String? = null
    var changed = false
    val lines = split('\n').map { line ->
        val trimmed = line.trim()
        val marker = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (marker != null) {
            if (fence == null) fence = marker else if (fence == marker) fence = null
            return@map line
        }
        if (fence != null) return@map line
        val destination = standaloneVideoDestination(line) ?: return@map line
        changed = true
        embeddedVideoNode(destination)
    }
    return if (changed) lines.joinToString("\n") else this
}

private fun standaloneVideoDestination(line: String): String? {
    if (line.startsWith("    ") || line.startsWith('\t')) return null
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    // One whole line that is a Markdown link to a video renders as the same card.
    STANDALONE_MARKDOWN_LINK.matchEntire(trimmed)?.let { match ->
        val destination = match.groupValues[1].removeSurrounding("<", ">")
        return markdownVideoLink(destination)
    }
    if (trimmed.any(Char::isWhitespace)) return null
    if (trimmed.first() in "<[!`" || '`' in trimmed || "](" in trimmed) return null
    return markdownVideoLink(trimmed)
}

/**
 * The renderer parses the plain `](path)` destination; an angle-bracketed destination is not
 * recognised and the node disappears. Spaces and parentheses are percent-encoded instead.
 */
private fun embeddedVideoNode(destination: String): String {
    val encoded = destination
        .replace(" ", "%20")
        .replace("(", "%28")
        .replace(")", "%29")
    return "![]($encoded)"
}

private val STANDALONE_MARKDOWN_LINK = Regex("""^\[[^\]]*]\(<?([^()\s<>]+)>?\)$""")

private val HTML_VIDEO_ELEMENT = Regex(
    """(?is)<video\b[^>]*/>|<video\b[^>]*>.*?</video\s*>""",
)

private val HTML_SRC_ATTRIBUTE = Regex("""(?is)\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

/**
 * A raw `<video src="...">` element becomes the same Markdown image node as an embedded image,
 * so a model that writes HTML markup still gets the inline video card. Fenced code keeps its text.
 */
internal fun String.embedHtmlVideoTags(): String {
    if (!contains("<video", ignoreCase = true)) return this
    val fenced = fencedCodeRanges()
    return HTML_VIDEO_ELEMENT.replace(this) { match ->
        val start = match.range.first
        val insideFence = fenced.any { start >= it.first && start <= it.last }
        if (insideFence) match.value else htmlVideoMarkdown(match.value) ?: match.value
    }
}

private fun htmlVideoMarkdown(element: String): String? {
    val source = HTML_SRC_ATTRIBUTE.find(element) ?: return null
    val destination = source.groupValues.drop(1).firstOrNull(String::isNotEmpty)?.trim().orEmpty()
    if (destination.isEmpty() || markdownVideoLink(destination) == null) return null
    return embeddedVideoNode(destination)
}

/** Character ranges covered by fenced code blocks, so rewrites never touch verbatim code. */
private fun String.fencedCodeRanges(): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var fence: String? = null
    var fenceStart = 0
    var offset = 0
    for (line in split('\n')) {
        val trimmed = line.trim()
        val marker = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (marker != null) {
            if (fence == null) {
                fence = marker
                fenceStart = offset
            } else if (fence == marker) {
                fence = null
                ranges += fenceStart..(offset + line.length)
            }
        }
        offset += line.length + 1
    }
    if (fence != null) ranges += fenceStart..length
    return ranges
}

internal fun String.escapeForMarkdown(): String = escapeDollarForMarkdown()
