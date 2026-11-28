package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.newoether.agora.R
import com.newoether.agora.fileserver.FileServerService

/** Settings > File Server: pick a folder, port, HTTPS, PIN, and start/stop the LAN server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsFileServerPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("file_server", 0) }

    var isRunning by remember { mutableStateOf(FileServerService.isRunning()) }
    var rootPath by remember { mutableStateOf(prefs.getString("root_path", "") ?: "") }
    var port by remember {
        mutableStateOf(prefs.getInt("port", FileServerService.DEFAULT_PORT).toString())
    }
    var useHttps by remember { mutableStateOf(prefs.getBoolean("use_https", false)) }
    var pinCode by remember { mutableStateOf(prefs.getString("pin", "") ?: "") }
    var serverUrl by remember { mutableStateOf(FileServerService.currentUrl()) }

    // The service computes the real LAN URL in onStartCommand; poll until it publishes it so the
    // Copy button never shows a placeholder.
    LaunchedEffect(isRunning) {
        if (isRunning) {
            repeat(20) {
                val url = FileServerService.currentUrl()
                if (url != null) {
                    serverUrl = url
                    return@LaunchedEffect
                }
                delay(250)
            }
        }
    }

    // A folder Agora provably owns under scoped storage (writable with no permission).
    val agoraStoragePath = remember {
        (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath
    }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            treeUriToPath(it)?.let { path ->
                rootPath = path
                prefs.edit().putString("root_path", path).apply()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text(stringResource(R.string.fileserver_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (isRunning && serverUrl != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.fileserver_running),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(serverUrl!!, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(serverUrl!!))
                            Toast.makeText(
                                context,
                                context.getString(R.string.fileserver_url_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }) { Text(stringResource(R.string.fileserver_copy_url)) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                stringResource(R.string.fileserver_shared_directory),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = rootPath,
                onValueChange = {
                    rootPath = it
                    prefs.edit().putString("root_path", it).apply()
                },
                label = { Text(stringResource(R.string.fileserver_path)) },
                placeholder = { Text(stringResource(R.string.fileserver_path_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    rootPath = agoraStoragePath
                    prefs.edit().putString("root_path", agoraStoragePath).apply()
                }) {
                    Text(stringResource(R.string.fileserver_use_agora_storage))
                }
                OutlinedButton(onClick = { dirPicker.launch(null) }) {
                    Text(stringResource(R.string.fileserver_browse))
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.fileserver_port), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = port,
                onValueChange = {
                    port = it.filter { c -> c.isDigit() }.take(5)
                    port.toIntOrNull()?.let { p -> prefs.edit().putInt("port", p).apply() }
                },
                label = { Text(stringResource(R.string.fileserver_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp),
                singleLine = true,
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fileserver_https), modifier = Modifier.weight(1f))
                Switch(checked = useHttps, onCheckedChange = {
                    useHttps = it
                    prefs.edit().putBoolean("use_https", it).apply()
                })
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.fileserver_pin), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = pinCode,
                onValueChange = {
                    pinCode = it.filter { c -> c.isDigit() }.take(8)
                    prefs.edit().putString("pin", pinCode).apply()
                },
                label = { Text(stringResource(R.string.fileserver_pin)) },
                placeholder = { Text(stringResource(R.string.fileserver_pin_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.width(220.dp),
                singleLine = true,
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (isRunning) {
                        FileServerService.stop(context)
                        isRunning = false
                        serverUrl = null
                    } else {
                        if (rootPath.isBlank()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.fileserver_select_dir_first),
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@Button
                        }
                        val p = port.toIntOrNull() ?: FileServerService.DEFAULT_PORT
                        FileServerService.start(context, rootPath, p, useHttps, pinCode.ifBlank { null })
                        isRunning = true
                        serverUrl = null // real URL arrives via LaunchedEffect once the service publishes it
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = if (isRunning) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    stringResource(
                        if (isRunning) R.string.fileserver_stop else R.string.fileserver_start,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.fileserver_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Best-effort conversion of a tree URI into a real filesystem path the server can read. */
private fun treeUriToPath(uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = docId.split(":")
    return when {
        parts.size == 2 && parts[0] == "primary" -> "/storage/emulated/0/${parts[1]}"
        parts.size == 2 -> "/storage/${parts[0]}/${parts[1]}"
        else -> null
    }
}
