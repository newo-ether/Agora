package com.newoether.agora.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.newoether.agora.R
import com.newoether.agora.assistant.AssistantOverlayService
import com.newoether.agora.assistant.AssistantRole
import com.newoether.agora.stt.SherpaSttBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Assistant (Layer 1). Lets the user turn Agora into their device
 * assistant: request the system ASSISTANT role (routes the assist gesture /
 * power-button handoff to Agora), and optionally enable a floating invoke
 * button over other apps (needs the draw-over-other-apps permission).
 *
 * Everything here is user-granted; no privileged/system capability is involved.
 * Context-only page (no ChatViewModel) following the SettingsScannerPage
 * convention. Grant state is re-checked on return from each system dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAssistantPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    val roleAvailable = remember { AssistantRole.isAvailable(ctx) }
    var roleHeld by remember { mutableStateOf(AssistantRole.isHeld(ctx)) }
    var overlayGranted by remember { mutableStateOf(AssistantOverlayService.canDraw(ctx)) }
    var overlayEnabled by remember {
        mutableStateOf(prefs.getBoolean(KEY_OVERLAY_ENABLED, false))
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { roleHeld = AssistantRole.isHeld(ctx) }

    val overlayPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayGranted = AssistantOverlayService.canDraw(ctx)
        // If the user came here to enable the bubble, honor it once granted.
        if (overlayGranted && overlayEnabled) AssistantOverlayService.start(ctx)
    }

    fun requestRole() {
        AssistantRole.requestIntent(ctx)?.let(roleLauncher::launch)
    }

    fun requestOverlayPermission() {
        val i = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}"),
        )
        overlayPermLauncher.launch(i)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
        if (enabled) {
            if (AssistantOverlayService.canDraw(ctx)) {
                AssistantOverlayService.start(ctx)
            } else {
                requestOverlayPermission()
            }
        } else {
            AssistantOverlayService.stop(ctx)
        }
    }

    // ── Offline voice input (sherpa-onnx STT) ──────────────────────────────
    val scope = rememberCoroutineScope()
    var sttInstalled by remember { mutableStateOf(SherpaSttBootstrap.isInstalled(ctx)) }
    var sttBusy by remember { mutableStateOf(false) }
    var sttStatus by remember { mutableStateOf("") }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    fun startSttDownload() {
        if (sttBusy) return
        sttBusy = true
        sttStatus = ""
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                SherpaSttBootstrap.setup(ctx) { line -> sttStatus = line }
            }
            sttInstalled = SherpaSttBootstrap.isInstalled(ctx)
            sttBusy = false
            if (!ok) sttStatus = ""
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        if (granted && !sttInstalled) startSttDownload()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text(stringResource(R.string.settings_assistant)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.assistant_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Spacer(Modifier.height(8.dp))

            SettingsGroupColumn {
                // Default assistant role.
                SettingsGroup(
                    title = stringResource(R.string.assistant_role_group),
                    items = listOf {
                        SettingsItem(
                            modifier = Modifier.clickable(enabled = roleAvailable && !roleHeld) {
                                requestRole()
                            },
                            leadingContent = { Icon(Icons.Default.Assistant, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.assistant_role_title)) },
                            supportingContent = {
                                Text(
                                    when {
                                        !roleAvailable -> stringResource(R.string.assistant_role_unavailable)
                                        roleHeld -> stringResource(R.string.assistant_role_held)
                                        else -> stringResource(R.string.assistant_role_request)
                                    },
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = roleHeld,
                                    enabled = roleAvailable && !roleHeld,
                                    onCheckedChange = { if (it) requestRole() },
                                )
                            },
                        )
                    },
                )

                // Optional floating invoke button (overlay).
                SettingsGroup(
                    title = stringResource(R.string.assistant_overlay_group),
                    items = listOf {
                        SettingsItem(
                            modifier = Modifier.clickable { setOverlayEnabled(!overlayEnabled) },
                            leadingContent = { Icon(Icons.Default.Layers, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.assistant_overlay_title)) },
                            supportingContent = {
                                Text(
                                    if (overlayEnabled && !overlayGranted) {
                                        stringResource(R.string.assistant_overlay_needs_permission)
                                    } else {
                                        stringResource(R.string.assistant_overlay_desc)
                                    },
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = overlayEnabled,
                                    onCheckedChange = { setOverlayEnabled(it) },
                                )
                            },
                        )
                    },
                )

                // Offline voice input (sherpa-onnx STT). The ~52 MB model is downloaded on
                // demand; mic permission is requested the first time the user installs it.
                SettingsGroup(
                    title = stringResource(R.string.assistant_voice_group),
                    items = listOf {
                        SettingsItem(
                            modifier = Modifier.clickable(enabled = !sttBusy) {
                                when {
                                    sttInstalled -> {
                                        SherpaSttBootstrap.remove(ctx)
                                        sttInstalled = false
                                        sttStatus = ""
                                    }
                                    !micGranted -> micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    else -> startSttDownload()
                                }
                            },
                            leadingContent = { Icon(Icons.Default.Mic, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.assistant_voice_title)) },
                            supportingContent = {
                                Text(
                                    when {
                                        sttBusy -> sttStatus.ifBlank { stringResource(R.string.assistant_voice_downloading) }
                                        sttInstalled -> stringResource(R.string.assistant_voice_installed)
                                        !micGranted -> stringResource(R.string.assistant_voice_needs_mic)
                                        else -> stringResource(R.string.assistant_voice_desc)
                                    },
                                )
                            },
                            trailingContent = {
                                if (sttBusy) {
                                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                                } else {
                                    Switch(
                                        checked = sttInstalled,
                                        enabled = !sttBusy,
                                        onCheckedChange = { want ->
                                            if (!want) {
                                                SherpaSttBootstrap.remove(ctx)
                                                sttInstalled = false
                                                sttStatus = ""
                                            } else if (!micGranted) {
                                                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            } else {
                                                startSttDownload()
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }
    }
}

private const val PREFS = "assistant"
private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
