package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.theme.ColorSchemePreset
import com.newoether.agora.ui.theme.SchemeStyle

/**
 * Rows and display-name lookups for the Appearance page, kept out of SettingsAppearancePage so the
 * page stays within the source size limit.
 */

@Composable
internal fun presetDisplayName(preset: ColorSchemePreset): String = when (preset) {
    ColorSchemePreset.MIDNIGHT -> stringResource(R.string.color_scheme_midnight)
    ColorSchemePreset.NORDIC -> stringResource(R.string.color_scheme_nordic)
    ColorSchemePreset.FOREST -> stringResource(R.string.color_scheme_forest)
    ColorSchemePreset.SUNSET -> stringResource(R.string.color_scheme_sunset)
    ColorSchemePreset.ROSE -> stringResource(R.string.color_scheme_rose)
    ColorSchemePreset.LAVENDER -> stringResource(R.string.color_scheme_lavender)
    ColorSchemePreset.SLATE -> stringResource(R.string.color_scheme_slate)
    ColorSchemePreset.OCEAN -> stringResource(R.string.color_scheme_ocean)
}

@Composable
internal fun styleDisplayName(style: SchemeStyle): String = when (style) {
    SchemeStyle.TONAL_SPOT -> stringResource(R.string.scheme_style_tonal_spot)
    SchemeStyle.EXPRESSIVE -> stringResource(R.string.scheme_style_expressive)
    SchemeStyle.VIBRANT -> stringResource(R.string.scheme_style_vibrant)
    SchemeStyle.NEUTRAL -> stringResource(R.string.scheme_style_neutral)
}

/** Prints each finished reply's average token rate beside its action icons. */
@Composable
internal fun GenerationSpeedSettingRow(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    SettingsItem(
        headlineContent = { Text(stringResource(R.string.show_generation_speed)) },
        supportingContent = { Text(stringResource(R.string.show_generation_speed_desc)) },
        leadingContent = {
            Icon(
                Icons.Default.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        },
        trailingContent = { Switch(checked = enabled, onCheckedChange = onEnabledChange) },
        modifier = Modifier.clickable { onEnabledChange(!enabled) },
    )
}
