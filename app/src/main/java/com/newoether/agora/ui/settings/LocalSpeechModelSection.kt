package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.speech.LocalSpeechModel
import com.newoether.agora.speech.LocalSpeechModelStatus
import com.newoether.agora.speech.LocalSpeechModelStore
import com.newoether.agora.speech.LocalSpeechModels
import com.newoether.agora.speech.LocalSpeechSource
import com.newoether.agora.speech.formatSize
import com.newoether.agora.ui.motion.MotionAwareLinearProgressIndicator
import kotlinx.coroutines.launch

@Composable
private fun sourceLabel(source: LocalSpeechSource): String = when (source) {
    LocalSpeechSource.HF_MIRROR -> stringResource(R.string.speech_source_hf_mirror)
    LocalSpeechSource.HUGGING_FACE -> "Hugging Face"
}

/**
 * Model picker, download host and the download/delete row for an on-device recognizer. Downloads
 * run in [store]'s scope, so leaving the page does not stop them.
 */
@Composable
internal fun LocalSpeechModelGroup(
    store: LocalSpeechModelStore,
    model: LocalSpeechModel,
    source: LocalSpeechSource,
    onModelChange: (LocalSpeechModel) -> Unit,
    onSourceChange: (LocalSpeechSource) -> Unit,
) {
    val statuses by store.status.collectAsState()
    val status = statuses[model.id] ?: LocalSpeechModelStatus.Missing
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val busy = status is LocalSpeechModelStatus.Downloading || status is LocalSpeechModelStatus.Verifying

    SettingsGroup(
        title = stringResource(R.string.speech_local_model_group),
        items = listOf(
            {
                SpeechDropdownItem(
                    title = stringResource(R.string.speech_local_model),
                    description = stringResource(R.string.speech_local_model_desc),
                    icon = Icons.Rounded.Memory,
                    selectedLabel = model.displayName,
                    options = LocalSpeechModels.all.map { it.id to "${it.displayName} · ${formatSize(it.totalBytes)}" },
                    selectedKey = model.id,
                    onSelect = { id -> LocalSpeechModels.byId(id)?.let(onModelChange) },
                )
            },
            {
                SpeechDropdownItem(
                    title = stringResource(R.string.speech_download_source),
                    description = stringResource(R.string.speech_download_source_desc),
                    icon = Icons.Rounded.Public,
                    selectedLabel = sourceLabel(source),
                    options = LocalSpeechSource.entries.map { it.id to sourceLabel(it) },
                    selectedKey = source.id,
                    onSelect = { id -> onSourceChange(LocalSpeechSource.fromId(id)) },
                )
            },
            {
                LocalModelStatusItem(
                    model = model,
                    status = status,
                    onDownload = { store.download(model, source) },
                    onCancel = { store.cancel(model) },
                    onDelete = { confirmDelete = true },
                    busy = busy,
                )
            },
        ),
    )

    if (confirmDelete) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.speech_model_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.speech_model_delete_confirm, model.displayName, formatSize(model.totalBytes))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { store.delete(model) }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun LocalModelStatusItem(
    model: LocalSpeechModel,
    status: LocalSpeechModelStatus,
    busy: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val size = formatSize(model.totalBytes)
    SettingsItem(
        headlineContent = {
            Text(
                when (status) {
                    LocalSpeechModelStatus.Installed -> stringResource(R.string.speech_model_installed)
                    is LocalSpeechModelStatus.Failed -> stringResource(R.string.speech_model_failed)
                    LocalSpeechModelStatus.Verifying -> stringResource(R.string.speech_model_verifying)
                    is LocalSpeechModelStatus.Downloading -> stringResource(R.string.speech_model_downloading)
                    LocalSpeechModelStatus.Missing -> stringResource(R.string.speech_model_missing)
                },
            )
        },
        supportingContent = {
            Column {
                when (status) {
                    is LocalSpeechModelStatus.Downloading -> {
                        val fraction = if (status.total > 0) status.downloaded.toFloat() / status.total else 0f
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(4.dp),
                        )
                        Text("${formatSize(status.downloaded)} / $size · ${(fraction * 100).toInt()}%")
                    }
                    LocalSpeechModelStatus.Verifying -> {
                        MotionAwareLinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(4.dp),
                        )
                        Text(size)
                    }
                    is LocalSpeechModelStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
                    LocalSpeechModelStatus.Installed -> Text(stringResource(R.string.speech_model_installed_desc, size))
                    LocalSpeechModelStatus.Missing -> Text(stringResource(R.string.speech_model_missing_desc, size))
                }
            }
        },
        leadingContent = {
            Icon(
                when (status) {
                    LocalSpeechModelStatus.Installed -> Icons.Rounded.DownloadDone
                    is LocalSpeechModelStatus.Failed -> Icons.Rounded.ErrorOutline
                    else -> Icons.Rounded.CloudDownload
                },
                null,
                tint = if (status is LocalSpeechModelStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    status is LocalSpeechModelStatus.Downloading -> TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel))
                    }
                    status == LocalSpeechModelStatus.Installed -> IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.speech_model_delete_title), tint = MaterialTheme.colorScheme.error)
                    }
                    !busy -> {
                        Spacer(Modifier.width(4.dp))
                        SandboxActionButton(onClick = onDownload, enabled = true) {
                            Text(stringResource(R.string.speech_model_download))
                        }
                    }
                }
            }
        },
    )
}
