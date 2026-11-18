package com.newoether.agora.phone

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Test

class PhoneUseSettingsStoreTest {
    @Test fun settingsPersistAndAccessListsStayIndependent() = runBlocking {
        val directory = Files.createTempDirectory("agora-phone-settings").toFile()
        var job = SupervisorJob()
        fun openStore() = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO),
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                directory.resolve("phone.preferences_pb").absolutePath.toPath()
            },
        )
        var preferences = openStore()
        try {
            val store = PhoneUseSettingsStore(preferences)
            assertEquals(PhoneUseSettings(), store.values.first())
            assertEquals(5, store.values.first().maxScreenshots)
            store.setMaxScreenshots(11)
            store.setEnabled(true)
            store.setMode("shizuku")
            store.setAutomation(true)
            store.setAccessMode("blocklist")
            store.setPackage("private", true, "blocklist", "agora")
            store.setPackage("public", true, "allowlist", "agora")
            job.cancelAndJoin()
            job = SupervisorJob()
            preferences = openStore()
            val reopened = PhoneUseSettingsStore(preferences)
            assertEquals(setOf("private"), reopened.values.first().packages)
            reopened.setAccessMode("allowlist")
            assertEquals(PhoneUseSettings(true, "shizuku", true, "allowlist",
                setOf("private"), setOf("public"), maxScreenshots = 11), reopened.values.first())
            reopened.setEnabled(false)
            assertEquals(setOf("public"), reopened.values.first().packages)
            preferences.edit {
                it[stringPreferencesKey("phone_use_mode")] = "invalid"
                it[stringPreferencesKey("phone_use_app_access")] = "invalid"
            }
            assertEquals("accessibility", reopened.values.first().mode)
            assertEquals("off", reopened.values.first().accessMode)
            assertEquals(11, reopened.values.first().maxScreenshots)
            reopened.setEnabled(true)
            assertEquals(11, reopened.values.first().maxScreenshots)
            val beforeLimitChanges = reopened.values.first()
            val screenshotKey = intPreferencesKey("phone_use_max_screenshots")
            for ((input, expected) in listOf(Int.MIN_VALUE to 1, 0 to 1, 1 to 1, 16 to 16, 20 to 16, Int.MAX_VALUE to 16)) {
                reopened.setMaxScreenshots(input)
                assertEquals(expected, preferences.data.first()[screenshotKey])
                assertEquals(beforeLimitChanges.copy(maxScreenshots = expected), reopened.values.first())
            }
            for ((input, expected) in listOf(-1 to 1, 0 to 1) + (17..20).map { it to 16 }) {
                preferences.edit { it[screenshotKey] = input }
                assertEquals(beforeLimitChanges.copy(maxScreenshots = expected), reopened.values.first())
                assertEquals(input, preferences.data.first()[screenshotKey])
            }
            preferences.edit { it.remove(screenshotKey) }
            assertEquals(beforeLimitChanges.copy(maxScreenshots = 5), reopened.values.first())

            reopened.setMaxScreenshots(16)
            reopened.setMaxScreenshots(PHONE_MAX_SCREENSHOTS_UNLIMITED)
            val unlimited = beforeLimitChanges.copy(maxScreenshots = PHONE_MAX_SCREENSHOTS_UNLIMITED)
            assertEquals(unlimited, reopened.values.first())
            assertEquals(16, preferences.data.first()[screenshotKey])

            job.cancelAndJoin()
            job = SupervisorJob()
            preferences = openStore()
            val afterRestart = PhoneUseSettingsStore(preferences)
            assertEquals(unlimited, afterRestart.values.first())
            afterRestart.setMaxScreenshots(16)
            assertEquals(beforeLimitChanges.copy(maxScreenshots = 16), afterRestart.values.first())

            job.cancelAndJoin()
            job = SupervisorJob()
            assertEquals(beforeLimitChanges.copy(maxScreenshots = 16),
                PhoneUseSettingsStore(openStore()).values.first())
        } finally {
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
