package com.newoether.agora.phone

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
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
                setOf("private"), setOf("public")), reopened.values.first())
            reopened.setEnabled(false)
            assertEquals(setOf("public"), reopened.values.first().packages)
            preferences.edit {
                it[stringPreferencesKey("phone_use_mode")] = "invalid"
                it[stringPreferencesKey("phone_use_app_access")] = "invalid"
            }
            assertEquals("accessibility", reopened.values.first().mode)
            assertEquals("off", reopened.values.first().accessMode)
        } finally {
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
