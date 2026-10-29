package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageGenerationBoundaryResolver
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.copyTextWithCitations
import com.newoether.agora.model.RunMessagePresentation
import com.newoether.agora.model.RunUiProjection
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.model.StableModelAliases
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.ui.chat.message.GroupedSegmentAutoExpansionController
import com.newoether.agora.ui.chat.message.MessageItem
import com.newoether.agora.ui.chat.message.MessageSegmentDetailHost
import com.newoether.agora.ui.chat.message.REGENERATION_ABORT_RESTORE_DURATION_MS
import com.newoether.agora.ui.chat.message.REGENERATION_EXIT_DURATION_MS
import com.newoether.agora.ui.chat.message.SegmentAppearanceRegistry
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.BranchReplacementTransitionRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageList(
    messages: StableMessageList,
    authoritativeMessages: StableMessageList = messages,
    allMessages: StableMessageList = StableMessageList(),
    conversationId: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    state: LazyListState = rememberLazyListState(),
    userScrollEnabled: Boolean = true,
    overscrollEffect: OverscrollEffect? = rememberOverscrollEffect(),
    messageActionsEnabled: Boolean = true,
    readOnlyActions: Boolean = false,
    isLoading: Boolean = false,
    isCompacting: Boolean = false, compactPreview: StateFlow<String>? = null,
    isStopping: Boolean = false,
    isSwitching: Boolean = false,
    streamingMessage: ChatMessage? = null,
    streamingAutoFollowEnabled: Boolean = isLoading && !isSwitching,
    streamingAutoFollowPaused: Boolean = false,
    streamingTailWithinAttachThreshold: Boolean = false,
    programmaticScrollActive: Boolean = false,
    streamingTailController: StreamingTailController = rememberStreamingTailController(),
    regenerationTransition: BranchReplacementTransitionRequest? = null,
    onRegenerationFadeOutFinished: (Long) -> Unit = {},
    visualizeContextRollout: Boolean = false,
    toolCallDisplayMode: String = ToolCallDisplayModes.DEFAULT,
    thinkingSegmentDisplayMode: String = ThinkingSegmentDisplayModes.DEFAULT,
    autoExpandActiveGroup: Boolean = true,
    parseInlineDollarMath: Boolean = false,
    autoWrapCodeBlocks: Boolean = true,
    showGenerationSpeed: Boolean = false,
    contextRetainedMessageIds: Set<String> = emptySet(),
    modelAliases: StableModelAliases = StableModelAliases(),
    customProviders: List<com.newoether.agora.data.CustomProviderConfig> = emptyList(),
    bottomBarHeight: androidx.compose.ui.unit.Dp = 0.dp,
    viewportHeight: Int = 0,
    messageHeights: SnapshotStateMap<String, Int> = remember { mutableStateMapOf() },
    observeMessage: (String) -> Flow<ChatMessage?> = { flowOf(null) },
    initialMessage: (String) -> ChatMessage? = { null },
    onMessageHydrated: (String?, String) -> Unit = { _, _ -> },
    onEditMessage: suspend (String, String) -> Boolean = { _, _ -> false },
    onSwitchBranch: (String?, String, Int) -> Unit = { _, _, _ -> },
    onRegenerate: (String) -> Boolean = { false },
    onFork: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onRecompact: (String) -> Unit = {},
    onDelete: (String, (Boolean) -> Unit) -> Boolean = { _, _ -> false },
    onDeleteConversation: (Set<String>, (Boolean) -> Unit) -> Boolean = { _, _ -> false },
    searchQuery: String = "",
    activeSearchMatch: ConversationSearchMatch? = null,
    searchScrollRequestKey: Any? = activeSearchMatch?.key,
    onSearchMatchDistance: (key: String, distanceToViewportCenter: Float) -> Unit = { _, _ -> },
    onSearchTurnsChanged: (List<MessageListTurn>) -> Unit = {},
    selectionMode: Boolean = false,
    selectedMessageIds: Set<String> = emptySet(),
    onToggleMessageSelection: (String) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() },
    lifecycleAppearanceRegistry: MessageLifecycleAppearanceRegistry =
        remember { MessageLifecycleAppearanceRegistry() },
    segmentAppearanceRegistry: SegmentAppearanceRegistry =
        remember { SegmentAppearanceRegistry() },
    lifecycleEntranceTargetMessageId: String? = null,
    leadingContentLayer: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val streamingMessageId = streamingMessage?.id
    val groupedSegmentAutoExpansionController = remember(conversationId) {
        GroupedSegmentAutoExpansionController()
    }
    val editingMessageIdState = remember(conversationId) { mutableStateOf<String?>(null) }
    var editingMessageId by editingMessageIdState
    var pendingEditMessageId by remember { mutableStateOf<String?>(null) }
    val editVisualKeyAliases = remember(conversationId) {
        mutableStateMapOf<String, String>()
    }
    var branchReplacementExitIds by remember(conversationId) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var retainedBranchReplacementExitMessages by remember(conversationId) {
        mutableStateOf<List<ChatMessage>>(emptyList())
    }
    var retainedBranchReplacementPresentations by remember(conversationId) {
        mutableStateOf<Map<String, RunMessagePresentation>>(emptyMap())
    }
    val branchReplacementExitAlpha = remember(conversationId) { Animatable(1f) }
    val latestBranchReplacementFadeFinished by rememberUpdatedState(onRegenerationFadeOutFinished)
    val mutationAnchorLock = remember(state) { MessageListMutationAnchorLock() }
    val mutationScope = rememberCoroutineScope()
    val pendingMutationSettles = remember(state) { mutableMapOf<String, Job>() }
    val searchMatchCentersInTurn = remember(state, activeSearchMatch?.key) {
        mutableStateMapOf<String, Float>()
    }
    val hydratedPayloads = remember(conversationId) { HydratedMessagePayloadLru() }
    var listRootY by remember(state) { mutableFloatStateOf(0f) }
    val streamingTailFollowModeState = remember(state, conversationId) {
        mutableStateOf(StreamingTailFollowMode.INACTIVE)
    }
    val userDragInProgressState = remember(state, conversationId) {
        mutableStateOf(false)
    }
    var streamingTailFollowMode by streamingTailFollowModeState
    val userDragInProgress by userDragInProgressState
    val density = androidx.compose.ui.platform.LocalDensity.current
    fun cacheHydratedPayload(message: ChatMessage) {
        hydratedPayloads.put(message)
    }
    fun cancelMutationAnchoring() {
        pendingMutationSettles.values.forEach { it.cancel() }
        pendingMutationSettles.clear()
        mutationAnchorLock.cancel()
    }
    LaunchedEffect(programmaticScrollActive) {
        if (programmaticScrollActive) cancelMutationAnchoring()
    }
    fun setStreamingTailFollowMode(nextMode: StreamingTailFollowMode) {
        streamingTailFollowMode = nextMode
        val attached =
            nextMode == StreamingTailFollowMode.ATTACHED ||
                nextMode == StreamingTailFollowMode.SETTLING
        streamingTailController.isAttached = attached
        if (!attached) streamingTailController.isAutoFollowing = false
    }
    MessageListTailInteractionEffects(
        state = state,
        conversationId = conversationId,
        isSwitching = isSwitching,
        streamingTailFollowModeState = streamingTailFollowModeState,
        userDragInProgressState = userDragInProgressState,
        streamingTailController = streamingTailController,
        cancelMutationAnchoring = ::cancelMutationAnchoring,
        setStreamingTailFollowMode = ::setStreamingTailFollowMode,
    )

    val visibleProjectionKey = remember(messages) {
        messages.list.map(ChatMessage::toRunProjectionKey)
    }
    val allProjectionKey = remember(allMessages) {
        allMessages.list.map(ChatMessage::toRunProjectionKey)
    }
    val inContextIds = contextRetainedMessageIds

    val activeMessageIds = remember(messages) { messages.list.mapTo(hashSetOf()) { message -> message.id } }
    val presentationMessages = remember(messages, retainedBranchReplacementExitMessages) {
        mergeBranchReplacementPresentationMessages(
            activeMessages = messages.list,
            retainedExitMessages = retainedBranchReplacementExitMessages,
        )
    }
    val turnCache = remember { MessageListTurnCache() }
    val turns = remember(presentationMessages) { turnCache.update(presentationMessages) }
    val latestSearchTurns by rememberUpdatedState(turns)
    val pageSpacing = remember(presentationMessages) { messageListPageTrailingSpacing(presentationMessages) }
    val tailAnchorKey = messageListTailAnchorKey(turns)
    val tailHolderKey = messageListTailHolderKey(turns)
    LaunchedEffect(conversationId, turns, searchQuery) { onSearchTurnsChanged(turns) }

    MessageListEditScrollEffect(
        conversationId = conversationId,
        editingMessageIdState = editingMessageIdState,
        turns = turns,
        allowProgrammaticScrollMotion = motionPolicy.allowProgrammaticScrollMotion,
        state = state,
        messageHeights = messageHeights,
        cancelMutationAnchoring = ::cancelMutationAnchoring,
    )
    val lastUserMessage =
        messages.list.lastOrNull(MessageGenerationBoundaryResolver::isRealUser)

    fun stableVisualKey(messageId: String): String = branchReplacementVisualKey(
        messageId = messageId,
        sourceUserMessageId = regenerationTransition?.sourceUserMessageId,
        targetUserMessageId = regenerationTransition?.targetUserMessageId,
        aliases = editVisualKeyAliases,
    )

    SideEffect {
        val sourceUserMessageId = regenerationTransition?.sourceUserMessageId
        val targetUserMessageId = regenerationTransition?.targetUserMessageId
        if (sourceUserMessageId != null && targetUserMessageId != null) {
            editVisualKeyAliases[targetUserMessageId] =
                editVisualKeyAliases[sourceUserMessageId] ?: sourceUserMessageId
        }
    }

    LaunchedEffect(regenerationTransition?.id) {
        val transition = regenerationTransition
        if (transition == null) {
            if (branchReplacementExitIds.any { exitId ->
                    messages.list.any { message -> message.id == exitId }
                }
            ) {
                branchReplacementExitAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = REGENERATION_ABORT_RESTORE_DURATION_MS,
                        easing = LinearEasing,
                    ),
                )
            } else {
                branchReplacementExitAlpha.snapTo(1f)
            }
            retainedBranchReplacementExitMessages = emptyList()
            retainedBranchReplacementPresentations = emptyMap()
            branchReplacementExitIds = emptySet()
            return@LaunchedEffect
        }

        retainedBranchReplacementExitMessages = branchReplacementExitMessages(
            messages = messages.list.map { message -> hydratedPayloads[message.id] ?: message },
            oldMessageId = transition.oldMessageId,
        )
        branchReplacementExitIds =
            retainedBranchReplacementExitMessages.mapTo(linkedSetOf()) { message -> message.id }
        retainedBranchReplacementPresentations =
            RunUiProjection.project(messages.list, allMessages.list)
                .filterKeys(branchReplacementExitIds::contains)
        if (retainedBranchReplacementExitMessages.isEmpty()) {
            branchReplacementExitAlpha.snapTo(1f)
            latestBranchReplacementFadeFinished(transition.id)
            return@LaunchedEffect
        }
        if (transition.stage != com.newoether.agora.viewmodel.BranchReplacementTransitionStage.ANIMATING) {
            branchReplacementExitAlpha.snapTo(0f)
            return@LaunchedEffect
        }
        branchReplacementExitAlpha.snapTo(1f)
        branchReplacementExitAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = REGENERATION_EXIT_DURATION_MS,
                easing = LinearEasing,
            ),
        )
        latestBranchReplacementFadeFinished(transition.id)
    }

    MessageListTailFollowEffects(
        state = state,
        conversationId = conversationId,
        isLoading = isLoading,
        streamingAutoFollowEnabled = streamingAutoFollowEnabled,
        streamingAutoFollowPaused = streamingAutoFollowPaused,
        lastUserMessageId = lastUserMessage?.id,
        streamingTailWithinAttachThreshold = streamingTailWithinAttachThreshold,
        streamingTailFollowModeState = streamingTailFollowModeState,
        userDragInProgressState = userDragInProgressState,
        streamingTailController = streamingTailController,
        cancelMutationAnchoring = ::cancelMutationAnchoring,
        setStreamingTailFollowMode = ::setStreamingTailFollowMode,
    )

    // Text/status/tool deltas do not change branch/run structure. Cache this O(n) projection by its
    // structural fields; copy text is read from the live MessageItem below.
    val runPresentation = remember(visibleProjectionKey, allProjectionKey) {
        RunUiProjection.project(messages.list, allMessages.list)
    }
    val tailMinHeightPx = if (tailAnchorKey == null || viewportHeight == 0) {
        0
    } else {
        calculateTailHolderMinHeightPx(
            turns = turns,
            semanticAnchorKey = tailAnchorKey,
            baseMinimumHeightPx = calculateTailMinHeightPx(
                viewportHeightPx = viewportHeight,
                targetTopPx = with(density) { 140.dp.roundToPx() },
                bottomObstructionPx = with(density) {
                    (bottomBarHeight + 8.dp).roundToPx()
                },
            ),
            messageHeights = messageHeights,
        )
    }
    val tailMinHeight = with(density) { tailMinHeightPx.toDp() }
    // One progressive actor owns the complete search movement. Far-away turns are approached in
    // bounded per-frame steps; once composed, the same actor retargets against exact glyph
    // geometry. There is no animateScrollToItem teleport and no second correction animation.
    LaunchedEffect(conversationId, searchScrollRequestKey, motionPolicy.allowProgrammaticScrollMotion) {
        if (searchScrollRequestKey == null) return@LaunchedEffect
        val match = activeSearchMatch ?: return@LaunchedEffect
        val turnIndex = messageListTurnIndex(turns, match.messageId)
        if (turnIndex < 0) return@LaunchedEffect
        cancelMutationAnchoring()
        val topInsetPx = with(density) { 140.dp.toPx() }
        val bottomInsetPx = with(density) { bottomBarHeight.toPx() }
        val targetCenterY = topInsetPx +
            ((viewportHeight - bottomInsetPx - topInsetPx).coerceAtLeast(0f) / 2f)
        val fallbackHeightPx = with(density) { 160.dp.toPx() }
        val estimatedTurnHeights = FloatArray(turns.size) { index ->
            estimateMessageListTurnHeightPx(
                turn = turns[index],
                messageHeights = messageHeights,
                fallbackHeightPx = fallbackHeightPx,
            )
        }
        val heightPrefix = FloatArray(turns.size + 1)
        for (index in estimatedTurnHeights.indices) {
            heightPrefix[index + 1] = heightPrefix[index] + estimatedTurnHeights[index]
        }
        val estimatedAnchorInTurn = estimateSearchMatchCenterInTurnPx(
            turn = turns[turnIndex],
            match = match,
            messageHeights = messageHeights,
            fallbackHeightPx = fallbackHeightPx,
        )
        if (!motionPolicy.allowProgrammaticScrollMotion) {
            state.scrollToItem(
                index = turnIndex,
                scrollOffset = searchMatchScrollOffsetPx(
                    matchCenterInTurnPx = estimatedAnchorInTurn,
                    viewportCenterInListPx = targetCenterY,
                ),
            )
            val exactCenterInTurn = snapshotFlow {
                searchMatchCentersInTurn[match.key]
            }
                .first { it != null }!!
            state.scrollToItem(
                index = messageListTurnIndex(latestSearchTurns, match.messageId).coerceAtLeast(0),
                scrollOffset = searchMatchScrollOffsetPx(
                    matchCenterInTurnPx = exactCenterInTurn,
                    viewportCenterInListPx = targetCenterY,
                ),
            )
            return@LaunchedEffect
        }

        state.smoothSeekToItem(
            targetIndex = { messageListTurnIndex(latestSearchTurns, match.messageId) },
            targetErrorPx = { visibleTarget ->
                searchMatchScrollErrorPx(
                    turnOffsetInListPx = visibleTarget.offset.toFloat(),
                    matchCenterInTurnPx =
                        searchMatchCentersInTurn[match.key] ?: estimatedAnchorInTurn,
                    viewportCenterInListPx = targetCenterY,
                )
            },
            estimatedErrorPx = {
                // The old prefix cannot describe a newly prepended page. The same seek
                // actor can approach the current target using its bounded viewport step.
                if (latestSearchTurns !== turns) return@smoothSeekToItem null
                val firstVisible = state.layoutInfo.visibleItemsInfo
                    .minByOrNull { item -> item.index }
                    ?: return@smoothSeekToItem null
                val firstIndex = firstVisible.index.coerceIn(0, turns.size)
                val distanceFromFirstToTarget =
                    heightPrefix[turnIndex] - heightPrefix[firstIndex]
                firstVisible.offset +
                    distanceFromFirstToTarget +
                    estimatedAnchorInTurn -
                    targetCenterY
            },
            exactTargetReady = {
                searchMatchCentersInTurn.containsKey(match.key)
            },
            minimumStepPx = with(density) { 2.dp.toPx() },
        )
    }
    val renderMessage: @Composable (
        ChatMessage,
        (String, List<Int>, Boolean) -> Unit,
    ) -> Unit = { messageStub, requestSegmentDetail ->
        val isStreamingOverlay = messageStub.id == streamingMessageId
        val cachedMessage = initialMessage(messageStub.id) ?: hydratedPayloads[messageStub.id]
        val observedMessage = if (isStreamingOverlay) {
            null
        } else {
            remember(messageStub.id, observeMessage) { observeMessage(messageStub.id) }
                .collectAsState(initial = cachedMessage)
                .value
        }
        val message = resolveMessagePayloadForRender(messageStub, streamingMessage, observedMessage, cachedMessage)
        val hydrationPending = !isStreamingOverlay && observedMessage == null && cachedMessage == null
        val hydrationMutationKey = "hydrate:${messageStub.id}"
        val hydrated = if (isStreamingOverlay) streamingMessage else observedMessage ?: cachedMessage
        LaunchedEffect(messageStub.id, hydrated, isStreamingOverlay) {
            if (isStreamingOverlay || hydrated != null) {
                hydrated?.let(::cacheHydratedPayload)
                onMessageHydrated(conversationId, messageStub.id)
                if (mutationAnchorLock.isActive(hydrationMutationKey)) {
                    withFrameNanos { }
                    withFrameNanos { }
                    mutationAnchorLock.finish(hydrationMutationKey)?.let { anchor ->
                        state.restoreMessageListViewportAnchor(turns, anchor)
                    }
                }
            } else if (
                messageListLayoutMode(
                    isSwitching = isSwitching,
                    isScrollInProgress = state.isScrollInProgress || programmaticScrollActive,
                    isUserDragging = userDragInProgress,
                ) == MessageListLayoutMode.STABLE
            ) {
                val anchor = mutationAnchorLock.begin(
                    key = hydrationMutationKey,
                    candidate = state.captureMessageListViewportAnchor(turns, ::stableVisualKey),
                )
                if (anchor != null) state.restoreMessageListViewportAnchor(turns, anchor)
            }
        }
        DisposableEffect(hydrationMutationKey) {
            onDispose { mutationAnchorLock.finish(hydrationMutationKey) }
        }

        val isRetainedBranchReplacementExit =
            message.id in branchReplacementExitIds && message.id !in activeMessageIds
        val messageIsStreaming = isStreamingOverlay &&
            message.participant == Participant.MODEL &&
            message.status in setOf(
                MessageStatus.SENDING,
                MessageStatus.THINKING,
                MessageStatus.TOOL_CALLING,
                MessageStatus.TRANSCRIBING,
            )
        val isInContext =
            messageIsStreaming ||
                (!isRetainedBranchReplacementExit && message.id in inContextIds)
        // Once the new branch commits, the active Run projection no longer contains the
        // transparent old answer. Retain its exact presentation until the branch-replacement
        // handoff releases that composition, otherwise the action row is conditionally removed instead
        // of participating in the fade.
        val presentation =
            runPresentation[message.id] ?: retainedBranchReplacementPresentations[message.id]
        val animateLifecycleEntrance =
            !isRetainedBranchReplacementExit &&
            message.id != regenerationTransition?.targetUserMessageId &&
                shouldAnimateMessageLifecycleEntrance(
                    message = message,
                    isKnown = lifecycleAppearanceRegistry.isKnown(message.id),
                    isLoading = isLoading,
                    isStreaming = messageIsStreaming,
                    lastUserMessageId = lastUserMessage?.id,
                    requestedTargetMessageId = lifecycleEntranceTargetMessageId,
                )
        // LazyColumn items are subcomposed on demand. Marking the whole projected list in the
        // parent composition races ahead of that subcomposition and makes a brand-new Send look
        // historical before its bubble gets a first frame. Claim "known" only after this concrete
        // item has composed and captured its one-shot entrance decision.
        SideEffect {
            lifecycleAppearanceRegistry.markKnown(message.id)
        }

        val reservedHydrationHeight = messageHeights[message.id]
            ?.takeIf { hydrationPending && it > 0 }
            ?.let { heightPx -> with(density) { heightPx.toDp() } }
        val hydrationHeightModifier = reservedHydrationHeight
            ?.let { height -> Modifier.heightIn(min = height) }
            ?: Modifier
        val deleteTargetMessageId = presentation?.deleteTargetMessageId ?: message.id
        MessageItem(
            message = message,
            outerPadding = pageSpacing[messageStub.id]?.let { PaddingValues(bottom = it.dp) } ?: PaddingValues(vertical = 8.dp),
            includeAssistantOuterSpacing = messageStub.displayPageId == null,
            segmentAppearanceRegistry = segmentAppearanceRegistry,
            modifier = (if (message.id in branchReplacementExitIds) {
                Modifier.graphicsLayer {
                    alpha = branchReplacementExitAlpha.value
                }
            } else {
                Modifier
            }).then(hydrationHeightModifier),
            animateEntrance = animateLifecycleEntrance,
            onEdit = { id, text ->
                if (!isRetainedBranchReplacementExit && pendingEditMessageId == null) {
                    pendingEditMessageId = id
                    mutationScope.launch {
                        val accepted = try {
                            onEditMessage(id, text)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (accepted && editingMessageId == id) {
                            editingMessageId = null
                        }
                        if (pendingEditMessageId == id) {
                            pendingEditMessageId = null
                        }
                    }
                }
            },
            // Every active MODEL owns its streaming renderer until its own terminal status.
            // Appending a queued USER must not dispose the previous turn's incremental renderer.
            isStreaming = messageIsStreaming,
            liveCompactPreview = compactPreview.takeIf {
                isCompacting &&
                    message.isContextCompact() &&
                    message.status in setOf(MessageStatus.SENDING, MessageStatus.THINKING)
            },
            isLoading = isLoading || pendingEditMessageId == message.id,
            isStopping = isStopping,
            compactActionsEnabled = compactMessageActionsEnabled(
                isLoading = isLoading,
                isStopping = isStopping,
                isCompacting = isCompacting,
            ),
            isRegenerationExiting = message.id in branchReplacementExitIds,
            isEditingAllowed = messageActionsEnabled && !isRetainedBranchReplacementExit &&
                !selectionMode &&
                (editingMessageId == null || editingMessageId == message.id) &&
                !isLoading,
            isEditing = editingMessageId == message.id,
            userBubbleSizeAnimationReady = userBubbleSizeAnimationReady(hydrationPending),
            isSwitching = isSwitching,
            isInContext = isInContext,
            modelAliases = modelAliases,
            customProviders = customProviders,
            visualizeContextRollout = visualizeContextRollout,
            toolCallDisplayMode = toolCallDisplayMode,
            thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
            autoExpandActiveGroup = autoExpandActiveGroup,

            parseInlineDollarMath = parseInlineDollarMath,
            autoWrapCodeBlocks = autoWrapCodeBlocks,
            showGenerationSpeed = showGenerationSpeed,
            groupedSegmentAutoExpansionController =
                groupedSegmentAutoExpansionController,
            onStartEdit = {
                if (!isRetainedBranchReplacementExit) editingMessageId = message.id
            },
            onCancelEdit = { editingMessageId = null },
            showActions = messageActionsEnabled && !selectionMode && presentation?.showActions == true,
            readOnlyActions = readOnlyActions && !selectionMode,
            actionCopyText = presentation
                ?.takeIf { it.showActions }
                ?.let {
                    val copyText = if (message.participant == Participant.MODEL) {
                        message.copyTextWithCitations()
                    } else {
                        message.text
                    }
                    copyText.takeIf(String::isNotBlank)
                },
            showBranchSelector = messageActionsEnabled && !selectionMode && presentation?.showBranchSelector == true,
            branchIndex = presentation?.branchIndex ?: 0,
            totalBranches = presentation?.totalBranches ?: 1,
            onSwitchBranch = { direction ->
                val anchorId = presentation?.branchAnchorMessageId
                if (anchorId != null) {
                    onSwitchBranch(
                        presentation.branchAnchorParentId,
                        anchorId,
                        direction,
                    )
                }
            },
            onRegenerate = onRegenerate,
            onFork = onFork,
            onShare = onShare,
            onRecompact = onRecompact,
            deleteTargetMessageId = deleteTargetMessageId,
            onDelete = onDelete,
            conversationMessages = { allMessages.list },
            onDeleteConversation = onDeleteConversation,
            onMediaClick = onMediaClick,
            onFileContentClick = onFileContentClick,
            onPdfPagesClick = onPdfPagesClick,
            onSegmentDetailRequest = requestSegmentDetail,
            searchQuery = searchQuery,
            activeSearchMatch = activeSearchMatch,
            onSearchMatchPosition = { key, measurementEpoch, centerY ->
                val activeKey = activeSearchMatch?.key
                if (!acceptsSearchMatchMeasurement(activeKey, key, measurementEpoch)) {
                    return@MessageItem
                }
                val turnIndex = messageListTurnIndex(turns, message.id)
                val visibleTurn = state.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == turnIndex }
                if (activeKey != null && visibleTurn != null) {
                    searchMatchCentersInTurn[key] = searchMatchCenterInTurnPx(
                        glyphCenterInRootPx = centerY,
                        listRootInRootPx = listRootY,
                        turnOffsetInListPx = visibleTurn.offset.toFloat(),
                    )
                }
                val topInsetPx = with(density) { 140.dp.toPx() }
                val bottomInsetPx = with(density) { bottomBarHeight.toPx() }
                val viewportCenterY = topInsetPx +
                    ((viewportHeight - bottomInsetPx - topInsetPx).coerceAtLeast(0f) / 2f)
                onSearchMatchDistance(
                    key,
                    kotlin.math.abs(centerY - listRootY - viewportCenterY),
                )
            },
            selectionMode = selectionMode,
            selected = !isRetainedBranchReplacementExit && message.id in selectedMessageIds,
            onToggleSelection = {
                if (!isRetainedBranchReplacementExit) onToggleMessageSelection(message.id)
            },
            onHeightChanged = { height ->
                if (height > 0 && messageHeights[message.id] != height) {
                    val mode = messageListLayoutMode(
                        isSwitching = isSwitching,
                        isScrollInProgress =
                            state.isScrollInProgress || programmaticScrollActive,
                        isUserDragging = userDragInProgress,
                    )
                    // Measurement remains available to explicit scrolling calculations, but
                    // bottom geometry no longer reads it. The tail's minimum height absorbs
                    // content changes atomically in the same measure pass.
                    messageHeights[message.id] = height
                    if (
                        mode == MessageListLayoutMode.STABLE &&
                        streamingTailFollowMode != StreamingTailFollowMode.ATTACHED
                    ) {
                        val lockedAnchor = mutationAnchorLock.anchor
                        if (lockedAnchor != null) {
                            state.restoreMessageListViewportAnchor(turns, lockedAnchor)
                        }
                    }
                }
            },
            onLayoutMutationStarted = { mutationKey ->
                pendingMutationSettles.remove(mutationKey)?.cancel()
                if (
                    streamingTailFollowMode != StreamingTailFollowMode.ATTACHED &&
                    messageListLayoutMode(
                        isSwitching = isSwitching,
                        isScrollInProgress =
                            state.isScrollInProgress || programmaticScrollActive,
                        isUserDragging = userDragInProgress,
                    ) == MessageListLayoutMode.STABLE
                ) {
                    val anchor = mutationAnchorLock.begin(
                        key = mutationKey,
                        candidate = state.captureMessageListViewportAnchor(turns, ::stableVisualKey),
                    )
                    // Pre-arm the very first remeasure. Waiting for onSizeChanged is one frame
                    // too late when an AnimatedVisibility reverses under rapid taps.
                    if (anchor != null) state.restoreMessageListViewportAnchor(turns, anchor)
                }
            },
            onLayoutMutationSettled = { mutationKey ->
                pendingMutationSettles.remove(mutationKey)?.cancel()
                pendingMutationSettles[mutationKey] = mutationScope.launch {
                    // Transition.isRunning reaches false before the final size has necessarily
                    // propagated through the parent LazyColumn. Keep the original anchor through
                    // two complete frames; a reversing tap cancels this pending release.
                    withFrameNanos { }
                    withFrameNanos { }
                    mutationAnchorLock.finish(mutationKey)
                    pendingMutationSettles.remove(mutationKey)
                    // onSizeChanged already held the exact pre-mutation anchor throughout the
                    // transition. A final requestScrollToItem here produced a visible end-frame
                    // correction after the animation was otherwise complete.
                }
            },
            thoughtExpandedStates = thoughtExpandedStates,
        )
    }
    MessageSegmentDetailHost(
        conversationId = conversationId,
        authoritativeMessages = authoritativeMessages.list,
        streamingMessage = streamingMessage,
        observeMessage = observeMessage,
        parseInlineDollarMath = parseInlineDollarMath,
        autoWrapCodeBlocks = autoWrapCodeBlocks,
        onMediaClick = onMediaClick,
        modifier = modifier,
    ) { requestSegmentDetail ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    listRootY = coordinates.positionInRoot().y
                },
            contentPadding = contentPadding,
            reverseLayout = false,
            state = state,
            userScrollEnabled = userScrollEnabled, overscrollEffect = overscrollEffect
        ) {
            items(turns, key = { turn -> stableVisualKey(turn.key) }) { turn ->
                val holdsTailMinimum = turn.key == tailHolderKey
                Box(modifier = Modifier) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = if (holdsTailMinimum) tailMinHeight else 0.dp,
                            ),
                    ) {
                        turn.messages.forEach { message ->
                            key(stableVisualKey(message.id)) {
                                renderMessage(message, requestSegmentDetail)
                            }
                        }
                    }
                    if (turn.key == turns.firstOrNull()?.key) leadingContentLayer?.invoke(this)
                }
            }
            // A stable physical-end target, deliberately separate from the streaming-tail
            // indicator. Reaching this item and exhausting canScrollForward means the actual
            // LazyColumn maximum extent has been reached.
            item(key = AbsoluteBottomSentinelKey) {
                Spacer(Modifier.fillMaxWidth().height(1.dp))
            }
        }
    }
}
