package com.newoether.agora.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.app.ActivityManager
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.util.CrashReporter
import com.newoether.agora.util.DebugLog

internal enum class ForegroundServiceLifecycleState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    DESTROYING,
}

internal sealed interface ForegroundServiceLeaseAction {
    data object None : ForegroundServiceLeaseAction
    data object Start : ForegroundServiceLeaseAction
    data class Stop(val startId: Int) : ForegroundServiceLeaseAction
}

internal data class ForegroundServiceLeaseTransition(
    val accepted: Boolean,
    val action: ForegroundServiceLeaseAction = ForegroundServiceLeaseAction.None,
)

/**
 * Thread-safe owner and lifecycle state machine for the shared generation foreground service.
 *
 * A call to startForegroundService() creates a platform obligation that remains until this
 * Service has actually called startForeground(). Stopping the Service while it is STARTING, or
 * starting it again while the previous ServiceRecord is being destroyed, can therefore produce
 * ForegroundServiceDidNotStartInTimeException even though onCreate() promotes immediately.
 *
 * STARTING deliberately survives a zero-owner interval so onStartCommand() can promote and then
 * stop with its exact startId. Acquires during STOPPING/DESTROYING wait until destruction has
 * completed before a replacement start is allowed.
 */
internal class ForegroundOwnerLeases {
    private val lock = Any()
    private val owners = linkedSetOf<String>()
    private var lifecycleState = ForegroundServiceLifecycleState.STOPPED
    private var latestStartId: Int? = null

    fun acquire(owner: String): ForegroundServiceLeaseTransition = synchronized(lock) {
        if (!owners.add(owner)) {
            return@synchronized ForegroundServiceLeaseTransition(accepted = false)
        }
        val action = if (lifecycleState == ForegroundServiceLifecycleState.STOPPED) {
            lifecycleState = ForegroundServiceLifecycleState.STARTING
            ForegroundServiceLeaseAction.Start
        } else {
            ForegroundServiceLeaseAction.None
        }
        ForegroundServiceLeaseTransition(accepted = true, action = action)
    }

    /**
     * Rolls back only the owner whose synchronous platform start request failed. Other owners stay
     * registered so their existing lifecycle state remains intact.
     */
    fun startRequestFailed(ownerToRollback: String? = null) = synchronized(lock) {
        if (lifecycleState != ForegroundServiceLifecycleState.STARTING) return@synchronized
        ownerToRollback?.let(owners::remove)
        latestStartId = null
        lifecycleState = ForegroundServiceLifecycleState.STOPPED
    }

    fun release(owner: String): ForegroundServiceLeaseTransition = synchronized(lock) {
        if (!owners.remove(owner)) {
            return@synchronized ForegroundServiceLeaseTransition(accepted = false)
        }
        val action =
            if (owners.isEmpty() && lifecycleState == ForegroundServiceLifecycleState.RUNNING) {
                lifecycleState = ForegroundServiceLifecycleState.STOPPING
                ForegroundServiceLeaseAction.Stop(checkNotNull(latestStartId))
            } else {
                ForegroundServiceLeaseAction.None
            }
        ForegroundServiceLeaseTransition(accepted = true, action = action)
    }

    /**
     * Called only after onCreate() has promoted the Service. A zero-owner STARTING service must
     * still reach this point before it can be stopped, satisfying the platform FGS obligation.
     */
    fun serviceCommandReceived(startId: Int): ForegroundServiceLeaseAction = synchronized(lock) {
        latestStartId = startId
        when (lifecycleState) {
            ForegroundServiceLifecycleState.STARTING,
            ForegroundServiceLifecycleState.STOPPED,
            ForegroundServiceLifecycleState.RUNNING -> {
                if (owners.isEmpty()) {
                    lifecycleState = ForegroundServiceLifecycleState.STOPPING
                    ForegroundServiceLeaseAction.Stop(startId)
                } else {
                    lifecycleState = ForegroundServiceLifecycleState.RUNNING
                    ForegroundServiceLeaseAction.None
                }
            }

            ForegroundServiceLifecycleState.STOPPING,
            ForegroundServiceLifecycleState.DESTROYING ->
                ForegroundServiceLeaseAction.Stop(startId)
        }
    }

    /** Prevents acquires from restarting the component until onDestroy() has fully returned. */
    fun serviceDestroyed() = synchronized(lock) {
        latestStartId = null
        lifecycleState = ForegroundServiceLifecycleState.DESTROYING
    }

    /**
     * Runs from the next main-loop turn after onDestroy(). Only now is the old ServiceRecord safe
     * to replace; owners may have appeared or disappeared while destruction was in progress.
     */
    fun completeServiceDestroyed(): ForegroundServiceLeaseAction = synchronized(lock) {
        if (lifecycleState != ForegroundServiceLifecycleState.DESTROYING) {
            return@synchronized ForegroundServiceLeaseAction.None
        }
        if (owners.isEmpty()) {
            lifecycleState = ForegroundServiceLifecycleState.STOPPED
            ForegroundServiceLeaseAction.None
        } else {
            lifecycleState = ForegroundServiceLifecycleState.STARTING
            ForegroundServiceLeaseAction.Start
        }
    }

    fun size(): Int = synchronized(lock) { owners.size }

    fun lifecycleState(): ForegroundServiceLifecycleState = synchronized(lock) { lifecycleState }
}

/** Uses all non-sign bits, including the Int.MIN_VALUE edge that Math.abs cannot normalize. */
internal fun stableCompletionNotificationId(conversationId: String): Int =
    conversationId.hashCode() and Int.MAX_VALUE

class AgoraForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "agora_generation_status"
        const val NOTIFICATION_ID = 1
        private const val COMPLETION_CHANNEL_ID = "agora_completed"
        private const val TAG = "AgoraForegroundService"
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgoraForegroundService? = null
        // Populated by createChannels() at app startup, independent of `instance`. startService()
        // must be able to call startForegroundService() the very first time a lease is acquired
        // in a process lifetime, before the Service (and therefore `instance`) has ever existed —
        // relying solely on `instance?.applicationContext` is circular and always fails cold.
        @Volatile private var appContextRef: Context? = null
        private val ownerLeases = ForegroundOwnerLeases()

        /** Acquires a lease on the shared foreground service. Returns false if duplicate owner or start failed. */
        fun acquireLease(owner: String): Boolean {
            if (owner.isBlank()) return false
            val transition = ownerLeases.acquire(owner)
            if (!transition.accepted) return false
            if (transition.action == ForegroundServiceLeaseAction.Start && !startService()) {
                ownerLeases.startRequestFailed(owner)
                return false
            }
            CrashReporter.note(
                "FGS.acquireLease owners=${ownerLeases.size()} state=${ownerLeases.lifecycleState()}"
            )
            return true
        }

        /** Releases a lease on the shared foreground service. Stops service if no owners remain. */
        fun releaseLease(owner: String) {
            val transition = ownerLeases.release(owner)
            val action = transition.action
            if (action is ForegroundServiceLeaseAction.Stop) {
                CrashReporter.note("FGS.stop requested startId=${action.startId}")
                instance?.requestLeaseStop(action.startId)
            }
            CrashReporter.note(
                "FGS.releaseLease released=${transition.accepted} owners=${ownerLeases.size()} " +
                    "state=${ownerLeases.lifecycleState()}"
            )
        }

        private fun startService(): Boolean {
            val appContext = appContextRef ?: instance?.applicationContext ?: return false
            val intent = Intent(appContext, AgoraForegroundService::class.java)
            val info = ActivityManager.RunningAppProcessInfo()
            val importance = try {
                ActivityManager.getMyMemoryState(info)
                info.importance
            } catch (e: Exception) {
                CrashReporter.note("FGS.start getMyMemoryState threw ${e.javaClass.simpleName}")
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
            CrashReporter.note("FGS.start api=${Build.VERSION.SDK_INT} importance=$importance trim=${info.lastTrimLevel}")
            if (importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                CrashReporter.note("FGS.start skipped importance=$importance not-foreground")
                DebugLog.w(TAG, "Skipping FGS start: process not foreground (importance=$importance)")
                return false
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
                CrashReporter.note("FGS.startForegroundService ok")
                true
            } catch (e: RuntimeException) {
                CrashReporter.note("FGS.startForegroundService threw ${e.javaClass.simpleName}")
                DebugLog.w(TAG, "Failed to start foreground service", e)
                false
            }
        }

        fun updateText(text: String) {
            instance?.updateNotificationText(text)
        }

        fun createChannels(context: Context) {
            appContextRef = context.applicationContext
            createGenerationChannel(context)
            createCompletionChannel(context)
        }

        private fun createGenerationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Generation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification while Agora is generating"
                setShowBadge(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }

        fun showTerminalNotification(
            context: Context,
            responseText: String,
            conversationId: String,
            isError: Boolean,
        ) {
            createCompletionChannel(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            val displayText = if (responseText.length > 200) {
                responseText.take(200) + "…"
            } else {
                responseText
            }
            val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
                .setContentTitle(
                    context.getString(if (isError) R.string.app_name else R.string.agora_responded)
                )
                .setContentText(displayText)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(
                    createPendingIntent(
                        context,
                        stableCompletionNotificationId(conversationId),
                        conversationId,
                    )
                )
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setStyle(NotificationCompat.BigTextStyle().bigText(displayText))
                .build()

            try {
                manager.notify(stableCompletionNotificationId(conversationId), notification)
            } catch (e: RuntimeException) {
                DebugLog.w(TAG, "Failed to show terminal notification", e)
            }
        }

        fun cancelTerminalNotification(context: Context, conversationId: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            try {
                manager.cancel(stableCompletionNotificationId(conversationId))
            } catch (e: RuntimeException) {
                DebugLog.w(TAG, "Failed to cancel terminal notification", e)
            }
        }

        private fun createCompletionChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                "Response Ready",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shown when a response finishes generating"
                setShowBadge(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build(),
                )
                enableVibration(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        private fun createPendingIntent(
            context: Context,
            requestCode: Int,
            conversationId: String? = null,
        ): PendingIntent {
            return PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    conversationId?.let {
                        data = Uri.Builder()
                            .scheme("agora")
                            .authority("conversation")
                            .appendPath(it)
                            .build()
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, it)
                    }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    // Null until the first updateText; the localized default resolves lazily because
    // getString needs an attached Context (not available at field-init time).
    @Volatile private var currentText: String? = null
    private var foregroundStarted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReporter.note("FGS.onCreate")
        createGenerationChannel(this)
        val notification = buildGenerationNotification(currentText ?: getString(R.string.generating_response))
        // Must NOT catch exceptions here: if startForeground() fails, the real
        // exception (SecurityException, ForegroundServiceStartNotAllowed, etc.)
        // must propagate so Crashlytics/logs capture it. Catching + stopSelf()
        // leaves the system's 5-second timeout to fire, which only surfaces the
        // useless ForegroundServiceDidNotStartInTimeException instead.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundServiceType()
        )
        foregroundStarted = true
        CrashReporter.note("FGS.startForeground ok")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground() already ran in onCreate(). Only now do we have the platform startId
        // required to stop without racing the ServiceRecord's foreground-start obligation.
        val action = ownerLeases.serviceCommandReceived(startId)
        CrashReporter.note(
            "FGS.onStartCommand startId=$startId owners=${ownerLeases.size()} " +
                "state=${ownerLeases.lifecycleState()}"
        )
        if (action is ForegroundServiceLeaseAction.Stop) {
            requestLeaseStop(action.startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ownerLeases.serviceDestroyed()
        if (instance === this) instance = null
        foregroundStarted = false
        CrashReporter.note("FGS.onDestroy owners=${ownerLeases.size()}")
        super.onDestroy()

        // Posting is essential: starting from inside onDestroy() can target the ServiceRecord that
        // is still being brought down. The next main-loop turn runs after destruction completion.
        mainHandler.post {
            val action = ownerLeases.completeServiceDestroyed()
            CrashReporter.note(
                "FGS.destroy complete owners=${ownerLeases.size()} " +
                    "state=${ownerLeases.lifecycleState()} restart=${action is ForegroundServiceLeaseAction.Start}"
            )
            if (action == ForegroundServiceLeaseAction.Start && !startService()) {
                ownerLeases.startRequestFailed()
            }
        }
    }

    private fun requestLeaseStop(startId: Int) {
        val stop = Runnable {
            val stopped = stopSelfResult(startId)
            CrashReporter.note("FGS.stopSelfResult startId=$startId stopped=$stopped")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stop.run()
        } else {
            mainHandler.post(stop)
        }
    }

    private fun updateNotificationText(text: String) {
        currentText = text
        if (!foregroundStarted) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildGenerationNotification(text))
        } catch (e: RuntimeException) {
            DebugLog.w(TAG, "Failed to update notification", e)
        }
    }

    private fun buildGenerationNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(createPendingIntent(this, 0))
            .build()
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
    }

    override fun onTimeout(type: Int, reason: Int) {
        CrashReporter.note("FGS.onTimeout type=$type reason=$reason")
        DebugLog.w(TAG, "Foreground service timed out: type=$type reason=$reason")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
