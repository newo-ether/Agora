package com.newoether.agora.phone

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map

/** A single DataStore snapshot keeps the mode and its app access decision consistent. */
data class PhoneUseSettings(
    val enabled: Boolean = false,
    val mode: String = "accessibility",
    val allowAutomation: Boolean = false,
    val accessMode: String = "off",
    val blocklist: Set<String> = emptySet(),
    val allowlist: Set<String> = emptySet(),
) {
    val packages: Set<String> get() = if (accessMode == "blocklist") blocklist else allowlist
}

class PhoneUseSettingsStore(private val store: DataStore<Preferences>) {
    private val enabled = booleanPreferencesKey("phone_use_enabled")
    private val mode = stringPreferencesKey("phone_use_mode")
    private val automation = booleanPreferencesKey("phone_use_allow_automation")
    private val access = stringPreferencesKey("phone_use_app_access")
    private val blocked = stringSetPreferencesKey("phone_use_blocklist")
    private val allowed = stringSetPreferencesKey("phone_use_allowlist")
    val values = store.data.map { pref -> PhoneUseSettings(
        enabled = pref[enabled] ?: false,
        mode = pref[mode]?.takeIf { it in listOf("accessibility", "shizuku", "root") } ?: "accessibility",
        allowAutomation = pref[automation] ?: false,
        accessMode = pref[access]?.takeIf { it in listOf("off", "blocklist", "allowlist") } ?: "off",
        blocklist = pref[blocked].orEmpty(), allowlist = pref[allowed].orEmpty(),
    ) }
    suspend fun setEnabled(value: Boolean) { store.edit { it[enabled] = value } }
    suspend fun setMode(value: String) {
        require(value in listOf("accessibility", "shizuku", "root"))
        store.edit { it[mode] = value }
    }
    suspend fun setAutomation(value: Boolean) { store.edit { it[automation] = value } }
    suspend fun setAccessMode(value: String) {
        require(value in listOf("off", "blocklist", "allowlist"))
        store.edit { it[access] = value }
    }
    suspend fun setPackage(packageName: String, selected: Boolean, listMode: String, ownPackage: String) {
        require(packageName != ownPackage)
        require(listMode == "blocklist" || listMode == "allowlist")
        val key = if (listMode == "blocklist") blocked else allowed
        store.edit { pref ->
            val current = pref[key].orEmpty() - ownPackage
            pref[key] = if (selected) current + packageName else current - packageName
        }
    }
}
