package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.newoether.agora.R
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.EinkListNavigation
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.OutlinedButton
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton
import com.newoether.agora.ui.components.optionClickable
import com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
import java.io.File

/** Presentation only: the existing incoming-share owner performs every draft mutation. */
@Composable
internal fun SharedTextDestinationDialog(
    attachment: SelectedAttachment,
    conversations: List<ChatConversation>,
    choosing: Boolean,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    attachmentCount: Int = 1,
) {
    val listState = rememberLazyListState()
    val contentHeight = (LocalConfiguration.current.screenHeightDp * .62f).dp
    AlertDialog(
        onDismissRequest = { if (!choosing) onDismiss() },
        title = { Text(stringResource(R.string.shared_content_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = contentHeight)) {
                SharedExcerptSummary(attachment)
                if (attachmentCount > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.shared_content_file_count, attachmentCount), style = MaterialTheme.typography.bodyMedium)
                }
                attachment.warning?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.shared_content_destination), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { onChoose(NEW_CHAT_WORKSPACE_ID) },
                    enabled = !choosing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.shared_content_new_draft))
                }
                if (conversations.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.conversations), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = listState) {
                        items(conversations, key = ChatConversation::id) { conversation ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .optionClickable(enabled = !choosing, role = Role.Button) { onChoose(conversation.id) }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(conversation.title, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    EinkListNavigation(listState)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !choosing) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SharedExcerptSummary(attachment: SelectedAttachment) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        attachment.sourceThumbnailPath?.let { path ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(File(path)).crossfade(false).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(52.dp, 74.dp).clip(MaterialTheme.shapes.small).testTag("shared-excerpt-cover"),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val isFileName = attachment.sourceTitle == null && attachment.fileName != null
            Text(attachment.sourceTitle ?: attachment.fileName ?: stringResource(R.string.shared_content_name),
                style = MaterialTheme.typography.titleMedium,
                maxLines = if (isFileName) 1 else 3,
                overflow = if (isFileName) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis)
            attachment.sourceBookTitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            attachment.sourceAuthors?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
