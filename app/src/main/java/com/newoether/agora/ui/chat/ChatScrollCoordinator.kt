package com.newoether.agora.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.ui.common.AgoraHaptics
import com.newoether.agora.ui.motion.AgoraMotionPolicy
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.AnimatedScrollDestination
import com.newoether.agora.viewmodel.AnimatedScrollRequest
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.BranchReplacementTransitionRequest
import com.newoether.agora.viewmodel.BranchReplacementTransitionStage
import com.newoether.agora.viewmodel.SwitchingRequestKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
private const val SCROLL_SETTLE_TIMEOUT_MS = 8_000L
private const val STABLE_LAYOUT_SAMPLES = 3
private const val LAYOUT_SAMPLE_INTERVAL_MS = 32L
internal val SendFeedbackScrollSpec = DefaultFeedbackScrollSpec.copy(
    startup = FeedbackScrollStartupSpec(
        durationMillis = 240L,
        easing = FastOutSlowInEasing,
    ),
)

@Stable
internal class ChatScrollCoordinator internal constructor(
    val listState: LazyListState,
    private val absoluteBottomScrollPhaseState: MutableState<AbsoluteBottomScrollPhase>,
    private val absoluteBottomRequestTokenState: MutableLongState,
    private val absoluteBottomRequestFeedbackSpecState: MutableState<FeedbackScrollSpec>,
    private val isNearAbsoluteBottomState: MutableState<Boolean>,
    private val isWithinAbsoluteBottomAttachThresholdState: MutableState<Boolean>,
    private val composerInputFocusedState: MutableState<Boolean>,
    private val imeBottomAnchorStateHolder: MutableState<ImeBottomAnchorState>,
    private val viewportHeightState: MutableIntState,
    val messageHeights: SnapshotStateMap<String, Int>,
    private val hydrationRegistry: ConversationHydrationRegistry,
    val messageLifecycleAppearanceRegistry: MessageLifecycleAppearanceRegistry,
    val streamingTailController: StreamingTailController,
) {
    private var userDragRevision: Long = 0L

    val absoluteBottomScrollPhase: AbsoluteBottomScrollPhase
        get() = absoluteBottomScrollPhaseState.value
    val isNearAbsoluteBottom: Boolean
        get() = isNearAbsoluteBottomState.value
    val isWithinAbsoluteBottomAttachThreshold: Boolean
        get() = isWithinAbsoluteBottomAttachThresholdState.value
    val imeBottomAnchorState: ImeBottomAnchorState
        get() = imeBottomAnchorStateHolder.value
    val viewportHeightPx: Int
        get() = viewportHeightState.intValue
    fun recordViewportHeight(heightPx: Int) { viewportHeightState.intValue = heightPx }
    fun recordMessageHydrated(conversationId: String?, messageId: String) {
        hydrationRegistry.record(conversationId, messageId)
    }
    fun setComposerInputFocused(focused: Boolean) {
        if (composerInputFocusedState.value != focused) {
            composerInputFocusedState.value = focused
        }
    }
    fun requestAbsoluteBottomScroll(
        feedbackSpec: FeedbackScrollSpec = DefaultFeedbackScrollSpec,
    ): Boolean {
        if (absoluteBottomScrollPhase.isActive) return false
        imeBottomAnchorStateHolder.value = reduceImeBottomAnchor(
            imeBottomAnchorState,
            ImeBottomAnchorEvent.Cancelled,
        )
        absoluteBottomScrollPhaseState.value = reduceAbsoluteBottomScroll(
            absoluteBottomScrollPhase,
            AbsoluteBottomScrollEvent.Requested,
        )
        absoluteBottomRequestFeedbackSpecState.value = feedbackSpec
        absoluteBottomRequestTokenState.longValue =
            if (absoluteBottomRequestTokenState.longValue == Long.MAX_VALUE) 1L
            else absoluteBottomRequestTokenState.longValue + 1L
        return true
    }
    @Composable
    internal fun BindLayoutObservation(
        currentConversationId: String?,
        loadedMessagesConversationId: String?,
        imeBottomPx: Int,
        density: Density,
    ) {
        val imeBottomEligibleNow =
            currentConversationId != null &&
                loadedMessagesConversationId == currentConversationId &&
                composerInputFocusedState.value &&
                isWithinAbsoluteBottomAttachThreshold
        SideEffect {
            val next = reduceImeBottomAnchor(
                current = imeBottomAnchorState,
                event = ImeBottomAnchorEvent.InsetsObserved(
                    insetPx = imeBottomPx,
                    bottomEligibleNow = imeBottomEligibleNow,
                    anchorAllowed = composerInputFocusedState.value,
                ),
            )
            if (next != imeBottomAnchorState) imeBottomAnchorStateHolder.value = next
        }
        val bottomButtonHideThresholdPx = with(density) { 64.dp.toPx() }
        val bottomButtonShowThresholdPx = with(density) { 96.dp.toPx() }
        LaunchedEffect(
            listState,
            currentConversationId,
            bottomButtonHideThresholdPx,
            bottomButtonShowThresholdPx,
        ) {
            val estimatedSentinelSizePx = with(density) { 1.dp.toPx() }
            snapshotFlow {
                val snapshot = absoluteBottomLayoutSnapshot(
                    layoutInfo = listState.layoutInfo,
                    canScrollForward = listState.canScrollForward,
                )
                snapshot to snapshot.estimatedRemainingDistancePx(estimatedSentinelSizePx)
            }
                .distinctUntilChanged()
                .collect { (snapshot, remainingDistancePx) ->
                    isWithinAbsoluteBottomAttachThresholdState.value =
                        isWithinAbsoluteBottomAttachThreshold(
                            snapshot = snapshot,
                            remainingDistancePx = remainingDistancePx,
                            thresholdPx = bottomButtonHideThresholdPx,
                        )
                    isNearAbsoluteBottomState.value = reduceAbsoluteBottomProximity(
                        wasNearBottom = isNearAbsoluteBottom,
                        canScrollForward = snapshot.canScrollForward,
                        remainingDistancePx = remainingDistancePx,
                        hideThresholdPx = bottomButtonHideThresholdPx,
                        showThresholdPx = bottomButtonShowThresholdPx,
                    )
                }
        }
    }

    @Composable
    internal fun BindImeEffects(
        currentConversationId: String?,
        messages: State<List<ChatMessage>>,
        density: Density,
        bottomBarHeight: Dp,
        shareSelectionBarSpace: Dp,
        imeBottomPx: Int,
    ) {
        val latestImeBottomAnchorState by rememberUpdatedState(imeBottomAnchorState)
        val latestImeBottomPx by rememberUpdatedState(imeBottomPx)
        LaunchedEffect(currentConversationId, imeBottomAnchorState.active) {
            if (!imeBottomAnchorState.active) return@LaunchedEffect

            val actorStartNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
            var lastObservedInsetPx = latestImeBottomPx
            var lastInsetChangeNanos = actorStartNanos
            var stableFrames = 0
            while (true) {
                val frameNanos = withFrameNanos { frameTimeNanos -> frameTimeNanos }
                if (!latestImeBottomAnchorState.active) return@LaunchedEffect
                if (latestImeBottomPx != lastObservedInsetPx) {
                    lastObservedInsetPx = latestImeBottomPx
                    lastInsetChangeNanos = frameNanos
                }

                val layout = absoluteBottomLayoutSnapshot(
                    layoutInfo = listState.layoutInfo,
                    canScrollForward = listState.canScrollForward,
                )
                val remainingDistancePx =
                    layout.remainingDistancePx
                        ?: estimateRemainingAbsoluteBottomDistance(
                            messages = messages.value,
                            density = density,
                            bottomBarHeight = bottomBarHeight,
                            shareSelectionBarSpace = shareSelectionBarSpace,
                        )
                        ?: if (listState.canScrollForward) {
                            layout.viewportSizePx * 0.5f
                        } else {
                            0f
                        }

                if (remainingDistancePx > 0.5f) {
                    // IME anchoring is a positional correction, not navigational travel. Consume
                    // each newly exposed gap in one frame so list and keyboard remain attached.
                    listState.dispatchRawDelta(remainingDistancePx)
                    stableFrames = 0
                } else {
                    val insetStableForNanos = frameNanos - lastInsetChangeNanos
                    stableFrames =
                        if (insetStableForNanos >= 80_000_000L) stableFrames + 1 else 0
                    if (stableFrames >= 3) {
                        imeBottomAnchorStateHolder.value = reduceImeBottomAnchor(
                            latestImeBottomAnchorState,
                            ImeBottomAnchorEvent.CorrectionSettled,
                        )
                        return@LaunchedEffect
                    }
                }

                if (frameNanos - actorStartNanos >= 1_600_000_000L) {
                    imeBottomAnchorStateHolder.value = reduceImeBottomAnchor(
                        latestImeBottomAnchorState,
                        ImeBottomAnchorEvent.CorrectionSettled,
                    )
                    return@LaunchedEffect
                }
            }
        }

    }

    @Composable
    internal fun BindTransitionEffects(
        currentConversationId: String?,
        currentConversation: ChatConversation?,
        loadedMessagesConversationId: String?,
        messages: State<List<ChatMessage>>,
        density: Density,
        motionPolicy: AgoraMotionPolicy,
        bottomBarHeight: Dp,
        shareSelectionBarSpace: Dp,
        imeBottomPx: Int,
        viewModel: ChatViewModel,
        haptics: AgoraHaptics,
    ) {
        val latestCurrentConversationId by rememberUpdatedState(currentConversationId)
        val latestCurrentConversation by rememberUpdatedState(currentConversation)
        val latestLoadedMessagesConversationId by rememberUpdatedState(
            loadedMessagesConversationId,
        )
        BindImeEffects(currentConversationId, messages, density, bottomBarHeight,
            shareSelectionBarSpace, imeBottomPx)

        val switchingScrollRequest by viewModel.switchingScrollRequest.collectAsState()
        val contextProjection by viewModel.conversationContextProjection.collectAsState()
        val latestContextProjection by rememberUpdatedState(contextProjection)
        LaunchedEffect(
            switchingScrollRequest?.id,
            switchingScrollRequest?.readyForUi,
            currentConversationId,
        ) {
            val request = switchingScrollRequest ?: return@LaunchedEffect
            if (!request.readyForUi || request.kind == SwitchingRequestKind.NEW_CHAT) {
                return@LaunchedEffect
            }
            // Selection and request flows may reach composition in either order. Start only on
            // the destination's coordinator, whose measurements and hydration belong to this page.
            if (
                request.kind == SwitchingRequestKind.CONVERSATION &&
                request.conversationId != null &&
                request.conversationId != currentConversationId
            ) return@LaunchedEffect
            var terminalized = false
            try {
                val targetConversationId = request.conversationId
                if (targetConversationId == null) {
                    viewModel.failSwitchingScroll(request.id, "conversation disappeared")
                    terminalized = true
                    return@LaunchedEffect
                }

                if (request.kind == SwitchingRequestKind.CONVERSATION) {
                    snapshotFlow {
                        Triple(
                            latestCurrentConversationId,
                            latestCurrentConversation?.id,
                            latestLoadedMessagesConversationId,
                        )
                    }.filter { (currentId, loadedConversationId, loadedMessagesId) ->
                        currentId == targetConversationId &&
                            loadedConversationId == targetConversationId &&
                            loadedMessagesId == targetConversationId
                    }.first()
                } else if (currentConversationId != targetConversationId) {
                    viewModel.failSwitchingScroll(request.id, "conversation changed")
                    terminalized = true
                    return@LaunchedEffect
                }

                snapshotFlow {
                    val conversation = latestCurrentConversation
                    val projection = latestContextProjection
                    conversation?.id == targetConversationId &&
                        projection.conversationId == targetConversationId &&
                        projection.selectedBranchesJson == conversation.selectedBranchesJson &&
                        projection.completed &&
                        !projection.loading
                }.first { settled -> settled }

                if (
                    settleCoveredTransition(
                        messages = messages,
                        targetMessageId = request.targetMessageId,
                        scrollToTarget = request.scrollToTarget,
                        scrollToAbsoluteBottom =
                            request.kind == SwitchingRequestKind.CONVERSATION,
                    )
                ) {
                    val completed = viewModel.completeSwitchingScroll(request.id)
                    if (
                        completed &&
                        request.kind == SwitchingRequestKind.CONVERSATION &&
                        request.hapticOnCompletion
                    ) {
                        haptics.confirm()
                    }
                } else {
                    viewModel.failSwitchingScroll(request.id, "layout failed to stabilize")
                }
                terminalized = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DebugLog.e("AgoraUI", "Switching request ${request.id} failed", error)
                viewModel.failSwitchingScroll(request.id, "unexpected UI failure")
                terminalized = true
            } finally {
                if (!terminalized) {
                    viewModel.failSwitchingScroll(request.id, "switching effect cancelled")
                }
            }
        }

        LaunchedEffect(currentConversationId) {
            if (viewModel.scrollRequests.suppressNextOpenScroll) {
                viewModel.scrollRequests.suppressNextOpenScroll = false
            }
        }
    }

    @Composable
    internal fun BindRequestEffects(
        currentConversationId: String?,
        isNewChatMode: Boolean,
        isLoading: Boolean,
        isStopping: Boolean,
        isSwitching: Boolean,
        conversationSearchActive: Boolean,
        shareSelectionActive: Boolean,
        regenerationTransition: BranchReplacementTransitionRequest?,
        animatedScrollRequest: AnimatedScrollRequest?,
        messages: State<List<ChatMessage>>,
        density: Density,
        motionPolicy: AgoraMotionPolicy,
        bottomBarHeight: Dp,
        shareSelectionBarSpace: Dp,
        onRegenerationScrollFinished: (Long, Boolean) -> Unit = { _, _ -> },
        onRegenerationTransitionFinished: (Long) -> Unit = {},
        onAnimatedScrollFinished: (Long) -> Unit = {},
    ) {
        val latestGenerationCanGrow by rememberUpdatedState(isLoading && !isStopping)
        LaunchedEffect(
            absoluteBottomRequestTokenState.longValue,
            currentConversationId,
            motionPolicy.allowProgrammaticScrollMotion,
        ) {
            if (absoluteBottomRequestTokenState.longValue == 0L) return@LaunchedEffect
            try {
                val reachedBottom = if (motionPolicy.allowProgrammaticScrollMotion) {
                    listState.animateToAbsoluteBottom(
                        isGenerationActive = { latestGenerationCanGrow },
                        estimateRemainingDistancePx = {
                            estimateRemainingAbsoluteBottomDistance(
                                messages = messages.value,
                                density = density,
                                bottomBarHeight = bottomBarHeight,
                                shareSelectionBarSpace = shareSelectionBarSpace,
                            )
                        },
                        minimumStepPx = with(density) { 2.dp.toPx() },
                        onPhaseChanged = { phase ->
                            absoluteBottomScrollPhaseState.value = phase
                        },
                        feedbackSpec = absoluteBottomRequestFeedbackSpecState.value,
                    )
                } else {
                    absoluteBottomScrollPhaseState.value = AbsoluteBottomScrollPhase.SEEKING
                    val lastIndex = listState.layoutInfo.totalItemsCount - 1
                    if (lastIndex >= 0) {
                        listState.scrollToItem(lastIndex)
                        withFrameNanos { }
                        !listState.canScrollForward
                    } else {
                        false
                    }
                }
                if (reachedBottom) {
                    imeBottomAnchorStateHolder.value = reduceImeBottomAnchor(
                        imeBottomAnchorState,
                        ImeBottomAnchorEvent.ExplicitBottomReached,
                    )
                }
            } finally {
                if (absoluteBottomScrollPhase.isActive) {
                    absoluteBottomScrollPhaseState.value = reduceAbsoluteBottomScroll(
                        absoluteBottomScrollPhase,
                        AbsoluteBottomScrollEvent.Cancelled,
                    )
                }
            }
        }
        LaunchedEffect(listState, currentConversationId) {
            listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) {
                    userDragRevision =
                        if (userDragRevision == Long.MAX_VALUE) 1L else userDragRevision + 1L
                    imeBottomAnchorStateHolder.value = reduceImeBottomAnchor(
                        imeBottomAnchorState,
                        ImeBottomAnchorEvent.UserDragStarted,
                    )
                    if (absoluteBottomScrollPhase.isActive) {
                        absoluteBottomScrollPhaseState.value = reduceAbsoluteBottomScroll(
                            absoluteBottomScrollPhase,
                            AbsoluteBottomScrollEvent.Cancelled,
                        )
                        absoluteBottomRequestTokenState.longValue = 0L
                    }
                }
            }
        }
        LaunchedEffect(
            conversationSearchActive,
            shareSelectionActive,
            isSwitching,
            regenerationTransition?.id,
            animatedScrollRequest?.id,
            imeBottomAnchorState.active,
        ) {
            val competingTransition =
                conversationSearchActive ||
                    shareSelectionActive ||
                    isSwitching ||
                    regenerationTransition != null ||
                    animatedScrollRequest != null
            if (competingTransition && absoluteBottomScrollPhase.isActive) {
                absoluteBottomScrollPhaseState.value = reduceAbsoluteBottomScroll(
                    absoluteBottomScrollPhase,
                    AbsoluteBottomScrollEvent.Cancelled,
                )
                absoluteBottomRequestTokenState.longValue = 0L
            }
            if (competingTransition && imeBottomAnchorState.active) {
                imeBottomAnchorStateHolder.value = reduceImeBottomAnchor(
                    imeBottomAnchorState,
                    ImeBottomAnchorEvent.Cancelled,
                )
            }
        }
        LaunchedEffect(
            regenerationTransition?.id,
            regenerationTransition?.targetUserMessageId,
            currentConversationId,
        ) {
            val request = regenerationTransition ?: return@LaunchedEffect
            if (request.scrollFinished) return@LaunchedEffect
            val targetUserMessageId = request.targetUserMessageId ?: return@LaunchedEffect
            if (request.conversationId != currentConversationId) {
                onRegenerationScrollFinished(request.id, false)
                return@LaunchedEffect
            }
            try {
                val committedMessages = snapshotFlow { messages.value }.first { path ->
                    path.any { message -> message.id == targetUserMessageId }
                }
                val success = animateToUserMessage(
                    messages = committedMessages,
                    targetMessageId = targetUserMessageId,
                    easing = SCROLL_EASING,
                    density = density,
                    motionPolicy = motionPolicy,
                )
                onRegenerationScrollFinished(request.id, success)
            } catch (error: CancellationException) {
                onRegenerationScrollFinished(request.id, false)
                throw error
            }
        }
        LaunchedEffect(
            regenerationTransition?.id,
            regenerationTransition?.stage,
            regenerationTransition?.scrollFinished,
            currentConversationId,
        ) {
            val request = regenerationTransition
                ?.takeIf {
                    it.stage == BranchReplacementTransitionStage.COMMITTED && it.scrollFinished
                }
                ?: return@LaunchedEffect
            val oldMessageId = request.oldMessageId
            if (request.conversationId == currentConversationId && oldMessageId != null) {
                snapshotFlow {
                    messages.value.none { message -> message.id == oldMessageId }
                }.first { oldPathRemoved -> oldPathRemoved }
                withFrameNanos { }
            }
            onRegenerationTransitionFinished(request.id)
        }
        LaunchedEffect(animatedScrollRequest?.id, currentConversationId) {
            val request = animatedScrollRequest ?: return@LaunchedEffect
            if (request.conversationId != currentConversationId) {
                if (currentConversationId != null || !isNewChatMode) {
                    onAnimatedScrollFinished(request.id)
                }
                return@LaunchedEffect
            }
            when (request.destination) {
                AnimatedScrollDestination.MESSAGE -> {
                    try {
                        if (!animateAfterTargetCommitted(
                                messages = messages,
                                targetMessageId = request.targetMessageId,
                                density = density,
                                motionPolicy = motionPolicy,
                            )
                        ) {
                            DebugLog.e(
                                "AgoraUI",
                                "Animated scroll target was not committed: ${request.targetMessageId}",
                            )
                        }
                    } finally {
                        onAnimatedScrollFinished(request.id)
                    }
                }
                AnimatedScrollDestination.ABSOLUTE_BOTTOM -> {
                    val attachedAtRequest =
                        isWithinAbsoluteBottomAttachThreshold ||
                            streamingTailController.isAttached ||
                            absoluteBottomScrollPhase.isActive
                    val userDragRevisionAtRequest = userDragRevision
                    val targetCommitted = try {
                        awaitScrollTargetCommitted(messages, request.targetMessageId)
                    } finally {
                        onAnimatedScrollFinished(request.id)
                    }
                    if (targetCommitted && request.conversationId == currentConversationId) {
                        val shouldScroll = shouldHonorAttachedBottomRequest(
                            attachedOnly = request.attachedOnly,
                            attachedAtRequest = attachedAtRequest,
                            userDragRevisionAtRequest = userDragRevisionAtRequest,
                            currentUserDragRevision = userDragRevision,
                        )
                        if (shouldScroll) {
                            requestAbsoluteBottomScroll(feedbackSpec = SendFeedbackScrollSpec)
                        }
                    } else if (!targetCommitted) {
                        DebugLog.e(
                            "AgoraUI",
                            "Absolute-bottom scroll target was not committed: ${request.targetMessageId}",
                        )
                    }
                }
            }
        }
    }

    private suspend fun awaitScrollTargetCommitted(
        messages: State<List<ChatMessage>>,
        targetMessageId: String?,
    ): Boolean = withTimeoutOrNull(SCROLL_SETTLE_TIMEOUT_MS) {
        // The final item is the bottom sentinel, never a committed message turn. A newly
        // appended turn initially occupies that old index until LazyColumn remeasures.
        snapshotFlow {
            val index = resolveScrollTargetIndex(messages.value, targetMessageId)
            index to listState.layoutInfo.totalItemsCount
        }.first { (index, itemCount) -> index >= 0 && index < itemCount - 1 }
        true
    } == true

    private suspend fun animateAfterTargetCommitted(
        messages: State<List<ChatMessage>>,
        targetMessageId: String?,
        density: Density,
        motionPolicy: AgoraMotionPolicy,
    ): Boolean {
        if (!awaitScrollTargetCommitted(messages, targetMessageId)) return false
        return animateToUserMessage(
            messages = messages.value,
            targetMessageId = targetMessageId,
            density = density,
            motionPolicy = motionPolicy,
        )
    }

    internal suspend fun settleOpenedConversation(messages: State<List<ChatMessage>>): Boolean =
        settleCoveredTransition(messages, null, false, true)

    private suspend fun settleCoveredTransition(
        messages: State<List<ChatMessage>>,
        targetMessageId: String?,
        scrollToTarget: Boolean,
        scrollToAbsoluteBottom: Boolean,
    ): Boolean = withTimeoutOrNull(SCROLL_SETTLE_TIMEOUT_MS) {
        val stability = CoveredLayoutStabilityTracker(STABLE_LAYOUT_SAMPLES)
        while (true) {
            delay(LAYOUT_SAMPLE_INTERVAL_MS)
            val currentMessages = messages.value
            if (scrollToAbsoluteBottom) {
                val lastTurnMessageIds = buildMessageListTurns(currentMessages)
                    .lastOrNull()
                    ?.messages
                    ?.map(ChatMessage::id)
                    .orEmpty()
                val layout = listState.layoutInfo
                val sentinel = layout.visibleItemsInfo.firstOrNull { item ->
                    item.key == AbsoluteBottomSentinelKey
                }
                val sample = CoveredAbsoluteBottomSample(
                    viewportHeightPx = viewportHeightPx,
                    totalItemsCount = layout.totalItemsCount,
                    canScrollForward = listState.canScrollForward,
                    sentinelIndex = sentinel?.index,
                    sentinelKey = sentinel?.key,
                )
                if (sample.needsScroll) {
                    listState.scrollToItem(sample.targetIndex)
                    stability.reset()
                    continue
                }
                if (!sample.ready) {
                    stability.reset()
                    continue
                }
                val signature = listOf(
                    currentMessages.map(ChatMessage::id),
                    lastTurnMessageIds.map { messageId ->
                        listOf(messageId, messageHeights[messageId] ?: 0)
                    },
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    layout.totalItemsCount,
                    layout.viewportStartOffset,
                    layout.viewportEndOffset,
                    layout.visibleItemsInfo.map { item ->
                        listOf(item.index, item.key, item.offset, item.size)
                    },
                    viewportHeightPx,
                )
                if (stability.observe(ready = true, signature = signature)) break
                continue
            }
            if (!scrollToTarget) {
                if (viewportHeightPx <= 0) {
                    stability.reset()
                    continue
                }
                val layout = listState.layoutInfo
                val signature = listOf(
                    currentMessages.map(ChatMessage::id),
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    layout.totalItemsCount,
                    layout.viewportStartOffset,
                    layout.viewportEndOffset,
                    layout.visibleItemsInfo.map { item ->
                        listOf(item.index, item.key, item.offset, item.size)
                    },
                    viewportHeightPx,
                )
                if (stability.observe(ready = true, signature = signature)) break
                continue
            }
            val targetIndex = resolveScrollTargetIndex(currentMessages, targetMessageId)
            val target = resolveScrollTargetMessage(currentMessages, targetMessageId)
            if (targetIndex == -1 || target == null || viewportHeightPx <= 0) {
                stability.reset()
                continue
            }
            val requestedTarget = targetMessageId?.let { id ->
                currentMessages.firstOrNull { it.id == id }
            }
            if (targetMessageId != null && requestedTarget == null) {
                stability.reset()
                continue
            }
            val requestedTargetHeight = requestedTarget?.let { messageHeights[it.id] }
            if (
                requestedTarget != null &&
                (requestedTargetHeight == null || requestedTargetHeight <= 0)
            ) {
                stability.reset()
                continue
            }

            val positioned =
                listState.firstVisibleItemIndex == targetIndex &&
                    listState.firstVisibleItemScrollOffset <= 2
            if (!positioned) {
                listState.scrollToItem(targetIndex, 0)
                stability.reset()
                continue
            }

            val targetInfo = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == targetIndex }
            val measuredHeight = messageHeights[target.id]
            if (targetInfo == null || measuredHeight == null || measuredHeight <= 0) {
                stability.reset()
                continue
            }
            val signature = listOf(
                targetIndex,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                targetInfo.offset,
                targetInfo.size,
                measuredHeight,
                viewportHeightPx,
                currentMessages.size,
                requestedTarget?.id.orEmpty(),
                requestedTargetHeight ?: 0,
            )
            if (stability.observe(ready = true, signature = signature)) break
        }
        true
    } == true
}
