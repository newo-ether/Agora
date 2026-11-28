package com.newoether.agora.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.newoether.agora.R
import com.newoether.agora.ui.components.AgoraDropdownMenu
import com.newoether.agora.ui.components.AgoraDropdownMenuItem
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.voice.live.LiveVoiceConfig
import com.newoether.agora.voice.live.LiveVoiceSessionController
import com.newoether.agora.voice.live.LiveVoiceState

/**
 * Settings > Live Voice. Selects the realtime (Live API) provider, model, and voice, and lets the
 * user try a session. Provider/model come from the live provider lists via [buildProviderModels]
 * (initially Google is the only supported direct route); models are filtered to Live-capable ids
 * with free-text fallback. Voice is a Google prebuilt voice.
 */
@Composable
fun SettingsLiveVoicePage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val persisted by viewModel.settings.liveVoiceConfig.collectAsState()
    val availableModels by viewModel.settings.availableModels.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val providerBaseUrls by viewModel.settings.providerBaseUrls.collectAsState()

    // Local editable mirror; persisted on each change.
    var config by remember(persisted) { mutableStateOf(persisted) }
    fun update(newConfig: LiveVoiceConfig) {
        config = newConfig
        viewModel.settings.setLiveVoiceConfig(newConfig)
    }

    // Live models are fetched dynamically; the Models page isn't the only entry to this data, so
    // trigger the same fingerprint-gated sync here (otherwise opening Live Voice directly shows only
    // the seeded ids and never the provider's actual bidiGenerateContent models).
    val lastFingerprint by viewModel.settings.lastModelsFetchFingerprint.collectAsState()
    LaunchedEffect(Unit) {
        if (viewModel.computeProviderFingerprint() != lastFingerprint) {
            viewModel.fetchAvailableModels()
        }
    }

    val controller = com.newoether.agora.voice.live.LiveVoiceSession.controller
    val sessionState by controller.state.collectAsState()
    // Shared session: do not stop on leaving Settings (the composer button owns lifecycle too).

    // Only Google supports the direct Live route today; still sourced from the shared helper so
    // custom Google-compatible providers appear and more can be enabled later.
    val providers = remember(availableModels, customProviders, providerBaseUrls) {
        buildProviderModels(availableModels, customProviders, providerBaseUrls)
    }
    val selectedProvider = providers.firstOrNull { it.name.equals(config.provider, ignoreCase = true) }
    val liveModels = remember(selectedProvider, config.provider) {
        val fetchedLive = selectedProvider?.models?.filter(LiveVoiceConfig::looksLiveCapable).orEmpty()
        // Seed known Google Live ids so the list is usable even before a model refetch.
        val seeds = if (config.provider.equals(com.newoether.agora.util.Constants.PROVIDER_GOOGLE, true)) {
            LiveVoiceConfig.GOOGLE_LIVE_MODELS
        } else {
            emptyList()
        }
        (fetchedLive + seeds).distinct()
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val key = viewModel.settings.resolveActiveKey(config.provider).orEmpty()
            controller.start(ctx, config, key)
        }
    }

    fun toggleSession() {
        if (sessionState != LiveVoiceState.IDLE) { controller.stop(); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO); return
        }
        val key = viewModel.settings.resolveActiveKey(config.provider).orEmpty()
        controller.start(ctx, config, key)
    }

    CollapsingSettingsScaffold(title = stringResource(R.string.settings_live_voice), onBack = onBack) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.settings_live_voice),
                items = buildList {
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.live_voice_enable)) },
                            supportingContent = { Text(stringResource(R.string.live_voice_enable_desc)) },
                            leadingContent = { Icon(Icons.Default.RecordVoiceOver, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Switch(checked = config.enabled, onCheckedChange = { update(config.copy(enabled = it)) })
                            },
                            modifier = Modifier.clickable { update(config.copy(enabled = !config.enabled)) },
                        )
                    }
                    // Provider picker
                    add {
                        var expanded by remember { mutableStateOf(false) }
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.live_voice_provider)) },
                            supportingContent = { Text(config.provider.ifBlank { stringResource(R.string.select_model) }) },
                            leadingContent = { Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    Icon(Icons.Default.ArrowDropDown, null)
                                    AgoraDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        providers.forEach { p ->
                                            AgoraDropdownMenuItem(
                                                text = { Text(p.name) },
                                                onClick = {
                                                    update(config.copy(provider = p.name, model = ""))
                                                    expanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { expanded = true },
                        )
                    }
                    // Model picker (live-capable, free-text fallback)
                    add {
                        var expanded by remember { mutableStateOf(false) }
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.live_voice_model)) },
                            supportingContent = { Text(config.model.ifBlank { stringResource(R.string.embedding_model_id_hint) }) },
                            leadingContent = { Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    Icon(Icons.Default.ArrowDropDown, null)
                                    AgoraDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        if (liveModels.isEmpty()) {
                                            AgoraDropdownMenuItem(
                                                text = { Text(stringResource(R.string.live_voice_no_models)) },
                                                onClick = { expanded = false },
                                            )
                                        }
                                        liveModels.forEach { m ->
                                            AgoraDropdownMenuItem(
                                                text = { Text(m) },
                                                onClick = { update(config.copy(model = m)); expanded = false },
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { expanded = true },
                        )
                    }
                    // Free-text model id (for ids not yet in the fetched list)
                    add {
                        SettingsIconContent(icon = Icons.Default.GraphicEq) {
                            McpLabeledField(
                                label = stringResource(R.string.live_voice_model),
                                value = config.model,
                                onValueChange = { update(config.copy(model = it)) },
                                placeholder = "gemini-3.8-live",
                            )
                        }
                    }
                    // Voice picker
                    add {
                        var expanded by remember { mutableStateOf(false) }
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.live_voice_voice)) },
                            supportingContent = { Text(config.voiceName) },
                            leadingContent = { Icon(Icons.Default.RecordVoiceOver, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = {
                                Box {
                                    Icon(Icons.Default.ArrowDropDown, null)
                                    AgoraDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        LiveVoiceConfig.GOOGLE_VOICES.forEach { v ->
                                            AgoraDropdownMenuItem(
                                                text = { Text(v) },
                                                onClick = { update(config.copy(voiceName = v)); expanded = false },
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { expanded = true },
                        )
                    }
                    // System instruction
                    add {
                        SettingsIconContent(icon = Icons.Default.RecordVoiceOver) {
                            McpLabeledField(
                                label = stringResource(R.string.live_voice_system_instruction),
                                value = config.systemInstruction,
                                onValueChange = { update(config.copy(systemInstruction = it)) },
                                placeholder = stringResource(R.string.live_voice_system_instruction_hint),
                            )
                        }
                    }
                },
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { toggleSession() },
                enabled = config.model.isNotBlank(),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                val active = sessionState != LiveVoiceState.IDLE
                Icon(if (active) Icons.Default.Stop else Icons.Default.Mic, null)
                Spacer(Modifier.height(0.dp))
                Text(
                    when (sessionState) {
                        LiveVoiceState.IDLE -> stringResource(R.string.live_voice_start)
                        LiveVoiceState.CONNECTING -> stringResource(R.string.live_voice_connecting)
                        LiveVoiceState.ACTIVE -> stringResource(R.string.live_voice_stop)
                    },
                )
            }
        }
    }
}
