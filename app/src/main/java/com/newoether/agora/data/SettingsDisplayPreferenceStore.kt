package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Chat-display preferences small enough to skip a dedicated store per setting. Lives outside
 * [SettingsManager] because that file sits at the source size limit.
 */
internal class SettingsDisplayPreferenceStore(private val dataStore: DataStore<Preferences>) {
    val showGenerationSpeed: Flow<Boolean> =
        dataStore.data.map { it[SHOW_GENERATION_SPEED] ?: false }

    suspend fun saveShowGenerationSpeed(enabled: Boolean) {
        dataStore.edit { it[SHOW_GENERATION_SPEED] = enabled }
    }
}
