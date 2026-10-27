package com.newoether.agora.phone

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PhonePermissions(private val context: Context, private val privileged: PhonePrivilegedConnection) {
    @Volatile var rootGranted = false
        private set
    fun granted(mode: String, includeOverlay: Boolean = true): Map<String, Boolean> = buildMap {
        if (includeOverlay) put("overlay", Settings.canDrawOverlays(context))
        put("write_settings", Settings.System.canWrite(context))
        put("notifications", NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
        when (mode) {
            "accessibility" -> put("accessibility", PhoneAccessibilityService.instance != null)
            "shizuku" -> {
                put("shizuku_installed", runCatching { context.packageManager.getApplicationInfo("moe.shizuku.privileged.api", 0) }.isSuccess)
                put("shizuku_running", rikka.shizuku.Shizuku.pingBinder())
                put("shizuku_permission", privileged.ready())
            }
            "root" -> put("root", rootGranted)
        }
    }
    fun ready(mode: String) = granted(mode).values.all { it }
    fun locked(): Boolean = context.getSystemService(KeyguardManager::class.java).let { it.isKeyguardLocked || it.isDeviceLocked }
    /** UI probes are isolated from the grant admitted by claim. Failed checks never revoke it. */
    suspend fun probeRoot(): Boolean = checkRoot()
    suspend fun claimRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            (privileged.command("root", "id -u").toString(Charsets.UTF_8).trim() == "0").also { rootGranted = it }
        } catch (error: PhoneFailure) {
            if (error.code.startsWith("permission_lost:")) rootGranted = false
            throw error
        }
    }
    private suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        try { privileged.command("root", "id -u").toString(Charsets.UTF_8).trim() == "0" }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    fun entryFailure(mode: String, claiming: Boolean): String? = granted(mode, includeOverlay = claiming)
        .entries.firstOrNull { !it.value }?.let { "permission_lost:not_ready:${it.key}" }
    fun open(key: String) {
        if (key == "shizuku_permission" && rikka.shizuku.Shizuku.pingBinder()) { privileged.request(); return }
        val intent = when (key) {
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            // This platform action is hidden from the public SDK; OEMs may not expose it.
            "accessibility" -> Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, android.content.ComponentName(context, PhoneAccessibilityService::class.java).flattenToString())
            else -> context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.getOrElse {
            val fallback = if (key == "accessibility") Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
