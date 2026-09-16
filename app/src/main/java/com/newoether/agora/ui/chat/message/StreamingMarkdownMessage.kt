package com.newoether.agora.ui.chat.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.StreamingTextDelta

/**
 * The single parameterized streaming Markdown message UI.
 *
 * It intentionally owns no generation state. Ordinary answers and detail sheets supply their
 * existing content/status plus presentation variants such as typography, tail-indicator
 * visibility, and an optional empty-stream label.
 */
@Composable
internal fun StreamingMarkdownMessage(
    content: String,
    isStreaming: Boolean,
    renderContext: ChatMarkdownRenderContext,
    modifier: Modifier = Modifier,
    selectionEnabled: Boolean = !isStreaming,
    textDeltas: List<StreamingTextDelta>? = null,
    fadeTracker: StreamingTailFadeTracker = remember { StreamingTailFadeTracker() },
    emptyStreamingText: String? = null,
    emptyStreamingTextColor: Color = Color.Unspecified,
    emptyStreamingTextStyle: TextStyle = renderContext.plainTextStyle,
) {
    val hasContent = content.isNotBlank()
    // Oversized legacy/imported rows must never enter the incremental AST pipeline:
    // parsing multi-MB text allocates multiples of the input and the resulting giant
    // layout kills the process. Plain preview with an expand affordance instead.
    if (isLargeForMarkdown(content)) {
        LargeMessageView(text = content, modifier = modifier)
        return
    }
    val showEmptyState = isStreaming && !hasContent && emptyStreamingText != null
    val resolvedEmptyColor = emptyStreamingTextColor.takeUnless { it == Color.Unspecified }
        ?: MaterialTheme.colorScheme.primary

    Box(modifier = modifier) {
        if (hasContent) {
            IncrementalStreamingMarkdownContent(
                content = content,
                isStreaming = isStreaming,
                renderContext = renderContext,
                modifier = Modifier.fillMaxWidth(),
                selectionEnabled = selectionEnabled,
                textDeltas = textDeltas,
                fadeTracker = fadeTracker,
            )
        }
        AnimatedVisibility(
            visible = showEmptyState,
            enter = fadeIn(tween(durationMillis = 180, easing = LinearEasing)),
            exit = fadeOut(tween(durationMillis = 180, easing = LinearEasing)),
        ) {
            Text(
                text = emptyStreamingText.orEmpty(),
                style = emptyStreamingTextStyle,
                color = resolvedEmptyColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}
