package com.newoether.agora.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.AgoraApplication
import com.newoether.agora.R
import com.newoether.agora.phone.AndroidPhoneDevice
import com.newoether.agora.phone.PhoneApp
import com.newoether.agora.phone.PhoneUseRuntime
import com.newoether.agora.ui.components.optionClickable
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsPhoneUsePage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = viewModel.settings
    val config by settings.phoneUse.collectAsState()
    val scope = rememberCoroutineScope()
    var runtime by remember { mutableStateOf<PhoneUseRuntime?>(null) }
    LaunchedEffect(Unit) { runtime = (context.applicationContext as AgoraApplication).awaitContainer()?.phoneUse }
    var revision by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    var grants by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    LaunchedEffect(runtime, config.enabled, config.mode, revision) {
        if (!config.enabled) return@LaunchedEffect
        if (config.mode == "root") runtime?.permissions?.probeRoot()
        while (true) {
            grants = runtime?.permissions?.granted(config.mode).orEmpty()
            delay(750)
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    var picker by remember { mutableStateOf(false) }
    CollapsingSettingsScaffold(title = stringResource(R.string.phone_title), onBack = onBack,
        scrollState = rememberSettingsScrollState()) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.phone_title), items = listOf {
                PhoneSwitch(R.string.phone_enable, R.string.phone_description, config.enabled) {
                    scope.launch { settings.phoneUseStore.setEnabled(it) }
                }
            })
            if (config.enabled) {
                SettingsGroup(title = stringResource(R.string.phone_mode), items = listOf {
                    SettingsChoiceItem(R.string.phone_mode, R.string.phone_mode_description, Icons.Default.TouchApp,
                        config.mode, listOf("accessibility" to R.string.phone_accessibility,
                            "shizuku" to R.string.phone_shizuku, "root" to R.string.phone_root)) { mode ->
                        scope.launch { settings.phoneUseStore.setMode(mode) }
                    }
                })
                SettingsGroup(title = stringResource(R.string.phone_permissions), items = grants.map { (key, granted) -> {
                    SettingsItem(headlineContent = { Text(stringResource(phonePermissionLabel(key))) },
                        supportingContent = { Text(stringResource(R.string.phone_permission_description)) },
                        leadingContent = { Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Text(stringResource(if (granted) R.string.phone_granted else R.string.phone_missing),
                            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge) },
                        modifier = Modifier.clickable {
                            when {
                                key == "root" -> scope.launch { runtime?.permissions?.probeRoot(); revision++ }
                                key == "notifications" && Build.VERSION.SDK_INT >= 33 &&
                                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else -> runtime?.permissions?.open(key)
                            }
                        })
                } })
                SettingsGroup(title = stringResource(R.string.phone_automation), items = listOf {
                    PhoneSwitch(R.string.phone_automation, R.string.phone_automation_description, config.allowAutomation) {
                        scope.launch { settings.phoneUseStore.setAutomation(it) }
                    }
                })
                SettingsGroup(title = stringResource(R.string.phone_app_access), items = buildList {
                    add {
                        SettingsChoiceItem(R.string.phone_app_access, R.string.phone_app_access_description, Icons.Default.Apps,
                            config.accessMode, listOf("off" to R.string.phone_access_off,
                                "blocklist" to R.string.phone_blocklist, "allowlist" to R.string.phone_allowlist)) { mode ->
                            scope.launch { settings.phoneUseStore.setAccessMode(mode) }
                        }
                    }
                    if (config.accessMode != "off") add {
                        SettingsItem(headlineContent = { Text(stringResource(R.string.phone_choose_apps)) },
                            supportingContent = { Text(stringResource(R.string.phone_choose_apps_description, config.packages.size)) },
                            leadingContent = { Icon(Icons.Default.Apps, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { picker = true })
                    }
                })
            }
        }
    }
    if (picker && config.enabled && config.accessMode != "off") runtime?.let {
        PhoneAppPicker(it, config.packages, config.accessMode, onDismiss = { picker = false })
    }
}

@Composable
private fun PhoneSwitch(title: Int, description: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsItem(headlineContent = { Text(stringResource(title)) }, supportingContent = { Text(stringResource(description)) },
        leadingContent = { Icon(Icons.Default.TouchApp, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange))
}

@Composable
private fun PhoneAppPicker(runtime: PhoneUseRuntime, selected: Set<String>, mode: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<PhoneApp>?>(null) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.packageManager.getInstalledApplications(0)
                .filter { it.packageName != context.packageName }
                .map { PhoneApp(it.packageName, it.loadLabel(context.packageManager).toString()) }
                .sortedBy { it.label.lowercase() }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.phone_choose_apps)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.phone_find_app)) }, singleLine = true)
                if (apps == null) CircularProgressIndicator(Modifier.padding(24.dp))
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(apps.orEmpty().filter { it.label.contains(query, true) || it.packageName.contains(query, true) }, key = { it.packageName }) { app ->
                        ListItem(headlineContent = { Text(app.label) }, supportingContent = { Text(app.packageName) },
                            trailingContent = { Checkbox(app.packageName in selected, onCheckedChange = null) },
                            modifier = Modifier.optionClickable(role = Role.Checkbox) {
                                scope.launch { runtime.settings.phoneUseStore.setPackage(app.packageName,
                                    app.packageName !in selected, mode, context.packageName) }
                            })
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.phone_done)) } })
}

private fun phonePermissionLabel(key: String): Int = when (key) {
    "overlay" -> R.string.phone_overlay_permission
    "notifications" -> R.string.phone_notifications_permission
    "accessibility" -> R.string.phone_accessibility
    "shizuku_installed" -> R.string.phone_shizuku_installed
    "shizuku_running" -> R.string.phone_shizuku_running
    "shizuku_permission" -> R.string.phone_shizuku_permission
    else -> R.string.phone_root
}
