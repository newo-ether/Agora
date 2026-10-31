package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.settings.SettingsItem

/** Shared with search-method rows; owns the existing selected-value/menu layout. */
@Composable
internal fun SettingsChoiceItem(
    titleRes: Int,
    descRes: Int,
    icon: ImageVector,
    selected: String,
    choices: List<Pair<String, Int>>,
    enabled: (String) -> Boolean = { true },
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsItem(
        headlineContent = { Text(stringResource(titleRes)) },
        supportingContent = { Text(stringResource(descRes)) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Box {
                Text(
                    choices.find { it.first == selected }?.let { stringResource(it.second) }.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(96.dp).padding(end = 4.dp),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AgoraDropdownMenu(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 16.dp, expanded = expanded, onDismissRequest = { expanded = false },
                ) {
                    choices.forEach { (key, label) ->
                        val available = enabled(key)
                        val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        AgoraDropdownMenuItem(
                            text = { Text(stringResource(label), color = if (available)
                                MaterialTheme.colorScheme.onSurface else disabled) },
                            leadingIcon = {
                                if (selected == key) Icon(Icons.Default.Check, null,
                                    tint = if (available) MaterialTheme.colorScheme.primary else disabled)
                            },
                            onClick = { if (available) { onSelect(key); expanded = false } },
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable { expanded = true },
    )
}
