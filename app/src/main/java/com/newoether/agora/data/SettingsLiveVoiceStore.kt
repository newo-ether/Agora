package com.newoether.agora.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.newoether.agora.voice.live.LiveVoiceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val LIVE_VOICE_CONFIG_JSON = stringPreferencesKey("live_voice_config_json")

class SettingsLiveVoiceStore(private val context: Context, private val json: Json) {
    val liveVoiceConfig: Flow<LiveVoiceConfig> = context.dataStore.data.map { pref ->
        val jsonStr = pref[LIVE_VOICE_CONFIG_JSON] ?: ""
        if (jsonStr.isBlank()) LiveVoiceConfig()
        else try {
            json.decodeFromString<LiveVoiceConfig>(jsonStr)
        } catch (_: Exception) {
            LiveVoiceConfig()
        }
    }

    suspend fun saveLiveVoiceConfig(config: LiveVoiceConfig) {
        context.dataStore.edit { it[LIVE_VOICE_CONFIG_JSON] = json.encodeToString(config) }
    }
}
