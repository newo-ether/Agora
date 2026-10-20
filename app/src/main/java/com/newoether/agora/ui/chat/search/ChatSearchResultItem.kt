package com.newoether.agora.ui.chat.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text

@Composable
internal fun SearchResultItem(
    title: String,
    messages: List<MessageEntity>,
    score: Float = 0f,
    query: String,
    customProviders: List<CustomProviderConfig>,
    onClick: () -> Unit
) {
    val displayTitle = replaceCustomProviderIdsForDisplay(title, customProviders)
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    fun snippetAroundMatch(text: String, q: String, radius: Int = 20): String {
        val idx = text.lowercase().indexOf(q.lowercase())
        if (idx < 0 || text.length <= radius * 2 + q.length) return text
        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + q.length + radius).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end) + suffix
    }

    fun highlight(text: String): androidx.compose.ui.text.AnnotatedString {
        if (query.isBlank()) return androidx.compose.ui.text.AnnotatedString(text)
        return buildAnnotatedString {
            var last = 0
            val lowerText = text.lowercase()
            val lowerQuery = query.lowercase()
            var idx = lowerText.indexOf(lowerQuery, last)
            while (idx >= 0) {
                append(text.substring(last, idx))
                withStyle(SpanStyle(background = highlightColor, fontWeight = FontWeight.Bold)) {
                    append(text.substring(idx, idx + query.length))
                }
                last = idx + query.length
                idx = lowerText.indexOf(lowerQuery, last)
            }
            append(text.substring(last))
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = highlight(displayTitle),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (score > 0f) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(score * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            messages.take(2).forEach { msg ->
                val role = if (msg.participant == Participant.USER)
                    stringResource(R.string.search_role_user)
                else
                    stringResource(R.string.search_role_model)
                val displayMessageText = replaceCustomProviderIdsForDisplay(
                    msg.text,
                    customProviders,
                )
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = textColor)) { append("$role: ") }
                        withStyle(SpanStyle(color = textColor)) {
                            append(highlight(snippetAroundMatch(displayMessageText, query)))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
        }
    }
}
