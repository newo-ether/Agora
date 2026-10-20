package com.newoether.agora.ui.chat.message

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSource
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Text

/** Alpha of the question lines and the unanswered label inside an ask_user bubble. */
internal const val ASK_USER_DIM_ALPHA = 0.6f

/** Questions sit one size below the 15 sp user body so they read apart from the answers. */
internal val ASK_USER_QUESTION_FONT_SIZE = 14.sp

/** Line height inside a wrapped question; only the question's own lines use it. */
internal val ASK_USER_QUESTION_LINE_HEIGHT = 20.sp

/**
 * The readable text of an ask_user bubble as the user sees it: question, answer, blank line
 * between groups, and the localized [unansweredLabel] for skipped questions. Copy and Select Text
 * use exactly this string, so they match what is on screen.
 */
internal fun askUserDisplayText(source: MessageSource, unansweredLabel: String): String =
    source.askUser.joinToString("\n\n") { item -> item.question + "\n" + (item.answer ?: unansweredLabel) }

/** A user message whose visible text is replaced by its localized ask_user form, if it has one. */
internal fun ChatMessage.withAskUserDisplayText(unansweredLabel: String): ChatMessage {
    val askUser = source?.takeIf { it.kind == MessageSource.Kind.ASK_USER } ?: return this
    return copy(text = askUserDisplayText(askUser, unansweredLabel))
}

/** Icon plus source name shown above an automatic user bubble. */
@Composable
internal fun MessageSourceLabel(source: MessageSource, modifier: Modifier = Modifier) {
    val (icon, label) = sourceIconAndLabel(source.kind)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.padding(bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Completion metadata is the only timeline body; the original result stays in Select Text. */
@Composable
internal fun SubagentResultSummary(result: MessageSource.SubagentResult, textColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            result.name.ifBlank { stringResource(R.string.message_source_subagent) },
            style = MaterialTheme.typography.titleSmall,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        subagentResultStatusLabel(result.status)?.let { statusLabel ->
            Text(
                stringResource(statusLabel),
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Unknown legacy statuses stay unlabeled rather than claiming a terminal outcome. */
@StringRes
internal fun subagentResultStatusLabel(status: String): Int? = when (status) {
    "completed" -> R.string.subagent_completed
    "failed" -> R.string.subagent_failed
    "stopped" -> R.string.subagent_stopped
    "running" -> R.string.subagent_running
    "idle" -> R.string.subagent_idle
    else -> null
}

internal fun messageSourceIcon(kind: MessageSource.Kind): ImageVector = when (kind) {
    MessageSource.Kind.TASK -> Icons.Default.Schedule
    MessageSource.Kind.LOOP -> Icons.Default.Repeat
    MessageSource.Kind.SUBAGENT -> Icons.Default.AccountTree
    MessageSource.Kind.ASK_USER -> Icons.Default.QuestionAnswer
}

@Composable
private fun sourceIconAndLabel(kind: MessageSource.Kind): Pair<ImageVector, String> =
    messageSourceIcon(kind) to stringResource(when (kind) {
        MessageSource.Kind.TASK -> R.string.message_source_task
        MessageSource.Kind.LOOP -> R.string.message_source_loop
        MessageSource.Kind.SUBAGENT -> R.string.message_source_subagent
        MessageSource.Kind.ASK_USER -> R.string.message_source_ask_user
    })
