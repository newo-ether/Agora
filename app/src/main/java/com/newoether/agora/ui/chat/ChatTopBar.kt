package com.newoether.agora.ui.chat

import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.rememberIdentityClipWidth
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.motion.AnimatedContent
import com.newoether.agora.ui.motion.Crossfade
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text
import androidx.compose.ui.layout.onSizeChanged
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.viewmodel.ChatViewModel

private const val TITLE_CAPSULE_MAX_WIDTH_DP = 260

@Composable
internal fun ChatTopBar(
    viewModel: ChatViewModel,
    reading: Boolean = false,
    onHeightChanged: (Int) -> Unit = {},
    dialogState: ChatAppDialogState,
    conversationInteraction: ConversationInteractionProjection,
    conversations: List<ChatConversation>,
    totalTokens: Int,
    contextTokenBudget: Int,
    systemPromptEnabled: Boolean,
    onNavigateBack: (() -> Unit)?,
    onOpenDrawer: () -> Unit,
    onForkConversation: () -> Unit,
    onNewChat: () -> Unit,
) {
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val currentConversation by viewModel.currentConversation.collectAsState()
    val loadedOwnerId by viewModel.loadedMessagesConversationId.collectAsState()
    val isNewChatMode by viewModel.isNewChatMode.collectAsState()
    val isSwitching by viewModel.isSwitching.collectAsState()
    val isTransitioningToNewChat by viewModel.isTransitioningToNewChat.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val submittingOwnerIds by viewModel.conversationComposerSubmission.activeOwnerIds.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val focusManager = LocalFocusManager.current
    val haptics = LocalAgoraHaptics.current
    val metadataEnabled = !isNewChatMode && currentConversationId != null &&
        currentConversation?.id == currentConversationId && loadedOwnerId == currentConversationId &&
        !isSwitching && !isTransitioningToNewChat && !conversationInteraction.shareSelectionActive

    // Recheck the source owner at dispatch; dialog state retains it after later navigation.
    fun currentTarget(conversationId: String): ChatConversation? {
        if (viewModel.isNewChatMode.value || viewModel.isSwitching.value ||
            viewModel.isTransitioningToNewChat.value || conversationInteraction.shareSelectionActive ||
            viewModel.currentConversationId.value != conversationId ||
            viewModel.loadedMessagesConversationId.value != conversationId
        ) return null
        return viewModel.currentConversation.value?.takeIf { it.id == conversationId }
    }

    ChatTopBar(
        isNewChatMode = isNewChatMode,
        reading = reading,
        onHeightChanged = onHeightChanged,
        conversations = conversations,
        currentConversationId = currentConversationId,
        currentConversationTitle = currentConversation?.title?.let {
            replaceCustomProviderIdsForDisplay(it, customProviders)
        },
        totalTokens = totalTokens,
        contextTokenBudget = contextTokenBudget,
        searchActive = conversationInteraction.searchActive,
        searchQuery = conversationInteraction.searchQuery,
        searchMatchIndex = conversationInteraction.searchMatchIndex,
        searchMatchCount = conversationInteraction.searchMatches.size,
        conversationActionsEnabled = !isNewChatMode && currentConversationId != null &&
            !isLoading && !conversationInteraction.shareSelectionActive,
        conversationMetadataActionsEnabled = metadataEnabled,
        conversationDeleteEnabled = currentConversationId !in submittingOwnerIds,
        systemPromptEnabled = systemPromptEnabled,
        onNavigateBack = onNavigateBack,
        onOpenDrawer = onOpenDrawer,
        onSearchQueryChange = conversationInteraction::updateSearchQuery,
        onSearchSubmit = conversationInteraction::submitSearch,
        onSearchPrevious = { if (conversationInteraction.previousSearchMatch()) haptics.selection() },
        onSearchNext = { if (conversationInteraction.nextSearchMatch()) haptics.selection() },
        onSearchDismiss = {
            conversationInteraction.dismissSearch()
            focusManager.clearFocus()
        },
        onSearchClick = conversationInteraction::activateSearch,
        onSystemPromptClick = dialogState::showPrompt,
        onForkConversation = onForkConversation,
        onShareConversation = {
            conversationInteraction.dismissSearch()
            focusManager.clearFocus()
            conversationInteraction.activateShareSelection()
        },
        onRenameConversation = { conversationId ->
            currentTarget(conversationId)?.let { conversation ->
                dialogState.requestRename(conversationId, conversation.title)
            }
        },
        onDeleteConversation = { conversationId ->
            if (currentTarget(conversationId) != null && !viewModel.isConversationDeleteLocked(conversationId)) {
                dialogState.requestDelete(conversationId)
            }
        },
        onNewChat = onNewChat,
    )
}

/**
 * The chat screen's top bar: a title capsule (drawer menu + brand/conversation
 * title with optional token subtitle) and an actions capsule (system prompt +
 * new chat). Extracted from [ChatApp]; all behavior is routed through callbacks.
 */
@Composable
internal fun ChatTopBar(
    isNewChatMode: Boolean,
    onHeightChanged: (Int) -> Unit = {},
    reading: Boolean = false,
    conversations: List<ChatConversation>,
    currentConversationId: String?,
    currentConversationTitle: String? = null,
    totalTokens: Int,
    contextTokenBudget: Int,
    contextAvailable: Boolean = totalTokens > 0,
    subtitle: String? = null,
    subtitleLeading: (@Composable () -> Unit)? = null,
    searchActive: Boolean = false,
    searchQuery: String = "",
    searchMatchIndex: Int = -1,
    searchMatchCount: Int = 0,
    conversationActionsEnabled: Boolean = false,
    conversationMetadataActionsEnabled: Boolean = false,
    conversationDeleteEnabled: Boolean = conversationMetadataActionsEnabled,
    systemPromptEnabled: Boolean = true,
    onNavigateBack: (() -> Unit)? = null,
    onOpenDrawer: () -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onSearchSubmit: () -> Unit = {},
    onSearchPrevious: () -> Unit = {},
    onSearchNext: () -> Unit = {},
    onSearchDismiss: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSystemPromptClick: () -> Unit,
    onForkConversation: () -> Unit = {},
    onShareConversation: () -> Unit = {},
    onRenameConversation: (String) -> Unit = {},
    onDeleteConversation: (String) -> Unit = {},
    onNewChat: () -> Unit,
    trailingActions: (@Composable RowScope.() -> Unit)? = null,
    newChatEnabled: Boolean = true,
    newChatDescription: String? = null,
    moreMenuContent: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
) {
    var moreMenuOpen by remember(
        if (moreMenuContent == null) currentConversationId else null,
        moreMenuContent == null && isNewChatMode,
    ) { mutableStateOf(false) }
    var moreMenuConversationId by remember { mutableStateOf<String?>(null) }
    var moreMenuRequest by remember { mutableStateOf<Any?>(null) }
    val menuRequest = moreMenuRequest
    val menuConversationId = moreMenuConversationId
    val latestConversationId by rememberUpdatedState(if (isNewChatMode) null else currentConversationId)
    val hasConversationTarget = !isNewChatMode && currentConversationId != null &&
        menuConversationId == currentConversationId
    val renameEnabled = hasConversationTarget && conversationMetadataActionsEnabled
    val deleteEnabled = hasConversationTarget && conversationMetadataActionsEnabled && conversationDeleteEnabled
    val latestRenameEnabled by rememberUpdatedState(renameEnabled)
    val latestMenuOpen by rememberUpdatedState(moreMenuOpen)
    val latestDeleteEnabled by rememberUpdatedState(deleteEnabled)
    val deleteColor = MaterialTheme.colorScheme.error.copy(alpha = if (deleteEnabled) 1f else 0.5f)
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchActive) {
        if (searchActive) {
            // Let AnimatedContent commit the search field before asking the IME for focus.
            // Requesting focus on the state-change frame makes the keyboard and enter
            // transition compete for the first layout and produces a visible flash.
            withFrameNanos { }
            searchFocusRequester.requestFocus()
        }
    }
    Column(
        modifier = Modifier.readingChrome(reading)
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height) }
            .defaultMinSize(minHeight = if (LocalAgoraMotionPolicy.current.eInkMode) 0.dp else 180.dp)
            // An expanded composer fills the screen and paints a background gradient across it, and
            // the Scaffold draws the bottom bar after the top one, so that fill would otherwise lie
            // over this bar's title and action capsules. The composer's own layer sits at 1f.
            .zIndex(1.5f)
            .then(if (LocalAgoraMotionPolicy.current.eInkMode) Modifier.background(MaterialTheme.colorScheme.background) else Modifier.background(
                Brush.verticalGradient(
                    0.0f to MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                    0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.80f),
                    1.0f to Color.Transparent
                )
            ))
    ) {
        AnimatedContent(
            targetState = searchActive,
            transitionSpec = {
                val contentTransform = if (!allowSpatialTransitions) {
                    fadeIn(tween(360, easing = FastOutSlowInEasing))
                        .togetherWith(
                            fadeOut(tween(300, easing = FastOutSlowInEasing)),
                        )
                } else if (targetState) {
                    (
                        fadeIn(tween(400, easing = FastOutSlowInEasing)) +
                            scaleIn(
                                initialScale = 0.94f,
                                animationSpec = tween(400, easing = FastOutSlowInEasing),
                            )
                        ).togetherWith(
                        fadeOut(tween(300, easing = FastOutSlowInEasing)) +
                            scaleOut(
                                targetScale = 0.97f,
                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                            )
                    )
                } else {
                    (
                        fadeIn(tween(360, easing = FastOutSlowInEasing)) +
                            scaleIn(
                                initialScale = 0.97f,
                                animationSpec = tween(360, easing = FastOutSlowInEasing),
                            )
                        ).togetherWith(
                        fadeOut(tween(320, easing = FastOutSlowInEasing)) +
                            scaleOut(
                                targetScale = 0.94f,
                                animationSpec = tween(320, easing = FastOutSlowInEasing),
                            )
                    )
                }
                contentTransform.using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.Center,
            label = "ChatTopBarSearchTransition",
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                .height(52.dp),
        ) { targetSearchActive ->
            Row(
                modifier = Modifier
                    .fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (targetSearchActive) {
                    ChatTopBarCapsule(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.width(5.dp))
                            IconButton(
                                onClick = onSearchDismiss,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            if (LocalAgoraMotionPolicy.current.eInkMode) {
                                IconButton(onClick = onSearchSubmit) {
                                    Icon(Icons.Default.Search, stringResource(R.string.search_hint))
                                }
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                singleLine = true,
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearchSubmit() }),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Search,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(searchFocusRequester),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                stringResource(R.string.conversation_search_hint),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    .copy(alpha = 0.6f),
                                                maxLines = 1,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                            Text(
                                text = if (searchMatchCount == 0) {
                                    "0/0"
                                } else {
                                    "${searchMatchIndex + 1}/$searchMatchCount"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                            IconButton(
                                enabled = searchMatchIndex > 0,
                                onClick = onSearchPrevious,
                                modifier = Modifier.size(38.dp),
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = null,
                                )
                            }
                            IconButton(
                                enabled = searchMatchIndex >= 0 &&
                                    searchMatchIndex < searchMatchCount - 1,
                                onClick = onSearchNext,
                                modifier = Modifier.size(38.dp),
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                )
                            }
                            Spacer(Modifier.width(5.dp))
                        }
                    }
                } else {
                // New Chat keeps only the menu. Unloaded existing titles retain their brand fallback.
                val resolvedTitle = if (isNewChatMode) null else {
                    currentConversationTitle?.takeIf { it.isNotBlank() }
                        ?: conversations.find { it.id == currentConversationId }?.title?.takeIf { it.isNotBlank() }
                }
                val showBrandTitle = resolvedTitle == null
                val appName = stringResource(R.string.app_name)
                val titlePresentation = Triple(
                    isNewChatMode,
                    if (showBrandTitle) null else currentConversationId,
                    resolvedTitle,
                )
                val textMeasurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val tokenSubtitle = if (!showBrandTitle && subtitle != null) subtitle
                else if (!showBrandTitle && contextAvailable) {
                    stringResource(
                        R.string.context_usage_messages,
                        ContextBudget.compactLabel(totalTokens),
                        ContextBudget.compactLabel(contextTokenBudget),
                    )
                } else {
                    null
                }
                val conversationTitleStyle =
                    if (tokenSubtitle != null) ChatType.conversationTitle else ChatType.conversationTitleSolo
                val targetTitleContentWidth = with(density) {
                    val primaryWidth = textMeasurer.measure(
                        text = AnnotatedString(if (showBrandTitle) appName else resolvedTitle.orEmpty()),
                        style = if (showBrandTitle) ChatType.brandTitle else conversationTitleStyle,
                        maxLines = 1,
                        softWrap = false,
                    ).size.width.toDp()
                    val subtitleWidth = tokenSubtitle?.let { subtitle ->
                        textMeasurer.measure(
                            text = AnnotatedString(subtitle),
                            style = ChatType.micro,
                            maxLines = 1,
                            softWrap = false,
                        ).size.width.toDp()
                    } ?: 0.dp
                    val leadingWidth = if (subtitleLeading != null && tokenSubtitle != null) 14.dp else 0.dp
                    minOf(maxOf(primaryWidth, subtitleWidth + leadingWidth), 180.dp)
                }
                val targetTitleCapsuleWidth = if (isNewChatMode) 52.dp else minOf(
                    5.dp + 44.dp + 5.dp + targetTitleContentWidth + 20.dp,
                    TITLE_CAPSULE_MAX_WIDTH_DP.dp,
                )
                // Only the rounded clip moves; the title content keeps its final layout.
                val visibleTitleCapsuleWidth = rememberIdentityClipWidth(
                    identity = titlePresentation,
                    targetWidth = targetTitleCapsuleWidth,
                    allowSpatialTransitions = allowSpatialTransitions,
                )
                val visibleTitleCapsuleWidthPx = with(density) {
                    visibleTitleCapsuleWidth.toPx()
                }
                val titleCapsuleClipShape = GenericShape { size, _ ->
                    val right = visibleTitleCapsuleWidthPx.coerceIn(0f, size.width)
                    val cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                    addRoundRect(
                        RoundRect(
                            left = 0f,
                            top = 0f,
                            right = right,
                            bottom = size.height,
                            cornerRadius = cornerRadius,
                        ),
                    )
                }

                val eInk = LocalAgoraMotionPolicy.current.eInkMode
                // Reserve the trailing capsule first; the title may only use the remaining width.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    ChatTopBarCapsule(
                        modifier = Modifier
                            .fillMaxHeight()
                            .then(if (eInk) Modifier.fillMaxWidth() else Modifier.width(TITLE_CAPSULE_MAX_WIDTH_DP.dp))
                            .graphicsLayer {
                                shape = titleCapsuleClipShape
                                clip = !eInk
                                shadowElevation = if (eInk) 0f else 4.dp.toPx()
                            },
                        shadowElevation = 0.dp,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(modifier = Modifier.width(5.dp))
                            IconButton(
                                onClick = onNavigateBack ?: onOpenDrawer,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = if (onNavigateBack != null) {
                                        Icons.AutoMirrored.Filled.ArrowBack
                                    } else {
                                        Icons.Default.Menu
                                    },
                                    contentDescription = stringResource(
                                        if (onNavigateBack != null) R.string.back else R.string.menu
                                    ),
                                    modifier = Modifier.size(26.dp).graphicsLayer {
                                        translationX = if (onNavigateBack == null) (-1).dp.toPx() else 0f
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Crossfade(
                                targetState = titlePresentation,
                                animationSpec = tween(
                                    durationMillis = 200,
                                    easing = FastOutSlowInEasing,
                                ),
                                label = "chatTopBarTitle",
                                modifier = if (eInk) Modifier.weight(1f) else Modifier,
                            ) { presentation ->
                                if (presentation.first) {
                                    // The menu remains while the outgoing title fades into the circular clip.
                                } else if (presentation.third == null) {
                                    Text(
                                        text = appName,
                                        style = ChatType.brandTitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(end = 20.dp)
                                            .then(if (eInk) Modifier.fillMaxWidth() else Modifier.widthIn(max = 180.dp))
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier.padding(end = 20.dp)
                                            .then(if (eInk) Modifier.fillMaxWidth() else Modifier.widthIn(max = 180.dp))
                                    ) {
                                        Text(
                                            text = presentation.third.orEmpty(),
                                            // Single-line (no token subtitle) uses a slightly-smaller-than-brand
                                            // solo size; with the token subtitle stacked below, the compact size.
                                            style = conversationTitleStyle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (tokenSubtitle != null) {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                subtitleLeading?.invoke()
                                                Text(
                                                    text = tokenSubtitle,
                                                    style = ChatType.micro,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Actions capsule: system prompt + new chat
                ChatTopBarCapsule(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(98.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(5.dp))
                        if (trailingActions != null) trailingActions() else {
                        IconButton(onClick = onNewChat, enabled = newChatEnabled, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.Add, contentDescription = newChatDescription ?: stringResource(R.string.new_chat), modifier = Modifier.size(30.dp))
                        }
                        Box {
                            IconButton(
                                onClick = {
                                    moreMenuConversationId = currentConversationId
                                    moreMenuRequest = Any()
                                    moreMenuOpen = true
                                },
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.options),
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                            AgoraDropdownMenu(
                                expanded = moreMenuOpen,
                                onDismissRequest = {
                                    moreMenuOpen = false
                                    moreMenuRequest = null
                                },
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 16.dp,
                            ) {
                                if (moreMenuContent != null) moreMenuContent { moreMenuOpen = false } else {
                                AgoraDropdownMenuItem(
                                    text = { Text(stringResource(R.string.conversation_search)) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    enabled = conversationActionsEnabled,
                                    onClick = {
                                        moreMenuOpen = false
                                        onSearchClick()
                                    },
                                )
                                AgoraDropdownMenuItem(
                                    text = { Text(stringResource(R.string.system_prompt)) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Psychology, contentDescription = null)
                                    },
                                    enabled = systemPromptEnabled,
                                    onClick = {
                                        moreMenuOpen = false
                                        onSystemPromptClick()
                                    },
                                )
                                AgoraDropdownMenuItem(
                                    text = { Text(stringResource(R.string.conversation_fork_menu)) },
                                    leadingIcon = {
                                        Icon(Icons.Default.CallSplit, contentDescription = null)
                                    },
                                    enabled = conversationActionsEnabled,
                                    onClick = {
                                        moreMenuOpen = false
                                        onForkConversation()
                                    },
                                )
                                AgoraDropdownMenuItem(
                                    text = { Text(stringResource(R.string.conversation_share)) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                    },
                                    enabled = conversationActionsEnabled,
                                    onClick = {
                                        moreMenuOpen = false
                                        onShareConversation()
                                    },
                                )
                                AgoraDropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    enabled = renameEnabled,
                                    onClick = {
                                        if (latestMenuOpen && menuRequest != null && menuRequest === moreMenuRequest &&
                                            latestRenameEnabled && menuConversationId == latestConversationId
                                        ) {
                                            moreMenuOpen = false
                                            moreMenuRequest = null
                                            menuConversationId?.let(onRenameConversation)
                                        }
                                    },
                                )
                                AgoraDropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete), color = deleteColor) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Delete, contentDescription = null, tint = deleteColor)
                                    },
                                    enabled = deleteEnabled,
                                    onClick = {
                                        if (latestMenuOpen && menuRequest != null && menuRequest === moreMenuRequest &&
                                            latestDeleteEnabled && menuConversationId == latestConversationId
                                        ) {
                                            moreMenuOpen = false
                                            moreMenuRequest = null
                                            menuConversationId?.let(onDeleteConversation)
                                        }
                                    },
                                )
                                }
                            }
                        }
                        }
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun ChatTopBarCapsule(
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 4.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier,
        propagateMinConstraints = true,
    ) {
        Surface(
            modifier = Modifier.matchParentSize(),
            shape = shape,
            color = Color.Transparent,
            shadowElevation = shadowElevation,
        ) {}
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 0.dp,
            content = content,
        )
    }
}
