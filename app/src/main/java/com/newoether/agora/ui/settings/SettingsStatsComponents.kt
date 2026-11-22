package com.newoether.agora.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator

/**
 * Inline refresh status of a statistics card: a small spinner and "Counting…". It sits inside the
 * card whose figures are being recounted instead of a page-wide progress bar.
 */
@Composable
internal fun StatsRefreshStatus(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MotionAwareCircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.stats_counting),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Alpha for figures that are shown while a newer count is still running. */
@Composable
internal fun statsStaleAlpha(stale: Boolean): Float {
    val motion = LocalAgoraMotionPolicy.current
    val alpha by animateFloatAsState(
        targetValue = if (stale) STALE_ALPHA else 1f,
        animationSpec = if (motion.allowContinuousMotion) tween(250) else snap(),
        label = "statsStaleAlpha",
    )
    return alpha
}

/** Grey block that stands in for a figure before the first count of this install finishes. */
@Composable
internal fun StatsPlaceholder(width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width, height)
            .clip(RoundedCornerShape(height / 2))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/** One figure of a [StatsCardGrid]. [value] null shows a placeholder block. */
internal data class StatsCard(
    val icon: ImageVector,
    val label: String,
    val value: String?,
    val detail: String? = null,
)

/**
 * Two-column statistics cards under a group title. Corners follow [SettingsGroup]: 24 dp on the
 * outer corners of the block, 5 dp inside, with 2 dp gaps between cards.
 */
@Composable
internal fun StatsCardGrid(
    title: String?,
    cards: List<StatsCard>,
    stale: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        val rows = cards.chunked(2)
        val alpha = statsStaleAlpha(stale)
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { column, card ->
                    if (column > 0) Spacer(Modifier.width(2.dp))
                    val top = rowIndex == 0
                    val bottom = rowIndex == rows.lastIndex
                    val start = column == 0
                    val end = column == row.lastIndex
                    val shape = RoundedCornerShape(
                        topStart = if (top && start) 24.dp else 5.dp,
                        topEnd = if (top && end) 24.dp else 5.dp,
                        bottomStart = if (bottom && start) 24.dp else 5.dp,
                        bottomEnd = if (bottom && end) 24.dp else 5.dp,
                    )
                    Surface(
                        shape = shape,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        StatsCardContent(card, Modifier.alpha(alpha))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCardContent(card: StatsCard, modifier: Modifier) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(card.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        if (card.value == null) {
            StatsPlaceholder(72.dp, 24.dp, Modifier.padding(vertical = 4.dp))
        } else {
            Text(
                card.value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            card.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (card.detail != null) {
            Text(
                card.detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Theme colors for breakdown segments, in order; later entries repeat with the container tones. */
@Composable
internal fun statsSegmentColors(): List<androidx.compose.ui.graphics.Color> {
    val scheme = MaterialTheme.colorScheme
    return listOf(
        scheme.primary,
        scheme.secondary,
        scheme.tertiary,
        scheme.outline,
        scheme.inversePrimary,
        scheme.onSecondaryContainer,
        scheme.onTertiaryContainer,
    )
}

private const val STALE_ALPHA = 0.45f
