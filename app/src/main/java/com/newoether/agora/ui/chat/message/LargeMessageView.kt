package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.Constants

/**
 * Fallback renderer for pathological oversized messages (e.g. multi-MB legacy or imported
 * rows inside a multi-million-token chat). Rich markdown parsing plus Compose layout of the
 * full text OOMs the process, so this shows a cheap plain-text preview with an explicit
 * expand affordance. The expanded view stays capped at
 * [Constants.LARGE_TEXT_EXPANDED_LIMIT] to keep a single Text composable within safe
 * measure bounds.
 */
@Composable
internal fun LargeMessageView(
    text: String,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    var expanded by remember(text.length) { mutableStateOf(false) }
    val preview = remember(text) {
        if (text.length > Constants.LARGE_TEXT_PREVIEW_LIMIT) {
            text.take(Constants.LARGE_TEXT_PREVIEW_LIMIT)
        } else text
    }
    val expandedText = remember(text) {
        if (text.length > Constants.LARGE_TEXT_EXPANDED_LIMIT) {
            text.take(Constants.LARGE_TEXT_EXPANDED_LIMIT)
        } else text
    }
    val isTruncated = text.length > Constants.LARGE_TEXT_EXPANDED_LIMIT
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(
                    R.string.message_too_large,
                    formatCharCount(text.length)
                ),
                style = ChatType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        SelectionContainer {
            Text(
                text = if (expanded) expandedText else preview,
                style = ChatType.body,
                color = textColor
            )
        }
        if (isTruncated && expanded) {
            Text(
                text = stringResource(
                    R.string.message_truncated,
                    formatCharCount(Constants.LARGE_TEXT_EXPANDED_LIMIT),
                    formatCharCount(text.length)
                ),
                style = ChatType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (text.length > Constants.LARGE_TEXT_PREVIEW_LIMIT) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) stringResource(R.string.show_less)
                    else stringResource(
                        R.string.show_full_message,
                        formatCharCount(text.length)
                    )
                )
            }
        }
    }
}

internal fun formatCharCount(chars: Int): String {
    return when {
        chars >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", chars / 1_000_000f)
        chars >= 1_000 -> String.format(java.util.Locale.US, "%.0fk", chars / 1_000f)
        else -> chars.toString()
    }
}

/** True when [text] is too large for rich markdown rendering. */
internal fun isLargeForMarkdown(text: String): Boolean =
    text.length > Constants.LARGE_TEXT_MARKDOWN_LIMIT
