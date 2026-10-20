package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.modelDisplayName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton

/** Read-only message metadata (timestamp + resolved model name) shown from the overflow menu. */
@Composable
internal fun MessageInfoDialog(
    message: ChatMessage,
    modelAliases: Map<String, String>,
    showProviderName: Boolean,
    customProviders: List<CustomProviderConfig> = emptyList(),
    onDismiss: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val dateString = sdf.format(Date(message.timestamp))
    val modelDisplay = if (message.modelName != null) {
        modelDisplayName(message.modelName, modelAliases, customProviders, showProviderName)
    } else stringResource(R.string.unknown)
    val tokenUsage = tokenUsagePresentation(message.tokenUsage)
    val inputTokens = when {
        tokenUsage.input == null -> "—"
        tokenUsage.cachedInput != null -> stringResource(
            R.string.token_count_with_cached,
            tokenUsage.input,
            tokenUsage.cachedInput,
        )
        else -> stringResource(R.string.token_count, tokenUsage.input)
    }
    val generationSpeed = tokenUsage.generationTokensPerSecond?.let {
        String.format(Locale.getDefault(), "~%.1f token/s", it)
    } ?: "—"
    val outputTokens = tokenUsage.output?.let {
        stringResource(R.string.token_count, it)
    } ?: "—"

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_info), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(stringResource(R.string.time_with_label, dateString), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
                if (message.participant == Participant.MODEL) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.model_with_label, modelDisplay), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.input_tokens_with_label,
                            inputTokens,
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.output_tokens_with_label,
                            outputTokens,
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.generation_speed_with_label, generationSpeed),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_close)) }
        }
    )
}

/** Specialized confirmation: Compact deletion reparents descendants and removes only the pill. */
@Composable
internal fun ContextCompactDeleteDialog(
    enabled: Boolean = true,
    pending: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { if (!pending) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !pending,
            dismissOnClickOutside = !pending,
        ),
        title = {
            Text(
                stringResource(R.string.delete_compact_message_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = { Text(stringResource(R.string.delete_compact_message_confirm)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = enabled && !pending,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                if (pending) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 3.dp)
                } else {
                    Text(stringResource(R.string.delete))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled && !pending) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Destructive confirmation for deleting a single message (and its subtree). */
@Composable
internal fun MessageDeleteDialog(
    deletesConversation: Boolean = false,
    enabled: Boolean = true,
    pending: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { if (!pending) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !pending,
            dismissOnClickOutside = !pending,
        ),
        title = {
            Text(
                stringResource(
                    if (deletesConversation) R.string.delete_conversation_title
                    else R.string.delete_message_title,
                ),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                stringResource(
                    if (deletesConversation) R.string.delete_conversation_from_message_confirm
                    else R.string.delete_message_confirm,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = enabled && !pending,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                if (pending) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 3.dp)
                } else {
                    Text(stringResource(R.string.delete))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled && !pending) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
