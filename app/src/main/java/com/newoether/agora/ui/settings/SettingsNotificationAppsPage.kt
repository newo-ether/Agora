package com.newoether.agora.ui.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NotificationAppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

@Composable
fun SettingsNotificationAppsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val allowedApps by viewModel.settings.notificationsAllowedApps.collectAsState()
    val initialized by viewModel.settings.notificationsAppsInitialized.collectAsState()
    var appList by remember { mutableStateOf<List<NotificationAppItem>?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            
            // Core messaging/system packages we always want to include even if they are system apps
            val alwaysInclude = setOf(
                "com.whatsapp",
                "org.telegram.messenger",
                "com.google.android.dialer",
                "com.android.dialer",
                "com.google.android.apps.messaging",
                "com.android.mms",
                "com.samsung.android.messaging",
                "com.samsung.android.dialer",
                "com.google.android.gms", // Play Services (System Updates)
                "com.android.vending", // Play Store (Updates)
            )

            val filtered = packages.filter { appInfo ->
                val isUserApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val isAlwaysIncluded = alwaysInclude.contains(appInfo.packageName)
                isUserApp || isAlwaysIncluded
            }

            val mapped = filtered.map { appInfo ->
                NotificationAppItem(
                    packageName = appInfo.packageName,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo)
                )
            }.sortedBy { it.label.lowercase() }

            if (!initialized) {
                // Initialize default allowed apps
                val defaultAllowed = mapped
                    .filter { alwaysInclude.contains(it.packageName) }
                    .map { it.packageName }
                    .toSet()
                viewModel.settings.setNotificationsAllowedApps(defaultAllowed)
                viewModel.settings.setNotificationsAppsInitialized()
            }

            appList = mapped
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.notifications_manage_apps),
        onBack = onBack,
    ) {
        if (appList == null) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val filteredApps = remember(appList, searchQuery) {
                appList!!.filter {
                    it.label.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
                }
            }

            SettingsGroupColumn {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search applications...") },
                        singleLine = true
                    )
                }
                
                SettingsGroup(
                    title = "Allowed Applications",
                    items = filteredApps.map { appItem ->
                        {
                            val isAllowed = allowedApps.contains(appItem.packageName)
                            SettingsItem(
                                headlineContent = { Text(appItem.label) },
                                supportingContent = { Text(appItem.packageName) },
                                leadingContent = {
                                    val iconBmp = remember(appItem.icon) {
                                        appItem.icon?.toBitmap(width = 120, height = 120)?.asImageBitmap()
                                    }
                                    if (iconBmp != null) {
                                        Image(
                                            bitmap = iconBmp,
                                            contentDescription = null,
                                            modifier = Modifier.size(40.dp)
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Android,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(40.dp)
                                        )
                                    }
                                },
                                trailingContent = {
                                    Switch(
                                        checked = isAllowed,
                                        onCheckedChange = { checked ->
                                            val newSet = if (checked) {
                                                allowedApps + appItem.packageName
                                            } else {
                                                allowedApps - appItem.packageName
                                            }
                                            viewModel.settings.setNotificationsAllowedApps(newSet)
                                        }
                                    )
                                },
                                modifier = Modifier.clickable {
                                    val newSet = if (!isAllowed) {
                                        allowedApps + appItem.packageName
                                    } else {
                                        allowedApps - appItem.packageName
                                    }
                                    viewModel.settings.setNotificationsAllowedApps(newSet)
                                }
                            )
                        }
                    }
                )
            }
        }
    }
}
