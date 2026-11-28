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
import com.newoether.agora.api.local.XnixMldClient
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.XnixMldBootstrap
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local models card for the flagship on-device Google Tensor G5 EdgeTPU model
 * (LFM3-Omni-2B abliterated heretic v2 via xnix-mld):
 * Download from files.foxnet.live, register it in local models, or remove it.
 */
@Composable
fun SettingsXnixMldChatCard(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(XnixMldBootstrap.isInstalled(context)) }
    var installedMb by remember {
        mutableStateOf((XnixMldBootstrap.installedBytes(context) / 1024 / 1024).toInt())
    }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Google Tensor G5 EdgeTPU Model", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "LFM3-Omni-2B (Abliterated Heretic v2). Multimodal vision & audio 100% offloaded to G5 TPU, language prefill/decode on GPU/CPU via xnix-mld.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (installed) {
                Text("Installed (${installedMb} MB)", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val existing = viewModel.settings.localChatModels.value
                            if (existing.none { it.modelId == XnixMldBootstrap.MODEL_ID }) {
                                viewModel.settings.saveLocalChatModels(
                                    existing + LocalChatModelConfig(
                                        modelId = XnixMldBootstrap.MODEL_ID,
                                        alias = XnixMldBootstrap.MODEL_ALIAS,
                                        localFilePath = XnixMldBootstrap.modelFile(context).absolutePath,
                                    ),
                                )
                            }
                            added = true
                        }
                    }) { Text("Add Model") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            XnixMldBootstrap.remove(context)
                            XnixMldClient.getInstance().unload()
                            viewModel.settings.saveLocalChatModels(
                                viewModel.settings.localChatModels.value
                                    .filterNot { it.modelId == XnixMldBootstrap.MODEL_ID },
                            )
                            installed = false
                            added = false
                        }
                    }) { Text(stringResource(R.string.onnx_chat_remove)) }
                }
                if (added) {
                    Spacer(Modifier.height(8.dp))
                    Text("Model added to Local Models", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Button(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                XnixMldBootstrap.setup(context) { msg -> progress = msg }
                            }
                            busy = false
                            progress = null
                            if (ok) {
                                installed = true
                                installedMb = (XnixMldBootstrap.installedBytes(context) / 1024 / 1024).toInt()
                            } else {
                                error = "Download failed. Check network or disk space."
                            }
                        }
                    },
                    enabled = !busy,
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Download from files.foxnet.live (~2.5 GB)")
                    }
                }
                progress?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
