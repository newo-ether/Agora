package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HtmlPreviewPromptPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun migratedDefaultGuidanceDeletionSurvivesDiskSaveAndTwoColdStarts() = runBlocking {
        val historical = DefaultSystemPrompt.versionBeforeHtmlPreviewForMigration(Locale.ENGLISH)
            .copy(id = "saved-default")
        var deleted: SystemPromptEntry? = null
        withStore("migrated.preferences_pb") { store, dataStore ->
            dataStore.edit {
                it[SYSTEM_PROMPTS_JSON] = Json.encodeToString(listOf(historical))
                it[FIRST_LAUNCH_TIME] = 1L
                it[ACTIVE_SYSTEM_PROMPT_ID] = historical.id
            }
            store.initialize(Locale.ENGLISH, 2L)
            val migrated = store.systemPrompts.first().single()
            assertTrue(HtmlPreviewGuidance.isPresent(migrated.systemItems))
            assertEquals(DefaultSystemPrompt.BUILT_IN_MIGRATION_VERSION, migrated.builtInMigrationVersion)
            store.update { prompts -> prompts.map { entry ->
                entry.copy(systemItems = entry.systemItems.filterNot { it.value == HtmlPreviewGuidance.text })
            } }
            deleted = store.systemPrompts.first().single()
            assertEquals(historical.systemItems.map { it.type to it.value },
                deleted!!.systemItems.map { it.type to it.value })
        }
        repeat(2) { index ->
            withStore("migrated.preferences_pb") { store, _ ->
                store.initialize(Locale.ENGLISH, 3L + index)
                assertEquals(deleted, store.systemPrompts.first().single())
                assertFalse(HtmlPreviewGuidance.isPresent(store.systemPrompts.first().single().systemItems))
                assertEquals(historical.id, store.activeSystemPromptId.first())
            }
        }
    }

    @Test
    fun freshDefaultDeletionSurvivesWholeCatalogSaveAndColdStart() = runBlocking {
        var deleted: SystemPromptEntry? = null
        withStore("fresh.preferences_pb") { store, _ ->
            store.initialize(Locale.ENGLISH, 1L)
            val initial = store.systemPrompts.first().single()
            deleted = initial.copy(systemItems = initial.systemItems.filterNot { it.value == HtmlPreviewGuidance.text })
            store.save(listOf(deleted!!))
        }
        withStore("fresh.preferences_pb") { store, _ ->
            store.initialize(Locale.ENGLISH, 2L)
            assertEquals(deleted, store.systemPrompts.first().single())
            assertFalse(HtmlPreviewGuidance.isPresent(store.systemPrompts.first().single().systemItems))
        }
    }

    @Test
    fun explicitCrudSaveOfHistoricalTextRecordsAcceptanceWithoutChangingText() = runBlocking {
        val historical = DefaultSystemPrompt.versionBeforeHtmlPreviewForMigration(Locale.ENGLISH)
            .copy(id = "explicit-default")
        withStore("explicit.preferences_pb") { store, dataStore ->
            dataStore.edit {
                it[SYSTEM_PROMPTS_JSON] = Json.encodeToString(listOf(historical.copy(title = "My prompt")))
                it[FIRST_LAUNCH_TIME] = 1L
            }
            store.update { listOf(historical) }
            assertEquals(DefaultSystemPrompt.BUILT_IN_MIGRATION_VERSION,
                store.systemPrompts.first().single().builtInMigrationVersion)
        }
        withStore("explicit.preferences_pb") { store, _ ->
            store.initialize(Locale.ENGLISH, 2L)
            assertEquals(historical.copy(builtInMigrationVersion = DefaultSystemPrompt.BUILT_IN_MIGRATION_VERSION),
                store.systemPrompts.first().single())
            assertFalse(HtmlPreviewGuidance.isPresent(store.systemPrompts.first().single().systemItems))
        }
    }

    private suspend fun withStore(
        filename: String,
        block: suspend (SettingsSystemPromptStore, DataStore<Preferences>) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                temporary.root.resolve(filename).absolutePath.toPath()
            },
        )
        try {
            block(SettingsSystemPromptStore(dataStore, Json), dataStore)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
        }
    }
}
