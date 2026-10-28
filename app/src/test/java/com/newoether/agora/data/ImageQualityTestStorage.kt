package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.util.SecretCrypto
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files

/** Real file storage with a test-owned lifetime; production readers still use context.dataStore. */
internal suspend fun withImageQualityTestSettings(
    suppliedContext: Context? = null,
    storageDirectory: File? = null,
    block: suspend (SettingsManager, Context) -> Unit,
) {
    val directory = storageDirectory ?: Files.createTempDirectory("agora-image-quality-settings").toFile()
    val context = suppliedContext ?: mockk<Context>().also {
        every { it.applicationContext } returns it
        every { it.filesDir } returns directory
    }
    val storageJob = SupervisorJob()
    val store = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(storageJob + Dispatchers.IO),
        migrations = listOf(modelProviderNamesMigration),
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
            directory.resolve("settings.preferences_pb").absolutePath.toPath()
        },
    )
    val settingsClass = "com.newoether.agora.data.SettingsManagerKt"
    mockkStatic(settingsClass)
    every { context.dataStore } returns store
    mockkObject(SecretCrypto)
    every { SecretCrypto.encrypt(any()) } answers { firstArg<String>() }
    every { SecretCrypto.decrypt(any()) } answers { firstArg<String>() }
    try {
        block(SettingsManager(context), context)
    } finally {
        unmockkObject(SecretCrypto)
        unmockkStatic(settingsClass)
        storageJob.cancelAndJoin()
        if (storageDirectory == null) directory.deleteRecursively()
    }
}
