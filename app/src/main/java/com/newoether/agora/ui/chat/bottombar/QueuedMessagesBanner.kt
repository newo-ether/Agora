package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.QueuedSend
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text

/** One fixed-height queue row. Ordering and placement motion belong to [ComposerStatusColumn]. */
@Composable
internal fun QueuedMessageRow(
    queued: QueuedSend,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    QueuedMessageRow(queued.text, queued.attachments.size, onRemove, modifier)
}

@Composable
internal fun QueuedMessageRow(
    text: String,
    attachmentCount: Int = 0,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(QUEUED_MESSAGE_HEIGHT),
        shape = QUEUED_MESSAGE_SHAPE,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (attachmentCount > 0) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = attachmentCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(4.dp))
            }
            if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.remove),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
