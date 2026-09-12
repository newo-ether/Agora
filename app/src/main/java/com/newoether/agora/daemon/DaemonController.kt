package com.newoether.agora.daemon

import android.content.Context
import com.newoether.agora.automation.HeartbeatScheduler
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker

/**
 * Controls the daemon lifecycle by acquiring/releasing a lease on [AgoraForegroundService].
 *
 * The daemon does NOT create its own foreground service. Instead, it registers as an owner
 * of the existing [AgoraForegroundService] (which already runs with `dataSync` type).
 * This avoids duplicate notifications and centralizes foreground-service management.
 *
 * When enabled:
 * 1. Requests POST_NOTIFICATIONS permission (Android 13+)
 * 2. Acquires a "daemon" lease on AgoraForegroundService
 * 3. Starts [HeartbeatScheduler]
 *
 * When disabled:
 * 1. Stops [HeartbeatScheduler]
 * 2. Releases the "daemon" lease
 */
class DaemonController(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val heartbeatScheduler: HeartbeatScheduler,
    private val appForegroundTracker: AppForegroundTracker,
) {
    @Volatile
    private var isStarted = false

    private val foregroundListener: (Boolean) -> Unit = { inForeground ->
        // App came to foreground and daemon is enabled — ensure lease is active
        if (inForeground && settingsRepository.daemonEnabled.value) {
            AgoraForegroundService.acquireLease("daemon")
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true

        // Check POST_NOTIFICATIONS permission on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val pm = context.packageManager
            val perm = pm.checkPermission(
                "android.permission.POST_NOTIFICATIONS",
                context.packageName,
            )
            if (perm != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Permission denied — revert setting and don't start
                settingsRepository.saveDaemonEnabled(false)
                isStarted = false
                return
            }
        }

        // Acquire lease on the shared foreground service
        AgoraForegroundService.acquireLease("daemon")

        // Start the heartbeat scheduler
        heartbeatScheduler.start()

        // Track foreground state for auto-restart
        appForegroundTracker.addListener(foregroundListener)
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false

        // Stop the heartbeat scheduler before releasing the lease. stop() is not suspend, so
        // calling it synchronously guarantees the loop is actually cancelled — launching it
        // inside this controller's scope and then cancelling that scope could skip it entirely.
        heartbeatScheduler.stop()
        appForegroundTracker.removeListener(foregroundListener)
        AgoraForegroundService.releaseLease("daemon")
    }

    fun isRunning(): Boolean = isStarted
}