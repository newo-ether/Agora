package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.local.LoopEntity
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import com.newoether.agora.ui.motion.AnimatedContent
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text

private const val LOOP_RISE_DURATION_MS = 300
private const val LOOP_FADE_IN_DURATION_MS = 180
private const val LOOP_FADE_OUT_DURATION_MS = 140
private const val NO_LOOP_CONTENT_KEY = "no-loop"
private val LOOP_VISIBLE_HEIGHT = 38.dp
private val LOOP_CONTENT_HEIGHT = 44.dp
private val LOOP_BACKDROP_HEIGHT = 72.dp

/**
 * Gives the active loop its own layer immediately behind the foreground chat bottom bar.
 *
 * The backdrop uses the exact width and 28dp shape of the outer chat bottom bar. Its 64dp total
 * height exceeds the 32dp exposed rise plus the 28dp corner radius; the foreground card therefore
 * fully covers the backdrop's lower corners. When spatial motion is allowed the card enters and
 * leaves through that covered edge; Reduce Motion keeps only the opacity transition and snaps the
 * layout change.
 */
@Composable
internal fun LoopStatusBackdrop(
    loop: LoopEntity?,
    isRunning: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions

    AnimatedContent(
        targetState = loop,
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
        contentKey = { it?.conversationId ?: NO_LOOP_CONTENT_KEY },
        transitionSpec = {
            val enter = if (allowSpatialTransitions) {
                slideInVertically(
                    animationSpec = tween(
                        durationMillis = LOOP_RISE_DURATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                    initialOffsetY = { fullHeight -> fullHeight },
                )
            } else {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = LOOP_FADE_IN_DURATION_MS,
                        easing = LinearEasing,
                    )
                )
            }
            val exit = if (allowSpatialTransitions) {
                slideOutVertically(
                    animationSpec = tween(
                        durationMillis = LOOP_RISE_DURATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                    targetOffsetY = { fullHeight -> fullHeight },
                )
            } else {
                fadeOut(
                    animationSpec = tween(
                        durationMillis = LOOP_FADE_OUT_DURATION_MS,
                        easing = LinearEasing,
                    )
                )
            }

            (enter togetherWith exit).using(
                SizeTransform(
                    clip = false,
                    sizeAnimationSpec = { _, _ ->
                        if (allowSpatialTransitions) {
                            tween(
                                durationMillis = LOOP_RISE_DURATION_MS,
                                easing = FastOutSlowInEasing,
                            )
                        } else {
                            snap()
                        }
                    },
                )
            )
        },
        label = "loopStatusBackdrop",
    ) { displayedLoop ->
        if (displayedLoop != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LOOP_VISIBLE_HEIGHT),
                contentAlignment = Alignment.TopCenter,
            ) {
                LoopControlBar(
                    loop = displayedLoop,
                    isRunning = isRunning,
                    onStop = onStop,
                    modifier = Modifier.wrapContentHeight(
                        align = Alignment.Top,
                        unbounded = true,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun LoopControlBar(
    loop: LoopEntity,
    isRunning: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember(loop.conversationId, loop.nextFireAt) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(loop.conversationId, loop.nextFireAt, isRunning) {
        while (isActive && !isRunning) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val remainingMs = (loop.nextFireAt - now).coerceAtLeast(0L)
    val remainingText = remember(remainingMs / 1_000L) { formatRemaining(remainingMs) }
    val status = if (isRunning) {
        stringResource(R.string.loop_running)
    } else {
        stringResource(R.string.loop_next_in, remainingText)
    }
    val cycle = loop.maxCycles?.let {
        stringResource(R.string.loop_cycle, loop.cycleCount, it)
    }

    val loopBarShape = RoundedCornerShape(
        topStart = 28.dp,
        topEnd = 28.dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    )
    val loopBarBrush = Brush.verticalGradient(
        0.00f to MaterialTheme.colorScheme.secondaryContainer,
        0.85f to MaterialTheme.colorScheme.secondaryContainer,
        0.92f to Color.Transparent,
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(LOOP_BACKDROP_HEIGHT)
            .background(brush = loopBarBrush, shape = loopBarShape),
        shape = loopBarShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LOOP_CONTENT_HEIGHT)
                    .align(Alignment.TopCenter)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = listOfNotNull(status, cycle).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.StopCircle,
                        contentDescription = stringResource(R.string.loop_stop),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private fun formatRemaining(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
