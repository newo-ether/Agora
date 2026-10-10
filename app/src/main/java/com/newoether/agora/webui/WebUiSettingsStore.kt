package com.newoether.agora.webui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

/**
 * WebUI preferences. The password is stored only as its PBKDF2 hash. These keys are
 * device-local: they are not part of the portable settings archive, so a backup never carries
 * the WebUI password to another device.
 */
internal class WebUiSettingsStore(private val dataStore: DataStore<Preferences>) {
    val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: false }
    val port: Flow<Int> = dataStore.data.map { it[PORT] ?: DEFAULT_PORT }
    val passwordHash: Flow<String?> = dataStore.data.map { it[PASSWORD_HASH] }
    val https: Flow<Boolean> = dataStore.data.map { it[HTTPS] ?: true }
    val sessionDigests: Flow<Set<String>> = dataStore.data.map { it[SESSIONS] ?: emptySet() }

    suspend fun addSession(expectedPasswordHash: String, digest: String): Boolean {
        var accepted = false
        dataStore.edit {
            if (it[PASSWORD_HASH] == expectedPasswordHash) {
                it[SESSIONS] = (it[SESSIONS] ?: emptySet()) + digest
                accepted = true
            }
        }
        return accepted
    }

    suspend fun removeSession(digest: String) {
        dataStore.edit { it[SESSIONS] = (it[SESSIONS] ?: emptySet()) - digest }
    }

    suspend fun clearSessions() { dataStore.edit { it.remove(SESSIONS) } }

    suspend fun saveEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    suspend fun savePort(port: Int) {
        require(port in PORT_RANGE) { "Port $port is outside $PORT_RANGE" }
        dataStore.edit { it[PORT] = port }
    }

    suspend fun saveHttps(enabled: Boolean) {
        dataStore.edit { it[HTTPS] = enabled }
    }
    suspend fun savePasswordHash(hash: String) {
        dataStore.edit {
            it[PASSWORD_HASH] = hash
            it.remove(SESSIONS)
        }
    }

    companion object {
        const val DEFAULT_PORT = 8686
        /** Unprivileged ports only; Android apps cannot bind below 1024. */
        val PORT_RANGE = 1024..65535
        private val ENABLED = booleanPreferencesKey("webui_enabled")
        private val PORT = intPreferencesKey("webui_port")
        private val PASSWORD_HASH = stringPreferencesKey("webui_password_hash")
        private val HTTPS = booleanPreferencesKey("webui_https")
        private val SESSIONS = stringSetPreferencesKey("webui_session_digests")
    }
}
