package com.newoether.agora.ui.settings

import com.newoether.agora.util.DebugLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.util.Constants
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsProviderDetailPage(
    providerName: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val apiKeys by viewModel.settings.apiKeys.collectAsState()
    val activeApiKeyIds by viewModel.settings.activeApiKeyIds.collectAsState()
    val providerBaseUrls by viewModel.settings.providerBaseUrls.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val localChatModels by viewModel.settings.localChatModels.collectAsState()

    val isLocal = providerName == Constants.PROVIDER_LOCAL
    val isCustom = customProviders.any { it.name == providerName }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Dialogs
    var showKeyDialog by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showDeleteKeyConfirm by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var importingModel by remember { mutableStateOf(false) }
    var copiedFilePath by remember { mutableStateOf<String?>(null) }
    var showAddModelDialog by remember { mutableStateOf(false) }
    var showEditModelDialog by remember { mutableStateOf<LocalChatModelConfig?>(null) }
    var showDeleteModelConfirm by remember { mutableStateOf<LocalChatModelConfig?>(null) }
    var showGgufError by remember { mutableStateOf(false) }
    var mmprojPickedUri by remember { mutableStateOf<String?>(null) }
    var showRenameProvider by remember { mutableStateOf(false) }
    var showDeleteProvider by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importingModel = true
            scope.launch(Dispatchers.IO) {
                try {
                    val dest = java.io.File(context.filesDir, "chat_model_${java.util.UUID.randomUUID()}.gguf")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    val magic = ByteArray(4); dest.inputStream().use { it.read(magic) }
                    if (magic[0] != 'G'.code.toByte() || magic[1] != 'G'.code.toByte() || magic[2] != 'U'.code.toByte() || magic[3] != 'F'.code.toByte()) {
                        dest.delete(); showGgufError = true
                    } else { copiedFilePath = dest.absolutePath; showAddModelDialog = true }
                } catch (e: Exception) { DebugLog.e("ProviderDetail", "GGUF import", e) }
                finally { importingModel = false }
            }
        }
    }
    val mmprojLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val dest = java.io.File(context.filesDir, "mmproj_${java.util.UUID.randomUUID()}.gguf")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    mmprojPickedUri = dest.absolutePath
                } catch (e: Exception) { DebugLog.e("ProviderDetail", "mmproj import", e) }
            }
        }
    }

    CollapsingSettingsScaffold(
        title = if (isLocal) stringResource(R.string.local_title) else providerName,
        onBack = onBack,
        actions = {
            if (isCustom) {
                Box {
                    IconButton(onClick = { providerMenuExpanded = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.options)) }
                    DropdownMenu(expanded = providerMenuExpanded, onDismissRequest = { providerMenuExpanded = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp, shape = RoundedCornerShape(12.dp)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { providerMenuExpanded = false; showRenameProvider = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { providerMenuExpanded = false; showDeleteProvider = true })
                    }
                }
            }
        }
    ) {
            SettingsGroupColumn {
                // Base URL (non-Local only)
                if (!isLocal) {
                val providerInstance = viewModel.getProviderInstance(providerName)
                val savedUrl = providerBaseUrls[providerName]
                // Don't key remember on savedUrl — that causes TextFieldState to be recreated
                // every time the debounced save writes back to DataStore, overwriting user input.
                val baseUrlState = remember { TextFieldState(savedUrl ?: "") }
                // Sync external changes (e.g. import) back into the text field.
                LaunchedEffect(savedUrl) {
                    val ext = savedUrl ?: ""
                    val cur = baseUrlState.text.toString()
                    if (ext.isNotEmpty() && ext != cur) {
                        baseUrlState.edit { replace(0, length, ext) }
                    }
                }
                // Save user input with 500ms debounce.
                LaunchedEffect(baseUrlState.text) {
                    delay(500)
                    viewModel.settings.setProviderBaseUrl(providerName, baseUrlState.text.toString())
                }
                SettingsGroup(
                    title = stringResource(R.string.provider_base_url),
                    items = listOf {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Icon(painterResource(R.drawable.link_24), null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.provider_base_url), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                                    Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                        OutlinedTextField(
                                            state = baseUrlState,
                                            placeholder = { Text(providerInstance.defaultBaseUrl, style = MaterialTheme.typography.bodyMedium) },
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }

            // Local models
            if (isLocal) {
                SettingsGroup(
                    title = stringResource(R.string.local_models_title),
                    items = buildList {
                        if (localChatModels.isEmpty()) {
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.provider_no_local_models), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    supportingContent = { Text(stringResource(R.string.provider_no_local_models_desc), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                                    leadingContent = { Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                                    modifier = Modifier.heightIn(min = 64.dp)
                                )
                            }
                        }
                        localChatModels.forEach { model ->
                            var showMenu by remember { mutableStateOf(false) }
                            add {
                                SettingsItem(
                                    modifier = Modifier.clickable { showMenu = true },
                                    headlineContent = { Text(model.alias, fontWeight = FontWeight.Medium) },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    },
                                    supportingContent = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(top = 1.dp)
                                        ) {
                                            if (model.mmprojPath.isNotBlank()) {
                                                Surface(
                                                    shape = RoundedCornerShape(5.dp),
                                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                                ) {
                                                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Visibility, null, modifier = Modifier.size(10.dp))
                                                        Spacer(Modifier.width(3.dp))
                                                        Text("Vision", style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(5.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            ) {
                                                Text(
                                                    "Context=${model.nCtx}",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(5.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            ) {
                                                Text(
                                                    "T=${model.temperature}",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        Box {
                                            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.options), modifier = Modifier.size(18.dp)) }
                                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp, shape = RoundedCornerShape(12.dp)) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; showEditModelDialog = model })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; showDeleteModelConfirm = model })
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        add {
                            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !importingModel) { filePickerLauncher.launch(arrayOf("*/*")) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                if (importingModel) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.importing_model), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.import_model_chat), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                )
            }

            // API Keys (non-Local)
            if (!isLocal) {
                val providerKeys = apiKeys.filter { it.provider == providerName }
                if (providerKeys.isEmpty()) {
                    SettingsGroup(
                        title = stringResource(R.string.provider_api_keys),
                        items = buildList {
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.provider_no_keys, providerName), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    leadingContent = { Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                                    modifier = Modifier.heightIn(min = 64.dp)
                                )
                            }
                            add {
                                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { showKeyDialog = ApiKeyEntry(name = "", key = "", provider = providerName) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.provider_add_key), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    )
                } else {
                    SettingsGroup(
                        title = stringResource(R.string.provider_api_keys),
                        items = buildList {
                            providerKeys.forEach { entry ->
                                var showMenu by remember { mutableStateOf(false) }
                                val isCurrentActive = entry.id == activeApiKeyIds[providerName]
                                add {
                                    SettingsItem(
                                        headlineContent = { Text(entry.name, fontWeight = FontWeight.Medium) },
                                        supportingContent = { Text(entry.key.take(4) + "••••••••" + entry.key.takeLast(4)) },
                                        leadingContent = { Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { RadioButton(selected = isCurrentActive, onClick = { viewModel.settings.setActiveApiKey(providerName, entry.id) }, modifier = Modifier.size(20.dp)) } },
                                        trailingContent = {
                                            Box {
                                                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.options), modifier = Modifier.size(18.dp)) }
                                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp, shape = RoundedCornerShape(12.dp)) {
                                                    DropdownMenuItem(text = { Text(stringResource(R.string.provider_edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; showKeyDialog = entry })
                                                    DropdownMenuItem(text = { Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; showDeleteKeyConfirm = entry })
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .clickable { viewModel.settings.setActiveApiKey(providerName, entry.id) }
                                    )
                                }
                            }
                            add {
                                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { showKeyDialog = ApiKeyEntry(name = "", key = "", provider = providerName) }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.provider_add_key), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    )
                }
            }
            }
    }

    // --- Dialogs ---
    if (showGgufError) {
        AlertDialog(containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showGgufError = false }, title = { Text(stringResource(R.string.import_invalid_gguf_title), fontWeight = FontWeight.Bold) }, text = { Text(stringResource(R.string.import_invalid_gguf_desc)) }, confirmButton = { TextButton(onClick = { showGgufError = false }) { Text(stringResource(R.string.ok)) } })
    }

    // Add model dialog
    if (showAddModelDialog && copiedFilePath != null) {
        var modelId by remember { mutableStateOf("") }; var modelAlias by remember { mutableStateOf("") }
        var addMmprojPath by remember { mutableStateOf("") }
        var nCtx by remember { mutableStateOf("2048") }; var temperature by remember { mutableStateOf("0.7") }; var topP by remember { mutableStateOf("0.9") }; var maxTokens by remember { mutableStateOf("4096") }
        var idError by remember { mutableStateOf<String?>(null) }; var formError by remember { mutableStateOf<String?>(null) }
        val idRegex = remember { Regex("^[a-z0-9._-]+\$") }
        LaunchedEffect(mmprojPickedUri) { if (mmprojPickedUri != null) { addMmprojPath = mmprojPickedUri!!; mmprojPickedUri = null } }
        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = {
                scope.launch(Dispatchers.IO) {
                    copiedFilePath?.let { java.io.File(it).delete() }
                    if (addMmprojPath.isNotBlank()) java.io.File(addMmprojPath).delete()
                }
                showAddModelDialog = false; copiedFilePath = null
            },
            title = { Text(stringResource(R.string.add_local_chat_model), fontWeight = FontWeight.Bold) },
            text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = modelId, onValueChange = { modelId = it; idError = null }, label = { Text(stringResource(R.string.model_id_label)) }, supportingText = if (idError != null) {{ Text(idError!!, color = MaterialTheme.colorScheme.error) }} else null, isError = idError != null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = modelAlias, onValueChange = { modelAlias = it }, label = { Text(stringResource(R.string.model_alias_label)) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = nCtx, onValueChange = { nCtx = it }, label = { Text(stringResource(R.string.local_ctx_size)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val hasMmproj = addMmprojPath.isNotBlank()
                    OutlinedButton(onClick = { mmprojLauncher.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasMmproj) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(6.dp)); Text(if (hasMmproj) addMmprojPath.split("/").lastOrNull() ?: "" else stringResource(R.string.local_mmproj_path_label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (hasMmproj) {
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { java.io.File(addMmprojPath).delete(); addMmprojPath = "" }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = temperature, onValueChange = { temperature = it }, label = { Text(stringResource(R.string.local_temperature)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = topP, onValueChange = { topP = it }, label = { Text(stringResource(R.string.local_top_p)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it }, label = { Text(stringResource(R.string.local_max_tokens)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                formError?.let { Spacer(modifier = Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }},
            confirmButton = { TextButton(onClick = {
                val id = modelId.trim(); idError = null; formError = null
                if (id.isBlank()) { idError = "ID is required"; return@TextButton }
                if (!idRegex.matches(id)) { idError = "Only a-z, 0-9, . _ - allowed"; return@TextButton }
                if (viewModel.isLocalModelIdTaken(id)) { idError = "Already in use"; return@TextButton }
                val n = nCtx.toIntOrNull()?.takeIf { it > 0 } ?: run { formError = "Context size must be positive"; return@TextButton }
                val t = temperature.toFloatOrNull()?.takeIf { it in 0f..2f } ?: run { formError = "Temperature must be 0–2"; return@TextButton }
                val p = topP.toFloatOrNull()?.takeIf { it in 0f..1f } ?: run { formError = "Top P must be 0–1"; return@TextButton }
                val m = maxTokens.toIntOrNull()?.takeIf { it > 0 } ?: run { formError = "Max tokens must be positive"; return@TextButton }
                viewModel.addLocalChatModel(LocalChatModelConfig(modelId = id, alias = modelAlias.ifBlank { id }, localFilePath = copiedFilePath!!, mmprojPath = addMmprojPath.trim(), nCtx = n, temperature = t, topP = p, maxTokens = m))
                showAddModelDialog = false; copiedFilePath = null
            }) { Text(stringResource(R.string.add)) } },
            dismissButton = { TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    copiedFilePath?.let { java.io.File(it).delete() }
                    if (addMmprojPath.isNotBlank()) java.io.File(addMmprojPath).delete()
                }
                showAddModelDialog = false; copiedFilePath = null
            }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // Edit model dialog
    showEditModelDialog?.let { model ->
        var editModelId by remember { mutableStateOf(model.modelId) }; var editAlias by remember { mutableStateOf(model.alias) }; var editMmprojPath by remember { mutableStateOf(model.mmprojPath) }
        var editNCtx by remember { mutableStateOf(model.nCtx.toString()) }; var editTemp by remember { mutableStateOf(model.temperature.toString()) }; var editTopP by remember { mutableStateOf(model.topP.toString()) }; var editMaxTokens by remember { mutableStateOf(model.maxTokens.toString()) }
        var editIdError by remember { mutableStateOf<String?>(null) }; var editFormError by remember { mutableStateOf<String?>(null) }
        val idRegex = remember { Regex("^[a-z0-9._-]+\$") }
        LaunchedEffect(mmprojPickedUri) { if (mmprojPickedUri != null) { editMmprojPath = mmprojPickedUri!!; mmprojPickedUri = null } }
        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showEditModelDialog = null },
            title = { Text(stringResource(R.string.edit), fontWeight = FontWeight.Bold) },
            text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = editModelId, onValueChange = { editModelId = it; editIdError = null }, label = { Text(stringResource(R.string.model_id_label)) }, supportingText = if (editIdError != null) {{ Text(editIdError!!, color = MaterialTheme.colorScheme.error) }} else null, isError = editIdError != null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = editAlias, onValueChange = { editAlias = it }, label = { Text(stringResource(R.string.model_alias_label)) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = editNCtx, onValueChange = { editNCtx = it }, label = { Text(stringResource(R.string.local_ctx_size)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val hasMmproj = editMmprojPath.isNotBlank()
                    OutlinedButton(onClick = { mmprojLauncher.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasMmproj) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(6.dp)); Text(if (hasMmproj) editMmprojPath.split("/").lastOrNull() ?: "" else stringResource(R.string.local_mmproj_path_label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (hasMmproj) {
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = {
                            if (editMmprojPath != model.mmprojPath) java.io.File(editMmprojPath).delete()
                            editMmprojPath = ""
                        }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = editTemp, onValueChange = { editTemp = it }, label = { Text(stringResource(R.string.local_temperature)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = editTopP, onValueChange = { editTopP = it }, label = { Text(stringResource(R.string.local_top_p)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = editMaxTokens, onValueChange = { editMaxTokens = it }, label = { Text(stringResource(R.string.local_max_tokens)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                editFormError?.let { Spacer(modifier = Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }},
            confirmButton = { TextButton(onClick = {
                val id = editModelId.trim(); editIdError = null; editFormError = null
                if (id.isBlank()) { editIdError = "ID is required"; return@TextButton }
                if (!idRegex.matches(id)) { editIdError = "Only a-z, 0-9, . _ - allowed"; return@TextButton }
                if (viewModel.isLocalModelIdTaken(id, excludeId = model.id)) { editIdError = "Already in use"; return@TextButton }
                val n = editNCtx.toIntOrNull()?.takeIf { it > 0 } ?: run { editFormError = "Context size must be positive"; return@TextButton }
                val t = editTemp.toFloatOrNull()?.takeIf { it in 0f..2f } ?: run { editFormError = "Temperature must be 0–2"; return@TextButton }
                val p = editTopP.toFloatOrNull()?.takeIf { it in 0f..1f } ?: run { editFormError = "Top P must be 0–1"; return@TextButton }
                val m = editMaxTokens.toIntOrNull()?.takeIf { it > 0 } ?: run { editFormError = "Max tokens must be positive"; return@TextButton }
                viewModel.updateLocalChatModel(model.id, id, editAlias.ifBlank { id }, n, t, p, m, mmprojPath = editMmprojPath.trim())
                showEditModelDialog = null
            }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = {
                if (editMmprojPath.isNotBlank() && editMmprojPath != model.mmprojPath) java.io.File(editMmprojPath).delete()
                showEditModelDialog = null
            }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // Delete model confirm
    showDeleteModelConfirm?.let { model ->
        AlertDialog(containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showDeleteModelConfirm = null }, title = { Text(stringResource(R.string.local_chat_delete_title), fontWeight = FontWeight.Bold) }, text = { Text(stringResource(R.string.local_chat_delete_text, model.alias)) }, confirmButton = { TextButton(onClick = { viewModel.deleteLocalChatModel(model.id); showDeleteModelConfirm = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = { showDeleteModelConfirm = null }) { Text(stringResource(R.string.cancel)) } })
    }

    // API Key dialog
    showKeyDialog?.let { entry ->
        var name by remember { mutableStateOf(entry.name) }; var key by remember { mutableStateOf(entry.key) }
        val isEdit = apiKeys.any { it.id == entry.id }
        AlertDialog(modifier = Modifier.clearFocusOnTap(), containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showKeyDialog = null }, title = { Text(if (isEdit) stringResource(R.string.provider_edit_key) else stringResource(R.string.provider_add_key_title), fontWeight = FontWeight.Bold) }, text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.provider_key_name_hint)) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().noOpBringIntoView())
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.noOpBringIntoView()) { OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("${providerName} API Key") }, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) }
            }
        }, confirmButton = { TextButton(onClick = { if (name.isNotBlank() && key.isNotBlank()) { if (isEdit) viewModel.settings.updateApiKey(entry.id, name, key) else viewModel.settings.addApiKey(name, key, providerName); showKeyDialog = null } }) { Text(if (isEdit) stringResource(R.string.provider_save) else stringResource(R.string.provider_add)) } }, dismissButton = { TextButton(onClick = { showKeyDialog = null }) { Text(stringResource(R.string.cancel)) } })
    }

    // Delete key confirm
    showDeleteKeyConfirm?.let { entry ->
        AlertDialog(containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showDeleteKeyConfirm = null }, title = { Text(stringResource(R.string.provider_delete_key_title), fontWeight = FontWeight.Bold) }, text = { Text(stringResource(R.string.provider_delete_key_text, entry.name)) }, confirmButton = { TextButton(onClick = { viewModel.settings.deleteApiKey(entry.id); showDeleteKeyConfirm = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.provider_delete)) } }, dismissButton = { TextButton(onClick = { showDeleteKeyConfirm = null }) { Text(stringResource(R.string.cancel)) } })
    }

    // Rename custom provider
    if (showRenameProvider) {
        var renameValue by remember { mutableStateOf(providerName) }
        var renameError by remember { mutableStateOf(false) }
        val allNames = listOf(Constants.PROVIDER_GOOGLE, Constants.PROVIDER_OPENAI, Constants.PROVIDER_ANTHROPIC, Constants.PROVIDER_DEEPSEEK, Constants.PROVIDER_QWEN, Constants.PROVIDER_GROQ, Constants.PROVIDER_OLLAMA, Constants.PROVIDER_OPEN_ROUTER) + customProviders.map { it.name }
        AlertDialog(modifier = Modifier.clearFocusOnTap(), containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showRenameProvider = false }, title = { Text(stringResource(R.string.custom_provider_rename_title), fontWeight = FontWeight.Bold) }, text = {
            OutlinedTextField(value = renameValue, onValueChange = { renameValue = it; renameError = false }, label = { Text(stringResource(R.string.custom_provider_name_label)) }, isError = renameError, supportingText = if (renameError) {{ Text(stringResource(R.string.custom_provider_name_error)) }} else null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
        }, confirmButton = { TextButton(onClick = {
            val trimmed = renameValue.trim()
            renameError = trimmed.isBlank() || (trimmed != providerName && trimmed in allNames)
            if (!renameError) { viewModel.renameCustomProvider(providerName, trimmed); showRenameProvider = false; onBack() }
        }) { Text(stringResource(R.string.custom_provider_rename)) } }, dismissButton = { TextButton(onClick = { showRenameProvider = false }) { Text(stringResource(R.string.cancel)) } })
    }

    // Delete custom provider
    if (showDeleteProvider) {
        AlertDialog(containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showDeleteProvider = false }, title = { Text(stringResource(R.string.custom_provider_delete_title), fontWeight = FontWeight.Bold) }, text = { Text(stringResource(R.string.custom_provider_delete_text, providerName)) }, confirmButton = { TextButton(onClick = { viewModel.deleteCustomProvider(providerName); showDeleteProvider = false; onBack() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.provider_delete)) } }, dismissButton = { TextButton(onClick = { showDeleteProvider = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
