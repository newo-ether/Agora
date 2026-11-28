package com.newoether.agora.data

import android.content.Context
import androidx.compose.ui.unit.sp
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import com.newoether.agora.ui.theme.codeBlockTextStyle
import com.newoether.agora.ui.theme.thoughtCodeBlockTextStyle
import com.newoether.agora.util.SecretCrypto
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockFontSizeSettingsTest {
    @Test
    fun normalizationClampsToSupportedRangeAndDefaultsNullToFourteen() {
        assertEquals(14, DEFAULT_CODE_BLOCK_FONT_SIZE_SP)
        assertEquals(10, MIN_CODE_BLOCK_FONT_SIZE_SP)
        assertEquals(20, MAX_CODE_BLOCK_FONT_SIZE_SP)
        assertEquals((10..20).toList(), CODE_BLOCK_FONT_SIZE_PRESETS)
        assertEquals("code_block_font_size_sp", CODE_BLOCK_FONT_SIZE_SP.name)
        assertEquals(14, normalizeCodeBlockFontSizeSp(null))

        CODE_BLOCK_FONT_SIZE_PRESETS.forEach { size ->
            assertEquals(size, normalizeCodeBlockFontSizeSp(size))
        }
        listOf(Int.MIN_VALUE, -5, 0, 9).forEach { below ->
            assertEquals(10, normalizeCodeBlockFontSizeSp(below))
        }
        listOf(21, 32, Int.MAX_VALUE).forEach { above ->
            assertEquals(20, normalizeCodeBlockFontSizeSp(above))
        }
    }

    @Test
    fun textStyleHelpersKeepExpectedDefaultMetricsAndClampExtremes() {
        val defaultCode = codeBlockTextStyle(DEFAULT_CODE_BLOCK_FONT_SIZE_SP)
        assertEquals(14.sp, defaultCode.fontSize)
        assertEquals(20.sp, defaultCode.lineHeight)

        val defaultThought = thoughtCodeBlockTextStyle(DEFAULT_CODE_BLOCK_FONT_SIZE_SP)
        assertEquals(12.sp, defaultThought.fontSize)
        assertEquals(17.sp, defaultThought.lineHeight)

        val minCode = codeBlockTextStyle(4)
        assertEquals(10.sp, minCode.fontSize)
        assertTrue(minCode.lineHeight.value > minCode.fontSize.value)

        val minThought = thoughtCodeBlockTextStyle(4)
        assertEquals(9.sp, minThought.fontSize)
        assertTrue(minThought.lineHeight.value > minThought.fontSize.value)

        val maxCode = codeBlockTextStyle(30)
        assertEquals(20.sp, maxCode.fontSize)
        assertTrue(maxCode.lineHeight.value > maxCode.fontSize.value)

        val maxThought = thoughtCodeBlockTextStyle(30)
        assertEquals(18.sp, maxThought.fontSize)
        assertTrue(maxThought.lineHeight.value > maxThought.fontSize.value)
    }

    @Test
    fun freshDefaultAndEveryPresetSurviveReloadAndExportAsNumber() = runTest {
        withSettings { manager, context ->
            assertEquals(14, manager.codeBlockFontSizeSp.first())
            val initialExport = PortableSettingsArchive.toJsonObject(manager, false)
            assertEquals(JsonPrimitive(14), initialExport["codeBlockFontSizeSp"])
            assertFalse((initialExport["codeBlockFontSizeSp"] as JsonPrimitive).isString)
            assertFalse(initialExport.containsKey(CODE_BLOCK_FONT_SIZE_SP.name))

            for (sizeSp in CODE_BLOCK_FONT_SIZE_PRESETS) {
                manager.saveCodeBlockFontSizeSp(sizeSp)
                assertEquals(sizeSp, context.dataStore.data.first()[CODE_BLOCK_FONT_SIZE_SP])
                val reloaded = SettingsManager(context)
                assertEquals(sizeSp, reloaded.codeBlockFontSizeSp.first())
                val exported = PortableSettingsArchive.toJsonObject(reloaded, false)
                assertEquals(JsonPrimitive(sizeSp), exported["codeBlockFontSizeSp"])
                assertFalse((exported["codeBlockFontSizeSp"] as JsonPrimitive).isString)
            }
        }
    }

    @Test
    fun outOfRangeStoredValuesClampOnReadAndExportWithoutMutatingStorageUntilSaved() = runTest {
        withSettings { manager, context ->
            for ((raw, expected) in listOf(Int.MIN_VALUE to 10, 0 to 10, 9 to 10, 21 to 20, Int.MAX_VALUE to 20)) {
                context.dataStore.edit { it[CODE_BLOCK_FONT_SIZE_SP] = raw }
                val before = context.dataStore.data.first().asMap()
                assertEquals(expected, manager.codeBlockFontSizeSp.first())
                assertEquals(expected, SettingsManager(context).codeBlockFontSizeSp.first())
                assertEquals(
                    JsonPrimitive(expected),
                    PortableSettingsArchive.toJsonObject(manager, false)["codeBlockFontSizeSp"],
                )
                assertEquals(before, context.dataStore.data.first().asMap())

                manager.saveCodeBlockFontSizeSp(raw)
                assertEquals(expected, context.dataStore.data.first()[CODE_BLOCK_FONT_SIZE_SP])
            }
        }
    }

    @Test
    fun archiveRoundTripClampsOutOfRangeImportsAndPreservesMergeOrClearsOnReplace() = runTest {
        withSettings { manager, context ->
            manager.saveCodeBlockFontSizeSp(18)
            val exported = PortableSettingsArchive.toJsonObject(manager, false)

            manager.saveCodeBlockFontSizeSp(11)
            restore(manager, exported, replace = true)
            assertEquals(18, SettingsManager(context).codeBlockFontSizeSp.first())

            restore(manager, JsonObject(mapOf("codeBlockFontSizeSp" to JsonPrimitive(4))))
            assertEquals(10, manager.codeBlockFontSizeSp.first())
            assertEquals(10, context.dataStore.data.first()[CODE_BLOCK_FONT_SIZE_SP])

            restore(manager, JsonObject(mapOf("codeBlockFontSizeSp" to JsonPrimitive(99))))
            assertEquals(20, manager.codeBlockFontSizeSp.first())
            assertEquals(20, context.dataStore.data.first()[CODE_BLOCK_FONT_SIZE_SP])

            restore(manager, JsonObject(emptyMap()), replace = false)
            assertEquals(20, manager.codeBlockFontSizeSp.first())

            context.dataStore.edit { it[AUTO_BACKUP_ENABLED] = true }
            restore(manager, JsonObject(emptyMap()), replace = true)
            val preferences = context.dataStore.data.first()
            assertNull(preferences[CODE_BLOCK_FONT_SIZE_SP])
            assertEquals(14, SettingsManager(context).codeBlockFontSizeSp.first())
            assertEquals(true, preferences[AUTO_BACKUP_ENABLED])
        }
    }

    private suspend fun restore(manager: SettingsManager, archive: JsonObject, replace: Boolean = false) {
        PortableSettingsArchive.restoreFromJsonObject(archive, manager, replace, false, null) { it }
    }

    private suspend fun withSettings(block: suspend (SettingsManager, Context) -> Unit) {
        val directory = Files.createTempDirectory("agora-code-block-font-size").toFile()
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory
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
            directory.deleteRecursively()
        }
    }
}
