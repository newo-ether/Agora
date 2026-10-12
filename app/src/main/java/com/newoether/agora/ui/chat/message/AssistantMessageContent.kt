package com.newoether.agora.ui.chat.message

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.citationRecords
import com.newoether.agora.ui.chat.GenerationActivityDot
import com.newoether.agora.ui.chat.shouldShowStreamingTailIndicator
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.motion.AnimatedVisibility
import com.newoether.agora.ui.motion.animateFloat
import com.newoether.agora.ui.motion.animateFloatAsState

internal val AssistantMessageHorizontalInset = 8.dp
private val FormerAssistantStatusSpacerHeight = 6.dp

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
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    contextAlpha: Modifier,
    isStreaming: Boolean,
    isLoading: Boolean,
    isStopping: Boolean,
    isRegenerationExiting: Boolean,
    isEditingAllowed: Boolean,
    showActions: Boolean,
    includeOuterSpacing: Boolean = true,
    actionCopyText: String?,
    showBranchSelector: Boolean,
    toolCallDisplayMode: String,
    thinkingSegmentDisplayMode: String,
    autoExpandActiveGroup: Boolean,

    groupedSegmentAutoExpansionController: GroupedSegmentAutoExpansionController,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean>,
    renderContext: ChatMarkdownRenderContext,
    searchHighlight: SearchHighlightSpec?,
    branchIndex: Int,
    totalBranches: Int,
    onSwitchBranch: (Int) -> Unit,
    onRegenerate: (String) -> Boolean,
    onFork: () -> Unit,
    onShare: () -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onShowInfo: () -> Unit,
    onShowDelete: () -> Unit,
    onSegmentSelected: (List<Int>, Boolean) -> Unit,
    onLayoutMutationStarted: (String) -> Unit,
    onLayoutMutationSettled: (String) -> Unit,
    setThoughtBlockHeight: (Int) -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalAgoraHaptics.current
    val uriHandler = LocalUriHandler.current
    val citations = remember(message.text, message.segments) {
        message.citationRecords()
    }
    var selectedCitation by remember(message.id) { mutableStateOf<CitationRecord?>(null) }
    var showCitationSources by remember(message.id) { mutableStateOf(false) }
    var groupedCitationSources by remember(message.id) {
        mutableStateOf<List<CitationRecord>?>(null)
    }
    val onSingleCitationActivate: (CitationRecord) -> Unit = { source ->
        val safeUrl = CitationPolicy.safeHttpUrl(source.url)
        if (safeUrl == null || runCatching { uriHandler.openUri(safeUrl) }.isFailure) {
            selectedCitation = source
        }
    }
    val onCitationActivate: (List<CitationRecord>) -> Unit = { sources ->
        if (sources.size > 1) {
            showCitationSources = false
            groupedCitationSources = sources
        } else {
            sources.singleOrNull()?.let(onSingleCitationActivate)
        }
    }
    selectedCitation?.let { source ->
        CitationSourceDetailDialog(
            source = source,
            onDismiss = { selectedCitation = null },
        )
    }
    var showMenu by remember(message.id) { mutableStateOf(false) }
    var regenerateRequested by remember(message.id) { mutableStateOf(false) }
    var observedRegenerationExit by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(isRegenerationExiting) {
        if (isRegenerationExiting) {
            observedRegenerationExit = true
        } else if (observedRegenerationExit) {
            // An aborted transition keeps the old answer composed. Restore its controls only
            // after the externally-owned regeneration state has genuinely ended.
            regenerateRequested = false
            observedRegenerationExit = false
        }
    }
    val regenerationActionsExiting = regenerateRequested || isRegenerationExiting
    val actionAvailability = assistantActionAvailability(
        isStreaming = isStreaming,
        isLoading = isLoading,
        regenerateRequested = regenerationActionsExiting,
    )
    val sourcesSummaryVisible = citationSummaryVisible(
        showActions = showActions,
        informationVisible = actionAvailability.informationVisible,
        sourceCount = citations.size,
    )
    LaunchedEffect(regenerationActionsExiting, sourcesSummaryVisible) {
        if (regenerationActionsExiting) showMenu = false
        if (!sourcesSummaryVisible) showCitationSources = false
    }
    if (showCitationSources) {
        CitationSourcesBottomSheet(
            messageId = message.id,
            citations = citations,
            searchSpec = null,
            onActivate = { source ->
                haptics.confirm()
                onSingleCitationActivate(source)
            },
            onDismiss = { showCitationSources = false },
        )
    }
    groupedCitationSources?.let { groupedSources ->
        CitationSourcesBottomSheet(
            messageId = message.id,
            citations = groupedSources,
            searchSpec = null,
            onActivate = { source ->
                haptics.confirm()
                onSingleCitationActivate(source)
            },
            onDismiss = { groupedCitationSources = null },
        )
    }
    // During generation, eat horizontal nested-scroll so code blocks
    // cannot be panned. Vertical scroll and taps (thinking header,
    // stop button) pass through normally. Text selection is already
    // prevented during streaming by the stable Markdown selection host.
    val horizontalScrollEater = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                Offset(available.x, 0f)
        }
    }
    val failedToGenerateText = stringResource(R.string.failed_to_generate)
    val presentation = remember(
        message,
        isStreaming,
        toolCallDisplayMode,
        thinkingSegmentDisplayMode,
        failedToGenerateText,
    ) {
        assistantContentPresentation(
            message = message,
            isStreaming = isStreaming,
            toolCallDisplayMode = toolCallDisplayMode,
            thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
            failedToGenerateText = failedToGenerateText,
        )
    }
    val mergedSegments = presentation.mergedSegments
    val answerTextDeltas = remember(mergedSegments) {
        mergedSegments
            .filter { it.isVisibleAnswerSegment() }
            .flatMap { it.streamingTextDeltas }
    }
    val answerFadeTracker =
        segmentAppearanceRegistry.streamingFadeTracker("${message.id}:answer")
    val generationActive = presentation.generationActive
    val hasAnswerContent = presentation.hasAnswerContent
    val inlineActivityPresentation = assistantInlineActivityPresentation(
        generationActive = generationActive,
        isStopping = isStopping,
        hasAnswer = hasAnswerContent,
        hasVisibleInfoSegment = mergedSegments.any { it.isInfoSegment() },
        retryText = message.retryText,
    )
    val inlineActivityMode = inlineActivityPresentation.mode
    val inlineActivityTransition = updateTransition(
        targetState = inlineActivityMode != AssistantInlineActivityMode.NONE ||
            inlineActivityPresentation.retainLayout,
        label = "AssistantInlineActivityVisibility",
    )
    val inlineActivityOpacity by inlineActivityTransition.animateFloat(
        transitionSpec = { snap() },
        label = "AssistantInlineActivityOpacity",
    ) { visible ->
        if (visible) 1f else 0f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AssistantMessageHorizontalInset)
            .then(contextAlpha)
            .then(if (isStreaming) Modifier.nestedScroll(horizontalScrollEater) else Modifier)
    ) {
        Column {
            if (includeOuterSpacing) Spacer(modifier = Modifier.height(FormerAssistantStatusSpacerHeight))
            InteractiveAnswerCards(message)

            // GenerationManager already publishes a bounded stream cadence. A second UI debounce
            // delayed every chunk, retained a stale text job through Stop, and then replaced the
            // whole document at terminalization. Feed the latest immutable snapshot directly to
            // the off-main Markdown parser.
            val renderedText = message.text

            Column {

                // Only zero out thought height when legacy thought block is not shown
                if (message.segments != null || message.thoughts.isNullOrBlank()) {
                    setThoughtBlockHeight(0)
                }
                val errorContent = presentation.errorContent
                val orderedSegments = presentation.orderedSegments
                val normalizedToolCallDisplayMode = presentation.normalizedToolCallDisplayMode
                val useThinkingSheet = presentation.useThinkingSheet
                val groupAdjacentTimelineTools = presentation.groupAdjacentTimelineTools
                val groupOrderedInfoBlocks = presentation.groupOrderedInfoBlocks
                val useTimelineSegments = presentation.useTimelineSegments
                val detailSegments = presentation.detailSegments
                val compactVisible = presentation.compactVisible
                val sheetCollapsedStates = remember(message.id) {
                    mutableStateMapOf<String, Boolean>()
                }
                val compactAppearanceKey = compactSegmentBlockAppearanceKey(message.id)
                val compactCardAppearanceKey = "$compactAppearanceKey:card"
                val latestVisibleAnswerIndex =
                    mergedSegments.indexOfLast { it.isVisibleAnswerSegment() }
                val latestVisibleAnswer = mergedSegments.getOrNull(latestVisibleAnswerIndex)
                val compactAnswerAppearanceKey = latestVisibleAnswer?.let { segment ->
                    "${segmentAppearanceKey(
                        message.id,
                        latestVisibleAnswerIndex,
                        segment,
                    )}:compact-answer"
                }

                if (useTimelineSegments) {
                    TimelineSegmentsContent(
                        segments = orderedSegments,
                        detailSegments = detailSegments,
                        message = message,
                        isStreaming = isStreaming,
                        generationActive = generationActive,
                        groupAdjacentBlocks = groupOrderedInfoBlocks,
                        autoExpandActiveGroup =
                            groupAdjacentTimelineTools && autoExpandActiveGroup,
                        autoExpansionController = groupedSegmentAutoExpansionController,
                        expandedStates =
                            if (useThinkingSheet) sheetCollapsedStates else thoughtExpandedStates,
                        renderContext = renderContext,
                        searchHighlight = searchHighlight,
                        citations = citations,
                        onCitationActivate = onCitationActivate,
                        segmentAppearanceRegistry = segmentAppearanceRegistry,
                        onLayoutMutationStarted = onLayoutMutationStarted,
                        onLayoutMutationSettled = onLayoutMutationSettled,
                        onMediaClick = onMediaClick,
                        opensDetailSheet = useThinkingSheet,
                        preserveInitialCompactIdentity =
                            normalizedToolCallDisplayMode == ToolCallDisplayModes.COMPACT ||
                                useThinkingSheet,
                        onGroupHeaderClick = if (useThinkingSheet) {
                            { indices -> onSegmentSelected(indices, true) }
                        } else {
                            null
                        },
                        onSegmentClick = { indices ->
                            onSegmentSelected(indices, false)
                        }
                    )
                }

                // Compact segment block: single block, newest title/icon when collapsed.
                // Answer segments are timeline anchors only; compact mode still renders
                // message.text below as the complete answer.
                if (compactVisible) {
                    AnimatedTimelineBlockAppearance(
                        animationKey = compactAppearanceKey,
                        appearanceRegistry = segmentAppearanceRegistry,
                        isStreaming = isStreaming,
                        forceOpaque = detailSegments.any { it.type == "tool" },
                    ) {
                        CompactSegmentBlock(
                            segs = detailSegments,
                            segmentIndices = detailSegments.indices.toList(),
                            message = message,
                            isStreaming = isStreaming,
                            useLiveStatus = true,
                            generationActive = generationActive,
                            isCurrentCard = !hasAnswerContent,
                            expandedStates = if (useThinkingSheet) sheetCollapsedStates else thoughtExpandedStates,
                            expansionKey = message.id,
                            cardAppearanceKey = compactCardAppearanceKey,
                            segmentAppearanceRegistry = segmentAppearanceRegistry,
                            onExpansionStarted = onLayoutMutationStarted,
                            onExpansionSettled = onLayoutMutationSettled,
                            onSegmentClick = { index ->
                                if (useThinkingSheet) {
                                    onSegmentSelected(detailSegments.indices.toList(), true)
                                } else {
                                    onSegmentSelected(listOf(index), false)
                                }
                            },
                            onHeaderClick = if (useThinkingSheet) {
                                {
                                    onSegmentSelected(
                                        detailSegments.indices.toList(),
                                        true,
                                    )
                                }
                            } else {
                                null
                            },
                            opensDetailSheet = useThinkingSheet,
                            onBlockHeightChanged = setThoughtBlockHeight,
                        )
                    }
                }

                val answerBodyText = presentation.answerBodyText
                val answerProjection = remember(answerBodyText, citations, isStreaming) {
                    citationMarkdownProjection(
                        answerText = answerBodyText.orEmpty(),
                        citations = citations,
                        isStreaming = isStreaming,
                    )
                }
                val answerContent = answerProjection?.markdown ?: answerBodyText.orEmpty()
                val terminalImmediatelyFollowsCard =
                    presentation.terminalImmediatelyFollowsCard(answerContent)
                val inlineTerminalText = presentation.inlineTerminalText(
                    message = message,
                    isStreaming = isStreaming,
                    stoppedText = stringResource(R.string.generation_stopped),
                )
                if (message.participant == Participant.MODEL) {
                    AssistantInlineActivity(
                        mode = inlineActivityMode,
                        retryText = message.retryText,
                        visibilityTransition = inlineActivityTransition,
                        activityOpacity = inlineActivityOpacity,
                        retainExitLayout = inlineActivityPresentation.retainLayout,
                        terminalText = inlineTerminalText,
                        terminalIsError = errorContent != null,
                        terminalShowLocalContextHelp =
                            errorContent?.showLocalContextHelp == true,
                        precededByCard = terminalImmediatelyFollowsCard,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .noOpBringIntoView()
                ) {
                    if (answerContent.isNotEmpty() && !useTimelineSegments) {
                        CitationTerminalProjectionHost(
                            animationKey = "${message.id}:answer",
                            projection = answerProjection,
                            isStreaming = isStreaming,
                            onLayoutMutationStarted = onLayoutMutationStarted,
                            onLayoutMutationSettled = onLayoutMutationSettled,
                            modifier = Modifier.fillMaxWidth(),
                        ) { presentedProjection, presentedIsStreaming ->
                            val presentedContent =
                                presentedProjection?.markdown ?: answerBodyText.orEmpty()
                            CitationInlineContentHost(
                                projection = presentedProjection,
                                onActivate = onCitationActivate,
                            ) {
                                CompositionLocalProvider(
                                    LocalSearchHighlightSpec provides searchHighlight,
                                ) {
                                if (compactAnswerAppearanceKey != null) {
                                    AnimatedTimelineBlockAppearance(
                                        animationKey = compactAnswerAppearanceKey,
                                        appearanceRegistry = segmentAppearanceRegistry,
                                        isStreaming = isStreaming,
                                    ) {
                                        StreamingMarkdownMessage(
                                            content = presentedContent,
                                            isStreaming = presentedIsStreaming,
                                            renderContext = renderContext,
                                            modifier = Modifier.fillMaxWidth(),
                                            selectionEnabled = !presentedIsStreaming,
                                            textDeltas = answerTextDeltas,
                                            fadeTracker = answerFadeTracker,
                                        )
                                    }
                                } else {
                                    StreamingMarkdownMessage(
                                        content = presentedContent,
                                        isStreaming = presentedIsStreaming,
                                        renderContext = renderContext,
                                        modifier = Modifier.fillMaxWidth(),
                                        selectionEnabled = !presentedIsStreaming,
                                        textDeltas = answerTextDeltas,
                                        fadeTracker = answerFadeTracker,
                                    )
                                }
                            }
                        }
                    }
                }
                }
                var retainedErrorText by remember { mutableStateOf("") }
                var retainedShowLocalContextHelp by remember { mutableStateOf(false) }
                LaunchedEffect(errorContent) {
                    errorContent?.let {
                        retainedErrorText = it.errorText
                        retainedShowLocalContextHelp = it.showLocalContextHelp
                    }
                }
                AnimatedVisibility(
                    visible = presentation.showsErrorBar,
                    enter = fadeIn(tween(durationMillis = 180, easing = LinearEasing)),
                    exit = fadeOut(tween(durationMillis = 180, easing = LinearEasing)),
                ) {
                    GenerationErrorBar(
                        errorText = errorContent?.errorText ?: retainedErrorText,
                        precededByCard = terminalImmediatelyFollowsCard,
                        showLocalContextHelp =
                            errorContent?.showLocalContextHelp
                                ?: retainedShowLocalContextHelp,
                    )
                }
                AnimatedVisibility(
                    visible = presentation.showsStoppedBar(message, isStreaming),
                    enter = fadeIn(tween(durationMillis = 180, easing = LinearEasing)),
                    exit = fadeOut(tween(durationMillis = 180, easing = LinearEasing)),
                ) {
                    StoppedGenerationBar(
                        precededByCard = terminalImmediatelyFollowsCard,
                    )
                }
                if (message.participant == Participant.MODEL && message.images.isNotEmpty()) {
                    val genImages = message.images
                    // Generated images are primary output, not input references:
                    // render as a full-width square card, image cropped to fill
                    // with rounded corners, tap to view fullscreen.
                    Column(
                        modifier = Modifier.padding(top = if (renderedText.isNotEmpty()) 8.dp else 0.dp),
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
                                        onLongClick = { haptics.longPress() },
                                        hapticFeedbackEnabled = false,
                                    )
                            )
                        }
                    }
                }
                if (message.participant == Participant.MODEL && showActions) {
                    val informationActionsAlpha by animateFloatAsState(
                        targetValue = if (actionAvailability.informationVisible) 1f else 0f,
                        animationSpec = tween(
                            durationMillis = if (actionAvailability.informationVisible) {
                                ACTIONS_ENTER_DURATION_MS
                            } else {
                                ACTIONS_EXIT_DURATION_MS
                            },
                            easing = LinearEasing,
                        ),
                        label = "assistantInformationActions:${message.id}",
                    )
                    val terminalActionsAlpha by animateFloatAsState(
                        targetValue = if (actionAvailability.terminalVisible) 1f else 0f,
                        animationSpec = tween(
                            durationMillis = if (actionAvailability.terminalVisible) {
                                ACTIONS_ENTER_DURATION_MS
                            } else {
                                ACTIONS_EXIT_DURATION_MS
                            },
                            easing = LinearEasing,
                        ),
                        label = "assistantActions:${message.id}",
                    )
                    val enabledActionTint =
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    val terminalActionTint =
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (actionAvailability.terminalEnabled) 0.6f else 0.3f
                        )
                    val destructiveActionTint =
                        MaterialTheme.colorScheme.error.copy(
                            alpha = if (actionAvailability.terminalEnabled) 1f else 0.38f
                        )
                    if (sourcesSummaryVisible || informationActionsAlpha > 0f) {
                        CitationSourcesSummaryCapsule(
                            messageId = message.id,
                            citations = citations,
                            searchSpec = null,
                            visible = sourcesSummaryVisible,
                            enabled = sourcesSummaryVisible,
                            onClick = {
                                groupedCitationSources = null
                                showCitationSources = true
                            },
                            modifier = Modifier
                                .offset(x = (-AUXILIARY_CARD_START_EXTENSION_DP).dp)
                                .padding(top = 12.dp)
                                .graphicsLayer { alpha = informationActionsAlpha },
                        )
                    }
                    val answerTailVisible = shouldShowStreamingTailIndicator(isStreaming, isStopping, message)
                    val actionContent: @Composable RowScope.() -> Unit = {
                        if (!actionCopyText.isNullOrBlank()) {
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(actionCopyText))
                                    haptics.confirm()
                                },
                                enabled = actionAvailability.informationEnabled,
                                modifier = Modifier
                                    .size(32.dp)
                                    .graphicsLayer { alpha = informationActionsAlpha },
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = enabledActionTint,
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                if (onRegenerate(message.id)) {
                                    regenerateRequested = true
                                    showMenu = false
                                }
                            },
                            enabled = actionAvailability.terminalEnabled,
                            modifier = Modifier
                                .size(32.dp)
                                .graphicsLayer { alpha = terminalActionsAlpha },
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(19.dp),
                                tint = terminalActionTint,
                            )
                        }
                        IconButton(
                            onClick = onFork,
                            enabled = actionAvailability.terminalEnabled,
                            modifier = Modifier
                                .size(32.dp)
                                .graphicsLayer { alpha = terminalActionsAlpha },
                        ) {
                            Icon(
                                Icons.Default.CallSplit,
                                contentDescription = stringResource(R.string.conversation_fork_from_here),
                                modifier = Modifier.size(18.dp),
                                tint = terminalActionTint,
                            )
                        }
                        IconButton(
                            onClick = onShare,
                            enabled = actionAvailability.terminalEnabled,
                            modifier = Modifier
                                .size(32.dp)
                                .graphicsLayer { alpha = terminalActionsAlpha },
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.conversation_share),
                                modifier = Modifier.size(16.dp),
                                tint = terminalActionTint,
                            )
                        }
                        Box {
                            IconButton(
                                onClick = {
                                    showMenu = true
                                },
                                enabled = actionAvailability.informationEnabled,
                                modifier = Modifier
                                    .size(32.dp)
                                    .graphicsLayer { alpha = informationActionsAlpha },
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = enabledActionTint,
                                )
                            }
                            AgoraDropdownMenu(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 16.dp,
                                expanded = showMenu && actionAvailability.informationVisible,
                                onDismissRequest = { showMenu = false },
                            ) {
                                AgoraDropdownMenuItem(
                                    text = { Text(stringResource(R.string.info)) },
                                    onClick = {
                                        showMenu = false
                                        onShowInfo()
                                    },
                                    enabled = actionAvailability.informationEnabled,
                                    leadingIcon = { Icon(Icons.Default.Info, null) },
                                )
                                AgoraDropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.delete),
                                            color = destructiveActionTint,
                                        )
                                    },
                                    onClick = {
                                        if (actionAvailability.terminalEnabled) {
                                            showMenu = false
                                            onShowDelete()
                                        }
                                    },
                                    enabled = actionAvailability.terminalEnabled,
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = destructiveActionTint,
                                        )
                                    },
                                )
                            }
                        }

                        if (showBranchSelector && totalBranches > 1) {
                            AssistantBranchSelector(
                                branchIndex = branchIndex,
                                totalBranches = totalBranches,
                                terminalActionsAlpha = terminalActionsAlpha,
                                terminalEnabled = actionAvailability.terminalEnabled,
                                isEditingAllowed = isEditingAllowed,
                                onSwitchBranch = onSwitchBranch,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth().height(44.dp).padding(top = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (answerTailVisible) GenerationActivityDot()
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actionContent,
                        )
                    }
                }

                if (includeOuterSpacing) Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
