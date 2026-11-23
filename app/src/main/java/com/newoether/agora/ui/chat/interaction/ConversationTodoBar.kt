package com.newoether.agora.ui.chat.interaction

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ConversationTodoItem
import com.newoether.agora.ui.components.SmoothBottomSheet
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private val TodoFirstLineCenter = HorizontalAlignmentLine { first, second -> minOf(first, second) }

/** Read-only progress; only the outgoing capsule retains an invalidated owner's payload. */
@Composable
internal fun ConversationTodoBar(
    conversationId: String?,
    items: List<ConversationTodoItem>,
    onHeightChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    inSharedRow: Boolean = false,
    onPresenceChanged: (Boolean) -> Unit = {},
    onMessageInsetChanged: (Float) -> Unit = {},
    appearanceReady: Boolean = true,
    animateEntry: Boolean = true,
    onTransitionChanged: (String?, Boolean) -> Unit = { _, _ -> },
) {
    val desired = (conversationId to items).takeIf { conversationId != null && items.isNotEmpty() }
    var shown by remember { mutableStateOf(desired) }
    val progress = remember { Animatable(0f) }
    val motion = LocalAgoraMotionPolicy.current
    val latestDesired by rememberUpdatedState(desired)
    val latestHeightChanged by rememberUpdatedState(onHeightChanged)
    val latestMessageInsetChanged by rememberUpdatedState(onMessageInsetChanged)
    val clearance = with(LocalDensity.current) { 8.dp.toPx() }
    val latestPresenceChanged by rememberUpdatedState(onPresenceChanged)
    val latestAppearanceReady by rememberUpdatedState(appearanceReady)
    val latestAnimateEntry by rememberUpdatedState(animateEntry)
    val latestTransitionChanged by rememberUpdatedState(onTransitionChanged)
    LaunchedEffect(Unit) {
        snapshotFlow { shown?.first to (shown != null && progress.value > 0f) }
            .collect { (owner, visible) -> latestTransitionChanged(owner, visible) }
    }
    var measuredHeight by remember { mutableFloatStateOf(0f) }
    var opened by remember(conversationId) { mutableStateOf(false) }
    val active = desired != null && shown?.first == conversationId
    val displayedItems = if (active) items else shown?.second.orEmpty()
    LaunchedEffect(conversationId, desired != null) {
        opened = false
        if (shown != null && !active) {
            progress.animateTo(0f, tween(180))
            shown = null
        }
        val target = latestDesired
        if (target != null) {
            shown = target
            snapshotFlow { latestAppearanceReady }.first { it }
            if (latestAnimateEntry) progress.animateTo(1f, tween(180)) else progress.snapTo(1f)
        }
    }
    SideEffect {
        if (active) shown = desired
        latestPresenceChanged(shown != null)
    }
    LaunchedEffect(motion.allowSpatialTransitions, active, clearance) {
        snapshotFlow {
            val height = if (shown == null) 0f else measuredHeight
            val fraction = if (motion.allowSpatialTransitions) progress.value else if (active) 1f else 0f
            height * fraction to if (height > 0f) (height + clearance) * fraction else 0f
        }.collect { (height, inset) ->
            latestHeightChanged(height)
            latestMessageInsetChanged(inset)
        }
    }
    DisposableEffect(Unit) {
        onDispose { latestHeightChanged(0f); latestMessageInsetChanged(0f); latestPresenceChanged(false) }
    }
    val title = stringResource(R.string.todo_title)
    val completed = displayedItems.count { it.status == "completed" }
    val count = stringResource(R.string.todo_progress, completed, displayedItems.size)
    if (shown != null) {
        Column(
            modifier.then(if (inSharedRow) Modifier else Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                .onSizeChanged { measuredHeight = it.height.toFloat() }
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                onClick = { if (active && appearanceReady) opened = true }, enabled = active && appearanceReady,
                shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 840.dp).height(48.dp).graphicsLayer {
                    alpha = progress.value
                    val scale = if (motion.allowSpatialTransitions) 0.96f + 0.04f * progress.value else 1f
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Checklist, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(count, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (opened && active) {
        val list = rememberLazyListState()
        SmoothBottomSheet(
            onDismissRequest = { opened = false },
            contentAtTop = { !list.canScrollBackward },
            header = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title, style = ChatType.detailTitle, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        count, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            },
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                itemsIndexed(items) { _, item ->
                    val status = when (item.status) {
                        "completed" -> R.string.todo_completed
                        "in_progress" -> R.string.todo_in_progress
                        else -> R.string.todo_pending
                    }
                    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            when (item.status) {
                                "completed" -> Icons.Rounded.CheckCircle
                                "in_progress" -> Icons.Rounded.Schedule
                                else -> Icons.Rounded.RadioButtonUnchecked
                            }, stringResource(status), Modifier.size(20.dp).alignBy { it.measuredHeight / 2 },
                            tint = if (item.status == "in_progress") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer(
                            Modifier.weight(1f).alignBy(TodoFirstLineCenter).layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                val center = textLayout?.let { (it.getLineTop(0) + it.getLineBottom(0)) / 2f } ?: 0f
                                layout(placeable.width, placeable.height,
                                    mapOf(TodoFirstLineCenter to center.roundToInt())) {
                                    placeable.placeRelative(0, 0)
                                }
                            },
                        ) {
                            Text(item.content, style = MaterialTheme.typography.bodyMedium,
                                onTextLayout = { textLayout = it })
                        }
                    }
                }
            }
        }
    }
}
