package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.viewmodel.QueuedSend
import com.newoether.agora.ui.motion.AnimatedContent

internal val QUEUED_MESSAGE_HEIGHT: Dp = 40.dp
internal val QUEUED_MESSAGE_RADIUS: Dp = 20.dp
internal val QUEUED_MESSAGE_SHAPE = RoundedCornerShape(QUEUED_MESSAGE_RADIUS)
// ChatBottomBar already has 4dp horizontal content padding. This additional 4dp makes the
// capsule concentric with the 28dp outer card: 28dp - 8dp inset = 20dp inner radius.
internal val QUEUED_MESSAGE_HORIZONTAL_INSET: Dp = 4.dp

private val STATUS_ROW_GAP = 4.dp
private const val STATUS_FADE_IN_DURATION_MS = 180
private const val STATUS_FADE_OUT_DURATION_MS = 140
private const val STATUS_SIZE_DURATION_MS = 220

/**
 * Queued sends stay in chronological order above the composer. Loop state is deliberately not
 * part of this stack: [LoopStatusBackdrop] owns its separate back layer directly behind the
 * foreground chat bottom bar.
 */
@Composable
internal fun ComposerStatusColumn(
    queuedSends: List<QueuedSend>,
    onRemoveQueuedSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = remember(queuedSends) { queuedSends.sortedBy(QueuedSend::createdAt) }
    ComposerStatusColumn(ordered, { "queue:${it.id}" }, modifier) { queued ->
        QueuedMessageRow(queued, onRemove = { onRemoveQueuedSend(queued.id) })
    }
}

@Composable
internal fun <T> ComposerStatusColumn(
    queuedSends: List<T>,
    itemKey: (T) -> String,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    AnimatedContent(
        targetState = queuedSends,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = QUEUED_MESSAGE_HORIZONTAL_INSET),
        contentAlignment = Alignment.BottomCenter,
        contentKey = { items -> items.map(itemKey) },
        transitionSpec = {
            val fade = fadeIn(
                animationSpec = tween(
                    durationMillis = STATUS_FADE_IN_DURATION_MS,
                    easing = LinearEasing,
                )
            ) togetherWith fadeOut(
                animationSpec = tween(
                    durationMillis = STATUS_FADE_OUT_DURATION_MS,
                    easing = LinearEasing,
                )
            )
            fade.using(
                SizeTransform(
                    clip = false,
                    sizeAnimationSpec = { _, _ ->
                        if (allowSpatialTransitions) {
                            tween(
                                durationMillis = STATUS_SIZE_DURATION_MS,
                                easing = FastOutSlowInEasing,
                            )
                        } else {
                            snap()
                        }
                    },
                )
            )
        },
        label = "composerStatusStack",
    ) { displayedItems ->
        Column(modifier = Modifier.fillMaxWidth()) {
            displayedItems.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(STATUS_ROW_GAP))
                key(itemKey(item)) { itemContent(item) }
            }
            if (displayedItems.isNotEmpty()) {
                Spacer(Modifier.height(STATUS_ROW_GAP))
            }
        }
    }
}
