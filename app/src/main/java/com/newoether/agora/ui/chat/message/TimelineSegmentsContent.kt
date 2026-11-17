package com.newoether.agora.ui.chat.message

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.ui.components.*
import com.newoether.agora.util.noOpBringIntoView

@Composable
internal fun TimelineSegmentsContent(
    segments: List<MessageSegment>,
    detailSegments: List<MessageSegment>,
    message: ChatMessage,
    isStreaming: Boolean,
    generationActive: Boolean,
    groupAdjacentBlocks: Boolean,
    autoExpandActiveGroup: Boolean,
    autoExpansionController: GroupedSegmentAutoExpansionController,
    expandedStates: SnapshotStateMap<String, Boolean>,
    renderContext: ChatMarkdownRenderContext,
    searchHighlight: SearchHighlightSpec?,
    citations: List<CitationRecord>,
    onCitationActivate: (List<CitationRecord>) -> Unit,
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    onLayoutMutationStarted: (String) -> Unit,
    onLayoutMutationSettled: (String) -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    opensDetailSheet: Boolean = false,
    preserveInitialCompactIdentity: Boolean = false,
    onGroupHeaderClick: ((List<Int>) -> Unit)? = null,
    onSegmentClick: (List<Int>) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        timelineBlocks(segments, isStreaming, groupAdjacentBlocks).forEach { block ->
            when (block) {
                is TimelineBlock.Answer -> {
                    val seg = block.segment
                    val index = block.index
                    val answerIsStreaming = block.isStreaming
                    val answerOffset = block.answerOffset
                    val citationProjection = citationMarkdownProjection(
                        answerText = seg.content,
                        citations = citationRecordsForAnswerSlice(
                            citations = citations,
                            sliceStart = answerOffset,
                            sliceText = seg.content,
                        ),
                        isStreaming = answerIsStreaming,
                    )
                    val answerSearchHighlight = searchHighlight?.forSourceSlice(
                        sliceStart = answerOffset,
                        sliceLength = seg.content.length,
                    )
                    val answerAppearanceKey =
                        "${segmentAppearanceKey(message.id, index, seg)}:timeline"
                    val answerFadeTracker =
                        segmentAppearanceRegistry.streamingFadeTracker("$answerAppearanceKey:fade")
                    AnimatedTimelineBlockAppearance(
                        animationKey = answerAppearanceKey,
                        appearanceRegistry = segmentAppearanceRegistry,
                        isStreaming = isStreaming,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (index == 0) 0.dp else 6.dp)
                        ) {
                            CitationTerminalProjectionHost(
                                animationKey = answerAppearanceKey,
                                projection = citationProjection,
                                isStreaming = answerIsStreaming,
                                onLayoutMutationStarted = onLayoutMutationStarted,
                                onLayoutMutationSettled = onLayoutMutationSettled,
                                modifier = Modifier.fillMaxWidth(),
                            ) { presentedProjection, presentedIsStreaming ->
                                val presentedContent =
                                    presentedProjection?.markdown ?: seg.content
                                CitationInlineContentHost(
                                    projection = presentedProjection,
                                    onActivate = onCitationActivate,
                                ) {
                                    CompositionLocalProvider(
                                        LocalSearchHighlightSpec provides answerSearchHighlight,
                                        LocalMarkdownImageOrdinalOffset provides remember(message.id, answerOffset, message.text) {
                                            shellMarkdownImageCount(message.text, answerOffset)
                                        },
                                    ) {
                                        StreamingMarkdownMessage(
                                            content = presentedContent,
                                            isStreaming = presentedIsStreaming,
                                            renderContext = renderContext,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .noOpBringIntoView(),
                                            selectionEnabled = !presentedIsStreaming,
                                            textDeltas = seg.streamingTextDeltas,
                                            fadeTracker = answerFadeTracker,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                is TimelineBlock.InfoGroup -> {
                    val blockDetailIndices = block.detailIndices
                    val firstDetailIndex = blockDetailIndices.firstOrNull() ?: block.startIndex
                    val useInitialCompactIdentity =
                        preserveInitialCompactIdentity &&
                            blockDetailIndices.firstOrNull() == 0
                    val expansionKey = if (useInitialCompactIdentity) {
                        message.id
                    } else {
                        groupedSegmentBlockAppearanceKey(message.id, firstDetailIndex)
                    }
                    val cardAppearanceKey = if (useInitialCompactIdentity) {
                        "${compactSegmentBlockAppearanceKey(message.id)}:card"
                    } else {
                        "$expansionKey:card"
                    }
                    CompactSegmentBlock(
                        segs = block.segments,
                        segmentIndices = blockDetailIndices,
                        message = message,
                        isStreaming = isStreaming,
                        useLiveStatus = block.useLiveStatus,
                        generationActive = generationActive,
                        isCurrentCard = block.isCurrentCard,
                        expandedStates = expandedStates,
                        expansionKey = expansionKey,
                        cardAppearanceKey = cardAppearanceKey,
                        segmentAppearanceRegistry = segmentAppearanceRegistry,
                        autoExpansionController = autoExpansionController,
                        autoExpansionEnabled = autoExpandActiveGroup,
                        autoExpansionActive = block.autoExpansionActive,
                        collapseForImageBoundary = block.imageBoundary != null,
                        topPaddingExtra = timelineInfoTopPaddingExtra(block.precededByAnswer),
                        bottomPaddingExtra = 0.dp,
                        onExpansionStarted = onLayoutMutationStarted,
                        onExpansionSettled = onLayoutMutationSettled,
                        onSegmentClick = { selectedDetailIndex ->
                            onSegmentClick(listOf(selectedDetailIndex))
                        },
                        onHeaderClick = if (opensDetailSheet) {
                            {
                                (onGroupHeaderClick ?: onSegmentClick)(blockDetailIndices)
                            }
                        } else {
                            null
                        },
                        opensDetailSheet = opensDetailSheet,
                    )
                    val imageBoundary = block.imageBoundary
                    val imageDetailIndex = block.imageDetailIndex
                    if (imageBoundary != null && imageDetailIndex != null) {
                        GeneratedImageThumbnail(
                            segment = imageBoundary,
                            messageId = message.id,
                            detailIndex = imageDetailIndex,
                            isStreaming = isStreaming,
                            segmentAppearanceRegistry = segmentAppearanceRegistry,
                            onMediaClick = onMediaClick,
                        )
                    }
                }
                is TimelineBlock.InfoCard -> {
                    val seg = block.segment
                    val currentDetailIndex = block.detailIndex
                    val timelineKey = detailSegmentAppearanceKey(
                        message.id,
                        currentDetailIndex,
                        seg,
                    )
                    TimelineInfoSegmentCard(
                        seg = seg,
                        detailSegments = detailSegments,
                        detailIndex = currentDetailIndex,
                        isStreamingContent = block.isStreamingContent,
                        animateAppearance = isStreaming,
                        topPaddingExtra = timelineInfoTopPaddingExtra(block.precededByAnswer),
                        groupPosition = block.groupPosition,
                        endsAtGeneratedImageBoundary = seg.isImageGenerationSegment(),
                        extendIntoMessageInsets = true,
                        cardAnimationKey = "$timelineKey:card",
                        segmentAppearanceRegistry = segmentAppearanceRegistry,
                        onClick = { onSegmentClick(listOf(currentDetailIndex)) },
                    )
                    if (seg.isImageGenerationSegment()) {
                        GeneratedImageThumbnail(
                            segment = seg,
                            messageId = message.id,
                            detailIndex = currentDetailIndex,
                            isStreaming = isStreaming,
                            segmentAppearanceRegistry = segmentAppearanceRegistry,
                            onMediaClick = onMediaClick,
                        )
                    }
                }
            }
        }
    }
}
