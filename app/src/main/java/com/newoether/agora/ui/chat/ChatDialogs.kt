package com.newoether.agora.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.R
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.ui.components.SystemPromptPickerDialog
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.viewmodel.ChatViewModel

/** Rename-conversation dialog. Owns its own editable text, seeded from [initialName]. */
@Composable
internal fun ChatRenameDialog(
    initialName: String,
    initialDisplayName: String = initialName,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initialName, initialDisplayName) { mutableStateOf(initialDisplayName) }
    var edited by remember(initialName, initialDisplayName) { mutableStateOf(false) }
    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_chat), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    edited = true
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(if (edited) name else initialName) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** Delete-conversation confirmation and blocker for the entire pending operation. */
@Composable
internal fun ChatDeleteConfirmDialog(
    phase: ChatDeleteDialogPhase,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    message: String? = null,
    confirmLabel: String? = null,
) {
    val pending = phase == ChatDeleteDialogPhase.PENDING
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = !pending,
            dismissOnClickOutside = !pending,
        ),
        title = { Text(title ?: stringResource(R.string.delete_chat), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(message ?: stringResource(R.string.delete_chat_confirm))
                if (phase == ChatDeleteDialogPhase.FAILED) {
                    Text(
                        text = stringResource(R.string.tool_state_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !pending,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                if (pending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 3.dp,
                    )
                } else {
                    Text(
                        confirmLabel ?: stringResource(
                            if (phase == ChatDeleteDialogPhase.FAILED) {
                                R.string.retry
                            } else {
                                R.string.delete
                            },
                        ),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !pending) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

internal data class ForkConversationRequest(val messageId: String?)

@Composable
internal fun ChatForkConfirmationHost(
    request: ForkConversationRequest?,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val activeRequest = request ?: return
    ChatForkConfirmDialog(
        fromMessage = activeRequest.messageId != null,
        onConfirm = {
            onDismiss()
            if (activeRequest.messageId == null) {
                viewModel.forkConversationFrom()
            } else {
                viewModel.forkConversationFrom(activeRequest.messageId)
            }
        },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun ChatForkConfirmDialog(
    fromMessage: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (fromMessage) {
                        R.string.conversation_fork_from_here
                    } else {
                        R.string.conversation_fork
                    },
                ),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                stringResource(
                    if (fromMessage) {
                        R.string.conversation_fork_from_here_confirm
                    } else {
                        R.string.conversation_fork_confirm
                    },
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(stringResource(R.string.conversation_fork_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Per-conversation system-prompt selector: the shared picker bound to this conversation. */
@Composable
internal fun ChatSystemPromptDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val isNewChatMode by viewModel.isNewChatMode.collectAsState()
    val currentConversation = conversations.orEmpty().find { it.id == currentConversationId }
    val pendingPrompt by viewModel.pendingSystemPromptId.collectAsState()

    SystemPromptPickerDialog(
        settings = viewModel.settings,
        initialSelectedId = if (isNewChatMode) pendingPrompt else currentConversation?.systemPromptId,
        selectionKey = listOf(isNewChatMode, currentConversationId, pendingPrompt, currentConversation?.systemPromptId),
        onSave = { selectedPromptId ->
            if (isNewChatMode) {
                viewModel.setPendingSystemPrompt(selectedPromptId)
            } else {
                currentConversationId?.let { id ->
                    viewModel.setConversationSystemPrompt(id, selectedPromptId)
                }
            }
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/**
 * Advanced (per-conversation generation params) dialog wrapper: resolves the active
 * conversation's overrides + global defaults, then defers to [AdvancedSettingsDialog].
 */
@Composable
internal fun ChatAdvancedSettingsDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val isNewChatMode by viewModel.isNewChatMode.collectAsState()
    val conversationSettings by viewModel.settings.conversationSettings.collectAsState()
    val pendingConversationSettings by viewModel.pendingConversationSettings.collectAsState()
    val maxContextWindow by viewModel.settings.maxContextWindow.collectAsState()
    val defaultTemperature by viewModel.settings.defaultTemperature.collectAsState()
    val defaultMaxTokens by viewModel.settings.defaultMaxTokens.collectAsState()
    val defaultTopP by viewModel.settings.defaultTopP.collectAsState()
    val defaultFrequencyPenalty by viewModel.settings.defaultFrequencyPenalty.collectAsState()
    val defaultPresencePenalty by viewModel.settings.defaultPresencePenalty.collectAsState()

    val currentId = conversationSettingsOwnerId(isNewChatMode, currentConversationId)
    val overrides = if (isNewChatMode) {
        pendingConversationSettings ?: ConversationSettings()
    } else {
        currentId?.let(conversationSettings::get) ?: ConversationSettings()
    }
    val defaults = ConversationSettings(
        contextWindow = maxContextWindow,
        temperature = defaultTemperature,
        maxTokens = defaultMaxTokens,
        topP = defaultTopP,
        frequencyPenalty = defaultFrequencyPenalty,
        presencePenalty = defaultPresencePenalty
    )
    AdvancedSettingsDialog(
        overrides = overrides,
        globalDefaults = defaults,
        onSave = { settings ->
            viewModel.setConversationSettings(currentId, settings)
            onDismiss()
        },
        onResetToDefaults = {
            viewModel.setConversationSettings(currentId, null)
        },
        onDismiss = onDismiss
    )
}
