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
    /** Drive a hidden virtual display. Only takes effect in Shizuku mode. */
    val background: Boolean = false,
    /** One of [PhoneBackgroundGeometry.SIZES]. */
    val backgroundSize: String = "native",
    /** Custom size as short and long side in pixels; 0 means unset. */
    val backgroundCustomShort: Int = 0,
    val backgroundCustomLong: Int = 0,
    val backgroundLandscape: Boolean = false,
    /** Ask the user before a conversation takes control of the phone. */
    val confirmClaim: Boolean = true,
) {
    val packages: Set<String> get() = if (accessMode == "blocklist") blocklist else allowlist
    val backgroundActive: Boolean get() = background && mode == "shizuku"
}

class PhoneUseSettingsStore(private val store: DataStore<Preferences>) {
    private val enabled = booleanPreferencesKey("phone_use_enabled")
    private val mode = stringPreferencesKey("phone_use_mode")
    private val automation = booleanPreferencesKey("phone_use_allow_automation")
    private val access = stringPreferencesKey("phone_use_app_access")
    private val blocked = stringSetPreferencesKey("phone_use_blocklist")
    private val allowed = stringSetPreferencesKey("phone_use_allowlist")
    private val maxScreenshots = intPreferencesKey("phone_use_max_screenshots")
    private val background = booleanPreferencesKey("phone_use_background")
    private val backgroundSize = stringPreferencesKey("phone_use_background_size")
    private val backgroundShort = intPreferencesKey("phone_use_background_short")
    private val backgroundLong = intPreferencesKey("phone_use_background_long")
    private val backgroundLandscape = booleanPreferencesKey("phone_use_background_landscape")
    private val confirmClaim = booleanPreferencesKey("phone_use_confirm_claim")
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
        background = pref[background] ?: false,
        backgroundSize = pref[backgroundSize]?.takeIf { it in PhoneBackgroundGeometry.SIZES } ?: "native",
        backgroundCustomShort = pref[backgroundShort] ?: 0,
        backgroundCustomLong = pref[backgroundLong] ?: 0,
        backgroundLandscape = pref[backgroundLandscape] ?: false,
        confirmClaim = pref[confirmClaim] ?: true,
    ) }
    suspend fun setConfirmClaim(value: Boolean) { store.edit { it[confirmClaim] = value } }
    suspend fun setBackground(value: Boolean) { store.edit { it[background] = value } }
    suspend fun setBackgroundSize(value: String) {
        require(value in PhoneBackgroundGeometry.SIZES)
        store.edit { it[backgroundSize] = value }
    }
    /** Stores a custom size and selects it; the sides are kept as short and long. */
    suspend fun setBackgroundCustom(a: Int, b: Int) {
        require(a > 0 && b > 0)
        store.edit {
            it[backgroundShort] = minOf(a, b)
            it[backgroundLong] = maxOf(a, b)
            it[backgroundSize] = "custom"
        }
    }
    suspend fun setBackgroundLandscape(value: Boolean) { store.edit { it[backgroundLandscape] = value } }
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
