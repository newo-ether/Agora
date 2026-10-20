package com.newoether.agora.ui.chat.message

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.ui.components.*
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.motion.animateDp
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.motion.animateFloatAsState
import com.newoether.agora.ui.motion.AnimatedVisibility
import com.newoether.agora.ui.motion.Crossfade

// ── Timeline / segment rendering (extracted from MessageItem.kt) ──────────────
// Pure code-motion. Entry points used by MessageItem.kt are `internal`; the rest
// stay file-private. Behavior unchanged.

@Composable
private fun StartAnchoredHorizontalOverflowHost(
    content: @Composable BoxScope.() -> Unit,
) = Box(
    modifier = Modifier
        .fillMaxWidth()
        .wrapContentWidth(Alignment.Start, unbounded = true),
    contentAlignment = Alignment.TopStart,
    content = content,
)

internal enum class CompactSegmentIcon {
    LOADING,
    THINKING,
    TOOL,
    IMAGE,
}

internal fun compactSegmentShowsLoading(
    generationActive: Boolean,
    isCurrentCard: Boolean,
): Boolean = generationActive && isCurrentCard

/** Only the current card of an active generation shows live titles. */
internal fun compactSegmentUsesLiveStatus(
    generationActive: Boolean,
    isCurrentCard: Boolean,
    useLiveStatus: Boolean,
): Boolean = generationActive && isCurrentCard && useLiveStatus

/** The collapsed header icon; [collapsedTitle] is the title the header shows. */
internal fun compactSegmentIcon(
    segs: List<MessageSegment>,
    message: ChatMessage,
    generationActive: Boolean,
    isCurrentCard: Boolean,
    cardUsesLiveStatus: Boolean,
    collapsedTitle: String,
): CompactSegmentIcon {
    val isThinking = cardUsesLiveStatus &&
        message.status == MessageStatus.THINKING &&
        segs.any { it.type == "thought" }
    val isTranscribing = cardUsesLiveStatus && message.status == MessageStatus.TRANSCRIBING
    val toolCount = segs.count { it.type == "tool" }
    val thoughtMs = thoughtDurationMs(segs, fallbackMs = message.thoughtTimeMs)
    val hasThought = thoughtMs != null && thoughtMs > 0
    return when {
        compactSegmentShowsLoading(generationActive, isCurrentCard) -> CompactSegmentIcon.LOADING
        !isThinking && !hasThought && toolCount > 0 -> CompactSegmentIcon.TOOL
        isTranscribing || collapsedTitle == "Image Transcription" -> CompactSegmentIcon.IMAGE
        else -> CompactSegmentIcon.THINKING
    }
}

@Composable
internal fun CompactSegmentBlock(
    segs: List<MessageSegment>,
    segmentIndices: List<Int>,
    message: ChatMessage,
    isStreaming: Boolean,
    useLiveStatus: Boolean,
    generationActive: Boolean,
    isCurrentCard: Boolean,
    expandedStates: SnapshotStateMap<String, Boolean>,
    expansionKey: String,
    cardAppearanceKey: String = "$expansionKey:card",
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    autoExpansionController: GroupedSegmentAutoExpansionController? = null,
    autoExpansionEnabled: Boolean = false,
    autoExpansionActive: Boolean = false,
    collapseForImageBoundary: Boolean = false,
    modifier: Modifier = Modifier,
    topPaddingExtra: Dp = 0.dp,
    bottomPaddingExtra: Dp = 6.dp,
    onSegmentClick: (Int) -> Unit,
    onHeaderClick: (() -> Unit)? = null,
    opensDetailSheet: Boolean = false,
    onExpansionStarted: (String) -> Unit = {},
    onExpansionSettled: (String) -> Unit = {},
    onBlockHeightChanged: (Int) -> Unit = {}
) {
    if (segs.isEmpty()) return
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val animateCardAppearance = rememberSegmentAppearance(
        registry = segmentAppearanceRegistry,
        animationKey = cardAppearanceKey,
        isStreaming = isStreaming,
    )
    val cardAppearanceModifier = generationLifecycleAppearanceModifier(
        animationKey = cardAppearanceKey,
        animate = animateCardAppearance,
        durationMillis = SEGMENT_ENTER_DURATION_MS,
        initialScale = SEGMENT_ENTER_INITIAL_SCALE,
    )
    val collapseImageBoundaryOnAppearance =
        autoExpansionController?.shouldCollapseForImageBoundary(
            key = expansionKey,
            hasImageBoundary = collapseForImageBoundary,
        ) == true
    val initiallyAutoExpanded =
        autoExpansionController?.shouldPresentInitiallyExpanded(
            key = expansionKey,
            isActive = autoExpansionActive,
            enabled = autoExpansionEnabled,
        ) == true
    val isExpanded = groupedSegmentExpandedState(
        persistedExpanded = expandedStates[expansionKey],
        initiallyAutoExpanded = initiallyAutoExpanded,
        collapseForImageBoundary = collapseImageBoundaryOnAppearance,
    )
    val currentOnExpansionStarted by rememberUpdatedState(onExpansionStarted)
    val currentOnExpansionSettled by rememberUpdatedState(onExpansionSettled)
    LaunchedEffect(
        autoExpansionController,
        expansionKey,
        autoExpansionEnabled,
        autoExpansionActive,
        collapseImageBoundaryOnAppearance,
        collapseForImageBoundary,
    ) {
        if (collapseImageBoundaryOnAppearance) {
            val claimed = autoExpansionController.claimImageBoundaryCollapse(
                key = expansionKey,
                hasImageBoundary = collapseForImageBoundary,
            ) == true
            if (claimed && expandedStates[expansionKey] != false) {
                expandedStates[expansionKey] = false
            }
            return@LaunchedEffect
        }
        val targetExpanded = when (
            autoExpansionController?.update(
                key = expansionKey,
                isActive = autoExpansionActive,
                enabled = autoExpansionEnabled,
            )
        ) {
            GroupedSegmentAutoExpansionAction.EXPAND -> true
            GroupedSegmentAutoExpansionAction.COLLAPSE -> false
            GroupedSegmentAutoExpansionAction.NONE, null -> null
        }
        if (
            targetExpanded != null &&
            (expandedStates[expansionKey] ?: false) != targetExpanded
        ) {
            val alreadyPresentedAtTarget =
                targetExpanded && initiallyAutoExpanded
            if (!alreadyPresentedAtTarget) {
                currentOnExpansionStarted(expansionKey)
            }
            expandedStates[expansionKey] = targetExpanded
        }
    }
    val cardUsesLiveStatus = compactSegmentUsesLiveStatus(generationActive, isCurrentCard, useLiveStatus)
    val collapsedTitle = compactSegmentDisplayTitle(
        segs = segs,
        message = message,
        useLiveStatus = cardUsesLiveStatus,
    )
    val collapsedIcon = compactSegmentIcon(
        segs = segs,
        message = message,
        generationActive = generationActive,
        isCurrentCard = isCurrentCard,
        cardUsesLiveStatus = cardUsesLiveStatus,
        collapsedTitle = collapsedTitle,
    )
    val expansionTransition = updateTransition(
        targetState = isExpanded,
        label = "compactSegmentExpansion",
    )
    val mergedBottomPadding = if (collapseImageBoundaryOnAppearance) {
        GENERATED_IMAGE_BOUNDARY_GAP_DP.dp
    } else if (allowSpatialTransitions) {
        val animatedPadding by expansionTransition.animateDp(
            transitionSpec = { tween(400) },
            label = "compactSegmentPad",
        ) { expanded ->
            if (expanded) {
                12.dp
            } else if (collapseForImageBoundary) {
                GENERATED_IMAGE_BOUNDARY_GAP_DP.dp
            } else {
                4.dp
            }
        }
        animatedPadding
    } else if (
        retainExpandedLayoutDuringFade(
            currentExpanded = expansionTransition.currentState,
            targetExpanded = expansionTransition.targetState,
        )
    ) {
        12.dp
    } else if (collapseForImageBoundary) {
        GENERATED_IMAGE_BOUNDARY_GAP_DP.dp
    } else {
        4.dp
    }
    LaunchedEffect(expansionTransition, expansionKey) {
        var observedRunning = false
        snapshotFlow { expansionTransition.isRunning }.collect { running ->
            if (running) {
                observedRunning = true
            } else if (observedRunning) {
                observedRunning = false
                currentOnExpansionSettled(expansionKey)
            }
        }
    }
    LaunchedEffect(isExpanded, allowSpatialTransitions, expansionKey) {
        if (!allowSpatialTransitions) {
            currentOnExpansionSettled(expansionKey)
        }
    }
    DisposableEffect(expansionKey) {
        onDispose { currentOnExpansionSettled(expansionKey) }
    }

    val compactTitleStyle = ChatType.body.copy(
        fontSize = 13.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val useExpandedHeaderLayout =
        !collapseImageBoundaryOnAppearance &&
            retainExpandedLayoutDuringFade(
                currentExpanded = expansionTransition.currentState,
                targetExpanded = expansionTransition.targetState,
            )
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopStart,
    ) {
        val titleWidth = with(density) {
            textMeasurer.measure(
                text = AnnotatedString(collapsedTitle),
                style = compactTitleStyle,
                softWrap = false,
                maxLines = 1,
            ).size.width.toDp()
        }
        val collapsedHeaderWidth =
            12.dp + 18.dp + 8.dp + titleWidth + 4.dp + 18.dp + 12.dp +
                THINKING_COLLAPSED_WIDTH_ALLOWANCE_DP.dp
        val availableWidth = if (maxWidth.value.isFinite()) {
            maxWidth + (AUXILIARY_CARD_START_EXTENSION_DP * 2).dp
        } else {
            collapsedHeaderWidth
        }
        val collapsedCardWidth = minOf(collapsedHeaderWidth, availableWidth)
        val cardWidth by expansionTransition.animateDp(
            transitionSpec = {
                if (collapseImageBoundaryOnAppearance || !allowSpatialTransitions) {
                    snap()
                } else {
                    tween(
                        durationMillis = 400,
                        easing = LinearOutSlowInEasing,
                    )
                }
            },
            label = "compactSegmentWidth",
        ) { expanded ->
            if (expanded) availableWidth else collapsedCardWidth
        }
        val contentLayoutWidth =
            if (useExpandedHeaderLayout) availableWidth else collapsedCardWidth
        val targetDisclosureRotation = when {
            opensDetailSheet -> -90f
            isExpanded -> 180f
            else -> 0f
        }
        val disclosureRotation by animateFloatAsState(
            targetValue = targetDisclosureRotation,
            animationSpec = if (
                allowSpatialTransitions && !collapseImageBoundaryOnAppearance
            ) {
                tween(durationMillis = 400, easing = LinearOutSlowInEasing)
            } else {
                snap()
            },
            label = "compactSegmentDisclosureRotation",
        )

        StartAnchoredHorizontalOverflowHost {
            Surface(
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .offset(x = (-AUXILIARY_CARD_START_EXTENSION_DP).dp)
                .width(cardWidth)
                .padding(
                    top = 8.dp + topPaddingExtra,
                    bottom = mergedBottomPadding + bottomPaddingExtra,
                )
                .then(cardAppearanceModifier)
                .noOpBringIntoView()
                .onSizeChanged { onBlockHeightChanged(it.height) },
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredWidth(contentLayoutWidth),
                ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        if (onHeaderClick != null) {
                            onHeaderClick()
                        } else {
                            currentOnExpansionStarted(expansionKey)
                            expandedStates[expansionKey] = !isExpanded
                        }
                    }
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
            ) {
                Crossfade(
                    targetState = collapsedIcon,
                    animationSpec = tween(
                        durationMillis = STATUS_CROSSFADE_DURATION_MS,
                        easing = LinearEasing,
                    ),
                    label = "compactSegmentIcon:$expansionKey",
                    modifier = Modifier.size(18.dp),
                ) { icon ->
                    when (icon) {
                        CompactSegmentIcon.LOADING -> CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            strokeWidth = 2.dp,
                        )
                        CompactSegmentIcon.TOOL -> Icon(
                            Icons.Default.Build,
                            null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                        CompactSegmentIcon.IMAGE -> Icon(
                            Icons.Filled.Image,
                            null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                        CompactSegmentIcon.THINKING -> Icon(
                            androidx.compose.ui.res.painterResource(
                                id = com.newoether.agora.R.drawable.neurology_24,
                            ),
                            null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Crossfade(
                    targetState = collapsedTitle,
                    animationSpec = tween(
                        durationMillis = STATUS_CROSSFADE_DURATION_MS,
                        easing = LinearEasing,
                    ),
                    label = "compactSegmentTitle:$expansionKey",
                    modifier = Modifier.weight(1f),
                ) { title ->
                    Text(
                        text = title,
                        style = compactTitleStyle,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(26.dp))
            }
            Box(modifier = Modifier.fillMaxWidth()) {
            expansionTransition.AnimatedVisibility(
                visible = { it },
                // Every group, with or without tool rows, fades its whole segment list while it
                // expands and shrinks (owner decision 2026-10-13: tool calls fade too).
                enter = when {
                    collapseImageBoundaryOnAppearance -> EnterTransition.None
                    allowSpatialTransitions -> fadeIn(tween(400)) + expandVertically(tween(400))
                    else -> fadeIn(tween(400))
                },
                exit = when {
                    collapseImageBoundaryOnAppearance -> ExitTransition.None
                    allowSpatialTransitions -> fadeOut(tween(400)) + shrinkVertically(tween(400))
                    else -> fadeOut(tween(400))
                },
            ) {
                Column {
                    Spacer(modifier = Modifier.height(2.dp))
                    segs.forEachIndexed { idx, seg ->
                      val detailIndex = segmentIndices.getOrElse(idx) { idx }
                      AnimatedTimelineBlockAppearance(
                        animationKey = detailSegmentAppearanceKey(
                            message.id,
                            detailIndex,
                            seg,
                        ),
                        appearanceRegistry = segmentAppearanceRegistry,
                        isStreaming = isStreaming,
                        forceOpaque = seg.type == "tool",
                      ) {
                       Column {
                        if ((seg.type == "thought" && seg.content.isNotBlank()) || seg.type == "transcription") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable {
                                        onSegmentClick(segmentIndices.getOrElse(idx) { idx })
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
                                    if (seg.type == "thought") {
                                        StreamingThoughtPreviewText(
                                            content = seg.content,
                                            streaming =
                                                isStreaming &&
                                                    useLiveStatus &&
                                                    idx == segs.lastIndex,
                                        )
                                    } else {
                                        StreamingMutedText(
                                            text = seg.content.replace('\n', ' '),
                                            streaming =
                                                isStreaming &&
                                                    useLiveStatus &&
                                                    idx == segs.lastIndex,
                                        )
                                    }
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
                                        onSegmentClick(segmentIndices.getOrElse(idx) { idx })
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    toolDisplayName(seg),
                                    style = ChatType.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val presentation = ToolPresentationResolver.resolve(seg)
                                ToolSummaryText(
                                    presentation = presentation,
                                    streaming =
                                        isStreaming &&
                                            useLiveStatus &&
                                            idx == segs.lastIndex,
                                )
                            }
                        }
                        if (idx < segs.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = Color.Transparent,
                            )
                        }
                       }
                      }
                    }
                }
            }
            }
                }
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 8.dp)
                        .size(18.dp)
                        .graphicsLayer { rotationZ = disclosureRotation },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
        }
    }
}

/**
 * Reduced Motion keeps expanded layout space for the whole content fade.
 *
 * On expansion the target state reserves the final layout immediately. On collapse the current
 * state retains that layout until the exit fade finishes. AnimatedVisibility then removes the
 * content in the same transition settlement that releases the card's external spacing.
 */
internal fun retainExpandedLayoutDuringFade(
    currentExpanded: Boolean,
    targetExpanded: Boolean,
): Boolean = currentExpanded || targetExpanded

internal fun timelineInfoTopPaddingExtra(hasVisibleMessageAbove: Boolean): Dp =
    if (hasVisibleMessageAbove) 8.dp else 0.dp

internal const val SEGMENT_GROUP_GAP_DP = 2
private const val GENERATED_IMAGE_BOUNDARY_GAP_DP = 8

private fun segmentGroupTopPadding(
    position: SegmentGroupPosition,
    topPaddingExtra: Dp,
): Dp = when (position) {
    SegmentGroupPosition.MIDDLE, SegmentGroupPosition.LAST -> SEGMENT_GROUP_GAP_DP.dp
    else -> 8.dp + topPaddingExtra
}

private fun segmentGroupBottomPadding(position: SegmentGroupPosition): Dp =
    if (position == SegmentGroupPosition.SINGLE || position == SegmentGroupPosition.LAST) {
        6.dp
    } else {
        0.dp
    }

@Composable
internal fun TimelineInfoSegmentCard(
    seg: MessageSegment,
    detailSegments: List<MessageSegment>,
    detailIndex: Int,
    isStreamingContent: Boolean,
    animateAppearance: Boolean,
    topPaddingExtra: Dp = 0.dp,
    groupPosition: SegmentGroupPosition = SegmentGroupPosition.SINGLE,
    endsAtGeneratedImageBoundary: Boolean = false,
    neutralPalette: Boolean = false,
    extendIntoMessageInsets: Boolean = false,
    cardAnimationKey: String,
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    onClick: () -> Unit
) {
    val animateCardAppearance = rememberSegmentAppearance(
        registry = segmentAppearanceRegistry,
        animationKey = cardAnimationKey,
        isStreaming = animateAppearance,
    )
    val groupShape = rememberAnimatedSegmentGroupShape(groupPosition)
    val cardColor = if (neutralPalette) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val iconTint = if (neutralPalette) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardAppearanceModifier = generationLifecycleAppearanceModifier(
            animationKey = cardAnimationKey,
            animate = animateCardAppearance,
            durationMillis = SEGMENT_ENTER_DURATION_MS,
            initialScale = SEGMENT_ENTER_INITIAL_SCALE,
            forceOpaque = seg.type == "tool",
        )
        val requestedCardWidth = if (extendIntoMessageInsets) {
            maxWidth + (AUXILIARY_CARD_START_EXTENSION_DP * 2).dp
        } else {
            maxWidth
        }
        val requestedCardOffset =
            if (extendIntoMessageInsets) (-AUXILIARY_CARD_START_EXTENSION_DP).dp else 0.dp
        StartAnchoredHorizontalOverflowHost {
            Surface(
                tonalElevation = if (neutralPalette) 1.dp else 2.dp,
                color = cardColor,
                shape = groupShape,
                modifier = Modifier
                    .offset(x = requestedCardOffset)
                    .width(requestedCardWidth)
            .padding(
                top = segmentGroupTopPadding(groupPosition, topPaddingExtra),
                bottom = if (endsAtGeneratedImageBoundary) {
                    GENERATED_IMAGE_BOUNDARY_GAP_DP.dp
                } else segmentGroupBottomPadding(groupPosition),
            )
            .then(cardAppearanceModifier)
            .clip(groupShape)
            .clickable {
                onClick()
            }
            .noOpBringIntoView()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            val isTool = seg.type == "tool"
            val isTranscription = seg.type == "transcription"
            if (isTool) {
                Icon(toolHeaderIcon(ToolPresentationResolver.kindForToolName(seg.toolName)), null,
                    modifier = Modifier.size(16.dp), tint = iconTint)
            } else if (isTranscription) {
                Icon(Icons.Filled.Image, null, modifier = Modifier.size(16.dp), tint = iconTint)
            } else {
                Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = Modifier.size(16.dp), tint = iconTint)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (seg.type) {
                        "tool" -> toolDisplayName(seg)
                        "transcription" -> transcriptionLabel(detailSegments, detailIndex)
                        else -> stringResource(R.string.tool_thinking)
                    },
                    style = ChatType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (seg.type == "thought" && seg.content.isNotBlank()) {
                    StreamingThoughtPreviewText(
                        content = seg.content,
                        streaming = isStreamingContent,
                    )
                } else {
                    val summary = when (seg.type) {
                        "tool" -> toolSummary(seg)
                        "transcription" -> seg.content.takeIf { it.isNotBlank() }
                            ?: "Image transcription is empty."
                        else -> ""
                    }
                    if (summary.isNotBlank()) {
                        if (isTool) {
                            ToolSummaryText(
                                presentation = ToolPresentationResolver.resolve(seg),
                                streaming = isStreamingContent,
                            )
                        } else {
                            StreamingMutedText(
                                text = summary,
                                streaming = isStreamingContent,
                            )
                        }
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            }
        }
        }
    }
}
