package com.newoether.agora.ui.chat.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColor
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.input.*

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.forDisplay
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.model.Participant
import com.newoether.agora.model.StableModelAliases
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.ui.chat.ConversationSearchMatch
import com.newoether.agora.ui.chat.conversationSearchMatchRanges
import com.newoether.agora.ui.chat.deletionRemovesEntireConversation
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.components.*
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.mikepenz.markdown.compose.components.markdownComponents
import kotlinx.coroutines.flow.StateFlow
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem



internal data class PendingMessageDeletion(
    val targetMessageId: String,
    val deletesConversation: Boolean,
    val expectedConversationMessageIds: Set<String>,
) {
    constructor(messages: List<ChatMessage>, targetMessageId: String, compactOnly: Boolean) : this(
        targetMessageId = targetMessageId,
        deletesConversation = deletionRemovesEntireConversation(messages, targetMessageId, compactOnly),
        expectedConversationMessageIds = messages.mapTo(linkedSetOf(), ChatMessage::id),
    )
}

internal enum class ContextCompactPillPresentation {
    IN_PROGRESS,
    SUCCESS,
    ERROR,
    STOPPED,
}

internal fun contextCompactPillPresentation(status: MessageStatus): ContextCompactPillPresentation =
    when (status) {
        MessageStatus.SENDING,
        MessageStatus.THINKING,
        MessageStatus.TOOL_CALLING,
        MessageStatus.TRANSCRIBING -> ContextCompactPillPresentation.IN_PROGRESS
        MessageStatus.ERROR -> ContextCompactPillPresentation.ERROR
        MessageStatus.STOPPED -> ContextCompactPillPresentation.STOPPED
        MessageStatus.SUCCESS -> ContextCompactPillPresentation.SUCCESS
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun MessageItem(
    message: ChatMessage,
    onEdit: (String, String) -> Unit,
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    modifier: Modifier = Modifier,
    animateEntrance: Boolean = false,
    outerPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    includeAssistantOuterSpacing: Boolean = true,
    isStreaming: Boolean = false,
    liveCompactPreview: StateFlow<String>? = null,
    isLoading: Boolean = false,
    isStopping: Boolean = false,
    compactActionsEnabled: Boolean = true,
    isRegenerationExiting: Boolean = false,
    isEditingAllowed: Boolean = true,
    isEditing: Boolean = false,
    userBubbleSizeAnimationReady: Boolean = true,
    isSwitching: Boolean = false,
    isInContext: Boolean = false,
    modelAliases: StableModelAliases = StableModelAliases(),
    customProviders: List<com.newoether.agora.data.CustomProviderConfig> = emptyList(),
    visualizeContextRollout: Boolean = false,
    toolCallDisplayMode: String = ToolCallDisplayModes.DEFAULT,
    thinkingSegmentDisplayMode: String = ThinkingSegmentDisplayModes.DEFAULT,
    autoExpandActiveGroup: Boolean = true,

    parseInlineDollarMath: Boolean = false,
    autoWrapCodeBlocks: Boolean = true,
    groupedSegmentAutoExpansionController: GroupedSegmentAutoExpansionController =
        remember { GroupedSegmentAutoExpansionController() },
    onStartEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    showActions: Boolean = true,
    showGenerationSpeed: Boolean = false,
    readOnlyActions: Boolean = false,
    actionCopyText: String? = message.text,
    showBranchSelector: Boolean = true,
    branchIndex: Int = 0,
    totalBranches: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
    onRegenerate: (String) -> Boolean = { false },
    onFork: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    deleteTargetMessageId: String = message.id,
    conversationMessages: () -> List<ChatMessage> = { emptyList() },
    onRecompact: (String) -> Unit = {},
    onDelete: (String, (Boolean) -> Unit) -> Boolean = { _, _ -> false },
    onDeleteConversation: (Set<String>, (Boolean) -> Unit) -> Boolean = { _, _ -> false },
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onSegmentDetailRequest: (String, List<Int>, Boolean) -> Unit = { _, _, _ -> },
    onHeightChanged: (Int) -> Unit = {},
    searchQuery: String = "",
    activeSearchMatch: ConversationSearchMatch? = null,
    onSearchMatchPosition: (
        key: String,
        measurementEpoch: String?,
        centerYInRoot: Float,
    ) -> Unit = { _, _, _ -> },
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onLayoutMutationStarted: (String) -> Unit = {},
    onLayoutMutationSettled: (String) -> Unit = {},
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
) {
    val unansweredLabel = stringResource(R.string.message_source_unanswered)
    val displayMessage = remember(message, customProviders, unansweredLabel) {
        message.forDisplay(customProviders).withAskUserDisplayText(unansweredLabel)
    }
    val displayActionCopyText = remember(actionCopyText, customProviders, displayMessage) {
        // An ask_user bubble copies exactly what it shows, including the localized label.
        if (displayMessage.source?.kind == MessageSource.Kind.ASK_USER) {
            actionCopyText?.let { displayMessage.text }
        } else {
            actionCopyText?.let { replaceCustomProviderIdsForDisplay(it, customProviders) }
        }
    }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showUserTextSelection by remember(message.id) { mutableStateOf(false) }
    var pendingDelete by remember(message.id) { mutableStateOf<PendingMessageDeletion?>(null) }
    var confirmedDelete by remember(message.id) { mutableStateOf<PendingMessageDeletion?>(null) }
    val onShowDelete = {
        pendingDelete = PendingMessageDeletion(
            messages = conversationMessages(),
            targetMessageId = deleteTargetMessageId,
            compactOnly = message.isContextCompact(),
        )
    }
    var showCompactDetail by remember(message.id) { mutableStateOf(false) }
    val haptics = LocalAgoraHaptics.current
    val motionPolicy = LocalAgoraMotionPolicy.current
    val compactPresentation = contextCompactPillPresentation(message.status)
    val compactInProgress =
        message.isContextCompact() &&
            compactPresentation == ContextCompactPillPresentation.IN_PROGRESS

    if (showInfoDialog) {
        MessageInfoDialog(
            message = displayMessage,
            modelAliases = modelAliases.map,
            showProviderName = modelAliases.providerNames[message.modelName] != false,
            customProviders = customProviders,
            onDismiss = { showInfoDialog = false }
        )
    }

    LaunchedEffect(confirmedDelete) {
        val confirmed = confirmedDelete ?: return@LaunchedEffect
        // Draw the loading action in the blocking confirmation before dispatch.
        withFrameNanos { }
        val onResult: (Boolean) -> Unit = { deleted ->
            pendingDelete = if (deleted) null else confirmed
            if (deleted) haptics.destructiveConfirmed()
            confirmedDelete = null
        }
        val accepted = if (confirmed.deletesConversation) {
            onDeleteConversation(confirmed.expectedConversationMessageIds, onResult)
        } else {
            onDelete(confirmed.targetMessageId, onResult)
        }
        if (!accepted) onResult(false)
    }

    pendingDelete?.let { pending ->
        val onConfirmDelete = {
            if (confirmedDelete == null) {
                confirmedDelete = pending
            }
            Unit
        }
        if (pending.deletesConversation) {
            MessageDeleteDialog(
                deletesConversation = true,
                pending = confirmedDelete != null,
                onConfirm = onConfirmDelete,
                onDismiss = { if (confirmedDelete == null) pendingDelete = null },
            )
        } else if (message.isContextCompact()) {
            ContextCompactDeleteDialog(
                pending = confirmedDelete != null,
                onConfirm = onConfirmDelete,
                onDismiss = { if (confirmedDelete == null) pendingDelete = null },
            )
        } else {
            MessageDeleteDialog(
                pending = confirmedDelete != null,
                onConfirm = onConfirmDelete,
                onDismiss = { if (confirmedDelete == null) pendingDelete = null },
            )
        }
    }

    val alignment = when (message.participant) {
        Participant.USER -> Alignment.End
        Participant.MODEL -> Alignment.Start
        Participant.ERROR -> Alignment.CenterHorizontally
    }

    val backgroundColor = when (message.participant) {
        Participant.USER -> MaterialTheme.colorScheme.primaryContainer
        Participant.MODEL -> Color.Transparent
        Participant.ERROR -> MaterialTheme.colorScheme.errorContainer
    }

    val textColor = when (message.participant) {
        Participant.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        Participant.MODEL -> MaterialTheme.colorScheme.onSurface
        Participant.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    val shape = when (message.participant) {
        // Three matching large corners (27dp, capped at half the bubble's smaller side) and a 6dp tail;
        // a single-line bubble reads as a capsule.
        Participant.USER -> UserBubbleShape()
        Participant.MODEL -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
        Participant.ERROR -> RoundedCornerShape(12.dp)
    }
    val selectionRippleShape = when (message.participant) {
        Participant.MODEL -> RoundedCornerShape(20.dp)
        else -> shape
    }

    val searchHighlight = searchQuery.takeIf { it.isNotBlank() }?.let { query ->
        val active = activeSearchMatch?.takeIf { it.messageId == message.id }
        val matchRanges = conversationSearchMatchRanges(displayMessage, query)
        val matchKeys = matchRanges.map { range ->
            "${message.id}:${range.first}:${range.last + 1}"
        }
        SearchHighlightSpec(
            query = query,
            activeRange = active
                ?.takeIf { it.citationSourceId == null }
                ?.let { it.start until it.endExclusive },
            activeKey = active?.key,
            matchKeys = matchKeys,
            sourceRanges = matchRanges,
            onMatchPosition = onSearchMatchPosition,
        )
    }
    val markdownAssets = rememberChatMarkdownAssets(
        textColor,
        parseInlineDollarMath,
        message.markdownImages,
        onMediaClick,
        message.preparedMarkdown,
        autoWrapCodeBlocks,
    )
    val markdownRenderContext = markdownAssets.renderContext
    val thoughtMarkdownRenderContext = markdownAssets.thoughtRenderContext

    val entranceModifier = generationLifecycleAppearanceModifier(
        animationKey = "message:${message.id}",
        animate = animateEntrance && !isSwitching,
        durationMillis = MESSAGE_ENTER_DURATION_MS,
        forceOpaque = displayMessage.segments.orEmpty().any { it.type == "tool" },
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged {
                onHeightChanged(it.height)
            }
            .padding(outerPadding)
            .then(entranceModifier),
        verticalAlignment = Alignment.Top,
    ) {
        AnimatedVisibility(
            visible = selectionMode,
            enter = if (motionPolicy.allowSpatialTransitions) {
                fadeIn() + expandIn()
            } else {
                fadeIn()
            },
            exit = if (motionPolicy.allowSpatialTransitions) {
                shrinkOut() + fadeOut()
            } else {
                fadeOut()
            },
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = alignment,
            ) {
                val contextAlphaValue by animateFloatAsState(
                    targetValue = if (visualizeContextRollout && !isInContext) 0.38f else 1f,
                    animationSpec = tween(durationMillis = 240),
                    label = "contextRolloutAlpha",
                )
                val contextAlpha = Modifier.alpha(contextAlphaValue)
                if (message.isContextCompact()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(contextAlpha),
                        contentAlignment = Alignment.Center,
                    ) {
                        ContextCompactPill(
                            presentation = compactPresentation,
                            actionsEnabled = compactActionsEnabled && !compactInProgress,
                            onClick = { showCompactDetail = true },
                            onRecompact = { onRecompact(message.id) },
                            onDelete = onShowDelete,
                        )
                    }
                } else if (message.participant == Participant.USER) {
                    UserMessageBubble(
                        message = displayMessage,
                        shape = shape,
                        backgroundColor = backgroundColor,
                        textColor = textColor,
                        contextAlpha = contextAlpha,
                        isEditing = isEditing,
                        sizeAnimationReady = userBubbleSizeAnimationReady,
                        isLoading = isLoading,
                        isEditingAllowed = isEditingAllowed,
                        showActions = showActions || readOnlyActions,
                        allowMutations = !readOnlyActions,
                        actionCopyText = displayActionCopyText,
                        showBranchSelector = showBranchSelector,
                        branchIndex = branchIndex,
                        totalBranches = totalBranches,
                        onEdit = onEdit,
                        onCancelEdit = onCancelEdit,
                        onStartEdit = onStartEdit,
                        onSelectText = { showUserTextSelection = true },
                        onSwitchBranch = onSwitchBranch,
                        onMediaClick = onMediaClick,
                        onFileContentClick = onFileContentClick,
                        onPdfPagesClick = onPdfPagesClick,
                        onShowInfo = { showInfoDialog = true },
                        onShowDelete = onShowDelete,
                        searchHighlight = searchHighlight,
                    )
                } else {
                    AssistantMessageContent(
                        message = displayMessage,
                        includeOuterSpacing = includeAssistantOuterSpacing,
                        segmentAppearanceRegistry = segmentAppearanceRegistry,
                        contextAlpha = contextAlpha,
                        isStreaming = isStreaming,
                        isLoading = isLoading,
                        isStopping = isStopping,
                        isRegenerationExiting = isRegenerationExiting,
                        isEditingAllowed = isEditingAllowed,
                        showActions = showActions,
                        showGenerationSpeed = showGenerationSpeed,
                        actionCopyText = displayActionCopyText,
                        showBranchSelector = showBranchSelector,
                        toolCallDisplayMode = toolCallDisplayMode,
                        thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
                        autoExpandActiveGroup = autoExpandActiveGroup &&
                            ThinkingSegmentDisplayModes.allowsAutoExpand(
                                thinkingSegmentDisplayMode,
                                toolCallDisplayMode,
                            ),

                        groupedSegmentAutoExpansionController =
                            groupedSegmentAutoExpansionController,
                        thoughtExpandedStates = thoughtExpandedStates,
                        renderContext = markdownRenderContext,
                        searchHighlight = searchHighlight,
                        branchIndex = branchIndex,
                        totalBranches = totalBranches,
                        onSwitchBranch = onSwitchBranch,
                        onRegenerate = onRegenerate,
                        onFork = { onFork(message.id) },
                        onShare = { onShare(message.id) },
                        onMediaClick = onMediaClick,
                        onShowInfo = { showInfoDialog = true },
                        onShowDelete = onShowDelete,
                        onSegmentSelected = { indices, showListFirst ->
                            onSegmentDetailRequest(message.id, indices, showListFirst)
                        },
                        onLayoutMutationStarted = onLayoutMutationStarted,
                        onLayoutMutationSettled = onLayoutMutationSettled,
                        setThoughtBlockHeight = {},
                    )
                }
            }
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(selectionRippleShape)
                        .clickable(onClick = onToggleSelection),
                )
            }
        }
    }

    val failedToGenerateText = stringResource(com.newoether.agora.R.string.failed_to_generate)
    val detailErrorText = remember(
        displayMessage.text,
        displayMessage.status,
        displayMessage.participant,
        displayMessage.segments,
        failedToGenerateText,
    ) {
        assistantErrorContent(
            message = displayMessage,
            mergedSegments = mergeAdjacentSegments(displayMessage.segments.orEmpty()),
            fallbackErrorText = failedToGenerateText,
        )?.errorText
    }

    if (showUserTextSelection) {
        SegmentDetailSheet(
            message = displayMessage,
            selectedSegmentIndex = 0,
            selectedSegmentIndices = listOf(0),
            isStreaming = false,
            markdownRenderContext = thoughtMarkdownRenderContext,
            onMediaClick = onMediaClick,
            titleOverride = stringResource(R.string.select_text),
            directSelectableTextContent = displayMessage.text,
            onDismiss = { showUserTextSelection = false },
        )
    }
    if (showCompactDetail) {
        val rawCompactDetailText = liveCompactPreview
            ?.takeIf { compactInProgress }
            ?.collectAsState()
            ?.value
            ?: message.text
        val compactDetailText = remember(rawCompactDetailText, customProviders) {
            replaceCustomProviderIdsForDisplay(rawCompactDetailText, customProviders)
        }
        SegmentDetailSheet(
            message = displayMessage,
            selectedSegmentIndex = 0,
            selectedSegmentIndices = listOf(0),
            isStreaming = compactInProgress,
            markdownRenderContext = thoughtMarkdownRenderContext,
            onMediaClick = onMediaClick,
            titleOverride = stringResource(com.newoether.agora.R.string.context_compact),
            directMarkdownContent = compactDetailText,
            emptyStreamingText = stringResource(R.string.context_compact_streaming),
            errorText = detailErrorText,
            handleBackInternally = true,
            onDismiss = { showCompactDetail = false },
        )
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun ContextCompactPill(
    presentation: ContextCompactPillPresentation,
    actionsEnabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onRecompact: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    val motionPolicy = LocalAgoraMotionPolicy.current
    val pillShape = RoundedCornerShape(100.dp)
    val presentationTransition = updateTransition(
        targetState = presentation,
        label = "compactPillPresentation",
    )
    val containerColor by presentationTransition.animateColor(
        transitionSpec = { tween(durationMillis = 240) },
        label = "compactPillContainer",
    ) { renderedPresentation ->
        if (renderedPresentation == ContextCompactPillPresentation.ERROR) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    }
    val contentColor by presentationTransition.animateColor(
        transitionSpec = { tween(durationMillis = 240) },
        label = "compactPillContent",
    ) { renderedPresentation ->
        if (renderedPresentation == ContextCompactPillPresentation.ERROR) {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    }
    val iconColor by presentationTransition.animateColor(
        transitionSpec = { tween(durationMillis = 240) },
        label = "compactPillIcon",
    ) { renderedPresentation ->
        if (renderedPresentation == ContextCompactPillPresentation.ERROR) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    }
    val destructiveActionTint = MaterialTheme.colorScheme.error.copy(
        alpha = if (actionsEnabled) 1f else 0.38f,
    )
    val presentationCrossfadeSpec = tween<Float>(durationMillis = 240)
    Surface(
        modifier = if (onClick != null) {
            Modifier
                .clip(pillShape)
                .clickable(onClick = onClick)
        } else {
            Modifier
        },
        shape = pillShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 42.dp)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                presentationTransition.Crossfade(
                    animationSpec = presentationCrossfadeSpec,
                ) { renderedPresentation ->
                    if (renderedPresentation == ContextCompactPillPresentation.IN_PROGRESS) {
                        com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = iconColor,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = when (renderedPresentation) {
                                ContextCompactPillPresentation.ERROR ->
                                    androidx.compose.material.icons.Icons.Default.Error
                                ContextCompactPillPresentation.STOPPED ->
                                    androidx.compose.material.icons.Icons.Default.StopCircle
                                else -> androidx.compose.material.icons.Icons.Default.Compress
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = iconColor,
                        )
                    }
                }
            }
            presentationTransition.AnimatedContent(
                transitionSpec = {
                    val fade = fadeIn(animationSpec = presentationCrossfadeSpec) togetherWith
                        fadeOut(animationSpec = presentationCrossfadeSpec)
                    fade.using(
                        SizeTransform(
                            clip = false,
                            sizeAnimationSpec = { _, _ ->
                                if (motionPolicy.allowSpatialTransitions) {
                                    tween(durationMillis = 240)
                                } else {
                                    snap()
                                }
                            },
                        )
                    )
                },
                contentAlignment = Alignment.CenterStart,
            ) { renderedPresentation ->
                Text(
                    when (renderedPresentation) {
                        ContextCompactPillPresentation.ERROR ->
                            stringResource(R.string.context_compact_error)
                        ContextCompactPillPresentation.STOPPED ->
                            stringResource(R.string.context_compact_stopped)
                        ContextCompactPillPresentation.IN_PROGRESS ->
                            stringResource(R.string.context_compacting)
                        ContextCompactPillPresentation.SUCCESS ->
                            stringResource(R.string.context_compact)
                    },
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Box {
                IconButton(
                    onClick = { actionsExpanded = true },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.MoreVert,
                        contentDescription = stringResource(com.newoether.agora.R.string.more),
                        modifier = Modifier.size(18.dp),
                    )
                }
                AgoraDropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 16.dp,
                ) {
                    AgoraDropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(com.newoether.agora.R.string.recompact),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
                                contentDescription = null,
                            )
                        },
                        enabled = actionsEnabled,
                        onClick = {
                            actionsExpanded = false
                            onRecompact()
                        },
                    )
                    AgoraDropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(com.newoether.agora.R.string.delete),
                                color = destructiveActionTint,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                                contentDescription = null,
                                tint = destructiveActionTint,
                            )
                        },
                        enabled = actionsEnabled,
                        onClick = {
                            actionsExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
