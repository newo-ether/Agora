package com.newoether.agora.data

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Notification-specific settings flows and functions.
 * Separated from SettingsManager to keep file sizes under the 999-line limit.
 */
class SettingsNotifications(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {
    // DataStore keys
    private val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    private val NOTIFICATIONS_PENDING = stringPreferencesKey("notifications_pending")
    private val NOTIFICATIONS_SYNC_STATE = stringPreferencesKey("notifications_sync_state")
    private val NOTIFICATIONS_ALLOWED_APPS = stringPreferencesKey("notifications_allowed_apps")
    private val NOTIFICATIONS_APPS_INITIALIZED = booleanPreferencesKey("notifications_apps_initialized")

    // ── Flows ────────────────────────────────────────────────────────

    val notificationsEnabled: Flow<Boolean> = dataStore.data.map { it[NOTIFICATIONS_ENABLED] ?: false }
    val notificationsPending: Flow<String> = dataStore.data.map { it[NOTIFICATIONS_PENDING] ?: "[]" }
    val notificationsSyncState: Flow<String> = dataStore.data.map { it[NOTIFICATIONS_SYNC_STATE] ?: "{}" }

    val notificationsAllowedApps: Flow<Set<String>> = dataStore.data.map { pref ->
        val jsonStr = pref[NOTIFICATIONS_ALLOWED_APPS] ?: "[]"
        try {
            kotlinx.serialization.json.Json.decodeFromString<Set<String>>(jsonStr)
        } catch (e: Exception) {
            emptySet()
        }
    }
    
    val notificationsAppsInitialized: Flow<Boolean> = dataStore.data.map { it[NOTIFICATIONS_APPS_INITIALIZED] ?: false }

    // Build/flavor capability gates — these must reflect whether THIS BUILD can ever support
    // the feature (fdroid manifest declares it) independent of whether the user has granted
    // the runtime permission yet. Checking live grant status here would hide the whole
    // Settings section (and its toggle) until permission is already granted — but the toggle
    // is the only way to *trigger* that permission request, so the feature would be
    // permanently invisible on a fresh install.
    val smsReaderSupported: Flow<Boolean> = dataStore.data.map {
        isPermissionDeclared(android.Manifest.permission.READ_SMS)
    }

    val notificationListenerSupported: Flow<Boolean> = dataStore.data.map {
        isNotificationListenerServiceDeclared()
    }

    private fun isPermissionDeclared(permission: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            )
            info.requestedPermissions?.contains(permission) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isNotificationListenerServiceDeclared(): Boolean {
        return try {
            val intent = android.content.Intent("android.service.notification.NotificationListenerService")
                .setPackage(context.packageName)
            context.packageManager.queryIntentServices(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
                .isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    // Notification listener status
    // Triggered when user returns from Notification Access settings to force a re-check
    private val _notificationListenerStatusRefresh = MutableStateFlow(0)
    val notificationListenerStatusRefresh: kotlinx.coroutines.flow.StateFlow<Int> = _notificationListenerStatusRefresh

    val notificationListenerStatus: Flow<NotificationListenerStatus> = combine(
        dataStore.data,
        _notificationListenerStatusRefresh,
    ) { prefs, _ ->
        val hasAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            // Resolved by name so main sources stay flavor-safe: the listener service is
            // only declared in the fdroid flavor manifest.
            val componentName = android.content.ComponentName(
                context.packageName,
                "com.newoether.agora.notifications.AgoraNotificationListenerService",
            )
            nm.isNotificationListenerAccessGranted(componentName)
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(context.packageName) ?: false
        }
        val intentEnabled = true // Intent can remain enabled even if access is missing
        NotificationListenerStatus(hasAccess = hasAccess, intentEnabled = intentEnabled)
    }

    // Call this when returning from Notification Access settings to force status refresh
    fun triggerNotificationListenerStatusRefresh() {
        _notificationListenerStatusRefresh.value++
    }

    // ── Write ────────────────────────────────────────────────────────

    suspend fun saveNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[NOTIFICATIONS_ENABLED] = enabled }
    }
    suspend fun saveNotificationsPending(pendingJson: String) {
        dataStore.edit { it[NOTIFICATIONS_PENDING] = pendingJson }
    }
    suspend fun saveNotificationsSyncState(syncStateJson: String) {
        dataStore.edit { it[NOTIFICATIONS_SYNC_STATE] = syncStateJson }
    }
    suspend fun clearNotificationsPending() {
        dataStore.edit { it[NOTIFICATIONS_PENDING] = "[]" }
    }
    suspend fun saveNotificationsAllowedApps(apps: Set<String>) {
        dataStore.edit { it[NOTIFICATIONS_ALLOWED_APPS] = kotlinx.serialization.json.Json.encodeToString(apps) }
    }
    suspend fun setNotificationsAppsInitialized() {
        dataStore.edit { it[NOTIFICATIONS_APPS_INITIALIZED] = true }
    }

    /**
     * Clears portable notification keys during a Settings REPLACE import.
     * Non-portable keys (pending queue, sync state) are intentionally preserved.
     */
    suspend fun resetPortableKeys() {
        dataStore.edit { prefs ->
            prefs.remove(NOTIFICATIONS_ENABLED)
            prefs.remove(NOTIFICATIONS_ALLOWED_APPS)
            prefs.remove(NOTIFICATIONS_APPS_INITIALIZED)
        }
    }
}