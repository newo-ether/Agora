package com.newoether.agora.ui.chat.message

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.newoether.agora.model.StreamingTextDelta
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

private const val LONG_DOCUMENT_THRESHOLD_CHARS = 8_000
private const val LONG_DOCUMENT_RENDER_INTERVAL_MS = 120L

/**
 * A parsed block whose source range is closed and can therefore keep the same identity for the
 * rest of an append-only generation. Its Markdown tree is built exactly once off the main thread.
 */
@Stable
internal class StableMarkdownBlock(
    val startOffset: Int,
    val endOffset: Int,
    val sourceContent: String,
    val root: ASTNode,
) {
    val identity: Int = 31 * startOffset + sourceContent.hashCode()
    val hasVisibleContent: Boolean = markdownVisibleBlocks(root).isNotEmpty()
}

/**
 * The only mutable Markdown block in an append-only document. It is reparsed off the main thread,
 * but it is always rendered through the exact same Markdown component tree as stable and final
 * blocks. Keeping a dedicated tail identity also preserves its composition across stream → final.
 */
@Stable
internal class LiveMarkdownBlock(
    val startOffset: Int,
    val sourceContent: String,
    val root: ASTNode,
) {
    val hasVisibleContent: Boolean = markdownVisibleBlocks(root).isNotEmpty()
}

@Immutable
internal data class StreamingMarkdownSnapshot(
    val inputContent: String,
    val stableBlocks: List<StableMarkdownBlock>,
    val tail: String,
    val liveBlock: LiveMarkdownBlock?,
    val isStreaming: Boolean,
    val fadeSample: StreamingTailFadeSample? = null,
    val textDeltas: List<StreamingTextDelta>? = null,
    val references: Map<String, String> = emptyMap(),
)

private data class StreamingMarkdownInput(
    val revision: Long,
    val content: String,
    val isStreaming: Boolean,
    val textDeltas: List<StreamingTextDelta>?,
)


@Stable
internal interface StreamingMarkdownInteractionController {
    fun setCodeBlockScrolling(owner: Any, active: Boolean)
}

internal val LocalStreamingMarkdownInteractionController =
    staticCompositionLocalOf<StreamingMarkdownInteractionController?> { null }

/** Per-block source window carrying the glyph timeline through AST promotion. */
@Immutable
internal data class StreamingGlyphFadeSpec(
    val tailCodePoints: Int,
    val birthTimesMs: LongArray,
)

internal val LocalStreamingGlyphFadeSpec =
    compositionLocalOf<StreamingGlyphFadeSpec?> { null }

/** Per-text-node slice of one block's glyph timeline. */
@Immutable
internal data class StreamingGlyphNodeFade(
    val tailCodePoints: Int,
    val birthTimesMs: LongArray,
)

/** Maps a block timeline to the overlapping code-point range of one text node. */
internal fun StreamingGlyphFadeSpec?.nodeFade(
    blockContent: String,
    nodeStart: Int,
    nodeEnd: Int,
): StreamingGlyphNodeFade? {
    if (this == null || tailCodePoints <= 0) return null
    val nodeStartCp = blockContent.codePointCount(0, nodeStart)
    val nodeEndCp = blockContent.codePointCount(0, nodeEnd)
    val windowStartCp = blockContent.codePointCount(0, blockContent.length) - tailCodePoints
    val overlapStart = max(nodeStartCp, windowStartCp)
    val overlapCount = nodeEndCp - overlapStart
    if (overlapCount <= 0) return null
    val sliceStart = max(0, nodeStartCp - windowStartCp)
    return StreamingGlyphNodeFade(
        tailCodePoints = overlapCount,
        birthTimesMs = birthTimesMs.copyOfRange(sliceStart, sliceStart + overlapCount),
    )
}

/** Splits the document timeline across stable blocks and the live block. */
internal fun computeBlockFadeSpecs(
    snapshot: StreamingMarkdownSnapshot,
): List<StreamingGlyphFadeSpec?> {
    val births = snapshot.fadeSample?.birthTimesMs ?: LongArray(0)
    if (births.isEmpty()) return emptyList()
    val blocks = snapshot.stableBlocks
    val live = snapshot.liveBlock
    val blockCount = blocks.size + (if (live != null) 1 else 0)
    if (blockCount == 0) return emptyList()

    val cpCounts = IntArray(blockCount) { index ->
        val text = if (index < blocks.size) blocks[index].sourceContent else live!!.sourceContent
        text.codePointCount(0, text.length)
    }
    val totalCp = cpCounts.sum()
    val windowStartCp = max(0, totalCp - births.size)

    val specs = ArrayList<StreamingGlyphFadeSpec?>(blockCount)
    var cursorCp = 0
    for (index in 0 until blockCount) {
        val blockStart = cursorCp
        val blockEnd = cursorCp + cpCounts[index]
        cursorCp = blockEnd
        val overlapStart = max(blockStart, windowStartCp)
        val tailCp = blockEnd - overlapStart
        if (tailCp <= 0) {
            specs.add(null)
            continue
        }
        val sliceStart = max(0, blockStart - windowStartCp)
        specs.add(
            StreamingGlyphFadeSpec(
                tailCodePoints = tailCp,
                birthTimesMs = births.copyOfRange(sliceStart, sliceStart + tailCp),
            )
        )
    }
    return specs
}

@Immutable
internal data class StreamingTailFadeSample(
    val observedAtMs: Long,
    val birthTimesMs: LongArray,
)

private data class StreamingFadingGlyph(
    val birthTimeMs: Long,
)

/** Owns published glyph timing; conflated inputs cannot age unpublished deltas. */
internal class StreamingTailFadeTracker {
    private var previousText = ""
    private val fadingGlyphs = java.util.ArrayDeque<StreamingFadingGlyph>()
    private val publishedDeltaSequences = mutableSetOf<Long>()

    @Synchronized
    fun update(
        text: String,
        nowMs: Long,
        textDeltas: List<StreamingTextDelta>? = null,
    ): StreamingTailFadeSample {
        require(nowMs >= 0L)
        pruneSolidPrefix(nowMs)
        val textCodePoints = text.codePointCount(0, text.length)
        val newDeltas = textDeltas.orEmpty().filter { delta ->
            delta.sequence !in publishedDeltaSequences
        }
        val newDeltaCodePoints = newDeltas.fold(0) { total, delta ->
            total + min(
                textCodePoints - total,
                delta.codePointCount.coerceAtLeast(0),
            )
        }

        when {
            text == previousText -> Unit
            text.startsWith(previousText) -> {
                val appendedCodePoints =
                    text.codePointCount(previousText.length, text.length)
                appendPublishedGlyphs(
                    codePointCount = if (textDeltas == null) {
                        appendedCodePoints
                    } else {
                        min(appendedCodePoints, newDeltaCodePoints)
                    },
                    nowMs = nowMs,
                )
            }
            previousText.endsWith(text) -> {
                // A closed Markdown block was promoted out of the live tail. The remaining text is
                // the old suffix, so its glyph timeline remains valid.
                retainFadingSuffix(textCodePoints)
            }
            else -> {
                val newlyPublishedCodePoints = if (textDeltas == null) {
                    textCodePoints
                } else {
                    newDeltaCodePoints
                }
                // Renderer-only rewrites retain the active terminal timeline. If Provider text
                // arrived with the rewrite, only that genuinely new terminal suffix is born now.
                retainFadingSuffix(textCodePoints - newlyPublishedCodePoints)
                appendPublishedGlyphs(newlyPublishedCodePoints, nowMs)
            }
        }
        if (text != previousText) {
            publishedDeltaSequences += newDeltas.map(StreamingTextDelta::sequence)
        }
        previousText = text
        return StreamingTailFadeSample(
            observedAtMs = nowMs,
            birthTimesMs = fadingGlyphs.map { it.birthTimeMs }.toLongArray(),
        )
    }

    private fun pruneSolidPrefix(nowMs: Long) {
        while (fadingGlyphs.isNotEmpty()) {
            val glyph = fadingGlyphs.first()
            if (streamingGlyphAlpha(glyph, nowMs) < 0.999f) break
            fadingGlyphs.removeFirst()
        }
    }

    private fun appendPublishedGlyphs(codePointCount: Int, nowMs: Long) {
        repeat(codePointCount.coerceAtLeast(0)) {
            fadingGlyphs.addLast(StreamingFadingGlyph(nowMs))
        }
    }

    private fun retainFadingSuffix(maximumSize: Int) {
        val keep = min(maximumSize.coerceAtLeast(0), fadingGlyphs.size)
        while (fadingGlyphs.size > keep) fadingGlyphs.removeFirst()
    }

    private fun streamingGlyphAlpha(glyph: StreamingFadingGlyph, nowMs: Long): Float {
        val elapsedSeconds = (nowMs - glyph.birthTimeMs).coerceAtLeast(0L) / 1_000f
        return (STREAM_TAIL_ALPHA_PER_SECOND * elapsedSeconds).coerceIn(0f, 1f)
    }
}

private data class MarkdownFence(
    val marker: Char,
    val length: Int,
)

/**
 * Kelivo-style append-only scanner with stronger CommonMark fence handling. Only appended code
 * units are scanned. Closed blocks are parsed once; only the still-open tail is reparsed as tokens
 * arrive, and both kinds of block use the same Markdown rendering path.
 */
internal class IncrementalMarkdownDocument(
    private val flavour: MarkdownFlavourDescriptor,
) {
    private var source = ""
    private val stableBlocks = mutableListOf<StableMarkdownBlock>()
    private var scanCursor = 0
    private var lineStart = 0
    private var blockStart = 0
    private var fence: MarkdownFence? = null
    private var finalized = false
    private var liveBlock: LiveMarkdownBlock? = null
    private val stableReferences = linkedMapOf<String, String>()
    private var liveReferences: Map<String, String> = emptyMap()

    internal var scannedCodeUnits: Long = 0L
        private set

    fun update(
        preparedSource: String,
        inputContent: String,
        isStreaming: Boolean,
    ): StreamingMarkdownSnapshot {
        if (
            preparedSource == source &&
            ((isStreaming && !finalized) || (!isStreaming && finalized))
        ) {
            return snapshot(inputContent, isStreaming)
        }

        val appendOnly = !finalized && preparedSource.startsWith(source)
        if (!appendOnly) {
            reset()
            scannedCodeUnits += preparedSource.length
        } else {
            scannedCodeUnits += preparedSource.length - source.length
        }
        source = preparedSource
        finalized = false

        scanCompletedLines()
        if (!isStreaming) {
            // Do not promote the live tail at terminalization. Its dedicated keyed composition
            // must survive stream → final so selection/status changes cannot replace the Markdown
            // subtree or briefly collapse its measured height.
            finalized = true
        }
        updateLiveBlock()
        return snapshot(inputContent, isStreaming)
    }

    private fun reset() {
        source = ""
        stableBlocks.clear()
        scanCursor = 0
        lineStart = 0
        blockStart = 0
        fence = null
        finalized = false
        liveBlock = null
        stableReferences.clear()
        liveReferences = emptyMap()
    }

    private fun scanCompletedLines() {
        while (scanCursor < source.length) {
            val newline = source.indexOf('\n', scanCursor)
            if (newline < 0) {
                // Resume from this position when the incomplete line receives more characters.
                scanCursor = source.length
                return
            }

            val line = source.substring(lineStart, newline).removeSuffix("\r")
            updateFence(line)
            if (fence == null && line.isBlank() && lineStart > blockStart) {
                val end = newline + 1
                if (commit(blockStart, end)) {
                    blockStart = end
                    liveBlock = null
                }
            }
            lineStart = newline + 1
            scanCursor = newline + 1
        }
    }

    private fun updateFence(line: String) {
        var indent = 0
        while (indent < line.length && indent < 4 && line[indent] == ' ') indent++
        if (indent > 3 || indent >= line.length) return

        val marker = line[indent]
        if (marker != '`' && marker != '~') return
        var markerEnd = indent
        while (markerEnd < line.length && line[markerEnd] == marker) markerEnd++
        val markerLength = markerEnd - indent
        if (markerLength < 3) return

        val active = fence
        if (active == null) {
            fence = MarkdownFence(marker, markerLength)
        } else if (
            marker == active.marker &&
            markerLength >= active.length &&
            line.substring(markerEnd).isBlank()
        ) {
            fence = null
        }
    }

    private fun commit(start: Int, end: Int): Boolean {
        if (end <= start) return true
        val blockText = source.substring(start, end)
        if (blockText.isBlank()) return true
        val root = runCatching {
            MarkdownParser(flavour).buildMarkdownTreeFromString(blockText)
        }.getOrNull() ?: return false
        stableBlocks += StableMarkdownBlock(
            startOffset = start,
            endOffset = end,
            sourceContent = blockText,
            root = root,
        )
        markdownReferenceDefinitions(root, blockText).forEach { (label, destination) ->
            stableReferences.putIfAbsent(label, destination)
        }
        return true
    }

    private fun updateLiveBlock() {
        val tail = source.substring(blockStart.coerceIn(0, source.length))
        if (tail.isEmpty()) {
            liveBlock = null
            liveReferences = emptyMap()
            return
        }
        if (liveBlock?.startOffset == blockStart && liveBlock?.sourceContent == tail) return
        val root = runCatching {
            MarkdownParser(flavour).buildMarkdownTreeFromString(tail)
        }.getOrNull() ?: return
        liveReferences = markdownReferenceDefinitions(root, tail)
        liveBlock = LiveMarkdownBlock(
            startOffset = blockStart,
            sourceContent = tail,
            root = root,
        )
    }

    private fun snapshot(
        inputContent: String,
        isStreaming: Boolean,
    ): StreamingMarkdownSnapshot = StreamingMarkdownSnapshot(
        inputContent = inputContent,
        stableBlocks = stableBlocks.toList(),
        tail = source.substring(blockStart.coerceIn(0, source.length)),
        liveBlock = liveBlock,
        isStreaming = isStreaming,
        references = buildMap {
            putAll(stableReferences)
            liveReferences.forEach { (label, destination) -> putIfAbsent(label, destination) }
        },
    )
}

/**
 * One persistent worker per rendered message. A conflated channel keeps only the newest pending
 * snapshot while the current delta is parsed, so CPU parsing is sequential and can never pile up.
 *
 * Fade birth times are assigned only when a parsed snapshot is actually published. Conflated or
 * stale parses and snapshots held by an active code-block gesture therefore cannot age glyphs
 * before their first rendered frame.
 */
@Stable
private class StreamingMarkdownRenderState(
    flavour: MarkdownFlavourDescriptor,
    private val parseInlineDollarMath: Boolean,
    initialContent: String,
    initialIsStreaming: Boolean,
    private val fadeTracker: StreamingTailFadeTracker,
) : StreamingMarkdownInteractionController {
    private val document = IncrementalMarkdownDocument(flavour)
    private val inputs = Channel<StreamingMarkdownInput>(Channel.CONFLATED)
    private val offeredRevision = AtomicLong(0L)
    private val interactionCommitGate =
        StreamingInteractionCommitGate<StreamingMarkdownSnapshot>()
    private val _snapshot = MutableStateFlow(
        StreamingMarkdownSnapshot(
            inputContent = initialContent,
            stableBlocks = emptyList(),
            tail = initialContent,
            liveBlock = null,
            isStreaming = initialIsStreaming,
        )
    )
    val snapshot: StateFlow<StreamingMarkdownSnapshot> = _snapshot.asStateFlow()

    fun offer(
        content: String,
        isStreaming: Boolean,
        textDeltas: List<StreamingTextDelta>?,
    ) {
        val revision = offeredRevision.incrementAndGet()
        inputs.trySend(StreamingMarkdownInput(revision, content, isStreaming, textDeltas))
    }

    override fun setCodeBlockScrolling(owner: Any, active: Boolean) {
        interactionCommitGate.setActive(owner, active)?.let { pending ->
            val nowMs = SystemClock.uptimeMillis()
            val preparedSource =
                pending.inputContent.toRenderableMarkdownText(parseInlineDollarMath)
            _snapshot.value = pending.copy(
                fadeSample = fadeTracker.update(
                    text = preparedSource,
                    nowMs = nowMs,
                    textDeltas = pending.textDeltas,
                ),
            )
        }
    }

    suspend fun run() {
        var lastRenderedAtMs = 0L
        for (received in inputs) {
            var input = received
            while (true) {
                // Inputs are conflated while a long tail waits/parses. Consume the newest value
                // before every cadence decision. Polling at most once per display frame lets a
                // terminal/stop snapshot bypass the long-document 120 ms cadence immediately.
                while (true) {
                    val newer = inputs.tryReceive().getOrNull() ?: break
                    input = newer
                }
                val minimumIntervalMs =
                    if (
                        input.isStreaming &&
                        input.content.length >= LONG_DOCUMENT_THRESHOLD_CHARS
                    ) {
                        LONG_DOCUMENT_RENDER_INTERVAL_MS
                    } else {
                        0L
                    }
                val remainingDelay =
                    (lastRenderedAtMs + minimumIntervalMs - SystemClock.uptimeMillis())
                        .coerceAtLeast(0L)
                if (remainingDelay <= 0L) break
                delay(minOf(remainingDelay, 16L))
            }
            val (next, preparedSource) = withContext(Dispatchers.Default) {
                val preparedSource =
                    input.content.toRenderableMarkdownText(parseInlineDollarMath)
                val next = document.update(
                    preparedSource = preparedSource,
                    inputContent = input.content,
                    isStreaming = input.isStreaming,
                ).copy(textDeltas = input.textDeltas)
                next to preparedSource
            }
            // Parsing is not cooperatively cancellable. A revision gate provides mapLatest
            // semantics anyway: if tokens arrived during parsing, keep the previous measured tree
            // until the newest parse succeeds instead of flashing this stale snapshot.
            if (offeredRevision.get() == input.revision) {
                interactionCommitGate.offer(next)?.let { published ->
                    val nowMs = SystemClock.uptimeMillis()
                    _snapshot.value = published.copy(
                        fadeSample = fadeTracker.update(
                            text = preparedSource,
                            nowMs = nowMs,
                            textDeltas = published.textDeltas,
                        ),
                    )
                }
                lastRenderedAtMs = SystemClock.uptimeMillis()
            }
        }
    }

    fun close() {
        inputs.close()
    }
}

@Composable
internal fun IncrementalStreamingMarkdownContent(
    content: String,
    isStreaming: Boolean,
    renderContext: ChatMarkdownRenderContext,
    modifier: Modifier = Modifier,
    selectionEnabled: Boolean = !isStreaming,
    textDeltas: List<StreamingTextDelta>? = null,
    fadeTracker: StreamingTailFadeTracker = remember { StreamingTailFadeTracker() },
) {
    var hasStreamed by remember { mutableStateOf(isStreaming || !textDeltas.isNullOrEmpty()) }
    SideEffect {
        if (isStreaming || !textDeltas.isNullOrEmpty()) hasStreamed = true
    }

    // A historical message can use the library's normal full-document path. A message that was
    // observed streaming stays on the incremental path during terminalization, preserving every
    // already-rendered stable block instead of replacing the whole subtree at Stop/Done.
    if (!isStreaming && !hasStreamed) {
        MarkdownSelectionHost(selectionEnabled) {
            Column(modifier = modifier) {
                MarkdownTextContent(
                    text = content,
                    renderContext = renderContext,
                )
            }
        }
        return
    }

    val state = remember(renderContext.flavour, renderContext.parseInlineDollarMath) {
        StreamingMarkdownRenderState(
            flavour = renderContext.flavour,
            parseInlineDollarMath = renderContext.parseInlineDollarMath,
            initialContent = content,
            initialIsStreaming = isStreaming,
            fadeTracker = fadeTracker,
        )
    }
    LaunchedEffect(state) {
        state.run()
    }
    LaunchedEffect(state, content, isStreaming, textDeltas) {
        // One offer per actual input snapshot. A SideEffect here also ran after fade-clock
        // recompositions and needlessly woke the parser worker for unchanged text.
        state.offer(content, isStreaming, textDeltas)
    }
    DisposableEffect(state) {
        onDispose(state::close)
    }

    val snapshot by state.snapshot.collectAsState()
    val lastVisibleBlockIndex = if (snapshot.liveBlock?.hasVisibleContent == true) {
        snapshot.stableBlocks.size
    } else snapshot.stableBlocks.indexOfLast { it.hasVisibleContent }
    val blockFadeSpecs = remember(snapshot) { computeBlockFadeSpecs(snapshot) }
    val referenceHandler = remember(snapshot.references) {
        MarkdownDocumentReferenceHandler(snapshot.references)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStreamingMarkdownInteractionController provides state,
    ) {
        MarkdownSelectionHost(selectionEnabled) {
            Column(modifier = modifier) {
                snapshot.stableBlocks.forEachIndexed { index, block ->
                    key(block.startOffset, block.identity) {
                        // A stable block inside the document fade window (the just-promoted
                        // block) keeps aging its tail instead of snapping to solid.
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalStreamingGlyphFadeSpec provides blockFadeSpecs.getOrNull(index),
                        ) {
                            StableMarkdownBlockContent(
                                block, renderContext, referenceHandler,
                                preserveTrailingSpacing = index < lastVisibleBlockIndex,
                            )
                        }
                    }
                }
                snapshot.liveBlock?.let { block ->
                    // The key is the tail's document start, not its changing content. Appending
                    // text and terminalization retain the Markdown subtree, fade clocks, and the
                    // code block's horizontal ScrollState.
                    key("live-tail", block.startOffset) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalStreamingGlyphFadeSpec provides blockFadeSpecs.lastOrNull(),
                        ) {
                            ParsedMarkdownBlockContent(
                                sourceContent = block.sourceContent,
                                root = block.root,
                                renderContext = renderContext,
                                referenceHandler = referenceHandler,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownSelectionHost(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    // A stable selection owner is cheaper and safer than replacing the complete Markdown subtree
    // at stream completion. This mirrors Kelivo's persistent SelectionArea; the renderer never
    // changes call sites merely because the message became terminal.
    NoAutoScrollSelectionContainer(
        enabled = enabled,
        content = content,
    )
}

@Composable
private fun StableMarkdownBlockContent(
    block: StableMarkdownBlock,
    renderContext: ChatMarkdownRenderContext,
    referenceHandler: ReferenceLinkHandler,
    preserveTrailingSpacing: Boolean,
) {
    ParsedMarkdownBlockContent(
        sourceContent = block.sourceContent,
        root = block.root,
        renderContext = renderContext,
        referenceHandler = referenceHandler,
        preserveTrailingSpacing = preserveTrailingSpacing,
    )
}

@Composable
private fun ParsedMarkdownBlockContent(
    sourceContent: String,
    root: ASTNode,
    renderContext: ChatMarkdownRenderContext,
    referenceHandler: ReferenceLinkHandler,
    preserveTrailingSpacing: Boolean = false,
) {
    val inlineContent = LocalMarkdownInlineContent.current
    val state = remember(sourceContent, root, referenceHandler) {
        State.Success(
            node = root,
            content = sourceContent,
            linksLookedUp = true,
            referenceLinkHandler = referenceHandler,
        )
    }
    val visibleNodes = remember(root, preserveTrailingSpacing) {
        markdownVisibleBlocks(root, preserveTrailingSpacing)
    }
    com.mikepenz.markdown.compose.Markdown(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        colors = renderContext.colors,
        typography = renderContext.typography,
        padding = renderContext.padding,
        components = renderContext.components,
        annotator = renderContext.annotator,
        imageTransformer = renderContext.imageTransformer,
        inlineContent = inlineContent,
        animations = markdownAnimations { this },
        success = { successState, components, successModifier ->
            Column(successModifier) {
                visibleNodes.forEach { node ->
                    MarkdownElement(
                        node = node,
                        components = components,
                        content = successState.content,
                        // The normal full-document renderer includes the block spacer for its
                        // first node too. Matching that contract removes live/final padding drift.
                        includeSpacer = true,
                    )
                }
            }
        },
    )
}
