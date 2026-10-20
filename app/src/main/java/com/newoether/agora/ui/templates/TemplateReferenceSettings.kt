package com.newoether.agora.ui.templates

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.templates.TemplateAndroidSources
import com.newoether.agora.templates.templateReferenceCatalogue
import kotlinx.coroutines.*

/** Folder grants and file checks are local settings actions, never a model request. */
@Composable
internal fun TemplateReferenceSettings(folder: String?, paths: List<String>, prefix: String, automatic: Boolean,
    enabled: Boolean, onFolder: (String, List<String>) -> Unit, onPaths: (List<String>) -> Unit,
    onPrefix: (String) -> Unit, onAutomatic: (Boolean) -> Unit, onRemove: () -> Unit, onBusy: (Boolean) -> Unit) {
    val context = LocalContext.current
    val access = remember(context) { TemplateAndroidSources(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run(action: suspend () -> Unit) {
        busy = true; onBusy(true); error = null
        scope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not read reference folder" }
            finally { busy = false; onBusy(false) }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) run {
            val id = withContext(Dispatchers.IO) { access.connect(uri) }
            onFolder(id, emptyList())
            if (automatic) onPaths(withContext(Dispatchers.IO) { access.list(id) })
        }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.template_folder_help))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = enabled && !busy, onClick = { picker.launch(null) }) { Text(stringResource(R.string.template_choose_folder)) }
            if (folder != null) TextButton(enabled = enabled && !busy, onClick = { run { onPaths(withContext(Dispatchers.IO) { access.list(folder) }) } }) {
                Text(stringResource(R.string.template_refresh_files))
            }
        }
        if (folder != null) {
            var name by remember(folder) { mutableStateOf("") }
            LaunchedEffect(folder) { name = withContext(Dispatchers.IO) { access.displayName(folder) } }
            Text(name)
            Row {
                Checkbox(checked = automatic, enabled = enabled && !busy, onCheckedChange = onAutomatic)
                Text(stringResource(R.string.template_auto_files), Modifier.padding(top = 12.dp))
            }
            OutlinedTextField(prefix, onPrefix, enabled = enabled && !busy,
                label = { Text(stringResource(R.string.template_hide_prefix)) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.template_file_count, paths.size))
            val references = remember(paths, prefix) { templateReferenceCatalogue(paths.filter { com.newoether.agora.templates.isTemplateFilePath(it) }, prefix) }
            Text(references.take(6).joinToString("\n") { (it.categories + it.label).joinToString(" → ") })
            if (!automatic) OutlinedTextField(paths.joinToString("\n"), { onPaths(it.lines().filter(String::isNotBlank)) },
                label = { Text(stringResource(R.string.template_reference_paths)) }, enabled = enabled && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp))
            TextButton(enabled = enabled && !busy, onClick = onRemove) { Text(stringResource(R.string.template_disconnect_folder)) }
        }
        if (busy) Text(stringResource(R.string.loading_label))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
