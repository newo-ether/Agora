package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.AgoraExposedDropdownMenu

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
internal fun AttachmentAddMenu(
    enabled: Boolean,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onVideos: () -> Unit,
    onFiles: () -> Unit,
    showCamera: Boolean = true,
    showVideos: Boolean = true,
    // The button stays usable while the source cannot accept attachments; only its rows are disabled.
    itemsEnabled: Boolean = true,
) {
    var showAddMenu by remember { mutableStateOf(false) }
    var lastAddDismissTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(enabled) { if (!enabled) showAddMenu = false }
    fun select(action: () -> Unit) {
        showAddMenu = false
        lastAddDismissTime = 0L
        action()
    }
    ExposedDropdownMenuBox(expanded = enabled && showAddMenu, onExpandedChange = {}) {
        IconButton(
            onClick = {
                val now = System.currentTimeMillis()
                if (showAddMenu) showAddMenu = false
                else if (now - lastAddDismissTime > 200) showAddMenu = true
            },
            enabled = enabled,
            modifier = Modifier.size(32.dp).menuAnchor(
                type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                enabled = enabled,
            ),
        ) {
            Icon(
                Icons.Rounded.Add,
                stringResource(R.string.add_attachment),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AgoraExposedDropdownMenu(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            expanded = enabled && showAddMenu,
            onDismissRequest = {
                if (showAddMenu) {
                    showAddMenu = false
                    lastAddDismissTime = System.currentTimeMillis()
                }
            },
            matchAnchorWidth = false,
        ) {
            if (showCamera) AttachmentMenuItem(Icons.Rounded.PhotoCamera, R.string.camera, itemsEnabled) { select(onCamera) }
            AttachmentMenuItem(Icons.Rounded.Image, R.string.photos, itemsEnabled) { select(onPhotos) }
            if (showVideos) AttachmentMenuItem(Icons.Rounded.Videocam, R.string.videos, itemsEnabled) { select(onVideos) }
            AttachmentMenuItem(Icons.Rounded.AttachFile, R.string.files, itemsEnabled) { select(onFiles) }
        }
    }
}

@Composable
private fun AttachmentMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    AgoraDropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(label))
            }
        },
        onClick = onClick,
        enabled = enabled,
    )
}
