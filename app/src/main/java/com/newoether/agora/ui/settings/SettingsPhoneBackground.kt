package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.phone.PhoneBackgroundGeometry
import com.newoether.agora.phone.PhoneNativeDisplay
import com.newoether.agora.phone.PhoneUseSettings

internal val PhoneBackgroundSizeChoices = listOf(
    "native" to R.string.phone_background_native,
    "1.25" to R.string.phone_background_125,
    "1.5" to R.string.phone_background_150,
    "custom" to R.string.phone_background_custom,
)

/**
 * The one row for the background screen size: the resolved pixels the next claim uses, with the
 * preset on the right. Tapping it opens the preset picker and custom fields in one dialog.
 */
@Composable
internal fun PhoneBackgroundSizeItem(config: PhoneUseSettings, onSave: (size: String, short: Int, long: Int) -> Unit) {
    val context = LocalContext.current
    val native = remember { runCatching { PhoneBackgroundGeometry.native(context) }.getOrNull() } ?: return
    val spec = PhoneBackgroundGeometry.resolve(native, config)
    val choiceLabel = PhoneBackgroundSizeChoices.firstOrNull { it.first == config.backgroundSize }?.second
        ?: R.string.phone_background_native
    var editing by remember { mutableStateOf(false) }
    SettingsItem(
        headlineContent = { Text(stringResource(R.string.phone_background_size)) },
        supportingContent = {
            Text(stringResource(R.string.phone_background_resolution_value, spec.width, spec.height, spec.shortDp))
        },
        leadingContent = { Icon(Icons.Rounded.AspectRatio, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Text(stringResource(choiceLabel), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge)
        },
        modifier = Modifier.clickable { editing = true },
    )
    if (editing) {
        PhoneBackgroundSizeDialog(
            native = native,
            config = config,
            onDismiss = { editing = false },
            onSave = { size, a, b -> editing = false; onSave(size, a, b) },
        )
    }
}

@Composable
internal fun PhoneBackgroundLandscapeItem(checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsItem(
        headlineContent = { Text(stringResource(R.string.phone_background_landscape)) },
        supportingContent = { Text(stringResource(R.string.phone_background_landscape_description)) },
        leadingContent = { Icon(Icons.Rounded.ScreenRotation, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun PhoneBackgroundSizeDialog(
    native: PhoneNativeDisplay,
    config: PhoneUseSettings,
    onDismiss: () -> Unit,
    onSave: (size: String, short: Int, long: Int) -> Unit,
) {
    var size by remember { mutableStateOf(config.backgroundSize.takeIf { it in PhoneBackgroundGeometry.SIZES } ?: "native") }
    val customSpec = PhoneBackgroundGeometry.resolve(native, config.copy(backgroundSize = "custom", backgroundLandscape = false))
    var short by remember { mutableStateOf(customSpec.width.toString()) }
    var long by remember { mutableStateOf(customSpec.height.toString()) }
    val custom = size == "custom"
    val minShort = PhoneBackgroundGeometry.minShortPx(native.dpi)
    val max = PhoneBackgroundGeometry.MAX_SIDE
    val a = short.toIntOrNull()
    val b = long.toIntOrNull()
    val customValid = a != null && b != null && minOf(a, b) >= minShort && maxOf(a, b) <= max
    // Presets always resolve; the preview and the read-only fields show what they produce.
    val preview = if (custom) {
        if (customValid) PhoneBackgroundGeometry.resolve(native, config.copy(backgroundSize = "custom",
            backgroundCustomShort = minOf(a!!, b!!), backgroundCustomLong = maxOf(a, b))) else null
    } else {
        PhoneBackgroundGeometry.resolve(native, config.copy(backgroundSize = size))
    }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.phone_background_size), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoneBackgroundSizeChoices.forEach { (value, label) ->
                        FilterChip(
                            selected = size == value,
                            onClick = { size = value },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                val shownShort = if (custom) short else preview?.let { minOf(it.width, it.height).toString() }.orEmpty()
                val shownLong = if (custom) long else preview?.let { maxOf(it.width, it.height).toString() }.orEmpty()
                SizeField(shownShort, R.string.phone_background_short_side, enabled = custom) { short = it }
                SizeField(shownLong, R.string.phone_background_long_side, enabled = custom) { long = it }
                if (preview != null) {
                    Text(
                        stringResource(R.string.phone_background_resolution_value, preview.width, preview.height, preview.shortDp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (custom) stringResource(R.string.phone_background_size_range, minShort, max)
                        else stringResource(R.string.phone_background_size_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!custom || customValid) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (custom) onSave(size, a!!, b!!) else onSave(size, 0, 0) },
                enabled = !custom || customValid,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SizeField(value: String, label: Int, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onChange(input.filter(Char::isDigit).take(4)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}
