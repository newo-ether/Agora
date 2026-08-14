package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType

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
    searchActive: Boolean = false,
    searchQuery: String = "",
    searchMatchIndex: Int = -1,
    searchMatchCount: Int = 0,
    conversationActionsEnabled: Boolean = false,
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

                // Title capsule: menu + title
                ChatTopBarCapsule(
                    modifier = Modifier.fillMaxHeight().widthIn(max = 260.dp)
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
                        if (showBrandTitle) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = ChatType.brandTitle.copy(
                                    brush = com.newoether.agora.ui.theme.GeminiAuroraGradient
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 180.dp)
                            )
                        } else {
                            Column(modifier = Modifier.widthIn(max = 180.dp)) {
                                Text(
                                    text = resolvedTitle,
                                    // Single-line (no token subtitle) uses a slightly-smaller-than-brand
                                    // solo size; with the token subtitle stacked below, the compact size.
                                    style = if (totalTokens > 0) ChatType.conversationTitle else ChatType.conversationTitleSolo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (totalTokens > 0) {
                                    Text(
                                        text = stringResource(R.string.total_tokens, totalTokens),
                                        style = ChatType.micro,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Actions capsule: system prompt + new chat
                ChatTopBarCapsule(
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Row(
                        modifier = Modifier.fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(5.dp))
                        IconButton(onClick = onNewChat, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat), modifier = Modifier.size(30.dp))
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
                            DropdownMenu(
                                expanded = moreMenuOpen,
                                onDismissRequest = { moreMenuOpen = false },
                                shape = RoundedCornerShape(12.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 16.dp,
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.conversation_search)) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    enabled = conversationActionsEnabled,
                                    onClick = {
                                        moreMenuOpen = false
                                        onSearchClick()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.system_prompt)) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Psychology, contentDescription = null)
                                    },
                                    onClick = {
                                        moreMenuOpen = false
                                        onSystemPromptClick()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.conversation_fork)) },
                                    leadingIcon = {
                                        Icon(Icons.Default.CallSplit, contentDescription = null)
                                    },
                                    enabled = conversationActionsEnabled,
                                    onClick = {
                                        moreMenuOpen = false
                                        onForkConversation()
                                    },
                                )
                                DropdownMenuItem(
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
            shadowElevation = 4.dp,
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
