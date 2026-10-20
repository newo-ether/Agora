package com.newoether.agora.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.ui.chat.search.DrawerSearchBar
import com.newoether.agora.ui.chat.search.SearchResultItem
import com.newoether.agora.ui.chat.search.rememberDrawerSearchState
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.verticalEdgeFade
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.motion.AnimatedVisibility
import com.newoether.agora.ui.motion.Crossfade
import com.newoether.agora.ui.motion.animateColorAsState
import com.newoether.agora.ui.motion.animateFloatAsState
import com.newoether.agora.ui.components.Button
import com.newoether.agora.ui.components.FilledTonalButton
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text

/**
 * The conversation navigation drawer: search box, new-chat button, conversation list with
 * per-item context menu, and the settings button. Reads its own flows from [viewModel];
 * shared host state (drawer slide progress, settings-button position, dialog requests) is
 * written back through callbacks so [ChatApp] keeps owning it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChatDrawerContent(
    viewModel: ChatViewModel,
    drawerWidth: Dp,
    scope: CoroutineScope,
    inputFocusRequester: FocusRequester,
    drawerVisible: Boolean,
    onRequestClose: suspend () -> Unit,
    onSettingsButtonTop: (Float) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenRemote: () -> Unit,
    onRequestRename: (String, String) -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    val haptics = LocalAgoraHaptics.current
    val motionPolicy = LocalAgoraMotionPolicy.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()

    val conversationList by viewModel.conversations.collectAsState()
    val conversations = conversationList.orEmpty()
    val conversationKeys = remember(conversations) { drawerConversationKeys(conversations) }
    val conversationsByKey = remember(conversations) {
        conversations.associateBy { "conversation:${it.id}" }
    }
    val isConversationListLoading = conversationList == null
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val selectedConversation by viewModel.currentConversation.collectAsState()
    val drawerConversationId = selectedConversation?.takeIf { it.id == currentConversationId }
        ?.parentConversationId ?: currentConversationId
    val isSwitching by viewModel.isSwitching.collectAsState()
    val generatingConversationIds by viewModel.generatingConversationIds.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val search = rememberDrawerSearchState(viewModel)

    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerTonalElevation = 1.dp,
        modifier = Modifier
            .width(drawerWidth)
            .graphicsLayer {
                clip = true
            }
    ) {
        val conversationListState = rememberLazyListState()
        val searchListState = rememberLazyListState()
        val activeListState = if (search.isActive) searchListState else conversationListState
        val edgeFadeTolerancePx = with(density) { DrawerEdgeFadeTolerance.roundToPx() }
        val latestConversations by rememberUpdatedState(conversations)
        val latestConversationKeys by rememberUpdatedState(conversationKeys)
        val latestMotionPolicy by rememberUpdatedState(motionPolicy)
        val submittingConversationIds by viewModel.conversationComposerSubmission
            .activeOwnerIds
            .collectAsState()
        LaunchedEffect(viewModel, conversationListState) {
            viewModel.firstMessageCommitted.collect { conversationId ->
                if (viewModel.currentConversationId.value != conversationId) return@collect
                snapshotFlow {
                    val currentConversations = latestConversations
                    currentConversations.firstOrNull()?.id == conversationId &&
                        conversationListState.layoutInfo.totalItemsCount ==
                            latestConversationKeys.size
                }.first { ready -> ready }
                if (viewModel.currentConversationId.value != conversationId) return@collect
                if (latestMotionPolicy.allowProgrammaticScrollMotion) {
                    conversationListState.animateToAbsoluteTop(
                        estimatedItemSizePx = with(density) { 44.dp.toPx() },
                        minimumStepPx = with(density) { 2.dp.toPx() },
                        feedbackSpec = SendFeedbackScrollSpec,
                    )
                } else {
                    conversationListState.scrollToItem(0)
                }
            }
        }
        val requestPinScroll = rememberDrawerPinScroll(
            listState = conversationListState,
            conversations = conversations,
            itemCount = conversationKeys.size,
            searchActive = search.isActive,
        )
        SideEffect {
            val firstVisibleIndex = conversationListState.firstVisibleItemIndex
            val firstVisibleConversationId = conversationListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == firstVisibleIndex }
                ?.key
                ?.toString()
            val indexedConversationId = conversationKeys.getOrNull(firstVisibleIndex)
            if (
                !search.isActive &&
                conversationListState.layoutInfo.totalItemsCount == conversationKeys.size &&
                firstVisibleConversationId != null &&
                indexedConversationId != null &&
                indexedConversationId != firstVisibleConversationId &&
                firstVisibleConversationId in conversationKeys
            ) {
                conversationListState.requestScrollToItem(
                    firstVisibleIndex,
                    conversationListState.firstVisibleItemScrollOffset,
                )
            }
        }
        val atTop by remember(activeListState, edgeFadeTolerancePx) {
            derivedStateOf {
                isDrawerListAtTop(
                    firstVisibleItemIndex = activeListState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffsetPx =
                        activeListState.firstVisibleItemScrollOffset,
                    tolerancePx = edgeFadeTolerancePx,
                )
            }
        }
        val atBottom by remember(activeListState, edgeFadeTolerancePx) {
            derivedStateOf {
                val layoutInfo = activeListState.layoutInfo
                val lastVisibleItem = layoutInfo.visibleItemsInfo.maxByOrNull { it.index }
                isDrawerListAtBottom(
                    totalItemsCount = layoutInfo.totalItemsCount,
                    lastVisibleItemIndex = lastVisibleItem?.index,
                    lastVisibleItemEndOffsetPx = lastVisibleItem?.let { it.offset + it.size },
                    viewportEndOffsetPx = layoutInfo.viewportEndOffset,
                    tolerancePx = edgeFadeTolerancePx,
                )
            }
        }
        val stw by animateFloatAsState(if (atTop) 0f else 1f, tween(200))
        val sbw by animateFloatAsState(if (atBottom) 0f else 1f, tween(200))
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .clearFocusOnTap()
        ) {
            DrawerSearchBar(
                query = search.query,
                onQueryChange = { search.query = it },
                searching = search.isSearching,
                onSubmit = search::submit,
                drawerVisible = drawerVisible,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Crossfade(
                targetState = search.results.takeIf { search.isActive }
                    ?.filter { "conversation:${it.first.conversationId}" in conversationsByKey },
                animationSpec = tween(180),
                label = "DrawerConversationContentTransition",
                modifier = Modifier.weight(1f),
            ) { targetResults ->
                if (targetResults == null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        FilledTonalButton(
                            onClick = {
                                focusManager.clearFocus()
                                onOpenTasks()
                                scope.launch { onRequestClose() }
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp,
                                bottomStart = 5.dp, bottomEnd = 5.dp)
                        ) {
                            Icon(Icons.Default.Repeat, null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.tasks), style = ChatType.drawerButton)
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        FilledTonalButton(
                            onClick = {
                                focusManager.clearFocus()
                                onOpenRemote()
                                scope.launch { onRequestClose() }
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp,
                                bottomStart = 24.dp, bottomEnd = 24.dp)
                        ) {
                            Icon(Icons.Default.Devices, null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.remote_title), style = ChatType.drawerButton)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val newChatDisabled = isSwitching
                        val newChatContainer by animateColorAsState(
                            if (newChatDisabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.primary,
                            tween(300), label = "newChatContainer"
                        )
                        val newChatContent by animateColorAsState(
                            if (newChatDisabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else MaterialTheme.colorScheme.onPrimary,
                            tween(300), label = "newChatContent"
                        )
                        Button(
                            onClick = {
                                if (!newChatDisabled) {
                                    viewModel.createNewChat()
                                    scope.launch {
                                        onRequestClose()
                                        inputFocusRequester.requestFocus()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            enabled = true,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = newChatContainer,
                                contentColor = newChatContent
                            )
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.new_chat), style = ChatType.drawerButton)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Box(modifier = Modifier.weight(1f)) {
                            com.newoether.agora.ui.motion.AnimatedVisibility(
                                visible = !isConversationListLoading,
                                modifier = Modifier.fillMaxSize(),
                                enter = fadeIn(tween(180)),
                                exit = fadeOut(tween(180)),
                            ) {
                                LazyColumn(
                                    state = conversationListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalEdgeFade(
                                        edgeFadeDp = 40f,
                                        topWeight = stw,
                                        bottomWeight = sbw,
                                    ),
                            ) {
                                items(
                                    conversationKeys,
                                    key = { it },
                                ) { key ->
                                    if (key.startsWith("section:")) {
                                        Text(
                                            stringResource(if (key == "section:pinned") {
                                                R.string.pinned_conversations
                                            } else {
                                                R.string.conversations
                                            }),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                // Extra top space separates Conversations from the Pinned group.
                                                .padding(
                                                    start = 16.dp,
                                                    end = 16.dp,
                                                    top = if (key == "section:pinned") 8.dp else 12.dp,
                                                    bottom = 8.dp,
                                                )
                                                .semantics { heading() },
                                        )
                                        return@items
                                    }
                                    val conversation = conversationsByKey.getValue(key)
                                    val isSelected = conversation.id == drawerConversationId
                                    val visibleConversationTitle = replaceCustomProviderIdsForDisplay(
                                        conversation.title,
                                        customProviders,
                                    )
                                    val isGenerating = conversation.id in generatingConversationIds
                                    val indicator = resolveDrawerConversationIndicator(
                                        isGenerating = isGenerating,
                                        isSelected = isSelected,
                                        hasUnreadGeneration = conversation.hasUnreadGeneration,
                                    )
                                    val menuEnabled = !isSwitching
                                    val deleteEnabled = menuEnabled &&
                                        conversation.id !in submittingConversationIds
                                    val unreadDescription =
                                        stringResource(R.string.conversation_unread_generation)
                                    var showMenu by remember { mutableStateOf(false) }
                                    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
                                    var lastPosition by remember {
                                        mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
                                    }
                                    val density = LocalDensity.current
                                    val selectionContainerColor by animateColorAsState(
                                        targetValue = if (isSelected) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            Color.Transparent
                                        },
                                        animationSpec = tween(durationMillis = 250),
                                        label = "drawerConversationSelectionContainer",
                                    )
                                    val selectionContentColor by animateColorAsState(
                                        targetValue = if (isSelected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        animationSpec = tween(durationMillis = 250),
                                        label = "drawerConversationSelectionContent",
                                    )
                                    val selectionIndicatorColor by animateColorAsState(
                                        targetValue = if (isSelected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        animationSpec = tween(durationMillis = 250),
                                        label = "drawerConversationSelectionIndicator",
                                    )

                                    Box(
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = null,
                                            placementSpec = if (
                                                motionPolicy.allowSpatialTransitions
                                            ) {
                                                tween(400)
                                            } else {
                                                null
                                            },
                                            fadeOutSpec = tween(180),
                                        ),
                                    ) {
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .padding(vertical = 2.dp)
                                                .clip(CircleShape)
                                                .pointerInput(showMenu) {
                                                    if (!showMenu) {
                                                        awaitPointerEventScope {
                                                            while (true) {
                                                                val event = awaitPointerEvent(
                                                                    PointerEventPass.Initial,
                                                                )
                                                                lastPosition =
                                                                    event.changes.first().position
                                                            }
                                                        }
                                                    }
                                                }
                                                .combinedClickable(
                                                    enabled = !isSwitching,
                                                    hapticFeedbackEnabled = false,
                                                    onClick = {
                                                        if (!isSelected) viewModel.selectConversation(conversation.id)
                                                        scope.launch {
                                                            onRequestClose()
                                                        }
                                                    },
                                                    onLongClick = {
                                                        haptics.longPress()
                                                        pressOffset = with(density) {
                                                            val x = lastPosition.x
                                                                .toDp()
                                                                .coerceIn(16.dp, 200.dp)
                                                            DpOffset(
                                                                x,
                                                                lastPosition.y.toDp() - 28.dp,
                                                            )
                                                        }
                                                        showMenu = true
                                                    }
                                                ),
                                            color = selectionContainerColor,
                                            shape = CircleShape
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = 16.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Crossfade(
                                                    targetState = visibleConversationTitle,
                                                    animationSpec = tween(
                                                        durationMillis = 200,
                                                        easing = FastOutSlowInEasing,
                                                    ),
                                                    modifier = Modifier.weight(1f),
                                                    label = "DrawerConversationTitleCrossfade",
                                                ) { title ->
                                                    Text(
                                                        text = title,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        color = selectionContentColor,
                                                    )
                                                }
                                                com.newoether.agora.ui.components.EinkMoreAction({ showMenu = true }, Modifier.size(40.dp))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Box(
                                                    modifier = Modifier.size(18.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    com.newoether.agora.ui.motion.AnimatedVisibility(
                                                        visible =
                                                            indicator ==
                                                                DrawerConversationIndicator.GENERATING,
                                                        enter = fadeIn(tween(200)),
                                                        exit = fadeOut(tween(200)),
                                                    ) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(18.dp),
                                                            strokeWidth = 2.dp,
                                                            color = selectionIndicatorColor,
                                                        )
                                                    }
                                                    com.newoether.agora.ui.motion.AnimatedVisibility(
                                                        visible =
                                                            indicator ==
                                                                DrawerConversationIndicator.UNREAD,
                                                        enter = fadeIn(tween(200)),
                                                        exit = fadeOut(tween(200)),
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(8.dp)
                                                                .background(
                                                                    MaterialTheme.colorScheme.primary,
                                                                    CircleShape,
                                                                )
                                                                .semantics {
                                                                    contentDescription =
                                                                        unreadDescription
                                                                },
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        AgoraDropdownMenu(
                                            containerColor =
                                                MaterialTheme.colorScheme.surfaceContainer,
                                            tonalElevation = 16.dp,
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false },
                                            offset = pressOffset
                                        ) {
                                            AgoraDropdownMenuItem(
                                                text = { Text(stringResource(if (conversation.isPinned) {
                                                    R.string.unpin_conversation
                                                } else {
                                                    R.string.pin_conversation
                                                })) },
                                                leadingIcon = {
                                                    Icon(
                                                        if (conversation.isPinned) Icons.Outlined.PushPin
                                                        else Icons.Default.PushPin,
                                                        contentDescription = null,
                                                    )
                                                },
                                                enabled = menuEnabled,
                                                onClick = {
                                                    showMenu = false
                                                    if (!conversation.isPinned) {
                                                        requestPinScroll(conversation.id)
                                                    }
                                                    viewModel.setConversationPinned(
                                                        conversation.id, !conversation.isPinned,
                                                    )
                                                },
                                            )
                                            AgoraDropdownMenuItem(
                                                text = {
                                                    Text(stringResource(R.string.generate_title))
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.Refresh,
                                                        contentDescription = null,
                                                    )
                                                },
                                                enabled = menuEnabled,
                                                onClick = {
                                                    showMenu = false
                                                    viewModel.generateTitle(conversation.id)
                                                }
                                            )
                                            AgoraDropdownMenuItem(
                                                text = { Text(stringResource(R.string.rename)) },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.Edit,
                                                        contentDescription = null,
                                                    )
                                                },
                                                enabled = menuEnabled,
                                                onClick = {
                                                    showMenu = false
                                                    onRequestRename(
                                                        conversation.id,
                                                        conversation.title,
                                                    )
                                                }
                                            )
                                            AgoraDropdownMenuItem(
                                                text = {
                                                    Text(
                                                        stringResource(R.string.delete),
                                                        color = if (deleteEnabled) {
                                                            MaterialTheme.colorScheme.error
                                                        } else {
                                                            MaterialTheme.colorScheme.error.copy(
                                                                alpha = 0.5f,
                                                            )
                                                        },
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = null,
                                                        tint = if (deleteEnabled) {
                                                            MaterialTheme.colorScheme.error
                                                        } else {
                                                            MaterialTheme.colorScheme.error.copy(
                                                                alpha = 0.5f,
                                                            )
                                                        },
                                                    )
                                                },
                                                enabled = deleteEnabled,
                                                onClick = {
                                                    showMenu = false
                                                    onRequestDelete(conversation.id)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            }

                            com.newoether.agora.ui.motion.AnimatedVisibility(
                                visible = isConversationListLoading,
                                modifier = Modifier.fillMaxSize(),
                                enter = fadeIn(tween(180)),
                                exit = fadeOut(tween(180)),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(36.dp),
                                        strokeWidth = 4.dp,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (targetResults.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Text(
                                    stringResource(R.string.search_no_results),
                                    modifier = Modifier.padding(vertical = 24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            val grouped = targetResults.groupBy { it.first.conversationId }
                            val titleMap = conversations.associate { it.id to it.title }
                            LazyColumn(
                                state = searchListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalEdgeFade(
                                        edgeFadeDp = 40f,
                                        topWeight = stw,
                                        bottomWeight = sbw,
                                    ),
                            ) {
                                items(
                                    grouped.entries.toList(),
                                    key = { "search:${it.key}" },
                                ) { (convId, entries) ->
                                    val bestScore = entries.maxOfOrNull { it.second } ?: 0f
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        SearchResultItem(
                                            title = titleMap[convId]
                                                ?: stringResource(R.string.unknown),
                                            messages = entries.map { it.first },
                                            score = bestScore,
                                            query = search.query,
                                            customProviders = customProviders,
                                            onClick = {
                                                if (convId != drawerConversationId) viewModel.selectConversation(convId)
                                                scope.launch {
                                                    onRequestClose()
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            com.newoether.agora.ui.components.EinkListNavigation(activeListState)
            FilledTonalButton(
                onClick = {
                    focusManager.clearFocus()
                    onOpenSettings()
                    scope.launch { onRequestClose() }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .onGloballyPositioned { coords ->
                        val buttonTopPx = coords.positionInWindow().y
                        onSettingsButtonTop((windowHeightPx - buttonTopPx) / density.density)
                    },
                shape = CircleShape
            ) {
                Icon(Icons.Default.Settings, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings), style = ChatType.drawerButton)
            }
        }
    }
}
