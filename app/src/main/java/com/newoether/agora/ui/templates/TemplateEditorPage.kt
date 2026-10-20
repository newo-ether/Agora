package com.newoether.agora.ui.templates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.InteractiveAnswer
import com.newoether.agora.templates.*
import com.newoether.agora.ui.chat.InteractiveAnswerViewer
import com.newoether.agora.ui.components.Text
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.IconButton
import com.newoether.agora.ui.components.TextButton
import com.newoether.agora.ui.settings.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Interface-only edits preserve the library identity and every Skill's references. */
internal fun editedTemplate(original: InteractiveTemplate, name: String, description: String,
    directory: String, code: String, folder: String? = original.sourceFolderId,
    paths: List<String> = original.resourceFiles, prefix: String = original.referencePrefix,
    automatic: Boolean = original.sourceFilesAuto): InteractiveTemplate {
    require(code.toByteArray(Charsets.UTF_8).size <= InteractiveTemplate.MAX_FILE_BYTES) { "Template source exceeds 100,000 bytes" }
    require(description.trim().split(Regex("\\s+")).filter(String::isNotEmpty).size <= 10) { "Keep the description to 10 words or fewer" }
    val draft = original.copy(name = name.trim(), description = description.trim(), shortcut = null, linkedSkill = null,
        sourceDirectory = directory.trim().ifBlank { null }, sourceFolderId = folder, resourceFiles = paths,
        referencePrefix = prefix, sourceFilesAuto = automatic)
    return applyTemplateEditCode(draft, code)
}

@Composable
internal fun TemplateEditorPage(original: InteractiveTemplate, busy: Boolean, saveError: String?,
    preparePreview: suspend (InteractiveTemplate) -> InteractiveAnswer, onBack: () -> Unit,
    onSave: (InteractiveTemplate) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(original) { mutableStateOf(original.name) }
    var description by remember(original) { mutableStateOf(original.description) }
    var directory by remember(original) { mutableStateOf(original.sourceDirectory.orEmpty()) }
    var folder by remember(original) { mutableStateOf(original.sourceFolderId) }
    var paths by remember(original) { mutableStateOf(original.resourceFiles) }
    var prefix by remember(original) { mutableStateOf(original.referencePrefix) }
    var automatic by remember(original) { mutableStateOf(original.sourceFilesAuto) }
    var code by remember(original) { mutableStateOf(Json { prettyPrint = true }.encodeToString(original.answer)) }
    var preview by remember(original) { mutableStateOf<InteractiveAnswer?>(null) }
    var preparing by remember(original) { mutableStateOf(false) }
    var localError by remember(original) { mutableStateOf<String?>(null) }
    // Parsing only when the draft changes; oversized clipboard payloads never enter the text editor.
    val parsed = remember(original, name, description, directory, code, folder, paths, prefix, automatic) {
        runCatching { editedTemplate(original, name, description, directory, code, folder, paths, prefix, automatic) }
    }
    val enabled = !busy && !preparing
    CollapsingSettingsScaffold(title = stringResource(R.string.template_edit), onBack = { if (enabled) onBack() }, actions = {
        IconButton(enabled = enabled && parsed.isSuccess, onClick = { onSave(parsed.getOrThrow()) }) {
            Icon(Icons.Default.Save, stringResource(R.string.save))
        }
    }) {
        SettingsGroupColumn {
            (localError ?: saveError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SettingsGroup(title = stringResource(R.string.template_details), items = listOf({
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, enabled = enabled, label = { Text(stringResource(R.string.template_name)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { description = it }, enabled = enabled, label = { Text(stringResource(R.string.template_description)) }, modifier = Modifier.fillMaxWidth())
                    if (folder == null) OutlinedTextField(directory, { directory = it }, enabled = enabled, label = { Text(stringResource(R.string.template_source_directory)) }, modifier = Modifier.fillMaxWidth())
                }
            }))
            SettingsGroup(title = stringResource(R.string.template_reference_folder), items = listOf({
                TemplateReferenceSettings(folder, paths, prefix, automatic, enabled,
                    onFolder = { id, files -> folder = id; paths = files; directory = "" },
                    onPaths = { paths = it }, onPrefix = { prefix = it }, onAutomatic = { automatic = it },
                    onRemove = { folder = null; paths = emptyList() }, onBusy = { preparing = it })
            }))
            SettingsGroup(title = stringResource(R.string.template_preview_interface), items = listOf({
                SettingsItem(headlineContent = { Text(stringResource(R.string.template_preview_interface)) },
                    supportingContent = { Text(stringResource(R.string.template_preview_desc)) },
                    leadingContent = { Icon(Icons.Default.Visibility, null) },
                    modifier = Modifier.clickable(enabled = enabled && parsed.isSuccess) {
                        preparing = true; localError = null
                        val snapshot = parsed.getOrThrow()
                        scope.launch {
                            try { preview = preparePreview(snapshot) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { localError = failure.message ?: context.getString(R.string.template_operation_failed) }
                            finally { preparing = false }
                        }
                    })
            }))
            SettingsGroup(title = stringResource(R.string.template_code), items = listOf({
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.template_code_hint))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = enabled, onClick = { clipboard.setText(AnnotatedString(code)) }) { Text(stringResource(R.string.template_copy_code)) }
                        TextButton(enabled = enabled, onClick = {
                            val text = clipboard.getText()?.text
                            if (text == null) localError = context.getString(R.string.template_clipboard_empty)
                            else if (text.toByteArray(Charsets.UTF_8).size > InteractiveTemplate.MAX_FILE_BYTES) localError = "Template source exceeds 100,000 bytes"
                            else try {
                                val next = editedTemplate(original, name, description, directory, text, folder, paths, prefix, automatic)
                                paths = next.resourceFiles; prefix = next.referencePrefix; automatic = next.sourceFilesAuto
                                code = Json { prettyPrint = true }.encodeToString(next.answer); localError = null
                            } catch (failure: Exception) { localError = failure.message }
                        }) { Text(stringResource(R.string.template_paste_code)) }
                    }
                    TextButton(enabled = enabled && parsed.isSuccess, onClick = {
                        clipboard.setText(AnnotatedString(templateAiEditingBrief(parsed.getOrThrow())))
                    }) { Text(stringResource(R.string.template_copy_ai)) }
                    OutlinedTextField(code, { code = it }, enabled = enabled,
                        label = { Text(stringResource(R.string.template_code)) }, isError = parsed.isFailure,
                        supportingText = { parsed.exceptionOrNull()?.message?.let { Text(it) } },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 480.dp))
                }
            }))
        }
    }
    preview?.let { answer -> InteractiveAnswerViewer(answer, onClose = { preview = null }, onSend = { false }, previewOnly = true, onFileAccess = { request ->
        withContext(Dispatchers.IO) { TemplateAndroidSources(context.applicationContext).execute(parsed.getOrThrow(), request) }
    }) }
}
