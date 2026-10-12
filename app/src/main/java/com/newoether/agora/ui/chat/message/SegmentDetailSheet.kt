package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.ui.components.CircularBackButton
import com.newoether.agora.ui.components.SmoothBottomSheet
import com.newoether.agora.ui.components.rememberSmoothBottomSheetState
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.NoAutoScrollSelectionContainer
import com.newoether.agora.util.noOpBringIntoView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import com.newoether.agora.ui.motion.AnimatedVisibility
import com.newoether.agora.ui.motion.animateFloatAsState
import com.newoether.agora.ui.components.Text

internal enum class SegmentSheetBackAction { DISMISS, SHOW_LIST }

internal fun resolveSegmentDetailMessage(
    messageId: String,
    streamingMessage: ChatMessage?,
    authoritativeMessage: ChatMessage?,
    roomMessage: ChatMessage?,
): ChatMessage? = when {
    streamingMessage?.id == messageId -> streamingMessage
    authoritativeMessage?.id == messageId && authoritativeMessage.hasSegmentDetailPayload() ->
        authoritativeMessage
    roomMessage?.id == messageId -> roomMessage
    else -> null
}

internal fun segmentDetailIndicesForSnapshot(
    message: ChatMessage,
    requestedIndices: List<Int>,
    showSegmentListFirst: Boolean,
): List<Int> {
    if (!showSegmentListFirst) return requestedIndices
    return mergeAdjacentSegments(message.segments.orEmpty())
        .filter { segment -> segment.type != "answer" }
        .mapIndexedNotNull { index, segment -> index.takeIf { segment.isInfoSegment() } }
}

private fun ChatMessage.hasSegmentDetailPayload(): Boolean =
    mergeAdjacentSegments(segments.orEmpty()).any(MessageSegment::isInfoSegment)

private fun MessageStatus.isSegmentDetailStreaming(): Boolean =
    this == MessageStatus.SENDING ||
        this == MessageStatus.THINKING ||
        this == MessageStatus.TOOL_CALLING ||
        this == MessageStatus.TRANSCRIBING

@Composable
internal fun MessageSegmentDetailHost(
    conversationId: String?,
    authoritativeMessages: List<ChatMessage>,
    streamingMessage: ChatMessage?,
    observeMessage: (String) -> Flow<ChatMessage?>,
    parseInlineDollarMath: Boolean,
    autoWrapCodeBlocks: Boolean = true,
    onMediaClick: (List<String>, Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ((String, List<Int>, Boolean) -> Unit) -> Unit,
) {
    var selectedMessageId by remember(conversationId) { mutableStateOf<String?>(null) }
    var requestedSegmentIndices by remember(conversationId) {
        mutableStateOf<List<Int>>(emptyList())
    }
    var showSegmentListFirst by remember(conversationId) { mutableStateOf(false) }

    Box(modifier = modifier) {
        content { messageId, segmentIndices, listFirst ->
            selectedMessageId = messageId
            requestedSegmentIndices = segmentIndices
            showSegmentListFirst = listFirst
        }
    }

    val messageId = selectedMessageId ?: return
    val roomMessage by remember(messageId, observeMessage) {
        observeMessage(messageId)
    }.collectAsState(initial = null)
    val authoritativeMessage = authoritativeMessages.firstOrNull { message ->
        message.id == messageId
    }
    val message = resolveSegmentDetailMessage(
        messageId = messageId,
        streamingMessage = streamingMessage,
        authoritativeMessage = authoritativeMessage,
        roomMessage = roomMessage,
    ) ?: return
    val selectedIndices = segmentDetailIndicesForSnapshot(
        message = message,
        requestedIndices = requestedSegmentIndices,
        showSegmentListFirst = showSegmentListFirst,
    )
    val markdownRenderContext = rememberChatMarkdownAssets(
        textColor = MaterialTheme.colorScheme.onSurface,
        parseInlineDollarMath = parseInlineDollarMath,
        autoWrapCodeBlocks = autoWrapCodeBlocks,
    ).thoughtRenderContext

    SegmentDetailSheet(
        message = message,
        selectedSegmentIndex = selectedIndices.firstOrNull() ?: -1,
        selectedSegmentIndices = selectedIndices,
        isStreaming = streamingMessage?.id == messageId &&
            streamingMessage.status.isSegmentDetailStreaming(),
        markdownRenderContext = markdownRenderContext,
        onMediaClick = onMediaClick,
        handleBackInternally = showSegmentListFirst,
        showSegmentListFirst = showSegmentListFirst,
        onDismiss = { selectedMessageId = null },
    )
}

internal fun usesVirtualizedSegmentDetail(
    selectedSegmentCount: Int,
    segmentType: String,
    segmentContentIsBlank: Boolean,
    isStreaming: Boolean,
    hasFooter: Boolean,
): Boolean =
    selectedSegmentCount == 1 &&
        segmentType != "tool" &&
        !(segmentType == "transcription" && segmentContentIsBlank) &&
        !isStreaming &&
        !hasFooter

internal fun segmentSheetBackAction(
    handleBackInternally: Boolean,
    showSegmentListFirst: Boolean,
    detailPageIndex: Int,
): SegmentSheetBackAction = if (
    handleBackInternally && showSegmentListFirst && detailPageIndex >= 0
) {
    SegmentSheetBackAction.SHOW_LIST
} else {
    SegmentSheetBackAction.DISMISS
}

// Segment detail bottom sheet (custom implementation).
//
// A self-contained draggable bottom sheet with its own finite-state machine
// (Collapsed / Half / Full) driving an Animatable fraction; the whole gesture +
// snap + dim subsystem lives here. The stable list-level host decides WHICH
// segment(s) to show and toggles visibility via [onDismiss].
@Composable
internal fun SegmentDetailSheet(
    message: ChatMessage,
    selectedSegmentIndex: Int,
    selectedSegmentIndices: List<Int>,
    isStreaming: Boolean,
    markdownRenderContext: ChatMarkdownRenderContext,
    onMediaClick: (List<String>, Int) -> Unit,
    titleOverride: String? = null,
    directMarkdownContent: String? = null,
    directSelectableTextContent: String? = null,
    emptyStreamingText: String? = null,
    errorText: String? = null,
    detailFooter: (@Composable () -> Unit)? = null,
    handleBackInternally: Boolean = false,
    showSegmentListFirst: Boolean = false,
    onDismiss: () -> Unit
) {
    val liveSegs = remember(message.segments) {
        mergeAdjacentSegments(message.segments.orEmpty()).filter { it.type != "answer" }
    }
    val selectedEntries = remember(
        liveSegs,
        selectedSegmentIndices,
        selectedSegmentIndex,
        directMarkdownContent,
        directSelectableTextContent,
    ) {
        if (directSelectableTextContent != null) {
            listOf(0 to MessageSegment(type = "thought", content = directSelectableTextContent))
        } else if (directMarkdownContent != null) {
            listOf(0 to MessageSegment(type = "thought", content = directMarkdownContent))
        } else {
            selectedSegmentIndices.mapNotNull { index ->
                liveSegs.getOrNull(index)?.let { index to it }
            }.ifEmpty {
                liveSegs.getOrNull(selectedSegmentIndex)
                    ?.let { listOf(selectedSegmentIndex to it) }
                    .orEmpty()
            }
        }
    }
    val firstSelectedSegment = selectedEntries.firstOrNull()?.second
    if (firstSelectedSegment == null) {
        LaunchedEffect(message.id, selectedSegmentIndices, selectedSegmentIndex) {
            onDismiss()
        }
    } else {
        var detailPageIndex by remember(message.id, showSegmentListFirst) {
            mutableIntStateOf(if (showSegmentListFirst) -1 else 0)
        }
        var lastDetailPageIndex by remember(message.id) {
            mutableIntStateOf(0)
        }
        val showSegmentListPage = showSegmentListFirst && detailPageIndex < 0
        val renderedEntries = if (showSegmentListFirst) {
            listOfNotNull(
                selectedEntries.getOrNull(
                    lastDetailPageIndex.coerceIn(0, selectedEntries.lastIndex),
                ),
            )
        } else {
            selectedEntries
        }
        val selectedSegs = renderedEntries.map { it.second }
        val seg = selectedSegs.first()
        val selectedLiveIndex = renderedEntries.first().first
        val scrollState = rememberScrollState()
        val segmentListScrollState = rememberScrollState()
        val lazyDetailListState = rememberLazyListState()
        var observedStreamingMarkdown by remember(message.id, selectedLiveIndex) {
            mutableStateOf(isStreaming)
        }
        SideEffect {
            if (isStreaming) observedStreamingMarkdown = true
        }
        val usesVirtualizedSingleMarkdown =
            directMarkdownContent == null &&
                directSelectableTextContent == null &&
                usesVirtualizedSegmentDetail(
                    selectedSegmentCount = selectedSegs.size,
                    segmentType = seg.type,
                    segmentContentIsBlank = seg.content.isBlank(),
                    isStreaming = observedStreamingMarkdown,
                    hasFooter = detailFooter != null || errorText != null,
                )
        val sheetState = rememberSmoothBottomSheetState()

        LaunchedEffect(detailPageIndex) {
            if (detailPageIndex >= 0) {
                scrollState.scrollTo(0)
                lazyDetailListState.scrollToItem(0)
            }
        }

        SmoothBottomSheet(
            state = sheetState,
            onDismissRequest = onDismiss,
            onBackRequest = {
                when (
                    segmentSheetBackAction(
                        handleBackInternally = handleBackInternally,
                        showSegmentListFirst = showSegmentListFirst,
                        detailPageIndex = detailPageIndex,
                    )
                ) {
                    SegmentSheetBackAction.SHOW_LIST -> {
                        detailPageIndex = -1
                        true
                    }
                    SegmentSheetBackAction.DISMISS -> false
                }
            },
            contentAtTop = {
                when {
                    showSegmentListPage -> segmentListScrollState.value == 0
                    usesVirtualizedSingleMarkdown ->
                        lazyDetailListState.firstVisibleItemIndex == 0 &&
                            lazyDetailListState.firstVisibleItemScrollOffset == 0
                    else -> scrollState.value == 0
                }
            },
            header = {
                val sheetTitle = when {
                    titleOverride != null -> titleOverride
                    showSegmentListPage ->
                        compactSegmentDisplayTitle(
                            segs = selectedEntries.map { it.second },
                            message = message,
                            useLiveStatus = true,
                        )
                    selectedSegs.size > 1 ->
                        compactSegmentTitle(
                            selectedSegs,
                            message,
                            useLiveStatus = false,
                        )
                    seg.type == "tool" -> toolDisplayName(seg)
                    seg.type == "transcription" ->
                        transcriptionLabel(liveSegs, selectedLiveIndex)
                    else -> stringResource(R.string.tool_thinking)
                }
                if (showSegmentListFirst && !showSegmentListPage) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                end = 24.dp,
                                top = 4.dp,
                                bottom = 8.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularBackButton(
                            onClick = { detailPageIndex = -1 },
                            containerColor =
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = sheetTitle,
                            style = ChatType.detailTitle,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Text(
                        text = sheetTitle,
                        style = ChatType.detailTitle,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            },
        ) {
            val detailPageContent: @Composable () -> Unit = {
                        if (directSelectableTextContent != null) {
                            DetailContentReveal(
                                revealKey = "${message.id}:select-text",
                                modifier = Modifier
                                    .fillMaxSize()

                                    .noOpBringIntoView()
                                    .navigationBarsPadding(),
                            ) { revealModifier, onReady ->
                                NoAutoScrollSelectionContainer(
                                    modifier = revealModifier.fillMaxSize(),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(scrollState)
                                            .padding(horizontal = 24.dp)
                                            .padding(top = 12.dp, bottom = 32.dp)
                                            .onSizeChanged { onReady() },
                                    ) {
                                        SearchHighlightedPlainText(
                                            text = directSelectableTextContent,
                                            style = ChatType.userBody.copy(fontSize = 14.sp),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            spec = null,
                                        )
                                    }
                                }
                            }
                        } else if (usesVirtualizedSingleMarkdown) {
                            DetailContentReveal(
                                revealKey = "${message.id}:$selectedLiveIndex",
                                modifier = Modifier
                                    .fillMaxSize()

                                    .noOpBringIntoView()
                                    .navigationBarsPadding(),
                            ) { revealModifier, onReady ->
                                NoAutoScrollSelectionContainer(
                                    modifier = revealModifier.fillMaxSize(),
                                ) {
                                    LazyMarkdownTextContent(
                                        text = seg.content,
                                        renderContext = markdownRenderContext,
                                        listState = lazyDetailListState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(
                                            start = 24.dp,
                                            top = 4.dp,
                                            end = 24.dp,
                                            bottom = 32.dp,
                                        ),
                                        onReady = onReady,
                                    )
                                }
                            }
                        } else {
                            // Tool and grouped details use one conventional scroll owner. An
                            // actively streaming Markdown document must retain its incremental
                            // renderer when it becomes terminal, so it remains in this branch.
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()

                                    .verticalScroll(scrollState)
                                    .noOpBringIntoView()
                                    .padding(top = if (seg.type == "tool") 6.dp else 4.dp)
                                    .navigationBarsPadding()
                                    .padding(bottom = 32.dp)
                            ) {
                                if (selectedSegs.size > 1) {
                                    selectedSegs.forEachIndexed { index, detailSeg ->
                                        val detailIndex = renderedEntries.getOrNull(index)?.first
                                            ?: liveSegs.indexOf(detailSeg).coerceAtLeast(0)
                                        Text(
                                            segmentDetailTitle(detailSeg, liveSegs, detailIndex),
                                            style = ChatType.detailTitle,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(
                                                start = 24.dp,
                                                end = 24.dp,
                                                top = if (index == 0) 0.dp else 18.dp,
                                                bottom = 8.dp,
                                            ),
                                        )
                                        if (detailSeg.type == "tool") {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = toolDetailHorizontalPadding(detailSeg)),
                                            ) {
                                                ToolDetailContent(
                                                    segment = detailSeg,
                                                    onMediaClick = onMediaClick,
                                                )
                                            }
                                        } else if (
                                            detailSeg.type == "transcription" &&
                                            detailSeg.content.isBlank()
                                        ) {
                                            Text(
                                                text = "Image transcription is empty.",
                                                style = ChatType.body,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.4f),
                                                modifier = Modifier.padding(horizontal = 24.dp),
                                            )
                                        } else if (
                                            detailSeg.type == "transcription" &&
                                            (detailSeg.content ==
                                                stringResource(R.string.transcription_ellipsis_single) ||
                                                detailSeg.content ==
                                                stringResource(R.string.transcription_ellipsis))
                                        ) {
                                            Text(
                                                text = detailSeg.content,
                                                style = markdownRenderContext.plainTextStyle,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 24.dp),
                                            )
                                        } else {
                                            val detailIsStreaming =
                                                isStreaming && index == selectedSegs.lastIndex
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 24.dp),
                                            ) {
                                                StreamingDetailMarkdownReveal(
                                                    revealKey = "${message.id}:$detailIndex",
                                                    content = detailSeg.content,
                                                    isStreaming = detailIsStreaming,
                                                    renderContext = markdownRenderContext,
                                                )
                                            }
                                        }
                                        if (index < selectedSegs.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(
                                                    start = 24.dp,
                                                    top = 18.dp,
                                                    end = 24.dp,
                                                ),
                                                color = MaterialTheme.colorScheme.outlineVariant
                                                    .copy(alpha = 0.3f),
                                            )
                                        }
                                    }
                                } else if (seg.type == "tool") {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = toolDetailHorizontalPadding(seg)),
                                    ) {
                                        ToolDetailContent(
                                            segment = seg,
                                            onMediaClick = onMediaClick,
                                        )
                                    }
                                } else if (
                                    seg.type == "transcription" &&
                                    seg.content.isBlank()
                                ) {
                                    Text(
                                        text = "Image transcription is empty.",
                                        style = ChatType.body,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.4f),
                                        modifier = Modifier.padding(horizontal = 24.dp),
                                    )
                                } else if (
                                    seg.type == "transcription" &&
                                    (seg.content ==
                                        stringResource(R.string.transcription_ellipsis_single) ||
                                        seg.content ==
                                        stringResource(R.string.transcription_ellipsis))
                                ) {
                                    Text(
                                        text = seg.content,
                                        style = markdownRenderContext.plainTextStyle,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 24.dp),
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp),
                                    ) {
                                        StreamingDetailMarkdownReveal(
                                            revealKey = "${message.id}:$selectedLiveIndex",
                                            content = seg.content,
                                            isStreaming = isStreaming,
                                            renderContext = markdownRenderContext,
                                            emptyStreamingText = emptyStreamingText,
                                        )
                                    }
                                }
                                errorText?.takeIf(String::isNotBlank)?.let {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp),
                                    ) {
                                        GenerationErrorBar(it)
                                    }
                                }
                                detailFooter?.let { footer ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp),
                                    ) {
                                        footer()
                                    }
                                }
                            }
                        }
                        }
                        if (showSegmentListFirst) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                SegmentSheetAnimatedPage(
                                    visible = showSegmentListPage,
                                    leadingPage = true,
                                ) {
                                    ThinkingSegmentListContent(
                                        message = message,
                                        segments = selectedEntries.map { it.second },
                                        segmentIndices = selectedEntries.map { it.first },
                                        isStreaming = isStreaming,
                                        scrollState = segmentListScrollState,
                                        modifier = Modifier
                                            .fillMaxSize()

                                            .navigationBarsPadding(),
                                        onSegmentSelected = { liveIndex ->
                                            val targetIndex = selectedEntries.indexOfFirst {
                                                it.first == liveIndex
                                            }
                                            if (targetIndex >= 0) {
                                                lastDetailPageIndex = targetIndex
                                                detailPageIndex = targetIndex
                                            }
                                        },
                                    )
                                }
                                SegmentSheetAnimatedPage(
                                    visible = !showSegmentListPage,
                                    leadingPage = false,
                                ) {
                                    detailPageContent()
                                }
                            }
                        } else {
                            detailPageContent()
                        }
        }
    }
}

@Composable
private fun SegmentSheetAnimatedPage(
    visible: Boolean,
    leadingPage: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { width -> if (leadingPage) -width else width } + fadeIn(),
        exit = slideOutHorizontally { width -> if (leadingPage) -width / 3 else width } + fadeOut(),
        content = { content() },
    )
}

@Composable
private fun ThinkingSegmentListContent(
    message: ChatMessage,
    segments: List<MessageSegment>,
    segmentIndices: List<Int>,
    isStreaming: Boolean,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    onSegmentSelected: (Int) -> Unit,
) {
    val appearanceRegistry = remember(message.id) { SegmentAppearanceRegistry() }
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            val liveIndex = segmentIndices.getOrElse(index) { index }
            val groupPosition = segmentGroupPosition(
                hasPrevious = index > 0,
                hasNext = index < segments.lastIndex,
            )
            TimelineInfoSegmentCard(
                seg = segment,
                detailSegments = segments,
                detailIndex = index,
                isStreamingContent = isStreaming && index == segments.lastIndex,
                animateAppearance = false,
                groupPosition = groupPosition,
                neutralPalette = true,
                cardAnimationKey = "${message.id}:sheet-list:$liveIndex",
                segmentAppearanceRegistry = appearanceRegistry,
                onClick = { onSegmentSelected(liveIndex) },
            )
        }
        Spacer(modifier = Modifier.navigationBarsPadding().height(24.dp))
    }
}

@Composable
private fun StreamingDetailMarkdownReveal(
    revealKey: String,
    content: String,
    isStreaming: Boolean,
    renderContext: ChatMarkdownRenderContext,
    emptyStreamingText: String? = null,
) {
    DetailContentReveal(
        revealKey = revealKey,
        modifier = Modifier.fillMaxWidth(),
    ) { revealModifier, onReady ->
        StreamingMarkdownMessage(
            content = content,
            isStreaming = isStreaming,
            renderContext = renderContext,
            modifier = revealModifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (size.height > 0 || content.isEmpty()) onReady()
                },
            selectionEnabled = !isStreaming,
            emptyStreamingText = emptyStreamingText,
        )
    }
}

@Composable
private fun DetailContentReveal(
    revealKey: String,
    modifier: Modifier = Modifier,
    content: @Composable (revealModifier: Modifier, onReady: () -> Unit) -> Unit,
) {
    var ready by remember(revealKey) { mutableStateOf(false) }
    var showLoading by remember(revealKey) { mutableStateOf(false) }
    val revealAlpha by animateFloatAsState(
        targetValue = if (ready) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = LinearEasing),
        label = "detailContentReveal:$revealKey",
    )

    LaunchedEffect(revealKey) {
        delay(120)
        if (!ready) showLoading = true
    }
    LaunchedEffect(ready) {
        if (ready) showLoading = false
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        content(
            Modifier.graphicsLayer { alpha = revealAlpha },
        ) {
            if (!ready) ready = true
        }
        AnimatedVisibility(
            visible = showLoading && !ready,
            enter = fadeIn(tween(160, easing = LinearEasing)),
            exit = fadeOut(tween(140, easing = LinearEasing)),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
