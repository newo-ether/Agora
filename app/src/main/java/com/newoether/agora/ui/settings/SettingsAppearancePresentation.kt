package com.newoether.agora.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.ui.theme.ColorSchemePreset
import com.newoether.agora.ui.theme.SchemeStyle
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.Switch
import com.newoether.agora.ui.components.Text
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.Contrast

/**
 * Display-name lookups for the theme color pickers on the Appearance page, kept out of
 * SettingsAppearancePage so the page stays within the source size limit.
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


@Composable
internal fun EinkModeSettingsItem(enabled: Boolean, onChange: (Boolean) -> Unit) {
    SettingsItem(
        headlineContent = { Text(stringResource(R.string.e_ink_mode)) },
        supportingContent = { Text(stringResource(R.string.e_ink_mode_desc)) },
        leadingContent = { Icon(
            androidx.compose.material.icons.Icons.Default.Contrast, null) },
        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
        modifier = androidx.compose.ui.Modifier.toggleable(
            value = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
    )
}
