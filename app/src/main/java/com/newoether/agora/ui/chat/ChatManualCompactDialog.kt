package com.newoether.agora.ui.chat


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.modelDisplayName
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.ui.components.AgoraExposedDropdownMenu
import com.newoether.agora.ui.components.AlertDialog
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.TextButton

/** Manual Compact uses the same Material alert-dialog treatment as the other chat editors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatManualCompactDialog(
    initialModel: String,
    initialPrompt: String,
    initialRetainCount: Int,
    enabledModels: Set<String>,
    modelAliases: Map<String, String>,
    modelProviderNames: Map<String, Boolean>,
    customProviders: List<CustomProviderConfig>,
    isCompacting: Boolean,
    onCompact: (model: String, prompt: String, retainCount: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var model by remember(initialModel) { mutableStateOf(initialModel) }
    var prompt by remember(initialPrompt) { mutableStateOf(initialPrompt) }
    var retain by remember(initialRetainCount) { mutableStateOf(initialRetainCount.toString()) }
    var modelMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val busy = submitting || isCompacting
    val unavailableModelError = stringResource(R.string.context_compact_select_available_model)
    val emptyPromptError = stringResource(R.string.context_compact_prompt_empty)
    val invalidRetainError = stringResource(R.string.context_compact_retain_invalid)

    LaunchedEffect(isCompacting) {
        if (isCompacting) onDismiss()
    }

    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                stringResource(R.string.context_compact_manual),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExposedDropdownMenuBox(
                    expanded = modelMenu,
                    onExpandedChange = { if (!busy) modelMenu = it },
                ) {
                    OutlinedTextField(
                        value = modelDisplayName(model, modelAliases, customProviders, modelProviderNames[model] != false),
                        onValueChange = {},
                        readOnly = true,
                        enabled = !busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.context_compact_model)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenu)
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(
                                type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                enabled = !busy,
                            ),
                    )
                    AgoraExposedDropdownMenu(
                        expanded = modelMenu,
                        onDismissRequest = { modelMenu = false },
                    ) {
                        enabledModels.sorted().forEach { candidate ->
                            AgoraDropdownMenuItem(
                                text = {
                                    Text(modelDisplayName(candidate, modelAliases, customProviders, modelProviderNames[candidate] != false))
                                },
                                onClick = {
                                    model = candidate
                                    modelMenu = false
                                    error = null
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it; error = null },
                    label = { Text(stringResource(R.string.context_compact_prompt)) },
                    enabled = !busy,
                    minLines = 3,
                    maxLines = 7,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = retain,
                    onValueChange = { retain = it.filter(Char::isDigit); error = null },
                    label = { Text(stringResource(R.string.context_compact_retain)) },
                    enabled = !busy,
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
            ) {
                Text(stringResource(R.string.provider_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (submitting || isCompacting) return@TextButton
                    val count = retain.toIntOrNull()
                    when {
                        model !in enabledModels -> error = unavailableModelError
                        prompt.isBlank() -> error = emptyPromptError
                        count == null -> error = invalidRetainError
                        else -> {
                            error = null
                            submitting = true
                            onCompact(model, prompt, count)
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.context_compact))
            }
        },
    )
}
