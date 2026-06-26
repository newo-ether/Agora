package com.newoether.agora.ui.chat.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.theme.ChatType
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import kotlinx.coroutines.launch

private const val STREAMING_MARKDOWN_FLUSH_MS = 250L

/**
 * The left-aligned assistant (and error) message content: the streaming status header,
 * the thinking / tool-call timeline or compact segment block, the debounced markdown
 * body, any generated images, the stopped indicator, and the regenerate/overflow
 * action row.
 *
 * Extracted from [MessageItem]. The parent owns the reported-height bookkeeping and the
 * segment-detail sheet, so this composable reports the thought block height through
 * [setThoughtBlockHeight] and surfaces clicked segments through [onSegmentSelected].
 */
@Composable
internal fun AssistantMessageContent(
    message: ChatMessage,
    contextAlpha: Modifier,
    isStreaming: Boolean,
    isLoading: Boolean,
    isEditingAllowed: Boolean,
    toolCallDisplayMode: String,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean>,
    isThoughtExpanded: Boolean,
    renderContext: ChatMarkdownRenderContext,
    markdownFlavour: MarkdownFlavourDescriptor,
    branchIndex: Int,
    totalBranches: Int,
    onSwitchBranch: (Int) -> Unit,
    onRegenerate: (String) -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onShowInfo: () -> Unit,
    onShowDelete: () -> Unit,
    onSegmentSelected: (List<Int>) -> Unit,
    setThoughtBlockHeight: (Int) -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalAgoraHaptics.current
    var showMenu by remember { mutableStateOf(false) }

    // During generation, eat horizontal nested-scroll so code blocks
    // cannot be panned. Vertical scroll and taps (thinking header,
    // stop button) pass through normally. Text selection is already
    // prevented during streaming — SelectionContainer is only in the
    // else (!isStreaming) branch.
    val horizontalScrollEater = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                Offset(available.x, 0f)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .then(contextAlpha)
            .then(if (isStreaming) Modifier.nestedScroll(horizontalScrollEater) else Modifier)
    ) {
        Column {
            // Status Header
            if (message.participant == Participant.MODEL) {
                val thinkingStatus = stringResource(R.string.thinking_ellipsis)
                val answeringStatus = stringResource(R.string.answering_ellipsis)
                // Hold the last non-fallback label so transitions between
                // "Thinking… → Answering…" don't flash "Sending…" while
                // the first answer token is still in-flight.
                var heldLabel by remember { mutableStateOf("") }
                var heldStatusText by remember { mutableStateOf("") }
                // Update heldLabel after composition to avoid double-recomposition flash
                val thinkingNow = message.status == MessageStatus.THINKING
                val isToolCalling = message.status == MessageStatus.TOOL_CALLING
                val isTranscribing = message.status == MessageStatus.TRANSCRIBING
                val hasActiveAnswer = message.hasActiveAnswerSegment()
                LaunchedEffect(thinkingNow, hasActiveAnswer, message.status) {
                    heldLabel = when {
                        thinkingNow -> "thinking"
                        isToolCalling -> "calling"
                        isTranscribing -> "transcribing"
                        hasActiveAnswer -> "answering"
                        message.status == MessageStatus.SUCCESS || message.status == MessageStatus.ERROR || message.status == MessageStatus.STOPPED -> ""
                        message.status == MessageStatus.SENDING -> ""
                        else -> heldLabel
                    }
                }
                val toolCallingStatus = stringResource(R.string.tool_calling_ellipsis)
                val transcribingStatus = stringResource(R.string.transcription_ellipsis)
                val statusText = when {
                    message.status == MessageStatus.SUCCESS -> if (message.tokenCount > 0) stringResource(R.string.cost_tokens, message.tokenCount) else null
                    isStreaming && isTranscribing -> transcribingStatus
                    isStreaming && isToolCalling -> toolCallingStatus
                    isStreaming && thinkingNow -> thinkingStatus
                    isStreaming && hasActiveAnswer -> answeringStatus
                    isStreaming -> when (heldLabel) {
                        "thinking" -> thinkingStatus
                        "calling" -> toolCallingStatus
                        "transcribing" -> transcribingStatus
                        "answering" -> answeringStatus
                        else -> stringResource(R.string.sending_ellipsis)
                    }
                    else -> null
                }.let { base ->
                    if (base != null && message.retryText != null) "$base (${message.retryText})"
                    else base
                }
                // Hold the last non-null label so the status bar doesn't collapse
                // during the timing gap between isStreaming→false and the DB
                // emitting the updated message status.
                val displayText = when {
                    statusText != null -> statusText.also { heldStatusText = it }
                    message.status == MessageStatus.SENDING || message.status == MessageStatus.THINKING || message.status == MessageStatus.TOOL_CALLING || message.status == MessageStatus.TRANSCRIBING -> heldStatusText.takeIf { it.isNotEmpty() }
                    else -> null.also { heldStatusText = "" }
                }

                AnimatedVisibility(
                    visible = displayText != null,
                    enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                ) {
                    val text = displayText ?: return@AnimatedVisibility
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                            if (isStreaming) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = if (text == thinkingStatus) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            } else {
                                val icon = when (message.status) {
                                    MessageStatus.SUCCESS -> Icons.Default.CheckCircle
                                    MessageStatus.STOPPED -> Icons.Default.Stop
                                    else -> Icons.Default.Info
                                }
                                Icon(icon, null, modifier = Modifier.size(14.dp), tint = if (message.status == MessageStatus.SUCCESS) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text, style = ChatType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            var debouncedText by remember(isStreaming) { mutableStateOf(if (isStreaming) "" else message.text) }
            if (!isStreaming) {
                debouncedText = message.text
            } else {
                var lastUpdateMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var flushJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                LaunchedEffect(message.text) {
                    if (message.text.isEmpty()) return@LaunchedEffect
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastUpdateMs
                    if (elapsed >= STREAMING_MARKDOWN_FLUSH_MS) {
                        flushJob?.cancel()
                        debouncedText = message.text
                        lastUpdateMs = now
                    } else {
                        flushJob?.cancel()
                        flushJob = launch {
                            kotlinx.coroutines.delay(STREAMING_MARKDOWN_FLUSH_MS - elapsed)
                            debouncedText = message.text
                            lastUpdateMs = System.currentTimeMillis()
                        }
                    }
                }
            }

            // Level 1: anti-shrink for text and thinking content (kept after streaming ends)
            var streamingMaxHeightPx by remember { mutableIntStateOf(0) }
            var thinkingContentMaxHeightPx by remember { mutableIntStateOf(0) }

            // Reset anti-shrink heights when streaming restarts (e.g. regeneration)
            LaunchedEffect(isStreaming) {
                if (isStreaming) {
                    streamingMaxHeightPx = 0
                    thinkingContentMaxHeightPx = 0
                }
            }

            Column {
                val isError = message.status == MessageStatus.ERROR || message.participant == Participant.ERROR

                // Only zero out thought height when legacy thought block is not shown
                if (message.segments != null || message.thoughts.isNullOrBlank()) {
                    setThoughtBlockHeight(0)
                }

                val segmentsOrNull = message.segments
                val mergedSegments = remember(segmentsOrNull) {
                    mergeAdjacentSegments(segmentsOrNull.orEmpty())
                }
                val normalizedToolCallDisplayMode = ToolCallDisplayModes.normalize(toolCallDisplayMode)
                val useTimelineSegments = normalizedToolCallDisplayMode != ToolCallDisplayModes.COMPACT &&
                    mergedSegments.any { it.type == "answer" }
                val groupAdjacentTimelineTools = normalizedToolCallDisplayMode == ToolCallDisplayModes.GROUPED_TIMELINE
                val timelineBlockKeys = remember(message.id, mergedSegments, groupAdjacentTimelineTools) {
                    buildTimelineBlockKeys(message.id, mergedSegments, groupAdjacentTimelineTools)
                }
                val timelineAppearanceSeenKeys = remember(message.id, normalizedToolCallDisplayMode) {
                    timelineBlockKeys.toMutableSet()
                }
                var timelineAppearanceInitialized by remember(message.id, normalizedToolCallDisplayMode) {
                    mutableStateOf(false)
                }
                val timelineAnimatedBlockKeys = if (isStreaming && timelineAppearanceInitialized) {
                    timelineBlockKeys.filterNotTo(linkedSetOf()) { it in timelineAppearanceSeenKeys }
                } else {
                    emptySet()
                }
                SideEffect {
                    timelineAppearanceSeenKeys.addAll(timelineBlockKeys)
                    if (!timelineAppearanceInitialized) {
                        timelineAppearanceInitialized = true
                    }
                }
                val detailSegments = remember(mergedSegments) {
                    mergedSegments.filter { it.type != "answer" }
                }

                AnimatedVisibility(
                    visible = useTimelineSegments,
                    // No container-level expand — each block animates its own alpha+scale
                    // appearance, so the section must not also unfold vertically from the top.
                    enter = fadeIn(tween(350, easing = LinearOutSlowInEasing)),
                    exit = fadeOut(tween(300))
                ) {
                    TimelineSegmentsContent(
                        segments = mergedSegments,
                        detailSegments = detailSegments,
                        message = message,
                        isStreaming = isStreaming,
                        groupAdjacentBlocks = groupAdjacentTimelineTools,
                        expandedStates = thoughtExpandedStates,
                        renderContext = renderContext,
                        animatedBlockKeys = timelineAnimatedBlockKeys,
                        onSegmentClick = { indices ->
                            onSegmentSelected(indices)
                        }
                    )
                }

                // Compact segment block: single block, newest title/icon when collapsed.
                // Answer segments are timeline anchors only; compact mode still renders
                // message.text below as the complete answer.
                AnimatedVisibility(
                    visible = !useTimelineSegments && detailSegments.isNotEmpty(),
                    // alpha + scale, fast-in slow-out — matches the timeline block appearance.
                    // Only plays when the block first appears live (visible false→true), not on history.
                    enter = fadeIn(tween(350, easing = LinearOutSlowInEasing)) +
                        scaleIn(initialScale = 0.9f, animationSpec = tween(350, easing = LinearOutSlowInEasing)),
                    exit = fadeOut(tween(500)) + shrinkVertically(tween(500))
                ) {
                    val segs = detailSegments
                    if (segs.isEmpty()) return@AnimatedVisibility
                    val lastSeg = segs.last()
                    val isLastTool = lastSeg.type == "tool"
                    val isToolInProgress = isLastTool && lastSeg.toolResult == null
                    val isThinking = message.status == MessageStatus.THINKING
                    val isToolCalling = message.status == MessageStatus.TOOL_CALLING
                    val isTranscribing = message.status == MessageStatus.TRANSCRIBING
                    val toolCount = segs.count { it.type == "tool" && it.toolResult != null }
                    val thoughtMs = thoughtDurationMs(segs) ?: message.thoughtTimeMs
                    val hasThought = thoughtMs != null && thoughtMs > 0
                    val collapsedTitle = when {
                        isThinking -> message.thoughtTitle ?: stringResource(R.string.thinking_ellipsis)
                        isTranscribing -> message.thoughtTitle ?: stringResource(R.string.transcription_ellipsis)
                        isToolCalling || isToolInProgress -> toolDisplayName(lastSeg.toolName)
                        else -> {
                            if (hasThought) {
                                thoughtDurationTitle(thoughtMs!!, toolCount)
                            } else if (toolCount > 0) {
                                stringResource(R.string.called_n_tools, toolCount)
                            } else if (message.thoughtTitle != null) {
                                message.thoughtTitle
                            } else if (segs.any { it.type == "transcription" }) {
                                "Image Transcription"
                            } else {
                                stringResource(R.string.thinking_complete)
                            }
                        }
                    }
                    val mergedBottomPadding by animateDpAsState(
                        targetValue = if (isThoughtExpanded) 12.dp else 4.dp,
                        animationSpec = tween(500), label = "mergedPad"
                    )
                    val infiniteTransition = rememberInfiniteTransition(label = "thinkingPulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 0.85f,
                        targetValue = 1.15f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "scale"
                    )
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 0.8f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )
                    val isAnimating = isStreaming && (isThinking || isToolCalling || isTranscribing || isToolInProgress)
                    val iconModifier = Modifier
                        .size(16.dp)
                        .then(
                            if (isAnimating) Modifier.graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                                alpha = pulseAlpha
                            } else Modifier
                        )

                    Surface(
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = mergedBottomPadding + 6.dp)
                            .noOpBringIntoView()
                            .onSizeChanged { setThoughtBlockHeight(it.height) }
                    ) {
                        Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { thoughtExpandedStates[message.id] = !isThoughtExpanded }
                                .padding(10.dp)
                        ) {
                            if (isToolCalling || isToolInProgress) {
                                Icon(Icons.Default.Build, null, modifier = iconModifier, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                            } else if (!isThinking && !hasThought && toolCount > 0) {
                                Icon(Icons.Default.Build, null, modifier = iconModifier, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                            } else if (isTranscribing || collapsedTitle == "Image Transcription") {
                                Icon(Icons.Filled.Image, null, modifier = iconModifier, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                            } else {
                                Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = iconModifier, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                collapsedTitle, style = ChatType.thoughtTitle,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (isThoughtExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                null, modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        AnimatedVisibility(
                            visible = isThoughtExpanded,
                            enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                            exit = fadeOut(tween(400)) + shrinkVertically(tween(400))
                        ) {
                            Column(
                                modifier = Modifier
                                    .then(
                                        if (thinkingContentMaxHeightPx > 0)
                                            Modifier.heightIn(min = with(LocalDensity.current) { thinkingContentMaxHeightPx.toDp() })
                                        else Modifier
                                    )
                                    .onSizeChanged { size ->
                                        if (isStreaming) {
                                            thinkingContentMaxHeightPx = maxOf(thinkingContentMaxHeightPx, size.height)
                                        }
                                    }
                            ) {
                                Spacer(modifier = Modifier.height(2.dp))
                                // Animate only items appended WHILE expanded during streaming.
                                val seenSegIdx = remember(message.id) { mutableSetOf<Int>() }
                                var seenSegInit by remember(message.id) { mutableStateOf(false) }
                                val newSegIdx = if (isStreaming && seenSegInit)
                                    segs.indices.filterNotTo(linkedSetOf()) { it in seenSegIdx } else emptySet()
                                SideEffect {
                                    segs.indices.forEach { seenSegIdx.add(it) }
                                    if (!seenSegInit) seenSegInit = true
                                }
                                segs.forEachIndexed { idx, seg ->
                                  AnimatedTimelineBlockAppearance(
                                    animationKey = "${message.id}:compactseg:$idx",
                                    animate = idx in newSegIdx
                                  ) {
                                   Column {
                                    if ((seg.type == "thought" && seg.content.isNotBlank()) || seg.type == "transcription") {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(18.dp))
                                                .clickable {
                                                    onSegmentSelected(listOf(idx))
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                if (seg.type == "transcription") transcriptionLabel(segs, idx) else stringResource(R.string.tool_thinking),
                                                style = ChatType.meta,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (seg.content.isNotBlank()) {
                                                val flat = seg.content.replace('\n', ' ')
                                                val preview = if (isStreaming && idx == segs.lastIndex) {
                                                    if (flat.length > 60) "…${flat.takeLast(60)}" else flat
                                                } else flat
                                                Text(
                                                    text = preview,
                                                    style = ChatType.metaNormal,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            } else {
                                                Text(
                                                    text = "Image transcription is empty.",
                                                    style = ChatType.metaNormal,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                )
                                            }
                                        }
                                    } else if (seg.type == "tool") {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(18.dp))
                                                .clickable {
                                                    onSegmentSelected(listOf(idx))
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                toolDisplayName(seg.toolName),
                                                style = ChatType.meta,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = toolSummary(seg),
                                                style = ChatType.metaNormal,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                    if (idx < segs.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 2.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                        )
                                    }
                                   }
                                  }
                                }
                            }
                        }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (streamingMaxHeightPx > 0)
                                Modifier.heightIn(min = with(LocalDensity.current) { streamingMaxHeightPx.toDp() })
                            else Modifier
                        )
                        .onSizeChanged { size ->
                            if (isStreaming) {
                                streamingMaxHeightPx = maxOf(streamingMaxHeightPx, size.height)
                            }
                        }
                        .noOpBringIntoView()
                ) {
                    if (isError) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f), contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(12.dp))
                                SelectionContainer {
                                    Text(
                                        debouncedText.ifEmpty { stringResource(R.string.failed_to_generate) },
                                        style = ChatType.errorBody,
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    } else if (debouncedText.isNotEmpty() && !useTimelineSegments) {
                        var keepBlockRenderer by remember(message.id) { mutableStateOf(false) }
                        val useBlockRenderer = isStreaming || keepBlockRenderer
                        val streamingBlocks = rememberStreamingMarkdownBlocks(
                            content = debouncedText,
                            flavour = markdownFlavour,
                            active = useBlockRenderer
                        )

                        LaunchedEffect(isStreaming) {
                            if (isStreaming) {
                                keepBlockRenderer = true
                            }
                        }

                        Box {
                            SelectionContainer {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    if (useBlockRenderer) {
                                        StreamingMarkdownBlockContent(
                                            blocks = streamingBlocks,
                                            renderContext = renderContext,
                                            modifier = Modifier
                                                .fillMaxWidth(),
                                            tailIsStreaming = isStreaming
                                        )
                                    }
                                    if (!useBlockRenderer && !isStreaming) {
                                        key("full-markdown") {
                                            RecomposeSafeMarkdown(
                                                content = debouncedText,
                                                isStreaming = false,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                            ) { text ->
                                                MarkdownTextContent(
                                                    text = text,
                                                    renderContext = renderContext
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (isStreaming) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .pointerInput(Unit) {
                                            detectTapGestures(onLongPress = { })
                                        }
                                )
                            }
                        }
                    }
                }
                if (message.participant == Participant.MODEL && message.images.isNotEmpty()) {
                    val genImages = message.images
                    // Generated images are primary output, not input references:
                    // render as a full-width square card, image cropped to fill
                    // with rounded corners, tap to view fullscreen.
                    Column(
                        modifier = Modifier.padding(top = if (debouncedText.isNotEmpty()) 8.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        genImages.forEachIndexed { idx, path ->
                            coil.compose.AsyncImage(
                                model = path,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = { onMediaClick(genImages, idx) },
                                        onLongClick = { haptics.longPress() }
                                    )
                            )
                        }
                    }
                }
                if (!isStreaming && message.status == MessageStatus.STOPPED) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = if (debouncedText.isNotEmpty()) 8.dp else 0.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Icon(Icons.Default.Info, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.generation_stopped), style = ChatType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }

                if (message.participant == Participant.MODEL) {
                    AnimatedVisibility(
                        visible = !isStreaming,
                        enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { clipboardManager.setText(AnnotatedString(message.text)); haptics.success() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                            IconButton(onClick = { onRegenerate(message.id) }, enabled = !isLoading, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(19.dp), tint = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                                DropdownMenu(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    tonalElevation = 16.dp,
                                    shape = RoundedCornerShape(12.dp),
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.info)) },
                                        onClick = { showMenu = false; onShowInfo() },
                                        leadingIcon = { Icon(Icons.Default.Info, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete), color = if (!isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) },
                                        onClick = { showMenu = false; onShowDelete() },
                                        enabled = !isLoading,
                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = if (!isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) }
                                    )
                                }
                            }

                            if (totalBranches > 1) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .clip(RoundedCornerShape(100))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(horizontal = 4.dp)
                                ) {
                                    IconButton(onClick = { onSwitchBranch(-1) }, enabled = branchIndex > 0 && isEditingAllowed, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(16.dp))
                                    }
                                    Text("${branchIndex + 1} / $totalBranches", style = MaterialTheme.typography.labelSmall)
                                    IconButton(onClick = { onSwitchBranch(1) }, enabled = branchIndex < totalBranches - 1 && isEditingAllowed, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
