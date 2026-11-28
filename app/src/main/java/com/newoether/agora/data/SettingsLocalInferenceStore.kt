package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Owns local llama.cpp inference tuning: worker thread count and KV cache precision. Both are
 * load-time parameters, so a change rebuilds the resident model on the next local request.
 */
internal class SettingsLocalInferenceStore(private val dataStore: DataStore<Preferences>) {
    val inferenceThreads: Flow<Int> = dataStore.data.map {
        normalizeLocalInferenceThreads(it[LOCAL_INFERENCE_THREADS])
    }
    val kvCacheType: Flow<String> = dataStore.data.map {
        normalizeLocalKvCacheType(it[LOCAL_KV_CACHE_TYPE])
    }

    suspend fun saveInferenceThreads(threads: Int) {
        dataStore.edit { it[LOCAL_INFERENCE_THREADS] = normalizeLocalInferenceThreads(threads) }
    }

    suspend fun saveKvCacheType(type: String) {
        dataStore.edit { it[LOCAL_KV_CACHE_TYPE] = normalizeLocalKvCacheType(type) }
    }
}
