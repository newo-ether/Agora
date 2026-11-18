package com.newoether.agora.phone

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.map

const val PHONE_MAX_SCREENSHOTS_DEFAULT = 5
const val PHONE_MAX_SCREENSHOTS_MIN = 1
const val PHONE_MAX_SCREENSHOTS_MAX = 16
const val PHONE_MAX_SCREENSHOTS_UNLIMITED = -1

fun normalizePhoneMaxScreenshots(value: Int): Int =
    if (value == PHONE_MAX_SCREENSHOTS_UNLIMITED) value else normalizePhoneScreenshotCount(value)

private fun normalizePhoneScreenshotCount(value: Int): Int =
    value.coerceIn(PHONE_MAX_SCREENSHOTS_MIN, PHONE_MAX_SCREENSHOTS_MAX)

/** A single DataStore snapshot keeps the mode and its app access decision consistent. */
data class PhoneUseSettings(
    val enabled: Boolean = false,
    val mode: String = "accessibility",
    val allowAutomation: Boolean = false,
    val accessMode: String = "off",
    val blocklist: Set<String> = emptySet(),
    val allowlist: Set<String> = emptySet(),
    val maxScreenshots: Int = PHONE_MAX_SCREENSHOTS_DEFAULT,
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
    private val maxScreenshots = intPreferencesKey("phone_use_max_screenshots")
    // A raw legacy count must never opt into Unlimited, even if it matches the domain sentinel.
    private val unlimitedScreenshots = booleanPreferencesKey("phone_use_screenshots_unlimited")
    val values = store.data.map { pref -> PhoneUseSettings(
        enabled = pref[enabled] ?: false,
        mode = pref[mode]?.takeIf { it in listOf("accessibility", "shizuku", "root") } ?: "accessibility",
        allowAutomation = pref[automation] ?: false,
        accessMode = pref[access]?.takeIf { it in listOf("off", "blocklist", "allowlist") } ?: "off",
        blocklist = pref[blocked].orEmpty(), allowlist = pref[allowed].orEmpty(),
        maxScreenshots = if (pref[unlimitedScreenshots] == true) PHONE_MAX_SCREENSHOTS_UNLIMITED
            else normalizePhoneScreenshotCount(pref[maxScreenshots] ?: PHONE_MAX_SCREENSHOTS_DEFAULT),
    ) }
    suspend fun setEnabled(value: Boolean) { store.edit { it[enabled] = value } }
    suspend fun setMode(value: String) {
        require(value in listOf("accessibility", "shizuku", "root"))
        store.edit { it[mode] = value }
    }
    suspend fun setAutomation(value: Boolean) { store.edit { it[automation] = value } }
    suspend fun setMaxScreenshots(value: Int) {
        val normalized = normalizePhoneMaxScreenshots(value)
        store.edit { pref ->
            if (normalized == PHONE_MAX_SCREENSHOTS_UNLIMITED) {
                pref[unlimitedScreenshots] = true
            } else {
                pref.remove(unlimitedScreenshots)
                pref[maxScreenshots] = normalized
            }
        }
    }
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
