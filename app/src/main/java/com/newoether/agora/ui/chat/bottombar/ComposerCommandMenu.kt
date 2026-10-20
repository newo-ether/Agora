package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import com.newoether.agora.ui.components.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.components.EinkListNavigation
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy

/** Presentation shared by local library entries and the selected native Remote session. */
internal data class ComposerCommand(val key: String, val label: String, val description: String, val input: String)

internal fun conciseCommandDescription(description: String): String {
    val words = description.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return words.take(5).joinToString(" ") + if (words.size > 5) "…" else ""
}

@Composable
internal fun ComposerCommandMenu(entries: List<ComposerCommand>, enabled: Boolean, emptyLabel: String,
    canSelect: (ComposerCommand) -> Boolean = { true }, onSelect: (ComposerCommand) -> Unit) {
    val eInk = LocalAgoraMotionPolicy.current.eInkMode
    val background = if (eInk) Color.White else MaterialTheme.colorScheme.surface
    val foreground = if (eInk) Color.Black else MaterialTheme.colorScheme.onSurface
    val detail = if (eInk) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
    val list = rememberLazyListState()
    Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), color = background,
        contentColor = foreground, border = BorderStroke(1.dp, if (eInk) Color.Black else MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.small) {
        Column {
            if (entries.isEmpty()) Text(emptyLabel, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            else {
                LazyColumn(state = list, modifier = Modifier.heightIn(max = 240.dp)) {
                    items(entries, key = { it.key }) { entry ->
                        Surface(onClick = { onSelect(entry) }, enabled = enabled && canSelect(entry), color = background, contentColor = foreground) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Icon(Icons.Default.Extension, contentDescription = null,
                                    modifier = Modifier.padding(end = 12.dp).size(24.dp),
                                    tint = if (eInk) Color.Black else MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(entry.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (entry.description.isNotBlank()) Text(conciseCommandDescription(entry.description),
                                        style = MaterialTheme.typography.bodySmall, color = detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (list.canScrollBackward || list.canScrollForward) EinkListNavigation(list)
            }
        }
    }
}
