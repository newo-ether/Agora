package com.newoether.agora.ui.settings

import android.text.format.Formatter
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Metadata only: the editor retains ownership of the imported file and draft. */
internal data class ModelImportFileInfo(val name: String, val sizeBytes: Long?)

internal fun readModelImportFile(path: String): ModelImportFileInfo {
    val file = File(path)
    val size = try {
        if (file.isFile) file.length() else null
    } catch (_: SecurityException) {
        null
    }
    return ModelImportFileInfo(file.name, size)
}

/** Shared import states; file ownership and picker admission stay with each editor. */
@Composable
internal fun ModelImportItem(
    title: String,
    description: String,
    icon: ImageVector,
    importing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filePath: String = "",
    emptyIcon: ImageVector = icon,
    importedEffect: String? = null,
    importedFile: ModelImportFileInfo? = null,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val gray = MaterialTheme.colorScheme.onSurfaceVariant
    val fileName = remember(filePath) { File(filePath).name }
    var fileInfo by remember(filePath, importing) { mutableStateOf<ModelImportFileInfo?>(null) }
    LaunchedEffect(filePath, importing) {
        if (filePath.isNotBlank() && !importing) {
            fileInfo = withContext(Dispatchers.IO) { readModelImportFile(filePath) }
        }
    }
    val imported = filePath.isNotBlank() || importedFile != null
    val metadata = if (filePath.isNotBlank()) fileInfo else importedFile
    val missing = imported && metadata != null && metadata.sizeBytes == null
    val support = when {
        importing -> stringResource(R.string.importing_label)
        missing -> stringResource(R.string.model_import_file_missing)
        imported -> {
            val details = metadata?.let { info ->
                "${info.name} · ${Formatter.formatShortFileSize(context, info.sizeBytes ?: 0)}"
            } ?: fileName
            if (importedEffect.isNullOrBlank() || metadata == null) details else "$details\n$importedEffect"
        }
        else -> stringResource(R.string.model_import_not_imported, description)
    }
    SettingsItem(
        modifier = modifier.clickable(enabled = enabled && !importing, role = Role.Button, onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = {
            Crossfade(targetState = support, animationSpec = tween(250), label = "model-import-description") {
                Text(it, color = if (missing) MaterialTheme.colorScheme.error else gray)
            }
        },
        leadingContent = {
            Crossfade(
                targetState = Triple(importing, imported, missing),
                modifier = Modifier.size(24.dp).testTag("model-import-leading"),
                animationSpec = tween(250),
                label = "model-import-leading",
            ) { (busy, selected, unavailable) ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (busy) {
                        MotionAwareCircularProgressIndicator(
                            modifier = Modifier.size(24.dp).testTag("model-import-progress"),
                            color = primary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = when {
                                unavailable -> Icons.Rounded.WarningAmber
                                selected -> icon
                                else -> emptyIcon
                            },
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = when {
                                unavailable -> MaterialTheme.colorScheme.error
                                selected -> primary
                                else -> gray
                            },
                        )
                    }
                }
            }
        },
        trailingContent = {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when {
                    importing -> Unit
                    !imported -> IconButton(onClick = onClick, enabled = enabled) {
                        Icon(Icons.Rounded.FileUpload, stringResource(R.string.import_button), tint = gray)
                    }
                    onRemove != null -> IconButton(onClick = onRemove, enabled = enabled) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.remove), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    )
}
