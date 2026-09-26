package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Settings for the ways the assistant interrupts the user: right now the `ask_user` tool, which
 * puts a decision back to the user instead of guessing at it.
 */
@Composable
fun SettingsInteractionPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val askUserEnabled by viewModel.settings.askUserEnabled.collectAsState()
    val scrollState = rememberScrollState()
    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_interaction),
        onBack = onBack,
        scrollState = scrollState,
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.settings_interaction),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.ask_user_enable)) },
                            supportingContent = {
                                Text(stringResource(R.string.ask_user_enable_desc))
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.QuestionAnswer,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = askUserEnabled,
                                    onCheckedChange = viewModel.settings::setAskUserEnabled,
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setAskUserEnabled(!askUserEnabled)
                            },
                        )
                    }
                },
            )
        }
    }
}
