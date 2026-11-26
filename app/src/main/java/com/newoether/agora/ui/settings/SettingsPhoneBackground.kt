package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.AlertDialog
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

private val PhoneBackgroundOrientationChoices = listOf(
    "portrait" to R.string.phone_background_portrait,
    "landscape" to R.string.phone_background_landscape,
)

/**
 * Background screen size as a dropdown like the other choices on the page. Presets apply at
 * once; Custom opens a small dialog for the two sides. The row shows the pixels the next claim uses.
 */
@Composable
internal fun PhoneBackgroundSizeItem(config: PhoneUseSettings, onSave: (size: String, short: Int, long: Int) -> Unit) {
    val context = LocalContext.current
    val native = remember { runCatching { PhoneBackgroundGeometry.native(context) }.getOrNull() } ?: return
    val spec = PhoneBackgroundGeometry.resolve(native, config)
    var editing by remember { mutableStateOf(false) }
    SettingsChoiceItem(
        titleRes = R.string.phone_background_size,
        descRes = R.string.phone_background_size_description,
        icon = Icons.Rounded.AspectRatio,
        selected = config.backgroundSize.takeIf { it in PhoneBackgroundGeometry.SIZES } ?: "native",
        choices = PhoneBackgroundSizeChoices,
        supporting = stringResource(R.string.phone_background_resolution_value, spec.width, spec.height, spec.shortDp),
    ) { size ->
        if (size == "custom") editing = true else onSave(size, 0, 0)
    }
    if (editing) {
        PhoneBackgroundCustomDialog(
            native = native,
            config = config,
            onDismiss = { editing = false },
            onSave = { a, b -> editing = false; onSave("custom", a, b) },
        )
    }
}

@Composable
internal fun PhoneBackgroundOrientationItem(landscape: Boolean, onChange: (Boolean) -> Unit) {
    SettingsChoiceItem(
        titleRes = R.string.phone_background_orientation,
        descRes = R.string.phone_background_landscape_description,
        icon = Icons.Rounded.ScreenRotation,
        selected = if (landscape) "landscape" else "portrait",
        choices = PhoneBackgroundOrientationChoices,
    ) { onChange(it == "landscape") }
}

/** Custom sides only; presets never reach this dialog. */
@Composable
private fun PhoneBackgroundCustomDialog(
    native: PhoneNativeDisplay,
    config: PhoneUseSettings,
    onDismiss: () -> Unit,
    onSave: (short: Int, long: Int) -> Unit,
) {
    val initial = PhoneBackgroundGeometry.resolve(native, config.copy(backgroundSize = "custom", backgroundLandscape = false))
    var short by remember { mutableStateOf(initial.width.toString()) }
    var long by remember { mutableStateOf(initial.height.toString()) }
    val minShort = PhoneBackgroundGeometry.minShortPx(native.dpi)
    val max = PhoneBackgroundGeometry.MAX_SIDE
    val a = short.toIntOrNull()
    val b = long.toIntOrNull()
    val valid = a != null && b != null && minOf(a, b) >= minShort && maxOf(a, b) <= max
    val preview = if (valid) {
        PhoneBackgroundGeometry.resolve(native, config.copy(backgroundSize = "custom",
            backgroundCustomShort = minOf(a!!, b!!), backgroundCustomLong = maxOf(a, b)))
    } else {
        null
    }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.phone_background_custom), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SizeField(short, R.string.phone_background_short_side, Modifier.weight(1f)) { short = it }
                    SizeField(long, R.string.phone_background_long_side, Modifier.weight(1f)) { long = it }
                }
                Text(
                    preview?.let {
                        stringResource(R.string.phone_background_resolution_value, it.width, it.height, it.shortDp)
                    } ?: stringResource(R.string.phone_background_size_range, minShort, max),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.phone_background_size_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun SizeField(value: String, label: Int, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onChange(input.filter(Char::isDigit).take(4)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    )
}
