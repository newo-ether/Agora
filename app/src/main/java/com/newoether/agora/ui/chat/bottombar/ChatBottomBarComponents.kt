package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.ScrollState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.theme.ChatType

internal const val CHAT_DROPDOWN_MENU_ICON_SIZE_DP = 24

/** The same controls capsule is used by ordinary and externally owned conversations. */
@Composable
internal fun ComposerControlGroup(content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(10.dp), RoundedCornerShape(100))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerModelSelector(
    displayText: String,
    isModelValid: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onDismissRequest: () -> Unit,
    enabled: Boolean = true,
    menuContent: @Composable ColumnScope.() -> Unit,
) {
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = {}) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.height(38.dp).widthIn(max = 160.dp)
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled),
            contentPadding = PaddingValues(8.dp),
        ) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isModelValid) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        ExposedDropdownMenu(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            expanded = expanded && enabled,
            onDismissRequest = onDismissRequest,
            matchTextFieldWidth = false,
            shape = CHAT_DROPDOWN_MENU_SHAPE,
            content = menuContent,
        )
    }
}

@Composable
internal fun ComposerModelMenuItem(displayText: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
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
    expanded: Boolean,
    onClick: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val available = estimatedTokens != null && tokenBudget != null
    val overCompactThreshold = estimatedTokens != null && tokenBudget != null &&
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
    ExposedDropdownMenuBox(expanded = expanded && available, onExpandedChange = {}) {
        IconButton(
            onClick = onClick,
            enabled = available,
            modifier = Modifier.size(32.dp)
                .semantics { contentDescription = title; stateDescription = usage }
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = available),
        ) {
            CircularProgressIndicator(
                progress = { if (available) contextProgress else 0f },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
                color = contextProgressColor,
            )
        }
        ExposedDropdownMenu(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            expanded = expanded && available,
            onDismissRequest = onDismissRequest,
            matchTextFieldWidth = false,
            shape = CHAT_DROPDOWN_MENU_SHAPE,
        ) {
            Column(
                modifier = Modifier.width(CONTEXT_MENU_WIDTH).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
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
                )
                Text(text = usage, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

fun Modifier.verticalScrollbar(
    scrollState: ScrollState,
    color: Color,
    width: androidx.compose.ui.unit.Dp = 3.dp
): Modifier = drawWithContent {
    drawContent()
    if (scrollState.maxValue > 0) {
        val viewPortHeight = size.height
        val totalHeight = scrollState.maxValue + viewPortHeight
        val thumbHeight = (viewPortHeight / totalHeight) * viewPortHeight
        val thumbOffset = (scrollState.value / totalHeight.toFloat()) * viewPortHeight
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
    DropdownMenuItem(
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

internal fun contextUsageAtCapacity(estimatedTokens: Int, tokenBudget: Int): Boolean =
    tokenBudget > 0 && estimatedTokens >= tokenBudget
