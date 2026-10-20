package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton

@Composable
internal fun ShareSelectionFab(
    allSelected: Boolean,
    hasSelection: Boolean,
    onDismiss: () -> Unit,
    onToggleAll: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
            }
            TextButton(onClick = onToggleAll) {
                Text(
                    stringResource(
                        if (allSelected) R.string.deselect_all else R.string.select_all
                    )
                )
            }
            IconButton(
                enabled = hasSelection,
                onClick = onConfirm,
            ) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.conversation_share))
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}
