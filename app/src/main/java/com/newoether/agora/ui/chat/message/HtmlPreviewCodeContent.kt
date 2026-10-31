package com.newoether.agora.ui.chat.message

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.R
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.ui.components.DialogWindowNoSystemDim

/** Uses the ordinary code header and source leaf; only the body gains a browser alternative. */
@Composable
internal fun HtmlPreviewCodeContent(
    code: String,
    language: String?,
    eligible: Boolean,
    searching: Boolean,
    source: @Composable () -> Unit,
) {
    if (!eligible) {
        ChatCodeBlockHeader(language = language, code = code)
        source()
        return
    }
    var preview by remember(code, language) { mutableStateOf(language.equals("html-preview", true)) }
    var fullScreen by remember(code) { mutableStateOf(false) }
    var revision by remember(code) { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val export = rememberHtmlPreviewExport(code) {
        Toast.makeText(context, R.string.html_preview_export_failed, Toast.LENGTH_SHORT).show()
    }
    val showingPreview = preview && !searching
    ChatCodeBlockHeader(language = language, code = code, actions = {
        ChatCodeBlockAction(
            icon = if (showingPreview) Icons.Default.Code else Icons.Default.PlayArrow,
            label = stringResource(if (showingPreview) R.string.html_preview_source else R.string.html_preview_run),
            onClick = { preview = !showingPreview },
            enabled = !searching,
            emphasized = true,
        )
    }, trailingActions = {
        ChatCodeBlockAction(
            icon = Icons.Default.Download,
            label = stringResource(R.string.html_preview_save),
            onClick = { export(false) },
        )
        Box {
            ChatCodeBlockAction(
                icon = Icons.Default.MoreVert,
                label = stringResource(R.string.more),
                onClick = { menu = true },
            )
            AgoraDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                AgoraDropdownMenuItem(
                    text = { Text(stringResource(R.string.html_preview_reload)) },
                    leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.size(24.dp)) },
                    onClick = { menu = false; preview = true; revision++ }, enabled = !searching,
                )
                AgoraDropdownMenuItem(
                    text = { Text(stringResource(R.string.html_preview_fullscreen)) },
                    leadingIcon = { Icon(Icons.Default.Fullscreen, null, Modifier.size(24.dp)) },
                    onClick = { menu = false; fullScreen = true }, enabled = !searching,
                )
                AgoraDropdownMenuItem(
                    text = { Text(stringResource(R.string.html_preview_share)) },
                    leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(24.dp)) },
                    onClick = { menu = false; export(true) },
                )
            }
        }
    })
    if (showingPreview) {
        if (!fullScreen) key(revision) { HtmlPreviewBrowser(code, Modifier.fillMaxWidth()) }
        else Box(Modifier.fillMaxWidth().height(360.dp))
    } else source()
    if (fullScreen && !searching) {
        // A focused dialog uses the existing Chat window-focus gate to suspend covered haptics.
        // This window must not inherit the message's selection registrar from another layout root.
        DisableSelection {
            Dialog(
                onDismissRequest = { fullScreen = false },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                DialogWindowEdgeToEdge()
                DialogWindowNoSystemDim()
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
                        ChatCodeBlockSurface(modifier = Modifier.weight(1f)) {
                            ChatCodeBlockHeader(language = language, code = code, actions = {
                                ChatCodeBlockAction(
                                    icon = Icons.Default.Code,
                                    label = stringResource(R.string.html_preview_source),
                                    onClick = { fullScreen = false; preview = false },
                                    emphasized = true,
                                )
                                ChatCodeBlockAction(
                                    icon = Icons.Default.Refresh,
                                    label = stringResource(R.string.html_preview_reload),
                                    onClick = { revision++ },
                                )
                            }, trailingActions = {
                                ChatCodeBlockAction(
                                    icon = Icons.Default.Download,
                                    label = stringResource(R.string.html_preview_save),
                                    onClick = { export(false) },
                                )
                                ChatCodeBlockAction(
                                    icon = Icons.Default.Close,
                                    label = stringResource(R.string.provider_close),
                                    onClick = { fullScreen = false },
                                )
                            })
                            key(revision) {
                                HtmlPreviewBrowser(code, Modifier.weight(1f).fillMaxWidth(), fullScreen = true)
                            }
                        }
                    }
                }
            }
        }
    }
}
