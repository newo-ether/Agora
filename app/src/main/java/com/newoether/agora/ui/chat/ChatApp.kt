package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.TopLevelPresentation
import com.newoether.agora.api.DebugProvider
import com.newoether.agora.data.forDisplay
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.util.gradientBlur
import com.newoether.agora.ui.chat.bottombar.CHAT_BOTTOM_BAR_OUTER_SHAPE
import com.newoether.agora.ui.chat.bottombar.ChatBottomBar
import com.newoether.agora.ui.chat.bottombar.LoopStatusBackdrop
import com.newoether.agora.ui.chat.interaction.ChatUserInteractionBar
import com.newoether.agora.ui.components.AnimatedBlobBackground
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.rememberAgoraHaptics
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.model.StableModelAliases
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.validChatModels
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun ChatApp(
    viewModel: ChatViewModel,
    initialComposerFocusReady: Boolean = true,
    onNavigateBack: (() -> Unit)? = null,
    drawerEnabled: Boolean = true,
    openDrawerOnStart: Boolean = false, initialScrollToTop: Boolean = false,
    onOpenSettings: () -> Unit,
    onOpenTasks: (String?) -> Unit = {},
    onOpenRemote: () -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit,
    onFileContentClick: ((String, String) -> Unit)? = null,
    onPdfPagesClick: ((List<String>, Int) -> Unit)? = null, onPdfPreviewSelect: ((List<String>, Int) -> Unit)? = null,
    pdfViewerSelection: Set<Int> = emptySet(),
    onTogglePdfSelection: ((Int) -> Unit)? = null,
    onInitPdfSelection: ((Set<Int>) -> Unit)? = null,
    fullScreenViewerUrls: List<String>? = null,
    topLevelPresentation: TopLevelPresentation = TopLevelPresentation.CHAT,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val motionPolicy = LocalAgoraMotionPolicy.current
    ConversationShareEffect(viewModel, context)
    val drawerState = rememberChatDrawerState()
    LaunchedEffect(openDrawerOnStart, drawerEnabled) {
        if (openDrawerOnStart && drawerEnabled) drawerState.openImmediately()
    }
    val conversations by viewModel.conversations.collectAsState()
    // Defer value reads to the narrow composition regions that actually render messages. The
    // State objects themselves are stable, so stream snapshots no longer recompose all ChatApp.
    val messagesState = viewModel.messages.collectAsState()
    val allMessagesState = viewModel.allMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isCompacting by viewModel.compactUi.isCompacting.collectAsState()
    val compactModel by viewModel.settings.contextCompactModel.collectAsState()
    val compactPrompt by viewModel.settings.contextCompactPrompt.collectAsState()
    val compactRetainCount by viewModel.settings.contextCompactRetainCount.collectAsState()
    val compactThresholdPercent by viewModel.settings.contextCompactThresholdPercent.collectAsState()
    val compactEnabled by viewModel.settings.contextCompactEnabled.collectAsState()
    val manualCompactDialogVisible = rememberSaveable { mutableStateOf(false) }
    val dialogState = rememberChatAppDialogState(manualCompactDialogVisible)
    val queuedSends by viewModel.queuedSends.collectAsState()
    val isStopping by viewModel.isStopping.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    var pendingForkRequest by remember(currentConversationId) { mutableStateOf<ForkConversationRequest?>(null) }
    val currentConversation by viewModel.currentConversation.collectAsState()
    val loadedMessagesConversationId by viewModel.loadedMessagesConversationId.collectAsState()
    val currentLoop by viewModel.currentLoop.collectAsState()
    val runningLoopIds by viewModel.runningLoopConversationIds.collectAsState()
    val generationSnapshot by viewModel.generationSnapshot.collectAsState()
    val selectedConversationGenerationSnapshot by viewModel.selectedConversationGenerationSnapshot.collectAsState()
    val selectedModel by viewModel.currentActiveModel.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val developerOptionsEnabled by viewModel.settings.developerOptionsEnabled.collectAsState()
    val debugModelEnabled by viewModel.settings.debugModelEnabled.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val modelProviderNames by viewModel.settings.modelProviderNames.collectAsState()
    val chatEnabledModels = validChatModels(
        enabledModels,
        developerOptionsEnabled,
        debugModelEnabled,
    )
    val chatModelAliases = if (DebugProvider.MODEL_ID in chatEnabledModels) {
        modelAliases + (DebugProvider.MODEL_ID to DebugProvider.PROVIDER_NAME)
    } else {
        modelAliases
    }
    val thoughtExpandedStates = remember(currentConversationId) { mutableStateMapOf<String, Boolean>() }
    val isNewChatMode by viewModel.isNewChatMode.collectAsState()
    val newChatEntryId by viewModel.newChatEntryId.collectAsState()
    val isSwitching by viewModel.isSwitching.collectAsState()
    val regenerationTransition by viewModel.regenerationTransitions.request.collectAsState()
    val isTransitioningToNewChat by viewModel.isTransitioningToNewChat.collectAsState()
    val visualizeContextRollout by viewModel.settings.visualizeContextRollout.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val displayConversations = remember(conversations, customProviders) { conversations.orEmpty().map { it.forDisplay(customProviders) } }
    val displayMessagesState = remember(messagesState, customProviders) { derivedStateOf { messagesState.value.map { it.forDisplay(customProviders) } } }
    val webSearchApiKeys by viewModel.settings.webSearchApiKeys.collectAsState()
    val shellDevices by viewModel.settings.shellDevices.collectAsState()
    val amoledEnabled by viewModel.settings.amoledEnabled.collectAsState()
    val toolCallDisplayMode by viewModel.settings.toolCallDisplayMode.collectAsState()
    val thinkingSegmentDisplayMode by viewModel.settings.thinkingSegmentDisplayMode.collectAsState()
    val autoExpandActiveGroup by viewModel.settings.autoExpandActiveGroup.collectAsState()

    val parseInlineDollarMath by viewModel.settings.parseInlineDollarMath.collectAsState()
    val autoWrapCodeBlocks by viewModel.settings.autoWrapCodeBlocks.collectAsState()
    val conversationControls = effectiveConversationControls(
        viewModel = viewModel,
        isNewChatMode = isNewChatMode,
        currentConversationId = currentConversationId,
        selectedModel = selectedModel,
        customProviders = customProviders,
    )
    val contextProjectionKey = rememberContextProjectionInvalidationKey(
        viewModel,
        listOf(
            conversationControls.codeExecutionEnabled,
            conversationControls.googleSearchEnabled,
            conversationControls.webSearchEnabled,
            conversationControls.shellEnabled,
            shellDevices,
            currentConversation?.systemPromptId,
            conversationControls.lowContextModeEnabled,
        ),
    )
    val contextProjection by viewModel.conversationContextProjection.collectAsState()
    LaunchedEffect(currentConversationId, currentConversation?.selectedBranchesJson, selectedModel, conversationControls.contextWindow, allMessagesState.value, contextProjectionKey) {
        viewModel.requestConversationContext(currentConversationId, currentConversation?.selectedBranchesJson, selectedModel, conversationControls.contextWindow)
    }
    val contextProjectionReady = contextProjection.completed && !contextProjection.loading && !contextProjection.failed && contextProjection.conversationId == currentConversationId && contextProjection.selectedBranchesJson == currentConversation?.selectedBranchesJson
    val contextUsage = contextProjection.usage ?: com.newoether.agora.api.util.ContextWindowUsage(0, conversationControls.contextWindow, 0, false)
    val blurEffectsEnabled by viewModel.settings.blurEffectsEnabled.collectAsState()
    val stickToBottom by viewModel.settings.stickToBottom.collectAsState()
    val reduceMotion = motionPolicy.reduceMotion
    val hapticsEnabled by viewModel.settings.hapticsEnabled.collectAsState()
    val haptics = rememberAgoraHaptics(hapticsEnabled && topLevelPresentation == TopLevelPresentation.CHAT)
    val chatWindow = LocalWindowInfo.current
    val chatHapticActive = topLevelPresentation == TopLevelPresentation.CHAT &&
        chatWindow.isWindowFocused && !drawerState.shouldHandleBack
    // The three send paths (manual Send, queue drain, loop cycle) converge in the Controller at
    // notifySendAccepted, the single choke point for Direct + Queued send acceptances. Wiring the
    // haptics there gives each visible accepted send one confirm(), independent of its send path.
    // Covered acceptances are consumed silently instead of replaying feedback after an overlay exits.
    SendAcceptedHapticBindingEffect(viewModel, haptics, chatHapticActive)

    var isExpanded by remember { mutableStateOf(false) }
    // Composer-expand spacer collapse (44dp → 0). An Animatable driven from an effect replaces the
    // former hand-rolled clock, which wrote animation state DURING composition (Compose forbids
    // that — it makes the frame's output depend on when it happened to be composed) and ticked on
    // a fixed 16ms sleep that drifts against the real refresh rate.
    val composerSpacerAnimation = rememberComposerSpacerAnimation(
        isExpanded = isExpanded,
        allowSpatialTransitions = motionPolicy.allowSpatialTransitions,
        expandedHeightPx = with(density) { 44.dp.toPx() },
    )
    val isExpandAnimating = composerSpacerAnimation.isRunning
    val outerSpacerHeightPx = composerSpacerAnimation.outerHeightPx

    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeightDp = with(density) {
        windowSize.height.toDp().value.coerceAtLeast(1f)
    }
    var bottomBarHeightPx by rememberSaveable { mutableFloatStateOf(0f) }
    val bottomBarHeight = with(density) { bottomBarHeightPx.toDp() }
    // Measured by the interaction bar so the scroll-to-bottom button clears it as well.
    var interactionBarHeightPx by remember { mutableFloatStateOf(0f) }
    val interactionBarHeight = with(density) { interactionBarHeightPx.toDp() }
    var drawerProgress by remember { mutableFloatStateOf(0f) }
    // Bottom offset to clear the Settings button in the drawer.
    var settingsButtonTopDp by remember { mutableFloatStateOf(80f) }
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // When expanded, the Surface fills the screen and the model-selector capsule sits
    // at the very bottom. Snackbar must clear: nav bar + IME + Surface outer padding + Box
    // bottom padding + Row height/margin + a small gap.
    val bottomInset = maxOf(navBarBottom, imeBottom)
    SnackbarOffsetEffect(
        drawerProgress = drawerProgress,
        isExpanded = isExpanded,
        bottomBarHeight = bottomBarHeight,
        settingsButtonTopDp = settingsButtonTopDp,
        bottomInset = bottomInset,
        onOffsetChanged = onSnackbarOffsetChanged,
    )
    val imeBottomPx = with(density) { imeBottom.roundToPx() }
    val scrollCoordinator = rememberChatScrollCoordinator(
        currentConversationId,
        imeBottomPx,
    )
    scrollCoordinator.BindLayoutObservation(
        currentConversationId = currentConversationId,
        loadedMessagesConversationId = loadedMessagesConversationId,
        imeBottomPx = imeBottomPx,
        density = density,
    )
    val listState = scrollCoordinator.listState
    ScreenshotInitialScrollEffect(initialScrollToTop, currentConversationId, loadedMessagesConversationId, listState)
    val absoluteBottomScrollPhase = scrollCoordinator.absoluteBottomScrollPhase
    val isNearAbsoluteBottom = scrollCoordinator.isNearAbsoluteBottom
    val isWithinAbsoluteBottomAttachThreshold =
        scrollCoordinator.isWithinAbsoluteBottomAttachThreshold
    val imeBottomAnchorState = scrollCoordinator.imeBottomAnchorState
    val streamingTailController = scrollCoordinator.streamingTailController
    val messageLifecycleAppearanceRegistry = scrollCoordinator.messageLifecycleAppearanceRegistry
    val messageHeights = scrollCoordinator.messageHeights
    val viewportHeightPx = scrollCoordinator.viewportHeightPx
    val renderMessagesState = rememberScrollIsolatedMessages(
        conversationId = currentConversationId,
        upstream = messagesState,
        listState = listState,
        bypassScrollIsolation =
            streamingTailController.isAutoFollowing || absoluteBottomScrollPhase.isActive,
    )
    val messageHydration = rememberChatMessageHydrationBindings(viewModel, customProviders)
    val conversationInteraction = rememberConversationInteractionState(
        currentConversationId = currentConversationId,
        messages = displayMessagesState,
        listState = listState,
        searchMessages = messageHydration.searchMessages,
    )
    val conversationSearchActive = conversationInteraction.searchActive
    val conversationSearchQuery = conversationInteraction.searchQuery
    val conversationSearchMatchIndex = conversationInteraction.searchMatchIndex
    val shareSelectionActive = conversationInteraction.shareSelectionActive
    val selectedShareMessageIds = conversationInteraction.selectedShareMessageIds
    val selectableShareMessageIds = conversationInteraction.selectableShareMessageIds
    val shareSelectionBarSpace = if (shareSelectionActive) 68.dp else 0.dp
    val conversationSearchMatches = conversationInteraction.searchMatches
    val textFieldState = rememberSaveable(saver = androidx.compose.foundation.text.input.TextFieldState.Saver) { androidx.compose.foundation.text.input.TextFieldState() }
    val sandboxEnabled by viewModel.settings.sandboxEnabled.collectAsState()
    val composer = com.newoether.agora.ui.chat.bottombar.rememberChatComposerState(
        sandboxEnabled,
        viewModel.isSandboxFlavor,
    )
    val inputFocusRequester = remember { FocusRequester() }
    var showLaunchContent by remember { mutableStateOf(false) }
    ChatLaunchInteractionEffects(
        initialComposerFocusReady = initialComposerFocusReady,
        inputFocusRequester = inputFocusRequester,
        onShowLaunchContent = { showLaunchContent = true },
    )

    scrollCoordinator.BindTransitionEffects(
        currentConversationId = currentConversationId,
        currentConversation = currentConversation,
        loadedMessagesConversationId = loadedMessagesConversationId,
        messages = messagesState,
        density = density,
        motionPolicy = motionPolicy,
        bottomBarHeight = bottomBarHeight,
        shareSelectionBarSpace = shareSelectionBarSpace,
        imeBottomPx = imeBottomPx,
        viewModel = viewModel,
        haptics = haptics,
    )

    val composerOwnerId = if (isNewChatMode) com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID else currentConversationId ?: com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
    val composerSnapshot by rememberComposerDraftSnapshot(
        ownerId = composerOwnerId,
        controller = viewModel.conversationComposer,
        viewModel = viewModel,
        textFieldState = textFieldState,
    )

    val animatedScrollRequest by viewModel.scrollRequests.request.collectAsState()
    scrollCoordinator.BindRequestEffects(
        currentConversationId = currentConversationId,
        isNewChatMode = isNewChatMode,
        isLoading = isLoading,
        isStopping = isStopping,
        isSwitching = isSwitching,
        conversationSearchActive = conversationSearchActive,
        shareSelectionActive = shareSelectionActive,
        regenerationTransition = regenerationTransition,
        animatedScrollRequest = animatedScrollRequest,
        messages = messagesState,
        density = density,
        motionPolicy = motionPolicy,
        bottomBarHeight = bottomBarHeight,
        shareSelectionBarSpace = shareSelectionBarSpace,
        onRegenerationScrollFinished = viewModel.regenerationTransitions::acknowledgeScroll,
        onRegenerationTransitionFinished = viewModel.regenerationTransitions::complete,
        onAnimatedScrollFinished = viewModel.scrollRequests::complete,
    )

    ChatNavigationEffects(
        drawerState = drawerState,
        focusManager = focusManager,
        scope = scope,
        motionPolicy = motionPolicy,
        onNavigateBack = onNavigateBack,
        conversationInteraction = conversationInteraction,
        onCollapseComposer = { isExpanded = false },
    )

    AnsweringHapticEffect(
        generationSnapshot = selectedConversationGenerationSnapshot,
        topLevelPresentation = topLevelPresentation,
        hapticsEnabled = hapticsEnabled && chatHapticActive,
        haptics = haptics,
    )

    CompositionLocalProvider(LocalAgoraHaptics provides haptics) {
    ChatDrawerHost(
        state = drawerState,
        drawerEnabled = drawerEnabled,
        motionPolicy = motionPolicy,
        onDrawerProgress = { drawerProgress = it },
        drawerContent = { drawerWidth, onRequestClose ->
            ChatDrawerContent(
                viewModel = viewModel,
                drawerWidth = drawerWidth,
                scope = scope,
                inputFocusRequester = inputFocusRequester,
                onRequestClose = onRequestClose,
                onSettingsButtonTop = { settingsButtonTopDp = it },
                onOpenSettings = onOpenSettings,
                onOpenTasks = { onOpenTasks(null) },
                onOpenRemote = onOpenRemote,
                onRequestRename = dialogState::requestRename,
                onRequestDelete = { conversationId ->
                    if (!viewModel.isConversationDeleteLocked(conversationId)) {
                        dialogState.requestDelete(conversationId)
                    }
                },
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clearFocusOnTap()
                .onSizeChanged { scrollCoordinator.recordViewportHeight(it.height) }
        ) {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val (targetCa, targetQa) = if (!dark) {
                0.00f to 0.00f
            } else if (isNewChatMode) {
                0.20f to 0.10f
            } else {
                0.02f to 0.01f
            }
            val ca by animateFloatAsState(targetCa, tween(800))
            val qa by animateFloatAsState(targetQa, tween(800))
            val newChatMotion = newChatMotionPolicy(
                reduceMotion = reduceMotion,
                isNewChatMode = isNewChatMode,
                isLoading = isLoading,
                isSwitching = isSwitching,
                newChatEntryId = newChatEntryId,
            )
            if (!amoledEnabled) {
                AnimatedBlobBackground(
                    centerAlpha = ca,
                    quarterAlpha = qa,
                    blurRadius = 40f,
                    dark = dark,
                    blurEnabled = blurEffectsEnabled,
                    motionEnabled = newChatMotion.animateBackground,
                )
            }

            @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    ChatTopBar(
                        isNewChatMode = isNewChatMode,
                        conversations = displayConversations,
                        currentConversationId = currentConversationId,
                        currentConversationTitle = currentConversation?.title?.let {
                            replaceCustomProviderIdsForDisplay(it, customProviders)
                        },
                        totalTokens = contextUsage.estimatedTokenCount,
                        contextTokenBudget = contextUsage.tokenBudget,
                        searchActive = conversationSearchActive,
                        searchQuery = conversationSearchQuery,
                        searchMatchIndex = conversationSearchMatchIndex,
                        searchMatchCount = conversationSearchMatches.size,
                        conversationActionsEnabled =
                            !isNewChatMode && currentConversationId != null && !isLoading &&
                                !shareSelectionActive,
                        systemPromptEnabled = !conversationControls.lowContextModeEnabled,
                        onNavigateBack = onNavigateBack,
                        onOpenDrawer = {
                            if (drawerEnabled) {
                                scope.launch { drawerState.toggle(motionPolicy) }
                            }
                        },
                        onSearchQueryChange = { query ->
                            conversationInteraction.updateSearchQuery(query)
                        },
                        onSearchPrevious = {
                            if (conversationInteraction.previousSearchMatch()) {
                                haptics.selection()
                            }
                        },
                        onSearchNext = {
                            if (conversationInteraction.nextSearchMatch()) {
                                haptics.selection()
                            }
                        },
                        onSearchDismiss = {
                            conversationInteraction.dismissSearch()
                            focusManager.clearFocus()
                        },
                        onSearchClick = {
                            conversationInteraction.activateSearch()
                        },
                        onSystemPromptClick = dialogState::showPrompt,
                        onForkConversation = { pendingForkRequest = ForkConversationRequest(messageId = null) },
                        onShareConversation = {
                            conversationInteraction.dismissSearch()
                            focusManager.clearFocus()
                            conversationInteraction.activateShareSelection()
                        },
                        onNewChat = {
                            if (!isNewChatMode) {
                                isExpanded = false
                                viewModel.createNewChat()
                                inputFocusRequester.requestFocus()
                            }
                        },
                    )
                }
            ) { padding -> Box(modifier = Modifier.fillMaxSize()) {
                    val topBarH = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
                    val pivotY =
                        ((windowHeightDp + topBarH.value / 2f - bottomBarHeight.value) / 2f)
                            .coerceAtLeast(0f) / windowHeightDp
                    AnimatedContent(
                        targetState = Pair(isNewChatMode, showLaunchContent),
                        transitionSpec = {
                            chatMainContentTransition(motionPolicy, pivotY)
                        },
                        label = "MainContentTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { (targetNewChat, targetShowLaunch) ->
                        if (!targetNewChat) {
                            val streamingFollowAvailability = streamingTailAvailability(
                                generationActive = isLoading,
                                blocked =
                                    isStopping ||
                                        isSwitching ||
                                        conversationSearchActive ||
                                        shareSelectionActive ||
                                        !motionPolicy.allowProgrammaticScrollMotion,
                                programmaticHandoff =
                                    imeBottomAnchorState.active ||
                                        absoluteBottomScrollPhase.isActive ||
                                        animatedScrollRequest?.conversationId ==
                                            currentConversationId ||
                                        regenerationTransition?.conversationId ==
                                            currentConversationId,
                            )
                            Box(modifier = Modifier.fillMaxSize()) {
                            MessageList(
                                messages = StableMessageList(renderMessagesState.value),
                                authoritativeMessages = StableMessageList(displayMessagesState.value),
                                allMessages = StableMessageList(allMessagesState.value),
                                conversationId = currentConversationId,
                                modifier = Modifier.align(Alignment.Center)
                                    .fillMaxHeight().widthIn(max = 840.dp).fillMaxWidth()
                                    .gradientBlur(
                                    blurAtTopDp = if (blurEffectsEnabled) 8f else 0f,
                                    blurAtBottomDp = 0f,
                                    fadeHeightDp = 40f,
                                    bottomOverlayHeight = bottomBarHeight + with(density) { outerSpacerHeightPx.toDp() } + 12.dp,
                                ),
                                state = listState,
                                // Per-conversation generation gate: isLoading mirrors the OPEN
                                // conversation's slot only (ConversationGenerationState.onActive
                                // gates on current == id), so message actions freeze while THIS
                                // conversation generates — background conversations don't affect it.
                                isLoading = isLoading,
                                isCompacting = isCompacting,
                                compactPreview = viewModel.compactUi.compactPreview,
                                isStopping = isStopping,
                                isSwitching = isSwitching,
                                streamingMessage = generationSnapshot.streamingMessage?.forDisplay(customProviders),
                                streamingAutoFollowEnabled =
                                    streamingFollowAvailability.enabled && stickToBottom,
                                streamingAutoFollowPaused =
                                    streamingFollowAvailability.paused,
                                streamingTailWithinAttachThreshold =
                                    isWithinAbsoluteBottomAttachThreshold,
                                programmaticScrollActive =
                                    animatedScrollRequest?.conversationId ==
                                        currentConversationId,
                                streamingTailController = streamingTailController,
                                regenerationTransition = regenerationTransition,
                                onRegenerationFadeOutFinished =
                                    viewModel.regenerationTransitions::acknowledgeFade,
                                visualizeContextRollout = visualizeContextRollout && contextProjectionReady,
                                toolCallDisplayMode = toolCallDisplayMode,
                                thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
                                autoExpandActiveGroup = autoExpandActiveGroup,
                                parseInlineDollarMath = parseInlineDollarMath,
                                autoWrapCodeBlocks = autoWrapCodeBlocks,
                                contextRetainedMessageIds = contextProjection.retainedMessageIds.orEmpty(),
                                modelAliases = StableModelAliases(modelAliases, modelProviderNames),
                                customProviders = customProviders,
                                bottomBarHeight = bottomBarHeight + shareSelectionBarSpace,
                                viewportHeight = viewportHeightPx,
                                messageHeights = messageHeights,
                                observeMessage = messageHydration.observeMessage,
                                onMessageHydrated = scrollCoordinator::recordMessageHydrated,
                                lifecycleAppearanceRegistry = messageLifecycleAppearanceRegistry,
                                lifecycleEntranceTargetMessageId = animatedScrollRequest
                                    ?.takeIf { it.conversationId == currentConversationId }
                                    ?.targetMessageId,
                                onEditMessage = { id, text ->
                                    val accepted = viewModel.editMessage(id, text)
                                    if (accepted) haptics.confirm()
                                    accepted
                                },
                                onSwitchBranch = { parentId, currentMessageId, direction ->
                                    haptics.selection()
                                    viewModel.switchBranch(parentId, currentMessageId, direction)
                                },
                                onRegenerate = { id ->
                                    val accepted = viewModel.regenerate(id)
                                    if (accepted) haptics.confirm()
                                    accepted
                                },
                                onFork = { id -> pendingForkRequest = ForkConversationRequest(messageId = id) },
                                onShare = { id ->
                                    viewModel.shareGeneration(id)
                                },
                                onRecompact = { id ->
                                    viewModel.compactUi.startRecompact(id)
                                },
                                onDelete = { id, result ->
                                    viewModel.deleteMessage(id, result) > 0
                                },
                                onDeleteConversation = { expectedIds, result ->
                                    currentConversationId?.let { id ->
                                        viewModel.deleteConversation(id, expectedIds, result)
                                    } ?: false
                                },
                                searchQuery = if (conversationSearchActive) {
                                    conversationSearchQuery
                                } else {
                                    ""
                                },
                                activeSearchMatch = conversationSearchMatches
                                    .getOrNull(conversationSearchMatchIndex),
                                onSearchMatchDistance =
                                    conversationInteraction::recordSearchMatchDistance,
                                onSearchTurnsChanged = conversationInteraction::recordSearchTurns,
                                selectionMode = shareSelectionActive,
                                selectedMessageIds = selectedShareMessageIds,
                                onToggleMessageSelection = { messageId ->
                                    haptics.selection()
                                    conversationInteraction.toggleShareMessage(messageId)
                                },
                                onMediaClick = { urls, index ->
                                    onMediaClick(urls, index)
                                },
                                onFileContentClick = onFileContentClick?.let { open ->
                                    { name, content ->
                                        open(name, content)
                                    }
                                },
                                onPdfPagesClick = { pages, idx ->
                                    onPdfPagesClick?.invoke(pages, idx)
                                },
                                thoughtExpandedStates = thoughtExpandedStates,
                                contentPadding = PaddingValues(
                                    start = 8.dp,
                                    end = 8.dp,
                                    top = 140.dp,
                                    bottom = bottomBarHeight + shareSelectionBarSpace + 8.dp
                                )
                            )
                            }
                        } else if (targetShowLaunch) {
                            ChatWelcomeContent(
                                bottomBarHeight, windowHeightDp, topBarH, newChatEntryId, newChatMotion,
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize())
                        }
                    }

                    // Recreate for every plain value captured by this derived state.
                    val regenerationScrollActive =
                        regenerationTransition?.conversationId == currentConversationId &&
                            regenerationTransition?.scrollFinished == false
                    val showButton by rememberAbsoluteBottomButtonVisible(
                        conversationId = currentConversationId,
                        loadedMessagesConversationId = loadedMessagesConversationId,
                        isNewChatMode = isNewChatMode,
                        isSwitching = isSwitching,
                        shareSelectionActive = shareSelectionActive,
                        isNearAbsoluteBottom = isNearAbsoluteBottom,
                        absoluteBottomScrollPhase = absoluteBottomScrollPhase,
                        listState = listState,
                        streamingTailController = streamingTailController,
                        regenerationScrollActive = regenerationScrollActive,
                        imeBottomAnchorActive = imeBottomAnchorState.active,
                    )
                    ChatBottomScrollButton(showButton, bottomBarHeight + interactionBarHeight) {
                        scrollCoordinator.requestAbsoluteBottomScroll()
                    }

                    ChatSelectionOverlay(
                        shareSelectionActive, motionPolicy, bottomBarHeight,
                        selectableShareMessageIds, selectedShareMessageIds,
                        conversationInteraction, haptics,
                        onShareMessages = { viewModel.shareMessages(it) },
                    )

                    ChatSwitchingOverlay(isSwitching, isTransitioningToNewChat)
                }
            }

            // Pending questions and shell confirmations sit above the composer instead of in a
            // dialog, so the conversation stays readable while they wait for an answer.
            ChatUserInteractionBar(
                viewModel = viewModel,
                conversationId = currentConversationId,
                autoWrapCodeBlocks = autoWrapCodeBlocks,
                bottomBarHeight = bottomBarHeight,
                onHeightChanged = { interactionBarHeightPx = it },
            )

            com.newoether.agora.ui.chat.bottombar.ChatComposerSurface(
                modifier = Modifier.align(Alignment.BottomCenter),
                contentMaxWidth = 840.dp,
                isExpanded = isExpanded,
                outerSpacerHeightPx = outerSpacerHeightPx,
                onBarHeightChanged = { bottomBarHeightPx = it },
                backdrop = {
                    LoopStatusBackdrop(
                        loop = currentLoop,
                        isRunning = currentConversationId in runningLoopIds,
                        onStop = { viewModel.stopCurrentLoop() },
                    )
                },
            ) {
                                ChatBottomBar(
                        submissionController = viewModel.conversationComposerSubmission,
                        composerOwnerId = composerOwnerId,
                        composerController = viewModel.conversationComposer,
                        composerSnapshot = composerSnapshot,
                        onStopGeneration = {
                            haptics.interrupt()
                            viewModel.stopGeneration()
                        },
                        isLoading = isLoading,
                        isCompacting = isCompacting,
                        isSwitching = isSwitching,
                        enabledModels = chatEnabledModels,
                        selectedModel = selectedModel,
                        modelAliases = chatModelAliases,
                        modelProviderNames = modelProviderNames,
                        customProviders = customProviders,
                        codeExecutionEnabled = conversationControls.codeExecutionEnabled,
                        googleSearchEnabled = conversationControls.googleSearchEnabled,
                        thinkingEnabled = conversationControls.thinkingEnabled, thinkingLevel = conversationControls.thinkingLevel,
                        thinkingBudgetEnabled = conversationControls.thinkingBudgetEnabled, thinkingBudgetTokens = conversationControls.thinkingBudgetTokens,
                        openAiWebSearchAvailable = conversationControls.openAiWebSearchAvailable,
                        openAiWebSearchEnabled = conversationControls.openAiWebSearchEnabled,
                        onOpenAiWebSearchToggle = { enabled -> updateOpenAiNativeSearch(viewModel, conversationControls.settingsOwnerId, haptics, enabled) },
                        openAiServiceTierAvailable = conversationControls.openAiServiceTierState.available,
                        openAiServiceTierEnabled = conversationControls.openAiServiceTierState.enabled,
                        openAiServiceTier = conversationControls.openAiServiceTierState.tier,
                        onOpenAiServiceTierToggle = { enabled -> updateOpenAiConversationServiceTierEnabled(viewModel, conversationControls.settingsOwnerId, haptics, enabled) },
                        onOpenAiServiceTierChange = { tier -> updateOpenAiConversationServiceTier(viewModel, conversationControls.settingsOwnerId, haptics, tier) },
                        onCodeExecutionToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(conversationControls.settingsOwnerId) { it.copy(codeExecutionEnabled = enabled) } },
                        onGoogleSearchToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(conversationControls.settingsOwnerId) { it.copy(googleSearchEnabled = enabled) } },
                        onThinkingToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(conversationControls.settingsOwnerId) { it.copy(thinkingEnabled = enabled) } },
                        onThinkingLevelChange = { level -> viewModel.updateConversationSetting(conversationControls.settingsOwnerId) { it.copy(thinkingLevel = level) } },
                        onThinkingBudgetEnabledChange = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(conversationControls.settingsOwnerId) { it.copy(thinkingBudgetEnabled = enabled) } },
                        onThinkingBudgetTokensChange = { tokens -> viewModel.updateConversationSetting(conversationControls.settingsOwnerId) { it.copy(thinkingBudgetTokens = tokens) } },
                        webSearchEnabled = conversationControls.webSearchEnabled,
                        onWebSearchToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(conversationControls.settingsOwnerId) { it.copy(webSearchEnabled = enabled) } },
                        shellEnabled = conversationControls.shellEnabled,
                        onShellToggle = { enabled -> haptics.toggle(enabled); viewModel.updateConversationSetting(conversationControls.settingsOwnerId) { it.copy(shellEnabled = enabled) } },
                        showLowContextMode = conversationControls.showLowContextMode,
                        lowContextModeEnabled = conversationControls.lowContextModeEnabled,
                        onLowContextModeToggle = { enabled ->
                            haptics.toggle(enabled)
                            viewModel.updateConversationSetting(conversationControls.settingsOwnerId) {
                                it.copy(lowContextModeEnabled = enabled)
                            }
                        },
                        // The model row owns its selection tick. Repeating it here produced the
                        // previous double buzz for one physical tap.
                        onModelSelect = { viewModel.setActiveModel(it) },
                        onAllMediaClick = { urls, idx -> onMediaClick(urls, idx) },
                        onFileContentClick = { name, content -> viewModel.mediaPreview.showFile(name, content) },
                        modifier = Modifier,
                        textFieldState = textFieldState,
                        composerState = composer,
                        focusRequester = inputFocusRequester,
                        onInputFocusChanged = { focused ->
                            scrollCoordinator.setComposerInputFocused(focused)
                        },
                        isExpanded = isExpanded,
                        isExpandAnimating = isExpandAnimating,
                        onCollapse = { isExpanded = false },
                        onExpand = { isExpanded = true },
                        showWebSearch = conversationControls.webSearchAvailable,
                        showShell = shellDevices.isNotEmpty() && conversationControls.shellAvailable,
                        onPdfPagesClick = { pages, idx -> onPdfPagesClick?.invoke(pages, idx) },
                        onPdfPreviewSelect = { pages, idx -> onPdfPreviewSelect?.invoke(pages, idx) },
                        pdfViewerSelection = pdfViewerSelection,
                        onTogglePdfSelection = onTogglePdfSelection,
                        onInitPdfSelection = onInitPdfSelection,
                        fullScreenViewerUrls = fullScreenViewerUrls,
                        compactDefaultModel = compactModel,
                        compactDefaultPrompt = compactPrompt,
                        compactDefaultRetainCount = compactRetainCount,
                        contextEstimatedTokens = contextUsage.estimatedTokenCount,
                        contextTokenBudget = contextUsage.tokenBudget,
                        contextCompactThresholdPercent = compactThresholdPercent,
                        contextCompactEnabled = compactEnabled,
                        contextSystemPromptTokens = contextUsage.systemPromptTokens,
                        contextToolTokens = contextUsage.toolTokens,
                        canCompact = currentConversationId != null && !isLoading && !isSwitching && !isStopping,
                        onCompactClick = {
                            dialogState.showManualCompact()
                        },
                        onAdvancedClick = dialogState::showAdvanced,
                        queuedSends = queuedSends,
                        onRemoveQueuedSend = viewModel::removeQueuedSend, onSendQueuedNow = viewModel::sendQueuedNow,
                        isStopping = isStopping,
                    )
            }
            }
        }
        }

    ChatAppDialogHost(
        state = dialogState,
        viewModel = viewModel,
        haptics = haptics,
        compactModel = compactModel,
        selectedModel = selectedModel,
        compactPrompt = compactPrompt,
        compactRetainCount = compactRetainCount,
        enabledModels = chatEnabledModels,
        modelAliases = chatModelAliases, customProviders = customProviders,
        modelProviderNames = modelProviderNames,
        isCompacting = isCompacting,
    )

    ChatForkConfirmationHost(pendingForkRequest, viewModel) { pendingForkRequest = null }
}
