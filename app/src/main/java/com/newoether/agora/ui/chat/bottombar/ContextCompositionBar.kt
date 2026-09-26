package com.newoether.agora.ui.chat.bottombar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy

private val BarHeight = 12.dp
private val LegendDotSize = 8.dp

/** Keeps neighbouring segments readable as separate blocks instead of one continuous fill. */
private val SegmentGap = 2.dp

/** A part that holds tokens stays visible even when its share rounds down to a hairline. */
private val MinSegmentWidth = 4.dp

/*
 * Segment colors are fixed instead of read from the theme roles. A seeded Material scheme gives
 * secondary, tertiary and surfaceVariant nearly the same muted hue, so the transcript segment was
 * indistinguishable from the free budget behind it. Categorical data needs categorical colors, and
 * these three stay legible on both the light and the dark surface.
 */
private val SystemPromptColor = Color(0xFF3DD6A0)
private val ToolsColor = Color(0xFF5B9DFF)
private val MessagesColor = Color(0xFFF2C14E)

/** Menu width that keeps the bar long enough for a small segment to stay visible. */
internal val CONTEXT_MENU_WIDTH = 240.dp

/**
 * Horizontal breakdown of what currently fills the context window.
 *
 * One segment per source, laid out in the order the provider sees them: the system prompt, the tool
 * definitions, then the transcript. The tail of the window above the automatic-compact threshold is
 * reserved and sits flush against the right edge, so the gap in the middle is the budget still free
 * for the conversation to grow into. Segments are drawn as fractions of the whole bar, so their
 * widths are comparable across models with different budgets.
 */
@Composable
internal fun ContextCompositionBar(
    systemPromptTokens: Int,
    toolTokens: Int,
    messageTokens: Int,
    tokenBudget: Int,
    compactThresholdPercent: Int,
    compactEnabled: Boolean,
    overCompactThreshold: Boolean,
    modifier: Modifier = Modifier,
) {
    val budget = tokenBudget.coerceAtLeast(1)
    val system = systemPromptTokens.coerceAtLeast(0)
    val tools = toolTokens.coerceAtLeast(0)
    val messages = messageTokens.coerceAtLeast(0)
    val used = (system + tools + messages).coerceAtMost(budget)
    // Without automatic compaction nothing claims the tail of the window, so there is no reserve to
    // show and the whole remainder is free.
    val reserved = if (compactEnabled) contextReservedTokens(budget, compactThresholdPercent) else null
    val free = (budget - used - (reserved ?: 0)).coerceAtLeast(0)
    val systemColor = SystemPromptColor
    val toolColor = ToolsColor
    // The transcript is the part that grows into the compact threshold, so it carries the warning.
    val messageColor = if (overCompactThreshold) MaterialTheme.colorScheme.error else MessagesColor
    val freeColor = MaterialTheme.colorScheme.surfaceVariant
    val reservedColor = MaterialTheme.colorScheme.outline
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SegmentedBar(
            fractions = listOf(
                system.toFloat() / budget,
                tools.toFloat() / budget,
                messages.toFloat() / budget,
            ),
            colors = listOf(systemColor, toolColor, messageColor),
            trackColor = freeColor,
            reservedFraction = (reserved ?: 0).toFloat() / budget,
            reservedColor = reservedColor,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendRow(systemColor, stringResource(R.string.context_part_system), system)
            LegendRow(toolColor, stringResource(R.string.context_part_tools), tools)
            LegendRow(messageColor, stringResource(R.string.context_part_messages), messages)
            LegendRow(freeColor, stringResource(R.string.context_part_free), free)
            if (reserved != null) {
                LegendRow(reservedColor, stringResource(R.string.context_part_reserved), reserved)
            }
        }
    }
}

/**
 * The part of the window the automatic compaction keeps free. Mirrors the threshold arithmetic in
 * [contextUsageExceedsCompactThreshold] so the bar and the warning state flip at the same token.
 */
internal fun contextReservedTokens(tokenBudget: Int, thresholdPercent: Int): Int {
    val budget = tokenBudget.coerceAtLeast(1)
    val percent = thresholdPercent.coerceIn(50, 100)
    val threshold = ((budget.toLong() * percent + 99L) / 100L)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return (budget - threshold).coerceAtLeast(0)
}

@Composable
private fun SegmentedBar(
    fractions: List<Float>,
    colors: List<Color>,
    trackColor: Color,
    reservedFraction: Float,
    reservedColor: Color,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val animated = fractions.mapIndexed { index, fraction ->
        val value by animateFloatAsState(
            targetValue = fraction.coerceIn(0f, 1f),
            animationSpec = if (motionPolicy.allowContinuousMotion) tween(400) else snap(),
            label = "contextSegment$index",
        )
        value
    }
    val animatedReserved by animateFloatAsState(
        targetValue = reservedFraction.coerceIn(0f, 1f),
        animationSpec = if (motionPolicy.allowContinuousMotion) tween(400) else snap(),
        label = "contextReservedSegment",
    )
    // Drawn rather than laid out: each segment is a fraction of the whole bar, while a Row would
    // give every child only the width its predecessors left over.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BarHeight)
            .clip(RoundedCornerShape(BarHeight / 2))
            .background(trackColor)
            .drawBehind {
                val gap = SegmentGap.toPx()
                val minWidth = MinSegmentWidth.toPx()
                // Reserved first, pinned right: usage that crosses the threshold then paints over
                // it, which is exactly the state the transcript's warning color announces.
                val reservedWidth = (size.width * animatedReserved)
                    .let { if (it > 0f) it.coerceAtLeast(minWidth) else 0f }
                    .coerceAtMost(size.width)
                if (reservedWidth > 0f) {
                    drawRect(
                        color = reservedColor,
                        topLeft = Offset(size.width - reservedWidth, 0f),
                        size = Size(reservedWidth, size.height),
                    )
                }
                var start = 0f
                animated.forEachIndexed { index, fraction ->
                    if (fraction <= 0f || start >= size.width) return@forEachIndexed
                    val width = (size.width * fraction)
                        .coerceAtLeast(minWidth)
                        .coerceAtMost(size.width - start)
                    drawRect(
                        color = colors[index],
                        topLeft = Offset(start, 0f),
                        size = Size(width, size.height),
                    )
                    // The gap exposes the track between parts, which is what separates them.
                    start += width + gap
                }
            },
    )
}

@Composable
private fun LegendRow(color: Color, label: String, tokens: Int) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(LegendDotSize).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            text = ContextBudget.compactLabel(tokens),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
