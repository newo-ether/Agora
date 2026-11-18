package com.newoether.agora.data

import android.app.NotificationManager
import android.os.Build
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.util.DebugLog
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BackupResult { NOT_DUE, SUCCESS, FAILED }

class AutoBackupManager(
    private val context: Context,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager,
    private val skillManager: SkillManager,
) {
    companion object {
        /** Cross-instance Mutex — ensures Worker and ChatViewModel instances don't race. */
        private val backupMutex = Mutex()
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "auto_backup"
        private const val DEFAULT_BACKUP_DIRECTORY = "Download/Agora/Backup"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Public API ────────────────────────────────────────────

    /**
     * Check if a backup is due and perform it if so.
     * Safe to call concurrently from any thread/coroutine — companion Mutex
     * guarantees exactly one backup runs at a time across all instances.
     */
    suspend fun checkAndBackup(): BackupResult {
        // Fast path — read settings without acquiring mutex
        if (!settingsManager.autoBackupEnabled.safeRead(true)) return BackupResult.NOT_DUE

        val lastBackup = settingsManager.lastBackupTimestamp.safeRead(0L)
        val periodHours = settingsManager.autoBackupPeriodHours.safeRead(24)
        val now = System.currentTimeMillis()
        val periodMs = periodHours.toLong() * 3600_000L

        if (now - lastBackup < periodMs) return BackupResult.NOT_DUE

        // Acquire mutex for the actual backup
        backupMutex.withLock {
            // Re-check after acquiring lock (another thread may have just backed up)
            val freshLastBackup = settingsManager.lastBackupTimestamp.safeRead(0L)
            if (now - freshLastBackup < periodMs) return BackupResult.NOT_DUE

            val backup = performBackup()
            if (backup != null) {
                val missingResourceCount = backup.export.missingResourceCount
                runCatching { settingsManager.saveLastBackupTimestamp(now) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                cleanupOldBackups(backup.target)
                if (missingResourceCount > 0) {
                    sendMissingResourceNotification(missingResourceCount)
                }
                return BackupResult.SUCCESS
            }
            return BackupResult.FAILED
        }
        // unreachable — withLock handles the return above
        @Suppress("UNREACHABLE_CODE")
        return BackupResult.NOT_DUE
    }

    /**
     * Read-only mirror of [checkAndBackup]'s fast-path due check. Lets the worker stay silent
     * (no foreground promotion, no notification) when the hourly tick fires but nothing is due.
     */
    suspend fun isBackupDue(): Boolean {
        if (!settingsManager.autoBackupEnabled.safeRead(true)) return false
        val lastBackup = settingsManager.lastBackupTimestamp.safeRead(0L)
        val periodHours = settingsManager.autoBackupPeriodHours.safeRead(24)
        return System.currentTimeMillis() - lastBackup >= periodHours.toLong() * 3600_000L
    }

    fun destroy() {
        scope.cancel()
    }

    // ── Private ───────────────────────────────────────────────

    private data class BackupOutcome(
        val export: DataExporter.ExportResult,
        val target: AutoBackupTarget,
    )

    private suspend fun performBackup(): BackupOutcome? =
        withContext(Dispatchers.IO) {
            try {
                val target = resolveBackupTarget() ?: return@withContext null
                val baseline = target.baseline()
                try {
                    exportBackup(target, baseline)
                } finally {
                    target.releaseBaseline(baseline)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("AutoBackup", "Backup failed", e)
                sendFailureNotification(e.localizedMessage ?: "Auto backup failed")
                null
            }
        }

    private suspend fun exportBackup(
        target: AutoBackupTarget,
        baseline: File?,
    ): BackupOutcome? {
        val categoryKeys = settingsManager.autoBackupCategories.safeRead("conversations,memories,system_prompts,settings")
            .split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()

        val categories = categoryKeys
            .mapNotNull { DataExporter.ExportCategory.fromManifestKey(it) }
            .toSet()

        if (categories.isEmpty()) return null

        // Honor the user's auto-backup category selection, including API keys (the dialog
        // shows an explicit warning when that box is checked).
        val includeApiKeys = DataExporter.ExportCategory.API_KEYS in categories

        val exporter = DataExporter(
            context,
            settingsManager,
            memoryManager,
            skillManager,
        )
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val filename = "Agora_backup_${sdf.format(Date())}.agora"

        var result: DataExporter.ExportResult? = null
        val written = target.write(filename) { uri ->
            result = exporter.export(
                uri = uri,
                categories = categories,
                includeApiKeys = includeApiKeys,
                baselineFile = baseline,
                onProgress = {},
            )
        }
        val export = result.takeIf { written }
        if (export == null) {
            sendFailureNotification("Failed to finalize backup file")
            return null
        }
        DebugLog.d("AutoBackup", "Backup created")
        return BackupOutcome(export, target)
    }

    private suspend fun resolveBackupTarget(): AutoBackupTarget? {
        val stored = settingsManager.autoBackupDirectory
            .safeRead(DEFAULT_BACKUP_DIRECTORY)
            .trim()

        // Storage Access Framework tree picked in settings.
        if (stored.startsWith("content://")) {
            val tree = runCatching { SafBackupTarget(context, Uri.parse(stored)) }.getOrNull()
            if (tree != null && runCatching { tree.entries() }.isSuccess) return tree
            return backupDirectoryUnavailable()
        }

        // Filesystem path
        if (stored.isNotEmpty()) {
            val target = FileBackupTarget(File(stored))
            if (target.prepare()) return target
        }

        // Fallback for this run only: rewriting the setting would erase the user's choice (#211).
        val fallback = FileBackupTarget(defaultBackupDir())
        if (fallback.prepare()) return fallback

        return backupDirectoryUnavailable()
    }

    private fun backupDirectoryUnavailable(): AutoBackupTarget? {
        sendFailureNotification("Backup directory unavailable")
        return null
    }

    private fun defaultBackupDir(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Agora/Backup"
        )
    }

    private suspend fun cleanupOldBackups(target: AutoBackupTarget) {
        val entries = runCatching { target.entries() }.getOrElse { error ->
            DebugLog.e("AutoBackup", "Failed to list backups for cleanup", error)
            return
        }

        // An interrupted write is never a valid backup, so it goes regardless of retention.
        entries.filter { isTemporaryBackupName(it.name) }.forEach(target::delete)

        val deleteEnabled = settingsManager.autoDeleteEnabled.safeRead(true)
        if (!deleteEnabled) return

        val deletePeriodHours = settingsManager.autoDeletePeriodHours.safeRead(168)
        val backupPeriodHours = settingsManager.autoBackupPeriodHours.safeRead(24)

        // Defense: auto-delete must be strictly greater than backup period
        if (deletePeriodHours <= backupPeriodHours) return

        val cutoffTime = System.currentTimeMillis() - deletePeriodHours.toLong() * 3600_000L

        // Providers may withhold the modified time; such files are kept rather than guessed at.
        entries.filter { isCompleteBackupName(it.name) }
            .filter { entry -> entry.lastModified != null && entry.lastModified < cutoffTime }
            .forEach(target::delete)
    }

    private fun sendFailureNotification(message: String) {
        sendNotification(
            title = "Auto backup failed",
            message = message,
        )
    }

    private fun sendMissingResourceNotification(count: Int) {
        sendNotification(
            title = context.getString(R.string.auto_backup_title),
            message = context.getString(R.string.auto_backup_missing_resources, count),
        )
    }

    private fun sendNotification(title: String, message: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    CHANNEL_ID,
                    "Auto Backup",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Auto backup status" }
                nm.createNotificationChannel(channel)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            nm.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .build()
            )
        } catch (e: Exception) {
            DebugLog.e("AutoBackup", "Failed to show notification", e)
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    /** Read a Flow's first value with error tolerance. */
    private suspend fun <T> Flow<T>.safeRead(default: T): T =
        try { first() } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { default }
}
