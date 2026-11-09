package com.newoether.agora.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import com.newoether.agora.data.local.LoopEntity
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.data.repository.TaskRepository
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.service.LoopWorker
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped owner of one-shot alarms for Tasks and conversation Loops.
 *
 * Room remains the source of truth. The in-memory maps remember the *logical* persisted fire
 * time, not the temporary `now + 1s` catch-up time used for an overdue alarm. Consequently an
 * unrelated Room emission never replaces an already-armed alarm and cannot skip or starve an
 * occurrence that Android is delaying in Doze. All map and AlarmManager operations are serialized
 * because boot/permission refreshes can run concurrently with the database collector.
 */
class AutomationScheduler(
    private val context: Context,
    private val taskRepository: TaskRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private data class Snapshot(
        val tasks: List<TaskEntity>,
        val loops: List<LoopEntity>,
        val exactRequested: Boolean,
        val appInForeground: Boolean,
    )

    private data class ArmedAlarm(
        val logicalFireAt: Long,
        val exact: Boolean,
    )

    private data class ForegroundLoopTimer(
        val logicalFireAt: Long,
        val job: Job,
    )

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val schedulingMutex = Mutex()
    private val armedTasks = mutableMapOf<String, ArmedAlarm>()
    private val armedLoops = mutableMapOf<String, ArmedAlarm>()
    private val foregroundLoopTimers = mutableMapOf<String, ForegroundLoopTimer>()
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                taskRepository.getAllTasks(),
                taskRepository.observeActiveLoops(),
                settings.exactExecutionEnabled,
                AppForegroundTracker.foreground,
            ) { tasks, loops, exact, foreground -> Snapshot(tasks, loops, exact, foreground) }
                .collect(::reschedule)
        }
    }

    /** Fire-and-forget refresh for ordinary app code. Receivers use [refreshAndAwait]. */
    fun refresh(recalculateForClockChange: Boolean = false) {
        scope.launch { refreshAndAwait(recalculateForClockChange) }
    }

    /**
     * Re-reads persisted state and does not return until every AlarmManager operation is complete.
     * This lets a BroadcastReceiver keep its `goAsync()` result alive through the actual re-arm.
     */
    suspend fun refreshAndAwait(recalculateForClockChange: Boolean = false) {
        var tasks = taskRepository.getAllTasks().first()
        var loops = taskRepository.observeActiveLoops().first()
        if (recalculateForClockChange) {
            val now = System.currentTimeMillis()
            // A one-shot's instant is absolute, so a clock/timezone change must not move it —
            // only cron occurrences are re-derived from the new wall clock.
            tasks.filter { it.enabled && it.cronExpr.isNotBlank() }.forEach { task ->
                val next = CronExpression.parse(task.cronExpr)?.next(now) ?: 0L
                taskRepository.updateTaskNextRunAtIfUnchanged(task, next)
            }
            loops.forEach { loop ->
                val maxCycles = loop.maxCycles ?: LoopPolicy.DEFAULT_MAX_CYCLES
                if (LoopPolicy.validate(loop.intervalMs, maxCycles) == null) {
                    taskRepository.updateLoopNextFireAtIfUnchanged(
                        loop,
                        LoopPolicy.nextFireAt(now, loop.intervalMs),
                    )
                } else {
                    taskRepository.deactivateLoopIfUnchanged(loop, maxCycles)
                }
            }
            // Re-read after CAS operations. A concurrent edit/stop/execution that won a race is
            // now authoritative and must be the snapshot handed to AlarmManager.
            tasks = taskRepository.getAllTasks().first()
            loops = taskRepository.observeActiveLoops().first()
        }
        reschedule(
            Snapshot(
                tasks = tasks,
                loops = loops,
                exactRequested = settings.exactExecutionEnabled.value,
                appInForeground = AppForegroundTracker.isInForeground,
            )
        )
    }

    private suspend fun reschedule(snapshot: Snapshot) = schedulingMutex.withLock {
        val now = System.currentTimeMillis()
        val useExact = snapshot.exactRequested && canScheduleExactAlarms()

        val activeTasks = snapshot.tasks.filter {
            it.enabled && it.name.isNotBlank() && it.prompt.isNotBlank() && when (val runAt = it.runAt) {
                null -> it.cronExpr.isNotBlank() && CronExpression.isValid(it.cronExpr)
                // One-shot: armable only while its instant is still ahead of us.
                else -> runAt > now
            }
        }
        val activeTaskIds = activeTasks.mapTo(mutableSetOf()) { it.id }
        (armedTasks.keys - activeTaskIds).toList().forEach(::cancelTaskLocked)
        activeTasks.forEach { task ->
            // nextRunAt may be 0 for an enabled task if a prior path cleared it without recomputing
            // (e.g. a future bug or a manual edit). Arm from cron in that case, and persist the
            // computed value so executeById's stale check matches and the alarm doesn't fire into
            // a permanent H4-style skip loop.
            val logicalFireAt = task.nextRunAt.takeIf { it > 0L }
                ?: (task.runAt?.takeIf { it > now }
                    ?: CronExpression.parse(task.cronExpr)?.next(now))?.also { next ->
                    taskRepository.updateTaskNextRunAtIfUnchanged(task, next)
                } ?: return@forEach
            val desired = ArmedAlarm(logicalFireAt, useExact)
            if (armedTasks[task.id] == desired) return@forEach

            val actualFireAt = logicalFireAt.coerceAtLeast(now + MIN_TRIGGER_DELAY_MS)
            if (arm(actualFireAt, taskPendingIntent(task.id, logicalFireAt), useExact)) {
                armedTasks[task.id] = desired
                DebugLog.d(
                    "AutomationScheduler",
                    "armed task=${task.id} logical=$logicalFireAt actual=$actualFireAt exact=$useExact",
                )
            }
        }

        val activeLoopIds = snapshot.loops.mapTo(mutableSetOf()) { it.conversationId }
        (armedLoops.keys - activeLoopIds).toList().forEach(::cancelLoopLocked)
        (foregroundLoopTimers.keys - activeLoopIds).toList().forEach(::cancelForegroundLoopTimerLocked)
        snapshot.loops.forEach { loop ->
            val logicalFireAt = loop.nextFireAt
            if (logicalFireAt <= 0L) return@forEach

            if (snapshot.appInForeground) {
                cancelLoopAlarmLocked(loop.conversationId)
                scheduleForegroundLoopTimerLocked(loop.conversationId, logicalFireAt)
                return@forEach
            }

            cancelForegroundLoopTimerLocked(loop.conversationId)
            val desired = ArmedAlarm(logicalFireAt, useExact)
            if (armedLoops[loop.conversationId] == desired) return@forEach

            val actualFireAt = logicalFireAt.coerceAtLeast(now + MIN_TRIGGER_DELAY_MS)
            if (arm(actualFireAt, loopPendingIntent(loop.conversationId, logicalFireAt), useExact)) {
                armedLoops[loop.conversationId] = desired
                DebugLog.d(
                    "AutomationScheduler",
                    "armed loop=${loop.conversationId} logical=$logicalFireAt actual=$actualFireAt exact=$useExact",
                )
            }
        }
    }

    private fun arm(triggerAt: Long, pendingIntent: PendingIntent, exact: Boolean): Boolean = try {
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        true
    } catch (e: SecurityException) {
        // Permission can be revoked between canScheduleExactAlarms() and setExact().
        DebugLog.w("AutomationScheduler", "Exact alarm permission changed; using inexact", e)
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            true
        } catch (fallback: RuntimeException) {
            DebugLog.e("AutomationScheduler", "Failed to arm fallback alarm", fallback)
            false
        }
    } catch (e: RuntimeException) {
        DebugLog.e("AutomationScheduler", "Failed to arm alarm", e)
        false
    }

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    suspend fun cancelTask(taskId: String) = schedulingMutex.withLock {
        cancelTaskLocked(taskId)
    }

    suspend fun cancelLoop(conversationId: String) = schedulingMutex.withLock {
        cancelLoopLocked(conversationId)
    }

    private fun cancelTaskLocked(taskId: String) {
        alarmManager.cancel(taskPendingIntent(taskId, 0L))
        armedTasks.remove(taskId)
    }

    private fun cancelLoopLocked(conversationId: String) {
        cancelLoopAlarmLocked(conversationId)
        cancelForegroundLoopTimerLocked(conversationId)
    }

    private fun cancelLoopAlarmLocked(conversationId: String) {
        alarmManager.cancel(loopPendingIntent(conversationId, 0L))
        armedLoops.remove(conversationId)
    }

    private fun cancelForegroundLoopTimerLocked(conversationId: String) {
        foregroundLoopTimers.remove(conversationId)?.job?.cancel()
    }

    /** Foreground loops use a process timer so a 60-second cadence is not degraded to an hour. */
    private fun scheduleForegroundLoopTimerLocked(conversationId: String, logicalFireAt: Long) {
        if (foregroundLoopTimers[conversationId]?.logicalFireAt == logicalFireAt) return
        cancelForegroundLoopTimerLocked(conversationId)
        val job = scope.launch {
            delay((logicalFireAt - System.currentTimeMillis()).coerceAtLeast(MIN_TRIGGER_DELAY_MS))
            schedulingMutex.withLock {
                val current = foregroundLoopTimers[conversationId]
                if (
                    current?.logicalFireAt == logicalFireAt &&
                    AppForegroundTracker.isInForeground
                ) {
                    // Keep the completed timer marker until Room advances the occurrence. This
                    // prevents unrelated DB emissions from enqueueing the same due cycle again.
                    LoopWorker.enqueue(context, conversationId, logicalFireAt)
                }
            }
        }
        foregroundLoopTimers[conversationId] = ForegroundLoopTimer(logicalFireAt, job)
    }

    private fun taskPendingIntent(taskId: String, scheduledAt: Long): PendingIntent =
        receiverPendingIntent(
            action = AutomationAlarmReceiver.ACTION_FIRE_TASK,
            data = "agora://automation/task/$taskId".toUri(),
            key = AutomationAlarmReceiver.EXTRA_TASK_ID,
            value = taskId,
            scheduledAt = scheduledAt,
        )

    private fun loopPendingIntent(conversationId: String, scheduledAt: Long): PendingIntent =
        receiverPendingIntent(
            action = AutomationAlarmReceiver.ACTION_FIRE_LOOP,
            data = "agora://automation/loop/$conversationId".toUri(),
            key = AutomationAlarmReceiver.EXTRA_CONVERSATION_ID,
            value = conversationId,
            scheduledAt = scheduledAt,
        )

    private fun receiverPendingIntent(
        action: String,
        data: Uri,
        key: String,
        value: String,
        scheduledAt: Long,
    ): PendingIntent {
        val intent = Intent(context, AutomationAlarmReceiver::class.java).apply {
            this.action = action
            this.data = data
            putExtra(key, value)
            putExtra(AutomationAlarmReceiver.EXTRA_SCHEDULED_AT, scheduledAt)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val MIN_TRIGGER_DELAY_MS = 1_000L
    }
}
