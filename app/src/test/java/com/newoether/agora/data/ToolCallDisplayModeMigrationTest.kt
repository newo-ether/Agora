package com.newoether.agora.data

import android.content.Context
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.util.SecretCrypto
import io.mockk.*
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Test

class ToolCallDisplayModeMigrationTest {
    @Test fun legacyStorageMigratesBeforePublicationAndSurvivesReopen() = runTest {
        withSettings { manager, reopen ->
            manager.saveThinkingSegmentDisplayMode(ThinkingSegmentDisplayModes.BOTTOM_SHEET)
            manager.saveContextCompactEnabled(false)
            manager.saveContextCompactPrompt("Keep this summary instruction")
            manager.saveContextCompactRetainCount(7)
            manager.contextStoreForTest().edit { it[TOOL_CALL_DISPLAY_MODE] = "compact" }
            val migrated = reopen()
            assertEquals(ToolCallDisplayModes.GROUPED_TIMELINE, migrated.toolCallDisplayMode.first())
            assertEquals(ToolCallDisplayModes.GROUPED_TIMELINE,
                migrated.contextStoreForTest().data.first()[TOOL_CALL_DISPLAY_MODE])
            assertEquals(ThinkingSegmentDisplayModes.BOTTOM_SHEET, migrated.thinkingSegmentDisplayMode.first())
            assertFalse(migrated.contextCompactEnabled.first())
            assertEquals("Keep this summary instruction", migrated.contextCompactPrompt.first())
            assertEquals(7, migrated.contextCompactRetainCount.first())
            val preferences = migrated.contextStoreForTest().data.first()
            assertFalse(toolCallDisplayModeMigration.shouldMigrate(preferences))
            assertEquals(preferences, toolCallDisplayModeMigration.migrate(preferences))
            assertEquals(ToolCallDisplayModes.GROUPED_TIMELINE, reopen().toolCallDisplayMode.first())
        }
    }

    @Test fun archiveMergeReplaceAndExportUseOnlyCurrentValues() = runTest {
        withSettings { manager, _ ->
            for (replace in listOf(false, true)) {
                manager.saveToolCallDisplayMode(ToolCallDisplayModes.TIMELINE)
                restore(manager, """{"toolCallDisplayMode":"compact"}""", replace)
                assertEquals(ToolCallDisplayModes.GROUPED_TIMELINE, manager.toolCallDisplayMode.first())
                assertEquals(ToolCallDisplayModes.GROUPED_TIMELINE,
                    manager.contextStoreForTest().data.first()[TOOL_CALL_DISPLAY_MODE])
                val exported = PortableSettingsArchive.toJsonObject(manager, false)
                assertEquals(ToolCallDisplayModes.GROUPED_TIMELINE,
                    exported.getValue("toolCallDisplayMode").jsonPrimitive.content)
            }
            manager.saveToolCallDisplayMode(ToolCallDisplayModes.TIMELINE)
            restore(manager, "{}", false)
            assertEquals(ToolCallDisplayModes.TIMELINE, manager.toolCallDisplayMode.first())
            restore(manager, "{}", true)
            assertEquals(ToolCallDisplayModes.DEFAULT, manager.toolCallDisplayMode.first())
            for (mode in listOf(ToolCallDisplayModes.TIMELINE, ToolCallDisplayModes.GROUPED_TIMELINE)) {
                restore(manager, """{"toolCallDisplayMode":"$mode"}""", false)
                assertEquals(mode, manager.toolCallDisplayMode.first())
                val preferences = manager.contextStoreForTest().data.first()
                assertFalse(toolCallDisplayModeMigration.shouldMigrate(preferences))
                assertEquals(preferences, toolCallDisplayModeMigration.migrate(preferences))
            }
        }
    }

    @Test fun normalizationRetainsBothModesAndRejectsRetiredAndUnknownValues() {
        for (value in listOf(null, "compact", "unknown", "", ToolCallDisplayModes.GROUPED_TIMELINE)) {
            assertEquals(ToolCallDisplayModes.GROUPED_TIMELINE, ToolCallDisplayModes.normalize(value))
        }
        assertEquals(ToolCallDisplayModes.TIMELINE, ToolCallDisplayModes.normalize("timeline"))
    }

    private suspend fun restore(manager: SettingsManager, text: String, replace: Boolean) {
        PortableSettingsArchive.restoreFromJsonObject(
            Json.parseToJsonElement(text).jsonObject, manager, replace, false, null,
        ) { it }
    }

    private lateinit var testContext: Context
    private fun SettingsManager.contextStoreForTest() = testContext.dataStore

    private suspend fun withSettings(
        block: suspend (SettingsManager, suspend () -> SettingsManager) -> Unit,
    ) {
        val directory = Files.createTempDirectory("agora-display-mode").toFile()
        val context = mockk<Context>()
        testContext = context
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory
        var storageJob = SupervisorJob()
        fun createStore() = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(storageJob + Dispatchers.IO),
            migrations = listOf(modelProviderNamesMigration, toolCallDisplayModeMigration),
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                directory.resolve("settings.preferences_pb").absolutePath.toPath()
            },
        )
        var store = createStore()
        val settingsClass = "com.newoether.agora.data.SettingsManagerKt"
        mockkStatic(settingsClass)
        every { context.dataStore } answers { store }
        mockkObject(SecretCrypto)
        every { SecretCrypto.encrypt(any()) } answers { firstArg<String>() }
        every { SecretCrypto.decrypt(any()) } answers { firstArg<String>() }
        try {
            block(SettingsManager(context)) {
                storageJob.cancelAndJoin()
                storageJob = SupervisorJob()
                store = createStore()
                SettingsManager(context)
            }
        } finally {
            unmockkObject(SecretCrypto)
            unmockkStatic(settingsClass)
            storageJob.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
