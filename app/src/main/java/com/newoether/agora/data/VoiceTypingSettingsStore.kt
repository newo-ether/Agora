package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.newoether.agora.voice.VoiceTypingConfig
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class VoiceTypingSettingsStore(private val store: DataStore<Preferences>) {
    private val key = VOICE_TYPING_CONFIG
    val config = store.data.map { VoiceTypingConfig.decode(it[key]) }
    suspend fun save(config: VoiceTypingConfig) = update { config }
    suspend fun update(change: (VoiceTypingConfig) -> VoiceTypingConfig) {
        store.edit { it[key] = Json.encodeToString(change(VoiceTypingConfig.decode(it[key]))) }
    }
}
