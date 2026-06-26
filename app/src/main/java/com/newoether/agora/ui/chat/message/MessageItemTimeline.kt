package com.newoether.agora.ui.chat.message

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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.input.*
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.alpha
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
import com.newoether.agora.ui.common.LocalAgoraHaptics
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
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

// ── Timeline / segment rendering (extracted from MessageItem.kt) ──────────────
// Pure code-motion. Entry points used by MessageItem.kt are `internal`; the rest
// stay file-private. Behavior unchanged.

@Composable
internal fun ToolDetailContent(seg: MessageSegment) {
    val args = seg.toolArgs
    if (!args.isNullOrBlank() && args != "{}") {
        Text(
            stringResource(R.string.arguments_label),
            style = ChatType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        JsonOrPlainView(args)
        Spacer(modifier = Modifier.height(16.dp))
    }

    Text(
        stringResource(R.string.result_label),
        style = ChatType.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    val result = seg.toolResult
    if (result != null && result.isNotEmpty()) {
        JsonOrPlainView(result)
    } else {
        Text(
            text = stringResource(R.string.tool_calling_ellipsis),
            style = ChatType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun segmentDetailTitle(
    seg: MessageSegment,
    detailSegments: List<MessageSegment>,
    detailIndex: Int
): String {
    return when (seg.type) {
        "tool" -> toolDisplayName(seg.toolName)
        "transcription" -> transcriptionLabel(detailSegments, detailIndex)
        else -> stringResource(R.string.tool_thinking)
    }
}

@Composable
internal fun thoughtDurationTitle(thoughtMs: Long, toolCount: Int): String {
    val seconds = (thoughtMs / 1000).toInt()
    return if (toolCount > 0) {
        if (seconds >= 60) {
            stringResource(R.string.thought_for_minutes_called_tools, seconds / 60, seconds % 60, toolCount)
        } else {
            stringResource(R.string.thought_for_seconds_called_tools, seconds, toolCount)
        }
    } else {
        if (seconds >= 60) {
            stringResource(R.string.thought_for_minutes, seconds / 60, seconds % 60)
        } else {
            stringResource(R.string.thought_for_seconds, seconds)
        }
    }
}

@Composable
internal fun compactSegmentTitle(
    segs: List<MessageSegment>,
    message: ChatMessage,
    useLiveStatus: Boolean
): String {
    val lastSeg = segs.lastOrNull() ?: return ""
    val isLastTool = lastSeg.type == "tool"
    val isToolInProgress = isLastTool && lastSeg.toolResult == null
    val isThinking = useLiveStatus && message.status == MessageStatus.THINKING
    val isToolCalling = useLiveStatus && message.status == MessageStatus.TOOL_CALLING
    val isTranscribing = useLiveStatus && message.status == MessageStatus.TRANSCRIBING
    val toolCount = segs.count { it.type == "tool" && it.toolResult != null }
    val thoughtMs = thoughtDurationMs(segs)
    val hasThought = thoughtMs != null && thoughtMs > 0
    return when {
        isThinking -> message.thoughtTitle ?: stringResource(R.string.thinking_ellipsis)
        isTranscribing -> message.thoughtTitle ?: stringResource(R.string.transcription_ellipsis)
        isToolCalling || isToolInProgress -> toolDisplayName(lastSeg.toolName)
        hasThought -> thoughtDurationTitle(thoughtMs!!, toolCount)
        toolCount > 0 -> stringResource(R.string.called_n_tools, toolCount)
        message.thoughtTitle != null -> message.thoughtTitle
        segs.any { it.type == "transcription" } -> "Image Transcription"
        else -> stringResource(R.string.thinking_complete)
    }
}

@Composable
private fun CompactSegmentBlock(
    segs: List<MessageSegment>,
    segmentIndices: List<Int>,
    message: ChatMessage,
    isStreaming: Boolean,
    useLiveStatus: Boolean,
    expandedStates: SnapshotStateMap<String, Boolean>,
    expansionKey: String,
    modifier: Modifier = Modifier,
    topPaddingExtra: Dp = 0.dp,
    bottomPaddingExtra: Dp = 6.dp,
    onSegmentClick: (Int) -> Unit,
    onBlockHeightChanged: (Int) -> Unit = {}
) {
    if (segs.isEmpty()) return
    val isExpanded by remember(expansionKey) {
        derivedStateOf { expandedStates[expansionKey] ?: false }
    }
    var contentMaxHeightPx by remember(expansionKey) { mutableIntStateOf(0) }
    LaunchedEffect(isStreaming, expansionKey) {
        if (isStreaming) {
            contentMaxHeightPx = 0
        }
    }

    val lastSeg = segs.last()
    val isLastTool = lastSeg.type == "tool"
    val isToolInProgress = isLastTool && lastSeg.toolResult == null
    val isThinking = useLiveStatus && message.status == MessageStatus.THINKING
    val isToolCalling = useLiveStatus && message.status == MessageStatus.TOOL_CALLING
    val isTranscribing = useLiveStatus && message.status == MessageStatus.TRANSCRIBING
    val toolCount = segs.count { it.type == "tool" && it.toolResult != null }
    val thoughtMs = thoughtDurationMs(segs)
    val hasThought = thoughtMs != null && thoughtMs > 0
    val collapsedTitle = compactSegmentTitle(segs, message, useLiveStatus)
    val mergedBottomPadding by animateDpAsState(
        targetValue = if (isExpanded) 12.dp else 4.dp,
        animationSpec = tween(500),
        label = "compactSegmentPad"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "timelinePulse")
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
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp + topPaddingExtra, bottom = mergedBottomPadding + bottomPaddingExtra)
            .noOpBringIntoView()
            .onSizeChanged { onBlockHeightChanged(it.height) }
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { expandedStates[expansionKey] = !isExpanded }
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
                    collapsedTitle,
                    style = ChatType.thoughtTitle,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                exit = fadeOut(tween(400)) + shrinkVertically(tween(400))
            ) {
                Column(
                    modifier = Modifier
                        .then(
                            if (contentMaxHeightPx > 0)
                                Modifier.heightIn(min = with(LocalDensity.current) { contentMaxHeightPx.toDp() })
                            else Modifier
                        )
                        .onSizeChanged { size ->
                            if (isStreaming) {
                                contentMaxHeightPx = maxOf(contentMaxHeightPx, size.height)
                            }
                        }
                ) {
                    Spacer(modifier = Modifier.height(2.dp))
                    // Animate only items appended WHILE expanded during streaming — not the
                    // items already present when the block is first expanded, nor on history.
                    val seenSegIdx = remember(expansionKey) { mutableSetOf<Int>() }
                    var seenSegInit by remember(expansionKey) { mutableStateOf(false) }
                    val newSegIdx = if (isStreaming && seenSegInit)
                        segs.indices.filterNotTo(linkedSetOf()) { it in seenSegIdx } else emptySet()
                    SideEffect {
                        segs.indices.forEach { seenSegIdx.add(it) }
                        if (!seenSegInit) seenSegInit = true
                    }
                    segs.forEachIndexed { idx, seg ->
                      AnimatedTimelineBlockAppearance(
                        animationKey = "$expansionKey:seg:$idx",
                        animate = idx in newSegIdx
                      ) {
                       Column {
                        if ((seg.type == "thought" && seg.content.isNotBlank()) || seg.type == "transcription") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable { onSegmentClick(segmentIndices.getOrElse(idx) { idx }) }
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
                                    val preview = if (isStreaming && useLiveStatus && idx == segs.lastIndex) {
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
                                    .clickable { onSegmentClick(segmentIndices.getOrElse(idx) { idx }) }
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

@Composable
internal fun TimelineSegmentsContent(
    segments: List<MessageSegment>,
    detailSegments: List<MessageSegment>,
    message: ChatMessage,
    isStreaming: Boolean,
    groupAdjacentBlocks: Boolean,
    expandedStates: SnapshotStateMap<String, Boolean>,
    renderContext: ChatMarkdownRenderContext,
    animatedBlockKeys: Set<String>,
    onSegmentClick: (List<Int>) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        var detailIndex = 0
        var index = 0
        var groupedBlockIndex = 0
        var previousVisibleWasAnswer = false
        while (index < segments.size) {
            val seg = segments[index]
            when (seg.type) {
                "answer" -> {
                    if (seg.content.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (index == 0) 0.dp else 6.dp)
                        ) {
                            SelectionContainer(modifier = Modifier.noOpBringIntoView()) {
                                RecomposeSafeMarkdown(
                                    content = seg.content,
                                    isStreaming = isStreaming && index == segments.lastIndex,
                                    modifier = Modifier.fillMaxWidth()
                                ) { text ->
                                    MarkdownTextContent(text = text, renderContext = renderContext)
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
                        previousVisibleWasAnswer = true
                    }
                    index++
                }
                "thought", "tool", "transcription" -> {
                    if (groupAdjacentBlocks) {
                        val blockSegments = mutableListOf<MessageSegment>()
                        val blockDetailIndices = mutableListOf<Int>()
                        var blockEnd = index
                        while (blockEnd < segments.size && !segments[blockEnd].isVisibleAnswerSegment()) {
                            val blockSeg = segments[blockEnd]
                            if (blockSeg.isInfoSegment()) {
                                blockSegments.add(blockSeg)
                                blockDetailIndices.add(detailIndex)
                                detailIndex++
                            }
                            blockEnd++
                        }
                        val expansionKey = "${message.id}:group:${blockDetailIndices.firstOrNull() ?: index}"
                        val blockTopPaddingExtra = if (groupedBlockIndex > 0) 8.dp else 0.dp
                        val blockContent: @Composable () -> Unit = {
                            CompactSegmentBlock(
                                segs = blockSegments,
                                segmentIndices = blockDetailIndices,
                                message = message,
                                isStreaming = isStreaming,
                                useLiveStatus = isStreaming && blockDetailIndices.lastOrNull() == detailSegments.lastIndex,
                                expandedStates = expandedStates,
                                expansionKey = expansionKey,
                                topPaddingExtra = blockTopPaddingExtra,
                                bottomPaddingExtra = 0.dp,
                                onSegmentClick = { detailIndex -> onSegmentClick(listOf(detailIndex)) }
                            )
                        }
                        AnimatedTimelineBlockAppearance(
                            animationKey = expansionKey,
                            animate = expansionKey in animatedBlockKeys
                        ) {
                            blockContent()
                        }
                        groupedBlockIndex++
                        previousVisibleWasAnswer = false
                        index = blockEnd
                    } else {
                        val currentDetailIndex = detailIndex
                        detailIndex++
                        val cardTopPaddingExtra = if (previousVisibleWasAnswer) 8.dp else 0.dp
                        val cardContent: @Composable () -> Unit = {
                            TimelineInfoSegmentCard(
                                seg = seg,
                                detailSegments = detailSegments,
                                detailIndex = currentDetailIndex,
                                isStreaming = isStreaming && index == segments.lastIndex,
                                topPaddingExtra = cardTopPaddingExtra,
                                onClick = { onSegmentClick(listOf(currentDetailIndex)) }
                            )
                        }
                        val timelineKey = "${message.id}:timeline:$currentDetailIndex"
                        AnimatedTimelineBlockAppearance(
                            animationKey = timelineKey,
                            animate = timelineKey in animatedBlockKeys
                        ) {
                            cardContent()
                        }
                        previousVisibleWasAnswer = false
                        index++
                    }
                }
                else -> {
                    index++
                }
            }
        }
    }
}

@Composable
private fun TimelineInfoSegmentCard(
    seg: MessageSegment,
    detailSegments: List<MessageSegment>,
    detailIndex: Int,
    isStreaming: Boolean,
    topPaddingExtra: Dp = 0.dp,
    onClick: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp + topPaddingExtra, bottom = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .noOpBringIntoView()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            val isTool = seg.type == "tool"
            val isTranscription = seg.type == "transcription"
            if (isTool) {
                Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            } else if (isTranscription) {
                Icon(Icons.Filled.Image, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            } else {
                Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (seg.type) {
                        "tool" -> toolDisplayName(seg.toolName)
                        "transcription" -> transcriptionLabel(detailSegments, detailIndex)
                        else -> stringResource(R.string.tool_thinking)
                    },
                    style = ChatType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val summary = when (seg.type) {
                    "tool" -> toolSummary(seg)
                    "transcription" -> seg.content.takeIf { it.isNotBlank() } ?: "Image transcription is empty."
                    else -> {
                        val flat = seg.content.replace('\n', ' ')
                        if (isStreaming && flat.length > 60) "…${flat.takeLast(60)}" else flat
                    }
                }
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = ChatType.metaNormal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

