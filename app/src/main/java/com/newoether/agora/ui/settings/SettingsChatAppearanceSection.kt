package com.newoether.agora.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.newoether.agora.R
import com.newoether.agora.ui.theme.contrastingOn
import com.newoether.agora.ui.theme.parseHexColor
import com.newoether.agora.viewmodel.ChatViewModel

/** Palette for bubble/background swatches (Material-ish accents). */
private val SWATCHES = listOf(
    "#2E7D32", "#1565C0", "#6A1B9A", "#AD1457", "#C62828",
    "#EF6C00", "#00838F", "#455A64", "#37474F", "#5D4037",
)

/**
 * Copy a picked image/video Uri into app-private storage; returns the chat-background spec
 * ("image:<path>" or "video:<path>") or null on failure. Type is detected from the content mime.
 */
fun copyChatBackgroundMedia(viewModel: ChatViewModel, uri: Uri): String? = try {
    val ctx = viewModel.getApplication<android.app.Application>()
    val isVideo = ctx.contentResolver.getType(uri)?.startsWith("video") == true
    val dir = java.io.File(ctx.filesDir, "appearance").apply { mkdirs() }
    // Unique filename per pick so Coil/ExoPlayer can't serve a stale file; prune olds first.
    dir.listFiles { f -> f.name.startsWith("chat_background") }?.forEach { it.delete() }
    val ext = if (isVideo) "mp4" else "img"
    val dest = java.io.File(dir, "chat_background_${System.currentTimeMillis()}.$ext")
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { input.copyTo(it) }
    }
    if (dest.isFile && dest.length() > 0) {
        (if (isVideo) "video:" else "image:") + dest.absolutePath
    } else null
} catch (_: Exception) {
    null
}

/**
 * Settings → Appearance → Chat appearance: a custom user-bubble color and a chat background
 * (none / solid color / image). Self-contained so it slots into the appearance page with one call.
 */
@Composable
fun SettingsChatAppearanceSection(viewModel: ChatViewModel) {
    val bubble by viewModel.settings.userBubbleColor.collectAsState()
    val background by viewModel.settings.chatBackground.collectAsState()

    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val spec = copyChatBackgroundMedia(viewModel, uri)
            if (spec != null) viewModel.settings.setChatBackground(spec)
        }
    }

    SettingsGroup(
        title = stringResource(R.string.appearance_chat_section),
        items = buildList {
            add {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.appearance_bubble_color), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    SwatchRow(
                        selectedHex = bubble,
                        onPick = { hex -> viewModel.settings.setUserBubbleColor(hex) },
                        onClear = { viewModel.settings.setUserBubbleColor("") },
                    )
                }
            }
            add {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(R.string.appearance_chat_background), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    SwatchRow(
                        selectedHex = background.removePrefix("color:").takeIf { background.startsWith("color:") } ?: "",
                        onPick = { hex -> viewModel.settings.setChatBackground("color:$hex") },
                        onClear = { viewModel.settings.setChatBackground("") },
                    )
                    Spacer(Modifier.height(12.dp))
                    // Prominent, hard-to-miss media picker (image OR video).
                    val hasMedia = background.startsWith("image:") || background.startsWith("video:")
                    OutlinedButton(
                        onClick = { mediaPicker.launch(arrayOf("image/*", "video/*")) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (hasMedia) stringResource(R.string.appearance_bg_change_media)
                                else stringResource(R.string.appearance_bg_pick_media),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    if (hasMedia) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.appearance_bg_clear),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clip(CircleShape)
                                .clickable { viewModel.settings.setChatBackground("") }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        BackgroundMediaConfig(viewModel, isVideo = background.startsWith("video:"))
                    }
                }
            }
        },
    )
}

@Composable
private fun BackgroundMediaConfig(viewModel: ChatViewModel, isVideo: Boolean) {
    val opacity by viewModel.settings.chatBackgroundOpacity.collectAsState()
    val muted by viewModel.settings.chatBackgroundVideoMuted.collectAsState()
    val loop by viewModel.settings.chatBackgroundVideoLoop.collectAsState()
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.appearance_bg_opacity, (opacity * 100).toInt()),
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = opacity,
        onValueChange = { viewModel.settings.setChatBackgroundOpacity(it) },
        valueRange = 0.05f..1f,
    )
    if (isVideo) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.appearance_bg_video_muted), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = muted, onCheckedChange = { viewModel.settings.setChatBackgroundVideoMuted(it) })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.appearance_bg_video_loop), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = loop, onCheckedChange = { viewModel.settings.setChatBackgroundVideoLoop(it) })
        }
    }
}

@Composable
private fun SwatchRow(selectedHex: String, onPick: (String) -> Unit, onClear: () -> Unit) {
    val selected = parseHexColor(selectedHex)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        // "Default / none" chip.
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable { onClear() },
            contentAlignment = Alignment.Center,
        ) {
            if (selected == null) Icon(Icons.Default.Check, null, Modifier.size(16.dp))
            else Icon(Icons.Default.Close, null, Modifier.size(14.dp))
        }
        SWATCHES.forEach { hex ->
            val c = parseHexColor(hex) ?: Color.Gray
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(c).clickable { onPick(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected != null && selectedHex.equals(hex, ignoreCase = true)) {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = contrastingOn(c))
                }
            }
        }
    }
}

