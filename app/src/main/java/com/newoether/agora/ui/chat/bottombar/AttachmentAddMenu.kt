package com.newoether.agora.ui.chat.bottombar

import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.Text

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
) {
    var showAddMenu by remember { mutableStateOf(false) }
    var lastAddDismissTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(enabled) { if (!enabled) showAddMenu = false }
    fun select(action: () -> Unit) {
        showAddMenu = false
        lastAddDismissTime = 0L
        action()
    }
    // Like an editable Material anchor, preserve the IME unless touch exploration needs menu focus.
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var menuNeedsFocus by remember(accessibility) { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    DisposableEffect(accessibility) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { menuNeedsFocus = it }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    BackHandler(enabled = enabled && showAddMenu && !menuNeedsFocus) { showAddMenu = false }
    Box {
        IconButton(
            onClick = {
                val now = System.currentTimeMillis()
                if (showAddMenu) showAddMenu = false
                else if (now - lastAddDismissTime > 200) showAddMenu = true
            },
            enabled = enabled,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Default.Add,
                stringResource(R.string.add_attachment),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AgoraDropdownMenu(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            expanded = enabled && showAddMenu,
            onDismissRequest = {
                if (showAddMenu) {
                    showAddMenu = false
                    lastAddDismissTime = System.currentTimeMillis()
                }
            },
            properties = PopupProperties(focusable = menuNeedsFocus),
        ) {
            if (showCamera) AttachmentMenuItem(Icons.Default.PhotoCamera, R.string.camera) { select(onCamera) }
            AttachmentMenuItem(Icons.Default.Image, R.string.photos) { select(onPhotos) }
            if (showVideos) AttachmentMenuItem(Icons.Default.Videocam, R.string.videos) { select(onVideos) }
            AttachmentMenuItem(Icons.Default.AttachFile, R.string.files) { select(onFiles) }
        }
    }
}

@Composable
private fun AttachmentMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
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
    )
}
