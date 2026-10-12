package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.SecretVisibilityToggle
import com.newoether.agora.ui.components.rememberSecretVisible
import com.newoether.agora.ui.components.secretVisualTransformation
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.TextButton

/** A preset (provider name, base URL, known model ids) for the remote-embedding dialog. */
internal data class EmbeddingProviderPreset(val name: String, val baseUrl: String, val models: List<String>)

/**
 * Hoisted form state for the "Add remote embedding model" dialog. Wrapping the ~10
 * fields in one holder keeps both [SettingsSearchPage] and [AddRemoteEmbeddingDialog]
 * free of a parameter clump.
 */
@Stable
internal class RemoteEmbeddingDialogState(providerCount: Int) {
    var name by mutableStateOf("")
    var selectedProviderIdx by mutableIntStateOf(0)
    var modelName by mutableStateOf("")
    var baseUrl by mutableStateOf("")
    val apiKeys = mutableStateListOf(*Array(providerCount) { "" })
    var batchSize by mutableStateOf("8")
    var showModelDropdown by mutableStateOf(false)
    var isCustomModel by mutableStateOf(false)
    var testStatus by mutableStateOf<String?>(null)
    var isTesting by mutableStateOf(false)

    /** Resets every field for a fresh "add remote model" flow. */
    fun prepareForNew(defaultProvider: EmbeddingProviderPreset, openAiKey: String) {
        name = ""
        selectedProviderIdx = 0
        modelName = defaultProvider.models.firstOrNull() ?: ""
        baseUrl = defaultProvider.baseUrl
        for (i in apiKeys.indices) apiKeys[i] = ""
        if (apiKeys.isNotEmpty()) apiKeys[0] = openAiKey
        batchSize = "8"
        isCustomModel = false
        testStatus = null
        isTesting = false
    }
}

@Composable
internal fun rememberRemoteEmbeddingDialogState(providerCount: Int): RemoteEmbeddingDialogState =
    remember { RemoteEmbeddingDialogState(providerCount) }

/**
 * Dialog for adding a remote (API-backed) embedding model: provider preset selector,
 * API key, base URL, model selector (with custom override), display name and batch
 * size. On confirm it test-connects, and only persists the model if the test passes.
 * Extracted from [SettingsSearchPage].
 */
@Composable
internal fun AddRemoteEmbeddingDialog(
    state: RemoteEmbeddingDialogState,
    providers: List<EmbeddingProviderPreset>,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val provider = providers[state.selectedProviderIdx]
    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { onDismiss(); state.testStatus = null },
        title = { Text(stringResource(R.string.add_remote_model), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                // Provider selector
                var provExpanded by remember { mutableStateOf(false) }
                val provFocus = remember { FocusRequester() }
                val focusManager = LocalFocusManager.current
                // Defocus the field whenever its dropdown closes (item picked / dismissed / back).
                LaunchedEffect(provExpanded) { if (!provExpanded) focusManager.clearFocus() }
                Box {
                OutlinedTextField(
                    value = provider.name,
                    onValueChange = { },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(provFocus),
                    label = { Text(stringResource(R.string.embedding_provider_label)) },
                    trailingIcon = {
                        Box {
                            IconButton(onClick = { provExpanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            AgoraDropdownMenu(
                                expanded = provExpanded,
                                onDismissRequest = { provExpanded = false },
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                providers.forEachIndexed { idx, p ->
                                    AgoraDropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            state.selectedProviderIdx = idx
                                            state.baseUrl = p.baseUrl
                                            // Auto-fill the configured key for the selected provider
                                            // (OpenAI, OpenRouter, …), replacing a key that was
                                            // auto-filled for the previously selected provider.
                                            viewModel.ragManager.resolveEmbeddingKeyForProviderExact(p.name)?.key?.let { state.apiKeys[idx] = it }
                                            if (p.models.isNotEmpty()) {
                                                state.modelName = p.models.first()
                                                state.isCustomModel = false
                                            } else {
                                                state.modelName = ""
                                                state.isCustomModel = true
                                            }
                                            provExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { provFocus.requestFocus(); provExpanded = true }
                )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // API Key
                val currentKey = state.apiKeys[state.selectedProviderIdx]
                var keyVisible by rememberSecretVisible()
                OutlinedTextField(
                    value = currentKey,
                    onValueChange = { state.apiKeys[state.selectedProviderIdx] = it },
                    label = { Text(stringResource(R.string.embedding_api_key)) },
                    placeholder = { Text(stringResource(R.string.embedding_api_key_hint)) },
                    visualTransformation = secretVisualTransformation(keyVisible),
                    trailingIcon = { SecretVisibilityToggle(keyVisible) { keyVisible = !keyVisible } },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Base URL
                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = { state.baseUrl = it },
                    readOnly = !state.isCustomModel,
                    label = { Text(stringResource(R.string.embedding_base_url_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Model selector (always shows dropdown)
                val modelFocus = remember { FocusRequester() }
                LaunchedEffect(state.showModelDropdown) { if (!state.showModelDropdown) focusManager.clearFocus() }
                Box {
                OutlinedTextField(
                    value = if (state.isCustomModel) stringResource(R.string.embedding_custom) else state.modelName,
                    onValueChange = { },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(modelFocus),
                    label = { Text(stringResource(R.string.embedding_model_label)) },
                    trailingIcon = {
                        Box {
                            IconButton(onClick = { state.showModelDropdown = true }) {
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            AgoraDropdownMenu(
                                expanded = state.showModelDropdown,
                                onDismissRequest = { state.showModelDropdown = false },
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                provider.models.forEach { model ->
                                    AgoraDropdownMenuItem(
                                        text = { Text(model) },
                                        onClick = {
                                            state.modelName = model
                                            state.isCustomModel = false
                                            state.showModelDropdown = false
                                        }
                                    )
                                }
                                AgoraDropdownMenuItem(
                                    text = { Text(stringResource(R.string.embedding_custom)) },
                                    onClick = {
                                        state.modelName = ""
                                        state.isCustomModel = true
                                        state.showModelDropdown = false
                                    }
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { modelFocus.requestFocus(); state.showModelDropdown = true }
                )
                }
                if (state.isCustomModel) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.modelName,
                        onValueChange = { state.modelName = it },
                        placeholder = { Text("model-name") },
                        supportingText = { Text(stringResource(R.string.embedding_custom_model_desc)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // Display name
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { state.name = it },
                    label = { Text(stringResource(R.string.model_name_label)) },
                    placeholder = { Text(state.modelName) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Batch Size
                OutlinedTextField(
                    value = state.batchSize,
                    onValueChange = { state.batchSize = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.embedding_batch_size)) },
                    supportingText = { Text(stringResource(R.string.embedding_batch_size_desc)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                state.testStatus?.let { status ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.startsWith("OK")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            val scope = rememberCoroutineScope()
            TextButton(
                onClick = {
                    if (state.name.isBlank() && state.modelName.isBlank()) return@TextButton
                    val finalName = state.name.ifBlank { state.modelName }
                    val finalModel = state.modelName.ifBlank { state.name }
                    if (finalModel.isBlank()) return@TextButton
                    state.isTesting = true
                    state.testStatus = null
                    scope.launch {
                        val result = viewModel.remoteEmbeddingConnectionTester.test(finalModel, state.baseUrl, state.apiKeys[state.selectedProviderIdx])
                        if (result != null && result.startsWith("OK")) {
                            viewModel.ragManager.addEmbeddingModel(
                                com.newoether.agora.data.EmbeddingModelConfig(
                                    name = finalName,
                                    type = com.newoether.agora.data.EmbeddingModelType.REMOTE,
                                    remoteModelName = finalModel,
                                    remoteBaseUrl = state.baseUrl,
                                    remoteApiKey = state.apiKeys[state.selectedProviderIdx],
                                    batchSize = state.batchSize.toIntOrNull() ?: 8
                                )
                            )
                            onDismiss()
                        } else {
                            state.testStatus = result ?: "Failed"
                        }
                        state.isTesting = false
                    }
                },
                enabled = !state.isTesting && state.name.isNotBlank() && state.modelName.isNotBlank()
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
