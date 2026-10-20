package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.ui.components.*
import com.newoether.agora.voice.VoiceTypingConfig
import com.newoether.agora.voice.VoiceTypingMode
import com.newoether.agora.voice.voiceKeyCandidates
import kotlinx.coroutines.launch

@Composable
internal fun VoiceTypingSettingsGroup(settings: SettingsRepository) {
    val config by settings.voiceTyping.config.collectAsState(initial = VoiceTypingConfig())
    val keys by settings.apiKeys.collectAsState()
    val activeIds by settings.activeApiKeyIds.collectAsState()
    val scope = rememberCoroutineScope()
    fun update(change: (VoiceTypingConfig) -> VoiceTypingConfig) { scope.launch { settings.voiceTyping.update(change) } }
    val modes = VoiceTypingMode.entries.map { mode -> mode to stringResource(when (mode) {
        VoiceTypingMode.ANDROID -> R.string.voice_typing_android
        VoiceTypingMode.ANDROID_OFFLINE -> R.string.voice_typing_offline
        VoiceTypingMode.OPENAI -> R.string.voice_typing_openai
        VoiceTypingMode.GROQ -> R.string.voice_typing_groq
        VoiceTypingMode.COMPATIBLE -> R.string.voice_typing_compatible
    }) }
    SettingsGroup(title = stringResource(R.string.voice_typing), items = buildList {
        add {
            SettingsItem(headlineContent = { Text(stringResource(R.string.voice_typing)) },
                supportingContent = { Text(stringResource(R.string.voice_typing_description)) },
                leadingContent = { Icon(Icons.Default.Mic, contentDescription = null) },
                trailingContent = { Switch(checked = config.enabled, onCheckedChange = { update { current -> current.copy(enabled = it) } }) },
                modifier = Modifier.clickable { update { it.copy(enabled = !it.enabled) } })
        }
        if (config.enabled) add {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceChoice(stringResource(R.string.voice_typing_engine), modes.first { it.first == config.mode }.second,
                    modes.map { it.second }, onSelect = { update { current -> current.select(modes[it].first) } })
                Text(stringResource(if (config.mode.usesApi) R.string.voice_typing_api_description else R.string.voice_typing_android_description))
                if (config.mode.usesApi) {
                    val candidates = voiceKeyCandidates(config, keys)
                    val automatic = candidates.firstOrNull { it.id == activeIds[it.provider] } ?: candidates.firstOrNull()
                    val labels = listOf(stringResource(R.string.voice_typing_default_key)) + candidates.map { "${it.provider} · ${it.name}" }
                    val selected = candidates.indexOfFirst { it.id == config.apiKeyId }
                    VoiceChoice(stringResource(R.string.voice_typing_key), labels[if (selected < 0) 0 else selected + 1], labels,
                        onSelect = { update { current -> current.copy(apiKeyId = candidates.getOrNull(it - 1)?.id) } })
                    if (config.apiKeyId == null && automatic != null && config.mode != VoiceTypingMode.COMPATIBLE) {
                        Text("${automatic.provider} · ${automatic.name}")
                    }
                    VoiceConfigField(config.baseUrl, stringResource(R.string.voice_typing_url), config.mode.name) { value -> update { it.copy(baseUrl = value) } }
                    VoiceConfigField(config.model, stringResource(R.string.voice_typing_model), config.mode.name) { value -> update { it.copy(model = value) } }
                }
                VoiceConfigField(config.language, stringResource(R.string.voice_typing_language), "language",
                    hint = stringResource(R.string.voice_typing_language_help)) { value -> update { it.copy(language = value) } }
            }
        }
    })
}

@Composable
private fun VoiceChoice(label: String, value: String, options: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("$label: $value") }
        AgoraDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, name -> AgoraDropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(index) }) }
        }
    }
}

@Composable
private fun VoiceConfigField(value: String, label: String, reset: String, hint: String? = null, onChange: (String) -> Unit) {
    var local by remember(reset) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) local = value }
    OutlinedTextField(local, { local = it; onChange(it) },
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        label = { Text(label) }, singleLine = true,
        supportingText = hint?.let { { Text(it) } })
}
