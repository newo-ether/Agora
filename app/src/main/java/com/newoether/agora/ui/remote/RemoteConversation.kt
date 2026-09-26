package com.newoether.agora.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.remote.*
import com.newoether.agora.ui.chat.*
import com.newoether.agora.ui.chat.bottombar.*
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.thinkingControlShortLabel
import com.newoether.agora.ui.common.openAiServiceTierShortLabel
import com.newoether.agora.ui.components.AnimatedBlobBackground
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.util.gradientBlur
import kotlinx.coroutines.flow.filterNotNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteConversation(
    state: RemoteState, vm: RemoteViewModel, settings: SettingsRepository, active: Boolean, onBack: () -> Unit,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onMessage: (String, String?, (() -> Unit)?) -> Unit,
) {
    val owner = state.owner ?: return
    val session = state.session ?: return
    val connectionStatus = when (state.devices.firstOrNull { it.id == state.deviceId }?.status) {
        RemoteDeviceStatus.CONNECTED -> com.newoether.agora.mcp.McpConnectionStatus.CONNECTED
        RemoteDeviceStatus.CONNECTING -> com.newoether.agora.mcp.McpConnectionStatus.CONNECTING
        RemoteDeviceStatus.ERROR -> com.newoether.agora.mcp.McpConnectionStatus.ERROR
        else -> com.newoether.agora.mcp.McpConnectionStatus.IDLE
    }
    val density = LocalDensity.current
    val motion = LocalAgoraMotionPolicy.current
    val haptics = LocalAgoraHaptics.current
    val chatWindow = androidx.compose.ui.platform.LocalWindowInfo.current
    val blur by settings.blurEffectsEnabled.collectAsState(initial = false)
    val amoled by settings.amoledEnabled.collectAsState(initial = false)
    val inlineMath by settings.parseInlineDollarMath.collectAsState(initial = false)
    val stickToBottom by settings.stickToBottom.collectAsState(initial = true)
    val autoWrapCodeBlocks by settings.autoWrapCodeBlocks.collectAsState(initial = true)
    val toolCallDisplayMode by settings.toolCallDisplayMode.collectAsState()
    val thinkingSegmentDisplayMode by settings.thinkingSegmentDisplayMode.collectAsState()
    val autoExpandActiveGroup by settings.autoExpandActiveGroup.collectAsState()
    var expanded by remember(owner) { mutableStateOf(false) }
    BackHandler(active && expanded) { expanded = false }
    val spacer = rememberComposerSpacerAnimation(expanded, motion.allowSpatialTransitions, with(density) { 44.dp.toPx() })
    val field = remember(owner) { TextFieldState(state.drafts[owner].orEmpty()) }
    val focus = remember { FocusRequester() }
    val attempt = state.attempts[owner]
    val attachments = state.attachments[owner].orEmpty()
    val running = state.runtime?.isRunning == true
    val stopping = state.isStopping
    val ready = state.isDraft || state.runtime?.status in setOf("idle", "active", "ready")
    val newChatEntry = remember(owner) { state.composerFocusOwner == owner }
    ChatLaunchInteractionEffects(
        initialComposerFocusReady = active && ready && state.composerFocusOwner == owner,
        inputFocusRequester = focus,
        onShowLaunchContent = {},
        onInitialFocusRequested = { vm.completeComposerFocus(owner) },
    )
    val messages = remember(state.messageGroups) { state.messageGroups.map { it.stub } }
    val tail = messages.lastOrNull()?.takeIf {
        it.status in setOf(MessageStatus.SENDING, MessageStatus.THINKING, MessageStatus.TOOL_CALLING)
    }
    val generationVisible = tail != null
    var activeMenu by remember(owner) { mutableStateOf<String?>(null) }
    var lastModelDismissTime by remember(owner) { mutableLongStateOf(0L) }
    var lastContextDismissTime by remember(owner) { mutableLongStateOf(0L) }
    var lastToolsDismissTime by remember(owner) { mutableLongStateOf(0L) }
    var showThinkingSheet by remember(owner) { mutableStateOf(false) }
    var showOpenAiServiceTierSheet by remember(owner) { mutableStateOf(false) }
    val effortChoices = state.settingsModel?.reasoningEfforts.orEmpty()
    val tierChoices = state.settingsModel?.serviceTiers.orEmpty()
    val ultraFastLabel = stringResource(R.string.openai_service_tier_ultrafast)
    val tierLabels = tierChoices.associate { it.id to if (it.id == "ultrafast") ultraFastLabel else it.name }
    val settingsEnabled = active && state.canEditSettings
    val thinkingEnabled = state.selectedEffort != null && state.selectedEffort != "none"
    val thinkingLevel = state.selectedEffort.orEmpty()
    val openAiServiceTierEnabled = state.selectedServiceTier != null
    val openAiServiceTier = state.selectedServiceTier.orEmpty()
    val serviceTierKnown = state.isDraft || state.runtime?.serviceTierKnown == true || state.runtime?.serviceTier != null
    LaunchedEffect(active) {
        if (!active) {
            activeMenu = null
            showThinkingSheet = false
            showOpenAiServiceTierSheet = false
        }
    }
    LaunchedEffect(owner, field) { snapshotFlow { field.text.toString() }.collect { vm.editDraft(owner, it) } }
    var clearedAttempt by remember(owner) {
        mutableStateOf(attempt?.takeIf { it.delivery == RemoteDelivery.DELIVERED }?.clientId)
    }
    var shownBusyAttempt by remember(owner) { mutableStateOf(clearedAttempt) }
    val acceptedPendingClear = attempt?.delivery == RemoteDelivery.DELIVERED && clearedAttempt != attempt.clientId
    val submitting = attempt?.delivery in setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED) || acceptedPendingClear
    LaunchedEffect(attempt, shownBusyAttempt) {
        if (attempt?.delivery == RemoteDelivery.DELIVERED && clearedAttempt != attempt.clientId && shownBusyAttempt == attempt.clientId) {
            clearedAttempt = attempt.clientId
            if (state.drafts[owner].isNullOrEmpty() && field.text.toString() == attempt.text) {
                field.edit { replace(0, length, "") }
            }
            expanded = false
            if (chatWindow.isWindowFocused && activeMenu == null &&
                !showThinkingSheet && !showOpenAiServiceTierSheet) haptics.confirm()
        }
    }
    val observe = remember(owner) { { id: String -> vm.observeMessage(owner, id) } }
    val loadToolImage: suspend (String, String) -> com.newoether.agora.model.ToolImageAttachment = remember(owner, vm) {
        { id, revision -> vm.loadToolImage(owner, id, revision) }
    }
    val initialMessage = remember(owner) { { id: String ->
        if (vm.state.value.owner == owner) vm.cachedMessage(owner, id) else null
    } }
    val streaming = tail?.let { message ->
        key(owner, message.id) {
            // A suspended observer does not delete the body already shown during navigation.
            val flow = remember(owner, message.id) { vm.observeMessage(owner, message.id).filterNotNull() }
            val payload by flow.collectAsState(initial = vm.cachedMessage(owner, message.id))
            payload
        }
    }
    val messageState = rememberUpdatedState(messages)
    val ime = WindowInsets.ime.getBottom(density)
    val scroll = rememberChatScrollCoordinator(owner, ime)
    val historyOverscroll = rememberRemoteHistoryOverscroll(scroll.listState)
    val focusManager = LocalFocusManager.current
    val searchMessages: suspend (String, List<String>) -> List<com.newoether.agora.model.ChatMessage> =
        remember(owner, state.hydrationRevision) { { _, ids -> vm.searchMessages(owner, ids) } }
    val searchAllMessages = remember(owner, vm, state.hydrationEnabled) {
        { query: String -> vm.searchHistory(query) }
    }
    val interaction = rememberConversationInteractionState(owner, messageState, scroll.listState, searchMessages,
        searchAllMessages = searchAllMessages)
    val searchMatch = interaction.searchMatches.getOrNull(interaction.searchMatchIndex)
    BackHandler(active && interaction.searchActive) {
        interaction.dismissSearch()
        focusManager.clearFocus()
    }
    val animatedScrollRequest by vm.animatedScrollRequest.collectAsState()
    var barHeightPx by remember { mutableFloatStateOf(0f) }
    val barHeight = with(density) { barHeightPx.toDp() }
    SnackbarOffsetEffect(drawerProgress = 0f, isExpanded = expanded, bottomBarHeight = barHeight,
        settingsButtonTopDp = 0f, bottomInset = maxOf(
            WindowInsets.ime.asPaddingValues().calculateBottomPadding(),
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        onOffsetChanged = { if (active) onSnackbarOffsetChanged(it) })
    var initiallyPositioned by remember(owner) { mutableStateOf(state.isDraft) }
    val switching = !initiallyPositioned
    scroll.BindLayoutObservation(owner, owner, ime, density)
    scroll.BindImeEffects(owner, messageState, density, barHeight, 0.dp, ime)
    scroll.BindRequestEffects(owner, false, generationVisible, false, switching, interaction.searchActive, false, null, animatedScrollRequest,
        messageState, density, motion, barHeight, 0.dp, onAnimatedScrollFinished = vm::completeAnimatedScroll)
    val renderMessages = rememberScrollIsolatedMessages(owner, messageState, scroll.listState,
        bypassScrollIsolation = scroll.absoluteBottomScrollPhase.isActive || scroll.streamingTailController.isAutoFollowing)
    var initialLeadingSpace by remember(owner) { mutableStateOf<Int?>(null) }
    val leadingSpace = initialLeadingSpace ?: messageListPageLeadingSpacing(renderMessages.value.firstOrNull())
    SideEffect { if (initialLeadingSpace == null && renderMessages.value.isNotEmpty()) initialLeadingSpace = leadingSpace }
    LaunchedEffect(owner, state.hydrationEnabled) {
        if (state.hydrationEnabled && !initiallyPositioned) {
            scroll.settleOpenedConversation(messageState)
            initiallyPositioned = true
        }
    }
    val follow = streamingTailAvailability(
        generationActive = generationVisible,
        blocked = switching || interaction.searchActive || !motion.allowProgrammaticScrollMotion,
        programmaticHandoff = scroll.imeBottomAnchorState.active ||
            scroll.absoluteBottomScrollPhase.isActive || animatedScrollRequest?.conversationId == owner,
    )
    val historyStartId = messages.firstOrNull()?.id
    val atHistoryBoundary by remember(scroll.listState, historyStartId) {
        derivedStateOf {
            historyStartId != null && !scroll.listState.canScrollBackward &&
                scroll.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.key == historyStartId
        }
    }
    LaunchedEffect(owner, active, switching, interaction.searchActive, state.historyCursor, state.loadingMore, state.error, historyStartId) {
        if (!active || switching || interaction.searchActive || state.historyCursor == null ||
            state.loadingMore || state.error) return@LaunchedEffect
        // Topology passes through scroll isolation before the list measures it. An old
        // layout is not the boundary of the newly admitted page, even while still at index 0.
        snapshotFlow { atHistoryBoundary }.collect { atTop ->
            if (atTop) vm.loadMore()
        }
    }
    val historyProgress = remember(owner) { androidx.compose.animation.core.MutableTransitionState(false) }
    SideEffect {
        historyProgress.targetState = active && !switching && !interaction.searchActive &&
            state.loadingMore && atHistoryBoundary
    }
    val unknownDeliveryText = stringResource(R.string.remote_unknown)
    val checkedDeliveryText = stringResource(R.string.remote_check)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clearFocusOnTap()
        .onSizeChanged { scroll.recordViewportHeight(it.height) }) {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        if (!amoled) AnimatedBlobBackground(centerAlpha = if (dark) 0.02f else 0f,
            quarterAlpha = if (dark) 0.01f else 0f, blurRadius = 40f, dark = dark,
            blurEnabled = blur, motionEnabled = false)
        // Insets are declared explicitly via contentWindowInsets above; the empty
        // content padding is intentional, so the Material3 usage lint does not apply.
        @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
        Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            ChatTopBar(
                isNewChatMode = false, conversations = emptyList(),
                currentConversationId = session.id, currentConversationTitle = session.displayTitle(stringResource(R.string.new_chat)),
                totalTokens = state.runtime?.contextTokens ?: 0,
                contextTokenBudget = state.runtime?.contextWindow ?: 0,
                contextAvailable = state.runtime?.contextTokens != null && state.runtime?.contextWindow != null,
                subtitle = stringResource(when (connectionStatus) {
                    com.newoether.agora.mcp.McpConnectionStatus.CONNECTED -> R.string.remote_online
                    com.newoether.agora.mcp.McpConnectionStatus.CONNECTING -> R.string.remote_connecting
                    else -> R.string.remote_offline
                }),
                subtitleLeading = { com.newoether.agora.ui.settings.McpStatusDot(connectionStatus) },
                searchActive = interaction.searchActive, searchQuery = interaction.searchQuery,
                searchMatchIndex = interaction.searchMatchIndex, searchMatchCount = interaction.searchMatches.size,
                onSearchQueryChange = interaction::updateSearchQuery,
                onSearchPrevious = { if (interaction.previousSearchMatch()) haptics.selection() },
                onSearchNext = { if (interaction.nextSearchMatch()) haptics.selection() },
                onSearchDismiss = { interaction.dismissSearch(); focusManager.clearFocus() },
                onNavigateBack = onBack, onOpenDrawer = onBack, onSystemPromptClick = {}, onNewChat = vm::newSession,
                newChatEnabled = active && !state.controlling,
                moreMenuContent = { dismiss ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.conversation_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        enabled = active && !switching,
                        onClick = { dismiss(); interaction.activateSearch() },
                    )
                },
            )
        }) { _ ->
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(com.newoether.agora.ui.chat.message.LocalToolImageLoader provides loadToolImage) {
                MessageList(messages = StableMessageList(renderMessages.value), allMessages = StableMessageList(messages),
                    authoritativeMessages = StableMessageList(messages), conversationId = owner,
                    state = scroll.listState, overscrollEffect = historyOverscroll, onMediaClick = onMediaClick, messageActionsEnabled = false, readOnlyActions = true, parseInlineDollarMath = inlineMath,
                    autoWrapCodeBlocks = autoWrapCodeBlocks,
                    isLoading = generationVisible, isSwitching = switching, streamingMessage = streaming,
                    searchQuery = if (interaction.searchActive) interaction.searchQuery else "",
                    activeSearchMatch = searchMatch,
                    searchScrollRequestKey = interaction.searchScrollRequestKey,
                    onSearchMatchDistance = interaction::recordSearchMatchDistance,
                    onSearchTurnsChanged = interaction::recordSearchTurns,
                    streamingAutoFollowEnabled = follow.enabled && stickToBottom,
                    streamingAutoFollowPaused = follow.paused,
                    streamingTailWithinAttachThreshold = scroll.isWithinAbsoluteBottomAttachThreshold,
                    streamingTailController = scroll.streamingTailController,
                    toolCallDisplayMode = toolCallDisplayMode, thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
                    autoExpandActiveGroup = autoExpandActiveGroup,
                    modifier = Modifier.fillMaxSize().gradientBlur(blurAtTopDp = if (blur) 8f else 0f,
                        blurAtBottomDp = 0f, fadeHeightDp = 40f, bottomOverlayHeight = barHeight + with(density) { spacer.outerHeightPx.toDp() } + 12.dp),
                    bottomBarHeight = barHeight, viewportHeight = scroll.viewportHeightPx,
                    messageHeights = scroll.messageHeights, observeMessage = observe, initialMessage = initialMessage,
                    programmaticScrollActive = animatedScrollRequest?.conversationId == owner,
                    onMessageHydrated = scroll::recordMessageHydrated,
                    lifecycleAppearanceRegistry = scroll.messageLifecycleAppearanceRegistry,
                    lifecycleEntranceTargetMessageId = animatedScrollRequest?.takeIf { it.conversationId == owner }?.targetMessageId,
                    leadingContentLayer = {
                        Box(
                            modifier = Modifier.matchParentSize().offset(y = (-30).dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            AnimatedVisibility(
                                visibleState = historyProgress,
                                enter = fadeIn(tween(300)),
                                exit = fadeOut(tween(300)),
                            ) {
                                MotionAwareCircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 140.dp + leadingSpace.dp, bottom = barHeight + 8.dp))
                }
                ChatBottomScrollButton(
                    shouldShowAbsoluteBottomButton(
                        isNewChatMode = newChatEntry && messages.isEmpty(),
                        isSwitching = switching,
                        conversationContentReady = initiallyPositioned,
                        shareSelectionActive = false,
                        hasItems = scroll.listState.layoutInfo.totalItemsCount > 1,
                        canScrollForward = scroll.listState.canScrollForward,
                        isNearBottom = scroll.isNearAbsoluteBottom,
                        isStreamingAutoFollowing = scroll.streamingTailController.isAutoFollowing,
                        scrollPhase = scroll.absoluteBottomScrollPhase,
                        competingProgrammaticScrollActive = scroll.imeBottomAnchorState.active,
                    ),
                    barHeight,
                ) {
                    scroll.requestAbsoluteBottomScroll()
                }

                AnimatedVisibility(
                    visible = switching && !newChatEntry && !state.error,
                    enter = fadeIn(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(200))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        MotionAwareCircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        ChatComposerSurface(expanded, { barHeightPx = it }, Modifier.align(Alignment.BottomCenter), spacer.outerHeightPx) {
            ChatComposerLayout(field, focus, scroll::setComposerInputFocused, expanded, spacer.isRunning,
                onExpand = { expanded = true }, onCollapse = { expanded = false },
                statusContent = {
                    ComposerStatusColumn(state.queued, { it.id }) { QueuedMessageRow(text = it.text) }
                },
                attachmentContent = {
                    if (attachments.isNotEmpty()) AttachmentPreviewRow(
                        attachments = attachments, editable = active && !submitting,
                        onRemove = { vm.removeAttachment(owner, it) }, onRetry = { vm.retryAttachment(owner, it) },
                        onAllMediaClick = onMediaClick, onFileContentClick = null, onPdfPagesClick = null,
                    )
                },
                controls = {
                    ComposerControlGroup {
                        RemoteAttachmentPicker(owner, active && !submitting, vm)
                        ComposerModelSelector(
                            displayText = (state.models.firstOrNull { it.id == state.selectedModel }?.name
                                ?: state.selectedModel)?.replace('-', ' ') ?: stringResource(
                                    if (state.modelsLoading || state.loading) R.string.loading_label else R.string.remote_model_unavailable),
                            isModelValid = state.selectedModel != null, expanded = activeMenu == "model",
                            enabled = active && ready && !submitting && !stopping && !state.controlling && state.models.isNotEmpty(),
                            onClick = {
                                val now = System.currentTimeMillis()
                                if (activeMenu == "model") activeMenu = null
                                else if (now - lastModelDismissTime > 200) activeMenu = "model"
                            },
                            onDismissRequest = {
                                if (activeMenu == "model") {
                                    activeMenu = null
                                    lastModelDismissTime = System.currentTimeMillis()
                                }
                            },
                            menuContent = {
                                val sortedModels = remember(state.models) { state.models.sortedBy { it.id.lowercase() } }
                                sortedModels.forEach { model ->
                                    ComposerModelMenuItem(
                                        displayText = model.name.replace('-', ' '),
                                        selected = model.id == state.selectedModel,
                                        onClick = {
                                            haptics.selection()
                                            vm.setModel(model.id)
                                            activeMenu = null
                                            lastModelDismissTime = 0L
                                        },
                                    )
                                }
                            },
                        )
                        ComposerContextIndicator(
                            estimatedTokens = state.runtime?.contextTokens, tokenBudget = state.runtime?.contextWindow,
                            expanded = activeMenu == "context",
                            onClick = {
                                val now = System.currentTimeMillis()
                                if (activeMenu == "context") activeMenu = null
                                else if (now - lastContextDismissTime > 200) activeMenu = "context"
                            },
                            onDismissRequest = {
                                if (activeMenu == "context") {
                                    activeMenu = null
                                    lastContextDismissTime = System.currentTimeMillis()
                                }
                            },
                        )
                        ExposedDropdownMenuBox(
                            expanded = activeMenu == "tools",
                            onExpandedChange = { }
                        ) {
                            IconButton(
                                onClick = {
                                    val now = System.currentTimeMillis()
                                    if (activeMenu == "tools") {
                                        activeMenu = null
                                    } else if (now - lastToolsDismissTime > 200) {
                                        activeMenu = "tools"
                                    }
                                },
                                enabled = active,
                                modifier = Modifier.size(32.dp).menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = active)
                            ) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.tools), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            ExposedDropdownMenu(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                expanded = activeMenu == "tools",
                                onDismissRequest = {
                                    if (activeMenu == "tools") {
                                        activeMenu = null
                                        lastToolsDismissTime = System.currentTimeMillis()
                                    }
                                },
                                matchTextFieldWidth = false,
                                shape = CHAT_DROPDOWN_MENU_SHAPE,
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(stringResource(R.string.thinking))
                                                Text(
                                                    text = if (state.selectedEffort == null) "" else thinkingControlShortLabel(
                                                        thinkingEnabled,
                                                        thinkingLevel,
                                                        normalizeLevel = false,
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    onClick = { activeMenu = null; showThinkingSheet = true },
                                    enabled = effortChoices.isNotEmpty() && state.selectedEffort != null,
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Speed,
                                                contentDescription = null,
                                                modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp),
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(stringResource(R.string.openai_service_tier_title))
                                                Text(
                                                    text = if (!serviceTierKnown) "" else openAiServiceTierShortLabel(
                                                        openAiServiceTierEnabled,
                                                        openAiServiceTier,
                                                        nativeLabel = tierLabels[openAiServiceTier]
                                                            ?: openAiServiceTier,
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                    enabled = state.settingsModel?.serviceTiers != null && serviceTierKnown,
                                    onClick = { activeMenu = null; showOpenAiServiceTierSheet = true },
                                )
                            }
                        }
                    }
                    val showStop = running && !stopping && field.text.isBlank() && attachments.isEmpty()
                    ComposerSendButton(isActionable = active && !stopping && !state.controlling && !submitting &&
                        (if (showStop) state.runtime?.activeTurnId != null else field.text.isNotBlank() || attachments.isNotEmpty()),
                        isBusy = submitting || stopping, showStop = showStop,
                        onBusyShown = { shownBusyAttempt = attempt?.clientId }) {
                        if (showStop) vm.stop()
                        else if (attempt?.delivery == RemoteDelivery.UNKNOWN) onMessage(unknownDeliveryText, checkedDeliveryText) {
                            vm.acknowledgeUnknown(owner)
                        }
                        else { vm.editDraft(owner, field.text.toString()); vm.send() }
                    }
                })
        }
    }
    if (showThinkingSheet) {
        com.newoether.agora.ui.motion.MotionAwareModalBottomSheet(
            onDismissRequest = { showThinkingSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            com.newoether.agora.ui.components.DialogWindowEdgeToEdge()
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp)) {
                com.newoether.agora.ui.common.ThinkingControlPanel(
                    enabled = thinkingEnabled, level = thinkingLevel,
                    budgetEnabled = false, budgetTokens = 4096,
                    onEnabledChange = {}, onLevelChange = vm::setThinkingLevel,
                    onBudgetEnabledChange = {}, onBudgetTokensChange = {},
                    animateSections = true,
                    availableEfforts = effortChoices, controlsEnabled = settingsEnabled,
                    showHeader = false, showEnabledToggle = false, showBudgetControls = false,
                    settingsRevision = state.settingsRevision,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (showOpenAiServiceTierSheet) {
        com.newoether.agora.ui.motion.MotionAwareModalBottomSheet(
            onDismissRequest = { showOpenAiServiceTierSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            com.newoether.agora.ui.components.DialogWindowEdgeToEdge()
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp)) {
                com.newoether.agora.ui.common.OpenAiServiceTierControlPanel(
                    enabled = openAiServiceTierEnabled, tier = openAiServiceTier,
                    onEnabledChange = {}, onTierChange = { vm.setServiceTier(it.takeIf(String::isNotEmpty)) },
                    availableTiers = listOf("") + tierChoices.filterNot { it.id == "default" }.map { it.id },
                    tierLabels = tierLabels +
                        ("" to stringResource(R.string.openai_service_tier_default)),
                    controlsEnabled = settingsEnabled, showHeader = false, showEnabledToggle = false,
                    settingsRevision = state.settingsRevision,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
