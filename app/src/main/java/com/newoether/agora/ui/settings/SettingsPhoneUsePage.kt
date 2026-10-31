package com.newoether.agora.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.newoether.agora.AgoraApplication
import com.newoether.agora.R
import com.newoether.agora.phone.PhoneApp
import com.newoether.agora.phone.PhonePermissions
import com.newoether.agora.phone.PhoneUseIcon
import com.newoether.agora.phone.PhoneUseRuntime
import com.newoether.agora.ui.components.optionClickable
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.ensureActive
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
                    SettingsChoiceItem(R.string.phone_mode, R.string.phone_mode_description, PhoneUseIcon,
                        config.mode, listOf("accessibility" to R.string.phone_accessibility,
                            "shizuku" to R.string.phone_shizuku, "root" to R.string.phone_root)) { mode ->
                        scope.launch { settings.phoneUseStore.setMode(mode) }
                    }
                })
                SettingsPhonePermissions(config.mode, revision, runtime?.permissions) { key ->
                    when {
                        key == "root" -> Unit
                        key == "notifications" && Build.VERSION.SDK_INT >= 33 &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> runtime?.permissions?.open(key)
                    }
                    revision++
                }
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
        leadingContent = { Icon(PhoneUseIcon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange))
}

@Composable
private fun PhoneAppPicker(runtime: PhoneUseRuntime, selected: Set<String>, mode: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<PhoneApp>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.packageManager.getInstalledApplications(0)
                .filter { it.packageName != context.packageName }
                .map { PhoneApp(it.packageName, it.loadLabel(context.packageManager).toString()) }
                .sortedBy { it.label.lowercase() }
        }
    }
    PhoneAppPickerDialog(
        apps = apps,
        selected = selected,
        onToggle = { packageName ->
            scope.launch { runtime.settings.phoneUseStore.setPackage(packageName,
                packageName !in selected, mode, context.packageName) }
        },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun PhoneAppPickerDialog(
    apps: List<PhoneApp>?,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.phone_choose_apps), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.phone_find_app)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (apps == null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }, key = { it.packageName }) { app ->
                        SettingsItem(
                            headlineContent = { Text(app.label) },
                            supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { Checkbox(app.packageName in selected, onCheckedChange = { onToggle(app.packageName) }) },
                            modifier = Modifier.optionClickable(role = Role.Checkbox) { onToggle(app.packageName) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.phone_done)) } },
    )
}

@Composable
internal fun SettingsPhonePermissions(
    mode: String,
    revision: Int,
    permissions: PhonePermissions?,
    onClick: (String) -> Unit,
) {
    // This is only the page's current projection, independent of claim readiness.
    var grants by remember(permissions, mode, revision) { mutableStateOf(emptyMap<String, Boolean>()) }
    LaunchedEffect(permissions, mode, revision) {
        permissions ?: return@LaunchedEffect
        grants = permissions.granted(mode).filterKeys { it != "root" }
        if (mode == "root") {
            val granted = permissions.probeRoot()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            grants = grants + ("root" to granted)
        }
    }
    val keys = listOf("overlay", "write_settings", "notifications") + when (mode) {
        "shizuku" -> listOf("shizuku_installed", "shizuku_running", "shizuku_permission")
        "root" -> listOf("root")
        else -> listOf("accessibility")
    }
    SettingsGroup(title = stringResource(R.string.phone_permissions), items = keys.map { permission -> {
        key(mode, permission) {
            SettingsItem(
                headlineContent = { Text(stringResource(phonePermissionLabel(permission))) },
                supportingContent = { Text(stringResource(if (permission == "write_settings")
                    R.string.phone_write_settings_description else R.string.phone_permission_description)) },
                leadingContent = { Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    Crossfade(grants[permission], Modifier.width(96.dp).padding(end = 4.dp),
                        animationSpec = tween(250), label = "phonePermissionStatus") { granted ->
                        Text(stringResource(when (granted) {
                            null -> R.string.about_checking
                            true -> R.string.phone_granted
                            false -> R.string.phone_missing
                        }), color = when (granted) {
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                        }, style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                modifier = Modifier.clickable { onClick(permission) },
            )
        }
    } })
}

private fun phonePermissionLabel(key: String): Int = when (key) {
    "write_settings" -> R.string.phone_write_settings_permission
    "overlay" -> R.string.phone_overlay_permission
    "notifications" -> R.string.phone_notifications_permission
    "accessibility" -> R.string.phone_accessibility
    "shizuku_installed" -> R.string.phone_shizuku_installed
    "shizuku_running" -> R.string.phone_shizuku_running
    "shizuku_permission" -> R.string.phone_shizuku_permission
    else -> R.string.phone_root
}
