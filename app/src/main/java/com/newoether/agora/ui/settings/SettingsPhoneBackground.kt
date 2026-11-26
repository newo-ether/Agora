package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

/** Size preset row for the background screen. */
@Composable
internal fun PhoneBackgroundSizeItem(config: PhoneUseSettings, onSelect: (String) -> Unit) {
    SettingsChoiceItem(R.string.phone_background_size, R.string.phone_background_size_description,
        Icons.Rounded.AspectRatio, config.backgroundSize, PhoneBackgroundSizeChoices, onSelect = onSelect)
}

/** Shows the size the next claim will use. Tapping it enters a custom size. */
@Composable
internal fun PhoneBackgroundResolutionItem(config: PhoneUseSettings, onCustom: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val native = remember { runCatching { PhoneBackgroundGeometry.native(context) }.getOrNull() } ?: return
    val spec = PhoneBackgroundGeometry.resolve(native, config)
    var editing by remember { mutableStateOf(false) }
    SettingsItem(
        headlineContent = { Text(stringResource(R.string.phone_background_resolution)) },
        supportingContent = {
            Text(stringResource(R.string.phone_background_resolution_value, spec.width, spec.height, spec.shortDp))
        },
        leadingContent = { Icon(Icons.Rounded.AspectRatio, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.clickable { editing = true },
    )
    if (editing) {
        PhoneBackgroundSizeDialog(
            native = native,
            initialShort = minOf(spec.width, spec.height),
            initialLong = maxOf(spec.width, spec.height),
            onDismiss = { editing = false },
            onSave = { a, b -> editing = false; onCustom(a, b) },
        )
    }
}

@Composable
private fun PhoneBackgroundSizeDialog(
    native: PhoneNativeDisplay,
    initialShort: Int,
    initialLong: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    var short by remember { mutableStateOf(initialShort.toString()) }
    var long by remember { mutableStateOf(initialLong.toString()) }
    val a = short.toIntOrNull()
    val b = long.toIntOrNull()
    val minShort = PhoneBackgroundGeometry.minShortPx(native.dpi)
    val max = PhoneBackgroundGeometry.MAX_SIDE
    val valid = a != null && b != null && minOf(a, b) >= minShort && maxOf(a, b) <= max
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.phone_background_custom), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SizeField(short, R.string.phone_background_short_side) { short = it }
                SizeField(long, R.string.phone_background_long_side) { long = it }
                if (valid) {
                    val s = minOf(a!!, b!!)
                    Text(
                        stringResource(R.string.phone_background_resolution_value, s, maxOf(a, b), s * 160 / native.dpi),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.phone_background_size_range, minShort, max),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(a!!, b!!) }, enabled = valid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SizeField(value: String, label: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onChange(input.filter(Char::isDigit).take(4)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}
