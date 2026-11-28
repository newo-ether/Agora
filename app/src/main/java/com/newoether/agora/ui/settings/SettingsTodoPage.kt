package com.newoether.agora.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

@Composable
fun SettingsTodoPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val todoEnabled by viewModel.settings.todoEnabled.collectAsState()
    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_todo),
        onBack = onBack,
        scrollState = rememberSettingsScrollState(),
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.settings_todo),
                items = listOf {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.todo_enable)) },
                        supportingContent = { Text(stringResource(R.string.todo_enable_desc)) },
                        leadingContent = {
                            Icon(
                                Icons.Rounded.Checklist,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Switch(checked = todoEnabled, onCheckedChange = null)
                        },
                        modifier = Modifier.toggleable(
                            value = todoEnabled,
                            role = Role.Switch,
                            onValueChange = viewModel.settings::setTodoEnabled,
                        ),
                    )
                },
            )
        }
    }
}
