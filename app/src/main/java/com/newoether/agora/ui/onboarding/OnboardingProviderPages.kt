package com.newoether.agora.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.ui.components.CustomEndpointProtocolSelector
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.components.providerIcon
import com.newoether.agora.util.Constants
import com.newoether.agora.ui.components.SecretVisibilityToggle
import com.newoether.agora.ui.components.secretVisualTransformation
import com.newoether.agora.ui.components.Icon
import com.newoether.agora.ui.components.OutlinedButton
import com.newoether.agora.ui.components.Surface
import com.newoether.agora.ui.components.Text

@Composable
internal fun ProviderPage(providers: List<String>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier, configuredProviders: Set<String> = emptySet()) {
    val scrollState = rememberScrollState()
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Surface(
        modifier = modifier.heightIn(max = 340.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Box(Modifier.fillMaxWidth().drawBehind {
            if (scrollState.maxValue > 0) {
                val progress = (scrollState.value.toFloat() / scrollState.maxValue).coerceIn(0f, 1f)
                val barW = 4.dp.toPx()
                val barX = size.width - barW - 8.dp.toPx()
                val barH = size.height - 40.dp.toPx()
                val barY = 20.dp.toPx()
                drawRoundRect(trackColor, topLeft = Offset(barX, barY), size = Size(barW, barH), cornerRadius = CornerRadius(2.dp.toPx()))
                val thumbH = barH * 0.35f
                val thumbY = barY + (barH - thumbH) * progress
                drawRoundRect(thumbColor, topLeft = Offset(barX, thumbY), size = Size(barW, thumbH), cornerRadius = CornerRadius(2.dp.toPx()))
            }
        }) {
            Column(Modifier.verticalScroll(scrollState)) {
                Spacer(Modifier.height(10.dp))
            providers.forEach { p ->
                val iconRes = providerIcon(p)
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp).clip(RoundedCornerShape(28.dp)).clickable { onSelect(p) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == p, onClick = { onSelect(p) })
                    Spacer(Modifier.width(8.dp))
                    when {
                        iconRes != 0 -> Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        p == Constants.PROVIDER_LOCAL -> Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        p == "Custom" -> Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        else -> Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(p, style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected == p) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
                Spacer(Modifier.height(10.dp))
        }
        }
    }
}

@Composable
internal fun ApiKeyPage(
    provider: String?, isCustom: Boolean, apiKeyText: String, onApiKeyChange: (String) -> Unit,
    baseUrlText: String, onBaseUrlChange: (String) -> Unit,
    customProtocol: CustomEndpointProtocol,
    onCustomProtocolChange: (CustomEndpointProtocol) -> Unit,
    apiKeyVisible: Boolean, onToggleVisibility: () -> Unit,
    isImporting: Boolean, onImportGGUF: () -> Unit, modifier: Modifier,
    localModels: List<com.newoether.agora.data.LocalChatModelConfig> = emptyList()
) {
    Surface(modifier, RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        if (provider == null) {
            Column(Modifier.padding(32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.onboarding_no_provider), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (provider == Constants.PROVIDER_LOCAL) {
            val label = if (isImporting) stringResource(R.string.onboarding_importing)
                else localModels.lastOrNull()?.alias ?: stringResource(R.string.onboarding_import_gguf)
            Column(Modifier.padding(32.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = onImportGGUF, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50), enabled = !isImporting) {
                    Text(label, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        } else if (provider == Constants.PROVIDER_OLLAMA) {
            Column(Modifier.padding(32.dp).fillMaxWidth().clearFocusOnTap()) {
                val iconRes = providerIcon(provider)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (iconRes != 0) {
                        Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                    } else {
                        Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = apiKeyText, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_ollama_hint)) },
                    singleLine = true, shape = RoundedCornerShape(50)
                )
            }
        } else if (isCustom) {
            Column(Modifier.padding(32.dp).fillMaxWidth().clearFocusOnTap()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                CustomEndpointProtocolSelector(
                    selected = customProtocol,
                    onSelected = onCustomProtocolChange,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = baseUrlText, onValueChange = onBaseUrlChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_custom_base_url_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true, shape = RoundedCornerShape(50)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKeyText, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_api_key_hint)) },
                    visualTransformation = secretVisualTransformation(apiKeyVisible),
                    trailingIcon = { SecretVisibilityToggle(apiKeyVisible, onToggleVisibility) },
                    singleLine = true, shape = RoundedCornerShape(50)
                )
            }
        } else {
            Column(Modifier.padding(32.dp).fillMaxWidth().clearFocusOnTap()) {
                val iconRes = providerIcon(provider)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (iconRes != 0) {
                        Icon(painterResource(iconRes), null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                    } else {
                        Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(provider, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = apiKeyText, onValueChange = onApiKeyChange, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.onboarding_api_key_hint)) },
                    visualTransformation = secretVisualTransformation(apiKeyVisible),
                    trailingIcon = { SecretVisibilityToggle(apiKeyVisible, onToggleVisibility) },
                    singleLine = true, shape = RoundedCornerShape(50)
                )
            }
        }
    }
}

