package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.util.gradientBlur
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.chat.bottombar.ChatBottomBar
import com.newoether.agora.ui.chat.message.hasActiveAnswerSegment
import com.newoether.agora.ui.components.AnimatedBlobBackground
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.components.TypewriterText
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.rememberAgoraHaptics
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.model.StableModelAliases
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private val SCROLL_EASING = CubicBezierEasing(0.3f, 0.0f, 0.0f, 1.0f)

// isVisibleAnswerSegment() / hasActiveAnswerSegment() are shared (internal) from
// MessageItemSegments.kt.

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatApp(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onFileContentClick: ((String, String) -> Unit)? = null,
    onPdfPagesClick: ((List<String>, Int) -> Unit)? = null,
    onPdfPreviewSelect: ((List<String>, Int) -> Unit)? = null,
    pdfViewerSelection: Set<Int> = emptySet(),
    onTogglePdfSelection: ((Int) -> Unit)? = null,
    onInitPdfSelection: ((Set<Int>) -> Unit)? = null,
    fullScreenViewerUrls: List<String>? = null,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current

    val drawerState = rememberDrawerState(
        initialValue = DrawerValue.Closed,
        confirmStateChange = { newValue ->
            if (newValue != DrawerValue.Closed) {
                focusManager.clearFocus()
            }
            true
        }
    )

    val conversations by viewModel.conversations.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val allMessages by viewModel.allMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val generatingInConversationId by viewModel.generatingInConversationId.collectAsState()
    val selectedModel by viewModel.currentActiveModel.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val thoughtExpandedStates = remember(currentConversationId) { mutableStateMapOf<String, Boolean>() }
    val isNewChatMode by viewModel.isNewChatMode.collectAsState()
    val isSwitching by viewModel.isSwitching.collectAsState()
    val isTransitioningToNewChat by viewModel.isTransitioningToNewChat.collectAsState()
    val totalTokens by viewModel.totalTokens.collectAsState()
    val visualizeContextRollout by viewModel.settings.visualizeContextRollout.collectAsState()
    val maxContextWindow by viewModel.settings.maxContextWindow.collectAsState()
    val globalCodeExecution by viewModel.settings.codeExecutionEnabled.collectAsState()
    val globalGoogleSearch by viewModel.settings.googleSearchEnabled.collectAsState()
    val globalThinkingEnabled by viewModel.settings.thinkingEnabled.collectAsState()
    val globalThinkingLevel by viewModel.settings.thinkingLevel.collectAsState()
    val globalThinkingBudgetEnabled by viewModel.settings.thinkingBudgetEnabled.collectAsState()
    val globalThinkingBudgetTokens by viewModel.settings.thinkingBudgetTokens.collectAsState()
    val globalWebSearch by viewModel.settings.webSearchEnabled.collectAsState()
    val webSearchApiKeys by viewModel.settings.webSearchApiKeys.collectAsState()
    val globalShell by viewModel.settings.shellEnabled.collectAsState()
    val shellDevices by viewModel.settings.shellDevices.collectAsState()
    val toolCallDisplayMode by viewModel.settings.toolCallDisplayMode.collectAsState()
    val conversationSettings by viewModel.settings.conversationSettings.collectAsState()
    val pendingSettings by viewModel.pendingConversationSettings.collectAsState()
    // Resolved per-conversation values: override → global default
    val convId = currentConversationId
    val convOverride = if (convId != null) conversationSettings[convId] else pendingSettings
    val codeExecutionEnabled = convOverride?.codeExecutionEnabled ?: globalCodeExecution
    val googleSearchEnabled = convOverride?.googleSearchEnabled ?: globalGoogleSearch
    val thinkingEnabled = convOverride?.thinkingEnabled ?: globalThinkingEnabled
    val thinkingLevel = convOverride?.thinkingLevel ?: globalThinkingLevel
    val thinkingBudgetEnabled = convOverride?.thinkingBudgetEnabled ?: globalThinkingBudgetEnabled
    val thinkingBudgetTokens = convOverride?.thinkingBudgetTokens ?: globalThinkingBudgetTokens
    // Web Search and Shell: global switch OFF → always false, regardless of override
    val webSearchEnabled = globalWebSearch && (convOverride?.webSearchEnabled ?: true)
    val shellEnabled = globalShell && (convOverride?.shellEnabled ?: true)
    val contextWindow = convOverride?.contextWindow ?: maxContextWindow
    val blurEffectsEnabled by viewModel.settings.blurEffectsEnabled.collectAsState()
    val hapticsEnabled by viewModel.settings.hapticsEnabled.collectAsState()
    val haptics = rememberAgoraHaptics(hapticsEnabled)


    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var conversationToRename by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf<String?>(null) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showAdvancedDialog by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var outerSpacerStartNanos by remember { mutableLongStateOf(0L) }
    var outerSpacerTickNanos by remember { mutableLongStateOf(0L) }
    val spacerDurationMs = 400f
    val spacerEasing = remember { CubicBezierEasing(0.15f, 0.5f, 0.25f, 1.0f) }

    // Start timing synchronously on the first expand frame; never reset
    if (isExpanded && outerSpacerStartNanos == 0L) {
        outerSpacerStartNanos = System.nanoTime()
    }
    if (!isExpanded) {
        outerSpacerStartNanos = 0L
        outerSpacerTickNanos = 0L
    }

    val spacerElapsedMs = if (outerSpacerStartNanos > 0L) {
        val tick = if (outerSpacerTickNanos > 0L) outerSpacerTickNanos else outerSpacerStartNanos
        ((tick - outerSpacerStartNanos) / 1_000_000f).coerceIn(0f, spacerDurationMs)
    } else 0f

    val isExpandAnimating = outerSpacerStartNanos > 0L && spacerElapsedMs < spacerDurationMs

    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            while (true) {
                outerSpacerTickNanos = System.nanoTime()
                if ((outerSpacerTickNanos - outerSpacerStartNanos) / 1_000_000f >= spacerDurationMs) break
                delay(16L)
            }
        }
    }

    val outerSpacerHeightPx: Float = if (outerSpacerStartNanos > 0L) {
        val easedFraction = spacerEasing.transform(spacerElapsedMs / spacerDurationMs)
        with(density) { 44.dp.toPx() } * (1f - easedFraction)
    } else 0f

    val configuration = LocalConfiguration.current
    val drawerWidth = configuration.screenWidthDp.dp * 0.8f
    var bottomBarHeightPx by rememberSaveable { mutableFloatStateOf(0f) }
    val bottomBarHeight = with(density) { bottomBarHeightPx.toDp() }
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    var drawerProgress by remember { mutableFloatStateOf(0f) }
    // Bottom offset to clear the Settings button in the drawer.
    var settingsButtonTopDp by remember { mutableFloatStateOf(80f) }
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // When expanded, the Surface fills the screen and the model-selector capsule sits
    // at the very bottom. Snackbar must clear: nav bar + IME + Surface outer padding + Box
    // bottom padding + Row height/margin + a small gap.
    val bottomInset = maxOf(navBarBottom, imeBottom)
    val expandedCapsuleOffset = bottomInset + 74.dp
    val targetSnackbarOffset = if (drawerProgress <= 0.5f) {
        if (isExpanded) expandedCapsuleOffset else (bottomBarHeight - 4.dp).coerceAtLeast(0.dp)
    } else {
        val t = ((drawerProgress - 0.5f) * 2f).coerceIn(0f, 1f)
        (bottomBarHeight.value + (settingsButtonTopDp - bottomBarHeight.value) * t).dp
    }
    LaunchedEffect(targetSnackbarOffset) { onSnackbarOffsetChanged(targetSnackbarOffset) }
    val listState = rememberLazyListState()
    val textFieldState = rememberSaveable(saver = androidx.compose.foundation.text.input.TextFieldState.Saver) { androidx.compose.foundation.text.input.TextFieldState() }
    val inputFocusRequester = remember { FocusRequester() }

    val messageHeights = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    var showLaunchContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50)
        showLaunchContent = true
        inputFocusRequester.requestFocus()
    }


    suspend fun scrollToLastUserMessage(animate: Boolean = true, targetMessageId: String? = null, easing: Easing = FastOutSlowInEasing) {
        if (messages.isEmpty() || viewportHeightPx == 0) return

        val targetIndex = if (targetMessageId != null) {
            val msg = messages.find { it.id == targetMessageId }
            if (msg?.participant == Participant.MODEL && msg.parentId != null) {
                messages.indexOfFirst { it.id == msg.parentId }
            } else {
                messages.indexOfFirst { it.id == targetMessageId }
            }
        } else {
            messages.indexOfLast { it.participant == Participant.USER }
        }
        if (targetIndex == -1) return

        with(density) {
            val targetTopPx = 140.dp.toPx()
            val topPaddingPx = 140.dp.toPx()

            var totalHeightBeforePx = 0
            var hasAnyHeight = false
            for (i in 0 until targetIndex) {
                val h = messageHeights[messages[i].id]
                if (h != null) { totalHeightBeforePx += h; hasAnyHeight = true }
            }

            if (!hasAnyHeight && targetIndex > 0) {
                listState.scrollToItem(targetIndex, 0)
            } else {
                val targetScrollPx = (topPaddingPx + totalHeightBeforePx - targetTopPx).coerceAtLeast(0f)

                if (animate) {
                    var currentOffsetPx = listState.firstVisibleItemScrollOffset.toFloat()
                    for (i in 0 until listState.firstVisibleItemIndex) {
                        if (i < messages.size) {
                            currentOffsetPx += (messageHeights[messages[i].id] ?: 0)
                        }
                    }

                    val diff = targetScrollPx - currentOffsetPx
                    if (kotlin.math.abs(diff) > 2) {
                        listState.animateScrollBy(diff, tween(600, easing = easing))
                    }
                } else {
                    listState.scrollToItem(0, targetScrollPx.toInt())
                }
            }
        }
    }

    val branchSwitchTrigger by viewModel.branchSwitchTrigger.collectAsState()

    LaunchedEffect(branchSwitchTrigger) {
        val targetMessageId = branchSwitchTrigger ?: return@LaunchedEffect
        if (currentConversationId == null) {
            viewModel.clearBranchSwitchTrigger()
            viewModel.setSwitching(false)
            return@LaunchedEffect
        }

        try {
            val currentMsgs = withTimeout(4000) {
                snapshotFlow { messages }
                    .filter { currentMsgs -> currentMsgs.any { it.id == targetMessageId } }
                    .first()
            }

            val msg = currentMsgs.find { it.id == targetMessageId }
            val currentTargetIndex = if (msg?.participant == Participant.MODEL && msg.parentId != null) {
                val parentIndex = currentMsgs.indexOfFirst { it.id == msg.parentId }
                if (parentIndex != -1) parentIndex else currentMsgs.indexOfFirst { it.id == targetMessageId }
            } else {
                currentMsgs.indexOfFirst { it.id == targetMessageId }
            }

            if (currentTargetIndex != -1) {
                listState.scrollToItem(currentTargetIndex, 0)
            }
        } catch (e: Exception) {
            // Timeout or intended cancellation
        }
        viewModel.clearBranchSwitchTrigger()
        viewModel.setSwitching(false)
    }

    LaunchedEffect(currentConversationId) {
        // New chat's first send creates the conversation; its own scroll-to-message handles
        // scrolling, so skip this conversation-open auto-scroll once to avoid a double scroll.
        if (viewModel.suppressNextOpenScroll) {
            viewModel.suppressNextOpenScroll = false
            viewModel.setSwitching(false)
            return@LaunchedEffect
        }
        if (currentConversationId != null) {
            snapshotFlow { messages }.filter { it.isNotEmpty() }.first()
            val targetIndex = messages.indexOfLast { it.participant == Participant.USER }

            if (targetIndex != -1) {
                try {
                    withTimeout(4000) {
                        snapshotFlow {
                            val sum = messageHeights.values.sum()
                            Triple(messages, sum, viewportHeightPx)
                        }.collectLatest { data ->
                            val currentMsgs = data.component1()
                            val vHeight = data.component3()

                            val currentTargetIndex = currentMsgs.indexOfLast { it.participant == Participant.USER }

                            if (currentTargetIndex != -1 && vHeight > 0) {
                                with(density) {
                                    var totalHeightBeforePx = 0
                                    for (i in 0 until currentTargetIndex) {
                                        totalHeightBeforePx += messageHeights[currentMsgs[i].id] ?: 0
                                    }
                                    listState.scrollToItem(currentTargetIndex, 0)
                                }
                            }

                            delay(500)
                            this@withTimeout.cancel()
                        }
                    }
                } catch (e: Exception) {
                    // Timeout or intended cancellation
                }
            }
            viewModel.setSwitching(false)
        } else {
            viewModel.setSwitching(false)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scrollToMessage.collect { messageId ->
            if (messageId != null) {
                try {
                    withTimeout(2000) {
                        snapshotFlow { messages.indexOfFirst { it.id == messageId } }
                            .filter { it != -1 }
                            .first()
                    }
                } catch (e: Exception) {
                    // Timeout
                }
                delay(50)
                scrollToLastUserMessage(animate = true, targetMessageId = messageId)
            } else {
                scrollToLastUserMessage(animate = true)
            }
        }
    }

    BackHandler(enabled = drawerState.currentValue != DrawerValue.Closed || drawerState.targetValue != DrawerValue.Closed) {
        focusManager.clearFocus()
        scope.launch { drawerState.close() }
    }

    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue != DrawerValue.Closed) {
            isExpanded = false
            focusManager.clearFocus()
        }
    }

    var observedGeneration by remember { mutableStateOf(isLoading) }
    var previousIsLoading by remember { mutableStateOf(isLoading) }
    LaunchedEffect(isLoading) {
        when {
            isLoading && !previousIsLoading -> {
                observedGeneration = true
            }
            !isLoading && previousIsLoading && observedGeneration -> {
                val terminalStatus = messages.lastOrNull { it.participant == Participant.MODEL }?.status
                when (terminalStatus) {
                    MessageStatus.ERROR -> haptics.reject()
                    MessageStatus.STOPPED -> haptics.generationStopped()
                    else -> haptics.generationEnd()
                }
                observedGeneration = false
            }
        }
        previousIsLoading = isLoading
    }

    val answeringHapticActive = isLoading &&
        generatingInConversationId == currentConversationId &&
        messages.lastOrNull { it.participant == Participant.MODEL }?.let { message ->
            message.status == MessageStatus.SENDING && message.hasActiveAnswerSegment()
        } == true
    DisposableEffect(answeringHapticActive, hapticsEnabled) {
        if (answeringHapticActive && hapticsEnabled) {
            haptics.startAnsweringTexture()
        }
        onDispose {
            haptics.stopAnsweringTexture()
        }
    }

    var pendingDrawerConversationHaptic by remember { mutableStateOf<String?>(null) }
    var previousIsSwitching by remember { mutableStateOf(isSwitching) }
    LaunchedEffect(isSwitching, currentConversationId) {
        if (
            previousIsSwitching &&
            !isSwitching &&
            pendingDrawerConversationHaptic != null &&
            pendingDrawerConversationHaptic == currentConversationId
        ) {
            haptics.success()
            pendingDrawerConversationHaptic = null
        }
        previousIsSwitching = isSwitching
    }

    CompositionLocalProvider(LocalAgoraHaptics provides haptics) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = DrawerDefaults.scrimColor,
        drawerContent = {
            ChatDrawerContent(
                viewModel = viewModel,
                drawerWidth = drawerWidth,
                drawerState = drawerState,
                scope = scope,
                inputFocusRequester = inputFocusRequester,
                onDrawerProgress = { drawerProgress = it },
                onSettingsButtonTop = { settingsButtonTopDp = it },
                onOpenSettings = onOpenSettings,
                onRequestRename = { id, title -> showRenameDialog = id; conversationToRename = title },
                onRequestDelete = { id -> showDeleteConfirmDialog = id },
                onPendingDrawerHaptic = { pendingDrawerConversationHaptic = it }
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clearFocusOnTap()
                .onSizeChanged { viewportHeightPx = it.height }
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
            AnimatedBlobBackground(centerAlpha = ca, quarterAlpha = qa, blurRadius = 40f, dark = dark)

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    ChatTopBar(
                        isNewChatMode = isNewChatMode,
                        conversations = conversations,
                        currentConversationId = currentConversationId,
                        totalTokens = totalTokens,
                        onOpenDrawer = { haptics.action(); focusManager.clearFocus(); scope.launch { drawerState.open() } },
                        onSystemPromptClick = { haptics.action(); showPromptDialog = true },
                        onNewChat = {
                            haptics.action()
                            isExpanded = false
                            viewModel.createNewChat()
                            inputFocusRequester.requestFocus()
                        },
                    )
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    val topBarH = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
                    val pivotY = ((LocalConfiguration.current.screenHeightDp + topBarH.value / 2f - bottomBarHeight.value) / 2f).coerceAtLeast(0f) / LocalConfiguration.current.screenHeightDp
                    AnimatedContent(
                        targetState = Pair(isNewChatMode, showLaunchContent),
                        transitionSpec = {
                            val targetNewChat = targetState.first
                            val targetShowLaunch = targetState.second
                            val initialNewChat = initialState.first
                            val initialShowLaunch = initialState.second

                            if (targetNewChat && (targetShowLaunch != initialShowLaunch || targetNewChat != initialNewChat)) {
                                // Entering new-chat mode: scale+fade animation
                                val enterSpec = tween<Float>(700, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f))
                                val fadeInSpec = tween<Float>(500)
                                (fadeIn(animationSpec = fadeInSpec) + scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(0.5f, pivotY), animationSpec = enterSpec))
                                    .togetherWith(fadeOut(animationSpec = tween(300)))
                            } else if (!targetNewChat && !initialNewChat) {
                                // Switching between existing conversations: no animation
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                // Returning from new-chat to an existing conversation
                                fadeIn(animationSpec = tween(300))
                                    .togetherWith(fadeOut(animationSpec = tween(300)))
                            }
                        },
                        label = "MainContentTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { (targetNewChat, targetShowLaunch) ->
                        if (!targetNewChat) {
                            val messageListModifier = if (blurEffectsEnabled) {
                                Modifier.fillMaxSize().gradientBlur(blurAtTopDp = 8f, blurAtBottomDp = 0f)
                            } else {
                                Modifier.fillMaxSize()
                            }
                            MessageList(
                                messages = StableMessageList(messages),
                                allMessages = StableMessageList(allMessages),
                                modifier = messageListModifier,
                                state = listState,
                                // Global generation gate: while ANY generation is in
                                // progress, all per-message actions (edit / delete /
                                // regenerate / branch-switch) are disabled. Bound to the
                                // global isLoading so there is no per-conversation timing
                                // window (generatingInConversationId is set asynchronously).
                                isLoading = isLoading,
                                isSwitching = isSwitching,
                                visualizeContextRollout = visualizeContextRollout,
                                toolCallDisplayMode = toolCallDisplayMode,
                                maxContextWindow = contextWindow,
                                modelAliases = StableModelAliases(modelAliases),
                                bottomBarHeight = bottomBarHeight,
                                viewportHeight = viewportHeightPx,
                                messageHeights = messageHeights,
                                onEditMessage = { id, text ->
                                    val isFirstMessage = messages.isEmpty()
                                    viewModel.editMessage(id, text)
                                    scope.launch {
                                        if (!isFirstMessage) {
                                            delay(50)
                                            scrollToLastUserMessage(animate = true)
                                        }
                                    }
                                },
                                onSwitchBranch = { parentId, currentMessageId, direction ->
                                    haptics.selection()
                                    viewModel.switchBranch(parentId, currentMessageId, direction)
                                },
                                onRegenerate = { id ->
                                    haptics.action()
                                    viewModel.regenerate(id)
                                    scope.launch {
                                        delay(50)
                                        scrollToLastUserMessage(animate = true)
                                    }
                                },
                                onDelete = { id -> viewModel.deleteMessage(id) },
                                onMediaClick = onMediaClick,
                                onFileContentClick = onFileContentClick,
                                onPdfPagesClick = { pages, idx -> haptics.action(); onPdfPagesClick?.invoke(pages, idx) },
                                thoughtExpandedStates = thoughtExpandedStates,
                                contentPadding = PaddingValues(
                                    start = 8.dp,
                                    end = 8.dp,
                                    top = 140.dp,
                                    bottom = bottomBarHeight + 8.dp
                                )
                            )
                        } else if (targetShowLaunch) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = bottomBarHeight),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    TypewriterText(
                                        text = stringResource(R.string.welcome_to_agora),
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.padding(top = ((LocalConfiguration.current.screenHeightDp + topBarH.value / 2f - bottomBarHeight.value) / 2).coerceAtLeast(0f).dp)
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize())
                        }
                    }

                    val showButton by remember {
                        derivedStateOf {
                            if (isNewChatMode) false
                            else {
                                val info = listState.layoutInfo
                                val total = info.totalItemsCount
                                total > 1 && info.visibleItemsInfo.none { it.index == total - 2 }
                            }
                        }
                    }

                    val fabElevation by animateDpAsState(
                        targetValue = if (showButton) 4.dp else 0.dp,
                        animationSpec = tween(400)
                    )

                    AnimatedVisibility(
                        visible = showButton,
                        enter = fadeIn(tween(400)) + scaleIn(initialScale = 0.6f, animationSpec = tween(400)),
                        exit = fadeOut(tween(400)) + scaleOut(targetScale = 0.6f, animationSpec = tween(400)),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomBarHeight + 8.dp)
                    ) {
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            FloatingActionButton(onClick = { scope.launch { scrollToLastUserMessage(animate = true, easing = SCROLL_EASING) } }, containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp), contentColor = MaterialTheme.colorScheme.onSurface, shape = CircleShape, elevation = FloatingActionButtonDefaults.elevation(fabElevation), modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.scroll_to_bottom), modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = isSwitching && !isTransitioningToNewChat,
                        enter = fadeIn(animationSpec = tween(250)),
                        exit = fadeOut(animationSpec = tween(250))
                    ) {
                        com.newoether.agora.ui.components.ShimmerChatPlaceholder()
                    }
                }
            }

            val gradientTopPaddingPx = with(density) { 20.dp.toPx() }
            val gradientWidthPx = with(density) { 40.dp.toPx() }
            val bgColor = MaterialTheme.colorScheme.background
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.fillMaxHeight().statusBarsPadding() else Modifier)
                    .drawBehind {
                        val totalH = size.height
                        if (totalH > 0f) {
                            val (transparentEnd, fadeEnd) = if (isExpanded) {
                                // In expanded mode, keep the gradient compact at the top
                                val h = gradientTopPaddingPx.coerceAtMost(totalH * 0.12f)
                                val w = gradientWidthPx.coerceAtMost(totalH * 0.24f)
                                (h / totalH) to ((h + w) / totalH)
                            } else {
                                val te = (gradientTopPaddingPx / totalH).coerceIn(0f, 1f)
                                val fe = ((gradientTopPaddingPx + gradientWidthPx) / totalH).coerceIn(0f, 1f)
                                te to fe
                            }
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        transparentEnd to Color.Transparent,
                                        fadeEnd to bgColor,
                                    ),
                                    startY = 0f,
                                    endY = totalH
                                )
                            )
                        }
                    },
                color = Color.Transparent
            ) {
                Column {
                    if (outerSpacerHeightPx > 0f) {
                        Spacer(modifier = Modifier.height(with(density) { outerSpacerHeightPx.toDp() }))
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                            .onSizeChanged {
                            if (!isExpanded) bottomBarHeightPx = it.height.toFloat()
                        }
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        ChatBottomBar(
                        onSendMessage = { text, attachments ->
                            viewModel.sendMessage(text, attachments = attachments).also { sent ->
                                if (sent) {
                                    haptics.action()
                                    scope.launch {
                                        delay(200)
                                        scrollToLastUserMessage(animate = true)
                                    }
                                }
                            }
                        },
                        onStopGeneration = {
                            haptics.generationStopped()
                            viewModel.stopGeneration()
                        },
                        isLoading = isLoading,
                        isSwitching = isSwitching,
                        enabledModels = enabledModels,
                        selectedModel = selectedModel,
                        modelAliases = modelAliases,
                        codeExecutionEnabled = codeExecutionEnabled,
                        googleSearchEnabled = googleSearchEnabled,
                        thinkingEnabled = thinkingEnabled,
                        thinkingLevel = thinkingLevel,
                        thinkingBudgetEnabled = thinkingBudgetEnabled,
                        thinkingBudgetTokens = thinkingBudgetTokens,
                        onCodeExecutionToggle = { enabled -> haptics.selection(); viewModel.updateConversationSetting(currentConversationId) { it.copy(codeExecutionEnabled = enabled) } },
                        onGoogleSearchToggle = { enabled -> haptics.selection(); viewModel.updateConversationSetting(currentConversationId) { it.copy(googleSearchEnabled = enabled) } },
                        onThinkingToggle = { enabled -> haptics.selection(); viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingEnabled = enabled) } },
                        onThinkingLevelChange = { level -> viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingLevel = level) } },
                        onThinkingBudgetEnabledChange = { enabled -> viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingBudgetEnabled = enabled) } },
                        onThinkingBudgetTokensChange = { tokens -> viewModel.updateConversationSetting(currentConversationId) { it.copy(thinkingBudgetTokens = tokens) } },
                        webSearchEnabled = webSearchEnabled,
                        onWebSearchToggle = { enabled -> haptics.selection(); viewModel.updateConversationSetting(currentConversationId) { it.copy(webSearchEnabled = enabled) } },
                        shellEnabled = shellEnabled,
                        onShellToggle = { enabled -> haptics.selection(); viewModel.updateConversationSetting(currentConversationId) { it.copy(shellEnabled = enabled) } },
                        onModelSelect = { haptics.selection(); viewModel.setActiveModel(it) },
                        onImageClick = { url -> haptics.action(); onMediaClick(listOf(url), 0) },
                        onAllMediaClick = { urls, idx -> haptics.action(); onMediaClick(urls, idx) },
                        onFileContentClick = { name, content -> haptics.action(); viewModel.showFilePreview(name, content) },
                        modifier = Modifier,
                        textFieldState = textFieldState,
                        focusRequester = inputFocusRequester,
                        isExpanded = isExpanded,
                        isExpandAnimating = isExpandAnimating,
                        onCollapse = { haptics.action(); isExpanded = false },
                        onExpand = { haptics.action(); isExpanded = true },
                        showWebSearch = globalWebSearch,
                        showShell = shellDevices.isNotEmpty() && globalShell,
                        onPdfPagesClick = { pages, idx -> haptics.action(); onPdfPagesClick?.invoke(pages, idx) },
                        onPdfPreviewSelect = { pages, idx -> haptics.action(); onPdfPreviewSelect?.invoke(pages, idx) },
                        pdfViewerSelection = pdfViewerSelection,
                        onTogglePdfSelection = onTogglePdfSelection,
                        onInitPdfSelection = onInitPdfSelection,
                        fullScreenViewerUrls = fullScreenViewerUrls,
                        onAdvancedClick = { showAdvancedDialog = true }
                    )
                }
            }
            }
        }
        }
    }
    }

    showRenameDialog?.let { id ->
        ChatRenameDialog(
            initialName = conversationToRename,
            onSave = { newName ->
                viewModel.renameConversation(id, newName)
                showRenameDialog = null
            },
            onDismiss = { showRenameDialog = null }
        )
    }

    showDeleteConfirmDialog?.let { id ->
        ChatDeleteConfirmDialog(
            onConfirm = {
                haptics.reject()
                viewModel.deleteConversation(id)
                showDeleteConfirmDialog = null
            },
            onDismiss = { showDeleteConfirmDialog = null }
        )
    }

    if (showPromptDialog) {
        ChatSystemPromptDialog(viewModel = viewModel, onDismiss = { showPromptDialog = false })
    }

    if (showAdvancedDialog) {
        ChatAdvancedSettingsDialog(viewModel = viewModel, onDismiss = { showAdvancedDialog = false })
    }
}

