package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.local.OnnxLlmProvider
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.OnnxChatModelBootstrap
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local models card for the on-device ONNX chat model (Qwen3-0.6B via onnxruntime-genai):
 * download, register it as a selectable local chat model, or remove it.
 */
@Composable
fun SettingsOnnxChatCard(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(OnnxChatModelBootstrap.isInstalled(context)) }
    var installedMb by remember {
        mutableStateOf((OnnxChatModelBootstrap.installedBytes(context) / 1024 / 1024).toInt())
    }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(false) }

    val failedMsg = stringResource(R.string.onnx_chat_failed)

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.onnx_chat_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.onnx_chat_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (installed) {
                Text(stringResource(R.string.onnx_chat_installed, installedMb), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val existing = viewModel.settings.localChatModels.value
                            if (existing.none { it.modelId == OnnxChatModelBootstrap.MODEL_ID }) {
                                viewModel.settings.saveLocalChatModels(
                                    existing + LocalChatModelConfig(
                                        modelId = OnnxChatModelBootstrap.MODEL_ID,
                                        alias = "Qwen3 0.6B (on-device)",
                                        localFilePath = OnnxChatModelBootstrap.modelDir(context).absolutePath,
                                    ),
                                )
                            }
                            added = true
                        }
                    }) { Text(stringResource(R.string.onnx_chat_add)) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            OnnxChatModelBootstrap.remove(context)
                            OnnxLlmProvider.reset()
                            viewModel.settings.saveLocalChatModels(
                                viewModel.settings.localChatModels.value
                                    .filterNot { it.modelId == OnnxChatModelBootstrap.MODEL_ID },
                            )
                            installed = false; added = false
                        }
                    }) { Text(stringResource(R.string.onnx_chat_remove)) }
                }
                if (added) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.onnx_chat_added), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Button(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                OnnxChatModelBootstrap.setup(context) { msg -> progress = msg }
                            }
                            busy = false; progress = null
                            if (ok) {
                                installed = true
                                installedMb = (OnnxChatModelBootstrap.installedBytes(context) / 1024 / 1024).toInt()
                                OnnxLlmProvider.reset()
                            } else error = failedMsg
                        }
                    },
                    enabled = !busy,
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.onnx_chat_download))
                }
                progress?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.onnx_chat_downloading, it), style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
