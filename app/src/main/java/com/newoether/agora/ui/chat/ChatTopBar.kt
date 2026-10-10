package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

private const val TITLE_CAPSULE_MAX_WIDTH_DP = 260

/**
 * The chat screen's top bar: a title capsule (drawer menu + brand/conversation
 * title with optional token subtitle) and an actions capsule (system prompt +
 * new chat). Extracted from [ChatApp]; all behavior is routed through callbacks.
 */
@Composable
internal fun ChatTopBar(
    isNewChatMode: Boolean,
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
    systemPromptEnabled: Boolean = true,
    onNavigateBack: (() -> Unit)? = null,
    onOpenDrawer: () -> Unit,
    onSearchQueryChange: (String) -> Unit = {},
    onSearchPrevious: () -> Unit = {},
    onSearchNext: () -> Unit = {},
    onSearchDismiss: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onSystemPromptClick: () -> Unit,
    onForkConversation: () -> Unit = {},
    onShareConversation: () -> Unit = {},
    onNewChat: () -> Unit,
    trailingActions: (@Composable RowScope.() -> Unit)? = null,
    newChatEnabled: Boolean = true,
    newChatDescription: String? = null,
    moreMenuContent: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
) {
    var moreMenuOpen by remember { mutableStateOf(false) }
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
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 180.dp)
            // An expanded composer fills the screen and paints a background gradient across it, and
            // the Scaffold draws the bottom bar after the top one, so that fill would otherwise lie
            // over this bar's title and action capsules. The composer's own layer sits at 1f.
            .zIndex(1.5f)
            .background(
                Brush.verticalGradient(
                    0.0f to MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                    0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.80f),
                    1.0f to Color.Transparent
                )
            )
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
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                singleLine = true,
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
                // Resolve the active conversation's title; null in new-chat mode OR
                // before the conversation/title has loaded. Both the brand TEXT and the
                // brand font SIZE are gated on this single value, so the title never
                // changes size before the text swaps (no transient "Agora at 17sp").
                val resolvedTitle = if (isNewChatMode) null else {
                    currentConversationTitle?.takeIf { it.isNotBlank() }
                        ?: conversations.find { it.id == currentConversationId }?.title?.takeIf { it.isNotBlank() }
                }
                val showBrandTitle = resolvedTitle == null
                val appName = stringResource(R.string.app_name)
                val titlePresentation = if (showBrandTitle) {
                    Triple(true, null, null)
                } else {
                    Triple(false, currentConversationId, resolvedTitle)
                }
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
                val targetTitleCapsuleWidth = minOf(
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
                            .width(TITLE_CAPSULE_MAX_WIDTH_DP.dp)
                            .graphicsLayer {
                                shape = titleCapsuleClipShape
                                clip = true
                                shadowElevation = 4.dp.toPx()
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
                                    modifier = Modifier.size(26.dp),
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
                            ) { presentation ->
                                if (presentation.first) {
                                    Text(
                                        text = appName,
                                        style = ChatType.brandTitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(end = 20.dp).widthIn(max = 180.dp)
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier.padding(end = 20.dp).widthIn(max = 180.dp)
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
                                onDismissRequest = { moreMenuOpen = false },
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
