package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Owns the scheduled-backup and retention preferences: whether the periodic backup runs, how often,
 * what it covers, where it writes, and when it last succeeded. The worker and the Data Control page
 * read the same store, so the schedule has a single owner instead of being spread across the
 * settings facade.
 */
internal class SettingsBackupPreferenceStore(private val dataStore: DataStore<Preferences>) {
    val autoBackupEnabled: Flow<Boolean> = dataStore.data.map { it[AUTO_BACKUP_ENABLED] ?: true }
    val autoBackupPeriodHours: Flow<Int> = dataStore.data.map { it[AUTO_BACKUP_PERIOD_HOURS] ?: DEFAULT_PERIOD_HOURS }
    val autoBackupCategories: Flow<String> = dataStore.data.map { it[AUTO_BACKUP_CATEGORIES] ?: DEFAULT_CATEGORIES }
    val autoBackupDirectory: Flow<String> = dataStore.data.map { it[AUTO_BACKUP_DIRECTORY] ?: DEFAULT_DIRECTORY }
    val autoDeleteEnabled: Flow<Boolean> = dataStore.data.map { it[AUTO_DELETE_ENABLED] ?: true }
    val autoDeletePeriodHours: Flow<Int> = dataStore.data.map { it[AUTO_DELETE_PERIOD_HOURS] ?: DEFAULT_DELETE_PERIOD_HOURS }
    val lastBackupTimestamp: Flow<Long> = dataStore.data.map { it[LAST_BACKUP_TIMESTAMP] ?: 0L }

    suspend fun saveAutoBackupEnabled(enabled: Boolean) {
        dataStore.edit { it[AUTO_BACKUP_ENABLED] = enabled }
    }

    suspend fun saveAutoBackupPeriodHours(hours: Int) {
        dataStore.edit { it[AUTO_BACKUP_PERIOD_HOURS] = hours }
    }

    suspend fun saveAutoBackupCategories(categories: String) {
        dataStore.edit { it[AUTO_BACKUP_CATEGORIES] = categories }
    }

    suspend fun saveAutoBackupDirectory(path: String) {
        dataStore.edit { it[AUTO_BACKUP_DIRECTORY] = path }
    }

    suspend fun saveAutoDeleteEnabled(enabled: Boolean) {
        dataStore.edit { it[AUTO_DELETE_ENABLED] = enabled }
    }

    suspend fun saveAutoDeletePeriodHours(hours: Int) {
        dataStore.edit { it[AUTO_DELETE_PERIOD_HOURS] = hours }
    }

    suspend fun saveLastBackupTimestamp(timestamp: Long) {
        dataStore.edit { it[LAST_BACKUP_TIMESTAMP] = timestamp }
    }

    companion object {
        const val DEFAULT_PERIOD_HOURS = 24
        const val DEFAULT_DELETE_PERIOD_HOURS = 168
        const val DEFAULT_CATEGORIES = "conversations,memories,system_prompts,settings"
        const val DEFAULT_DIRECTORY = "Download/Agora/Backup"
    }
}
