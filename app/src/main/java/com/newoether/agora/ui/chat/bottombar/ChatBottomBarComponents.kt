package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.ScrollState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import com.newoether.agora.ui.motion.rememberIdentityClipWidth
import com.newoether.agora.R
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.AgoraExposedDropdownMenu
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.TextButton
import com.newoether.agora.ui.motion.animateFloatAsState
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Switch
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.motion.Crossfade

internal const val CHAT_DROPDOWN_MENU_ICON_SIZE_DP = 24

private val MODEL_SELECTOR_HEIGHT = 38.dp
private val MODEL_SELECTOR_PADDING = 8.dp
private val COMPOSER_SEND_BUTTON_GAP = 14.dp

/**
 * The same controls capsule is used by ordinary and externally owned conversations. It may grow
 * up to the send button minus a fixed gap; only the model selector inside it is flexible.
 */
@Composable
internal fun RowScope.ComposerControlGroup(content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f, fill = false).height(COMPOSER_CONTROL_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp), RoundedCornerShape(100))
            // The 32 dp end buttons stay concentric with the capsule (radius 16 + 8 = 24); the Row
            // centers the 38 dp model selector vertically (radius 19 + 5 = 24).
            .padding(horizontal = COMPOSER_CONTROL_HEIGHT / 2 - 16.dp),
        content = content,
    )
    Spacer(modifier = Modifier.width(COMPOSER_SEND_BUTTON_GAP))
}

/** The only flexible child of [ComposerControlGroup]; the space left in the group caps its width. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RowScope.ComposerModelSelector(
    displayText: String,
    isModelValid: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onDismissRequest: () -> Unit,
    enabled: Boolean = true,
    menuContent: @Composable ColumnScope.() -> Unit,
) {
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp)
    BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
        val availableWidth = if (constraints.hasBoundedWidth) maxWidth else Dp.Unspecified
        val labelMaxWidth = if (availableWidth.isSpecified) availableWidth - MODEL_SELECTOR_PADDING * 2 else Dp.Unspecified
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // The final button width, measured on its own so that no animated value ever constrains the
        // button or its label: 8 dp padding on each side, the button's minimum width, and the space
        // left in the controls group.
        val targetWidth = with(density) {
            val labelWidth = textMeasurer.measure(
                text = AnnotatedString(displayText),
                style = labelStyle,
                maxLines = 1,
                softWrap = false,
            ).size.width.toDp()
            (labelWidth + MODEL_SELECTOR_PADDING * 2)
                .coerceAtLeast(ButtonDefaults.MinWidth)
                .let { width -> if (availableWidth.isSpecified) width.coerceAtMost(availableWidth) else width }
        }
        val clipWidth = rememberIdentityClipWidth(
            identity = displayText,
            targetWidth = targetWidth,
            allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions,
        )
        ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = {}) {
            // The slot takes the clip width, so the controls after it follow the visible edge, and one
            // start-anchored rounded clip cuts the whole button, ripple included, at that edge.
            Box(
                modifier = Modifier
                    .height(MODEL_SELECTOR_HEIGHT)
                    .width(clipWidth)
                    .clip(RoundedCornerShape(50)),
            ) {
                TextButton(
                    onClick = onClick,
                    enabled = enabled,
                    modifier = Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .size(targetWidth, MODEL_SELECTOR_HEIGHT)
                        .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled),
                    contentPadding = PaddingValues(MODEL_SELECTOR_PADDING),
                ) {
                    Crossfade(
                        targetState = displayText,
                        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                        label = "composerModelSelector",
                    ) { label ->
                        // Each label keeps its own width while it fades, so an outgoing longer name is
                        // cut only by the moving clip; it ellipsizes only past the space left.
                        Text(
                            text = label,
                            style = labelStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isModelValid) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier
                                .wrapContentWidth(Alignment.Start, unbounded = true)
                                .widthIn(max = labelMaxWidth),
                        )
                    }
                }
            }
            AgoraExposedDropdownMenu(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                expanded = expanded && enabled,
                onDismissRequest = onDismissRequest,
                matchAnchorWidth = false,
                content = menuContent,
            )
        }
    }
}

@Composable
internal fun ComposerModelMenuItem(displayText: String, selected: Boolean, onClick: () -> Unit) {
    AgoraDropdownMenuItem(
        text = { Text(displayText) },
        leadingIcon = {
            if (selected) Icon(Icons.Default.Check, null, Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
            else Spacer(Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
        },
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerContextIndicator(
    estimatedTokens: Int?,
    tokenBudget: Int?,
    compactThresholdPercent: Int = 90,
    compactEnabled: Boolean = true,
    systemPromptTokens: Int = 0,
    toolTokens: Int = 0,
    showBreakdown: Boolean = true,
    expanded: Boolean,
    onClick: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val available = estimatedTokens != null && tokenBudget != null
    val overCompactThreshold = showBreakdown && estimatedTokens != null && tokenBudget != null &&
        contextUsageExceedsCompactThreshold(estimatedTokens, tokenBudget, compactThresholdPercent)
    val contextProgressColor = if (overCompactThreshold) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val contextProgressTarget = if (estimatedTokens == null || tokenBudget == null || tokenBudget <= 0) 0f
        else (estimatedTokens.toFloat() / tokenBudget).coerceIn(0f, 1f)
    val contextProgress by animateFloatAsState(
        targetValue = contextProgressTarget,
        animationSpec = if (motionPolicy.allowContinuousMotion) tween(durationMillis = 400) else snap(),
        label = "contextProgress",
    )
    val title = stringResource(R.string.context_title)
    val usage = if (estimatedTokens != null && tokenBudget != null) stringResource(
        R.string.context_usage_messages,
        ContextBudget.compactLabel(estimatedTokens),
        ContextBudget.compactLabel(tokenBudget),
    ) else stringResource(R.string.unknown)
    val usagePercent = if (estimatedTokens != null && tokenBudget != null) {
        java.text.NumberFormat.getPercentInstance().format(contextUsagePercent(estimatedTokens, tokenBudget) / 100.0)
    } else {
        null
    }
    ExposedDropdownMenuBox(expanded = expanded && available, onExpandedChange = {}) {
        IconButton(
            onClick = onClick,
            enabled = available,
            modifier = Modifier.size(32.dp)
                .semantics { contentDescription = title; stateDescription = usage }
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = available),
        ) {
            if (motionPolicy.eInkMode) {
                Canvas(Modifier.size(20.dp)) {
                    // White capacity remains visible between the two black donut boundaries.
                    val edge = 0.5.dp.toPx()
                    val band = 4.5.dp.toPx()
                    val radius = size.minDimension / 2 - edge - band / 2
                    val inset = size.minDimension / 2 - radius
                    drawCircle(Color.White, radius = radius, style = Stroke(band))
                    drawArc(
                        Color.Black,
                        startAngle = -90f, sweepAngle = 360f * if (available) contextProgress else 0f,
                        useCenter = false, topLeft = Offset(inset, inset),
                        size = Size(2 * radius, 2 * radius), style = Stroke(band),
                    )
                    drawCircle(Color.Black, radius = radius + band / 2, style = Stroke(1.dp.toPx()))
                    drawCircle(Color.Black, radius = radius - band / 2, style = Stroke(1.dp.toPx()))
                }
            } else {
                CircularProgressIndicator(
                    progress = { if (available) contextProgress else 0f },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = contextProgressColor,
                )
            }
        }
        AgoraExposedDropdownMenu(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            expanded = expanded && available,
            onDismissRequest = onDismissRequest,
            matchAnchorWidth = false,
        ) {
            Column(
                modifier = Modifier.width(CONTEXT_MENU_WIDTH).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // The usage heads the popup; "Context" stays the anchor's accessibility label.
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = usage, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    if (usagePercent != null) {
                        Spacer(Modifier.width(12.dp))
                        Text(text = usagePercent, style = MaterialTheme.typography.titleSmall)
                    }
                }
                ContextCompositionBar(
                    systemPromptTokens = systemPromptTokens,
                    toolTokens = toolTokens,
                    messageTokens = (
                        (estimatedTokens ?: 0) - systemPromptTokens - toolTokens
                        ).coerceAtLeast(0),
                    tokenBudget = tokenBudget ?: 0,
                    compactThresholdPercent = compactThresholdPercent,
                    compactEnabled = compactEnabled,
                    overCompactThreshold = overCompactThreshold,
                    showBreakdown = showBreakdown,
                )
            }
        }
    }
}

fun Modifier.verticalScrollbar(
    scrollState: ScrollState,
    color: Color,
    width: androidx.compose.ui.unit.Dp = 3.dp,
    // The track starts this far below the top edge, e.g. to stay clear of a rounded container corner.
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
): Modifier = drawWithContent {
    drawContent()
    if (scrollState.maxValue > 0) {
        val viewPortHeight = size.height
        val trackTop = topInset.toPx().coerceAtMost(viewPortHeight)
        val trackHeight = viewPortHeight - trackTop
        val totalHeight = scrollState.maxValue + viewPortHeight
        val thumbHeight = (viewPortHeight / totalHeight) * trackHeight
        val thumbOffset = trackTop + (scrollState.value / totalHeight.toFloat()) * trackHeight
        drawRoundRect(color = color, topLeft = Offset(size.width - width.toPx() - 4.dp.toPx(), thumbOffset), size = Size(width.toPx(), thumbHeight), cornerRadius = CornerRadius(width.toPx() / 2))
    }
}

@Composable
internal fun NativeSearchMenuItem(
    checked: Boolean,
    provider: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    AgoraDropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.provider_openai),
                    contentDescription = null,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.openai_search))
                Spacer(modifier = Modifier.width(10.dp))
                ProviderBadge(provider)
            }
        },
        trailingIcon = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.scale(0.7f),
            )
        },
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
    )
}

@Composable
internal fun ProviderBadge(provider: String) {
    val badgeColor = when (provider.lowercase()) {
        "google", "gemini", "openai" -> MaterialTheme.colorScheme.onPrimaryContainer
        "anthropic" -> Color(0xFFD97757)
        else -> MaterialTheme.colorScheme.primary
    }
    val badgeBackground = when (provider.lowercase()) {
        "google", "gemini", "openai" -> MaterialTheme.colorScheme.primaryContainer
        else -> badgeColor.copy(alpha = 0.15f)
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = badgeBackground
    ) {
        Text(
            provider,
            style = ChatType.micro,
            color = badgeColor,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

/**
 * Share of the whole window already used by the system prompt, tools and messages, rounded to a
 * whole percent. The compaction reserve is not usage, so it is not counted.
 */
internal fun contextUsagePercent(estimatedTokens: Int, tokenBudget: Int): Int =
    if (tokenBudget <= 0) 0 else Math.round(estimatedTokens.coerceAtLeast(0) * 100.0 / tokenBudget).toInt()

internal fun contextUsageAtCapacity(estimatedTokens: Int, tokenBudget: Int): Boolean =
    tokenBudget > 0 && estimatedTokens >= tokenBudget
