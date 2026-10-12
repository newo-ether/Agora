package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.motion.AnimatedVisibility

/** Shared composer drawing; callers own drafts, attachment work and submission. */
@Composable
internal fun ChatComposerLayout(
    textFieldState: TextFieldState,
    focusRequester: FocusRequester,
    onInputFocusChanged: (Boolean) -> Unit,
    isExpanded: Boolean,
    isExpandAnimating: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    statusContent: @Composable () -> Unit = {},
    attachmentContent: @Composable () -> Unit = {},
    controls: @Composable RowScope.() -> Unit,
) {
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val composerOcclusionColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    val composerOcclusionShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    Box(modifier = modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier).padding(start = COMPOSER_HOST_SIDE_PADDING, end = COMPOSER_HOST_SIDE_PADDING, top = COMPOSER_HOST_TOP_PADDING, bottom = COMPOSER_CONTROLS_INSET)) {
        Column(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)) {
            com.newoether.agora.ui.motion.AnimatedVisibility(
                visible = isExpanded,
                enter = EnterTransition.None,
                exit = if (allowSpatialTransitions) {
                    shrinkVertically(tween(250)) + fadeOut(tween(250))
                } else {
                    fadeOut(tween(250))
                },
            ) {
                Spacer(modifier = Modifier.height(44.dp))
            }
            statusContent()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.weight(1f) else Modifier)
                    .then(
                        if (allowSpatialTransitions) {
                            Modifier.animateContentSize(
                                animationSpec = tween(durationMillis = 400),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(composerOcclusionShape)
                    .background(composerOcclusionColor)
                    .zIndex(1f),
            ) {
        attachmentContent()

        Box(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.weight(1f) else Modifier).noOpBringIntoView()) {
            TextField(
                state = textFieldState,
                scrollState = scrollState,
                modifier = Modifier
                    // Any non-zero minimum replaces Material's 56 dp TextField minimum, which
                    // otherwise adds empty space under a single line; the content sets the height.
                    .heightIn(min = TEXT_FIELD_MIN_HEIGHT)
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                    .then(inputModifier)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        onInputFocusChanged(focusState.isFocused)
                    }
                    // The thumb starts level with the expand icon's top, below the 28 dp corner.
                    .verticalScrollbar(
                        scrollState,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        topInset = COMPOSER_CORNER_CONTENT_INSET - COMPOSER_HOST_TOP_PADDING,
                    ),
                placeholder = {
                    Text(
                        stringResource(R.string.ask_agora),
                        style = ChatType.input,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                lineLimits = TextFieldLineLimits.MultiLine(1, if (isExpanded) Int.MAX_VALUE else 6),
                contentPadding = PaddingValues(
                    start = COMPOSER_TEXT_START_INSET - COMPOSER_HOST_SIDE_PADDING,
                    top = COMPOSER_TEXT_TOP_INSET - COMPOSER_HOST_TOP_PADDING,
                    end = 16.dp,
                    bottom = COMPOSER_TEXT_CONTROLS_GAP - CONTROLS_ROW_TOP_PADDING,
                ),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                textStyle = ChatType.input.copy(color = MaterialTheme.colorScheme.onSurface)
            )
            com.newoether.agora.ui.motion.AnimatedVisibility(
                visible = !isExpanded,
                enter = fadeIn(tween(250)),
                exit = ExitTransition.None,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                IconButton(onClick = { if (!isExpandAnimating) onExpand() }, modifier = Modifier.offset(x = COMPOSER_HOST_SIDE_PADDING - EXPAND_BUTTON_EDGE_INSET, y = EXPAND_BUTTON_EDGE_INSET - COMPOSER_HOST_TOP_PADDING).size(COMPOSER_EXPAND_BUTTON_SIZE).background(Brush.radialGradient(listOf(elevatedSurface, elevatedSurface.copy(alpha = 0.5f), Color.Transparent)), CircleShape)) { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.expand_all_24px), contentDescription = stringResource(R.string.expand), modifier = Modifier.size(COMPOSER_EXPAND_ICON_SIZE), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)) }
            }
        }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = CONTROLS_ROW_TOP_PADDING, start = COMPOSER_CONTROLS_INSET - COMPOSER_HOST_SIDE_PADDING, end = COMPOSER_CONTROLS_INSET - COMPOSER_HOST_SIDE_PADDING), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            controls()
        }
        }
        com.newoether.agora.ui.motion.AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp)
        ) {
            val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            IconButton(onClick = { if (!isExpandAnimating) onCollapse() }, modifier = Modifier.size(40.dp).background(Brush.radialGradient(listOf(elevatedSurface, elevatedSurface.copy(alpha = 0.5f), Color.Transparent)), CircleShape)) { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.collapse_all_24px), contentDescription = stringResource(R.string.collapse), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)) }
        }
    }

}

// The expand button's edge offset that puts its centered icon at the corner content inset.
private val EXPAND_BUTTON_EDGE_INSET = COMPOSER_CORNER_CONTENT_INSET - (COMPOSER_EXPAND_BUTTON_SIZE - COMPOSER_EXPAND_ICON_SIZE) / 2

// The text ends COMPOSER_TEXT_CONTROLS_GAP above the controls: its bottom padding + this gap.
private val CONTROLS_ROW_TOP_PADDING = 6.dp
private val TEXT_FIELD_MIN_HEIGHT = 1.dp
