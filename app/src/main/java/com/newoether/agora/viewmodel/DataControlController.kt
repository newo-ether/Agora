package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SkillManager
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.service.AutoBackupWorker
import com.newoether.agora.util.DebugLog
import com.newoether.agora.data.StorageReport
import com.newoether.agora.data.local.UsageLedgerRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal interface AutoBackupSchedulePort {
    fun schedule()
    fun cancel()
}

internal class AndroidAutoBackupSchedulePort(
    private val application: Application,
) : AutoBackupSchedulePort {
    override fun schedule() = AutoBackupWorker.schedule(application)
    override fun cancel() = AutoBackupWorker.cancel(application)
}

/** Owns Data Control counts and the automatic-backup/delete settings lifecycle. */
class DataControlController internal constructor(
    private val conversations: ConversationRepository,
    private val memory: MemoryManager,
    private val skills: SkillManager,
    private val settings: SettingsRepository,
    private val backupManager: AutoBackupManager,
    private val backupSchedule: AutoBackupSchedulePort,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val storageReportSource: (suspend () -> StorageReport)? = null,
    /** Last persisted Storage report, shown dimmed while the first scan of a process runs. */
    private val storageCachedSource: (suspend () -> StorageReport?)? = null,
    private val usageRowsSource: (suspend () -> List<UsageLedgerRow>)? = null,
) {
    private val _usageRows = MutableStateFlow<List<UsageLedgerRow>?>(null)
    /** Raw usage ledger rows; the page aggregates them for the local time zone. */
    val usageRows: StateFlow<List<UsageLedgerRow>?> = _usageRows.asStateFlow()

    private val _usageRefreshing = MutableStateFlow(false)
    val usageRefreshing: StateFlow<Boolean> = _usageRefreshing.asStateFlow()
    private var usageJob: Job? = null
    @Volatile
    private var usageReloadRequested = false

    /**
     * Reloads the usage ledger in the background. A request made while a reload runs is folded
     * into one more pass, so a ledger write that lands mid-query is never missed.
     */
    fun refreshUsage() {
        val source = usageRowsSource ?: return
        usageReloadRequested = true
        if (usageJob?.isActive == true) return
        usageJob = scope.launch(ioDispatcher) {
            _usageRefreshing.value = true
            try {
                while (usageReloadRequested) {
                    usageReloadRequested = false
                    _usageRows.value = source()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.e("DataControl", "Usage query failed", error)
            } finally {
                _usageRefreshing.value = false
            }
        }
    }

    private val _storageReport = MutableStateFlow<StorageReport?>(null)
    /** Last Storage page report; kept for the process so re-entry shows it at once. */
    val storageReport: StateFlow<StorageReport?> = _storageReport.asStateFlow()

    private val _storageRefreshing = MutableStateFlow(false)
    val storageRefreshing: StateFlow<Boolean> = _storageRefreshing.asStateFlow()
    private var storageJob: Job? = null

    /** Recomputes the Storage report in the background; a running refresh is reused. */
    fun refreshStorage() {
        val source = storageReportSource ?: return
        if (storageJob?.isActive == true) return
        storageJob = scope.launch(ioDispatcher) {
            _storageRefreshing.value = true
            try {
                if (_storageReport.value == null) {
                    // The cache reader already treats an unreadable file as no cache.
                    storageCachedSource?.invoke()?.let { cached ->
                        if (_storageReport.value == null) _storageReport.value = cached
                    }
                }
                _storageReport.value = source()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.e("DataControl", "Storage report failed", error)
            } finally {
                _storageRefreshing.value = false
            }
        }
    }

    private val _conversationCount = MutableStateFlow(0)
    val conversationCount: StateFlow<Int> = _conversationCount.asStateFlow()

    private val _memoryCount = MutableStateFlow(0)
    val memoryCount: StateFlow<Int> = _memoryCount.asStateFlow()

    private val _systemPromptCount = MutableStateFlow(0)
    val systemPromptCount: StateFlow<Int> = _systemPromptCount.asStateFlow()

    internal fun startAutoBackup() {
        try {
            backupSchedule.schedule()
        } catch (error: Exception) {
            DebugLog.e("ChatViewModel", "AutoBackupWorker.schedule failed", error)
        }
    }

    internal fun destroy() {
        backupManager.destroy()
    }

    fun refreshCounts() {
        scope.launch(ioDispatcher) {
            _conversationCount.value = conversations.getAllConversationsList().size
            _memoryCount.value = memory.listFiles().size +
                skills.listFiles().size +
                (if (memory.getActiveMemory().isNotEmpty()) 1 else 0)
            _systemPromptCount.value = settings.getSystemPrompts().size
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        scope.launch(ioDispatcher) {
            settings.saveAutoBackupEnabled(enabled)
            if (enabled) {
                try {
                    backupSchedule.schedule()
                } catch (_: Exception) {
                }
            } else {
                try {
                    backupSchedule.cancel()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun setAutoBackupPeriodHours(hours: Int) {
        scope.launch(ioDispatcher) {
            settings.saveAutoBackupPeriodHours(hours)
            val deleteHours = settings.autoDeletePeriodHours.value
            if (deleteHours <= hours) {
                val nextDelete = AUTO_DELETE_TIERS_HOURS.firstOrNull { it > hours }
                    ?: AUTO_DELETE_TIERS_HOURS.last()
                settings.saveAutoDeletePeriodHours(nextDelete)
            }
        }
    }

    fun setAutoBackupCategories(categories: String) {
        scope.launch(ioDispatcher) { settings.saveAutoBackupCategories(categories) }
    }

    fun setAutoBackupDirectory(path: String) {
        scope.launch(ioDispatcher) { settings.saveAutoBackupDirectory(path) }
    }

    fun setAutoDeleteEnabled(enabled: Boolean) {
        scope.launch(ioDispatcher) { settings.saveAutoDeleteEnabled(enabled) }
    }

    fun setAutoDeletePeriodHours(hours: Int) {
        scope.launch(ioDispatcher) {
            val backupHours = settings.autoBackupPeriodHours.value
            val minimumValid = AUTO_DELETE_TIERS_HOURS.firstOrNull { it > backupHours }
                ?: AUTO_DELETE_TIERS_HOURS.last()
            settings.saveAutoDeletePeriodHours(maxOf(hours, minimumValid))
        }
    }

    private companion object {
        val AUTO_DELETE_TIERS_HOURS = listOf(168, 720, 8760)
    }
}
