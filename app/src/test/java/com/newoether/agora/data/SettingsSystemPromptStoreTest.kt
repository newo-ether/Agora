package com.newoether.agora.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.FileSystem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

class SettingsSystemPromptStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun concurrentCatalogTransformsPreserveEveryEntryAndOneDefault() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, storage =
            OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                temporary.root.resolve("prompts.preferences_pb").absolutePath.toPath()
            })
        val store = SettingsSystemPromptStore(dataStore, Json)
        try {
            coroutineScope {
                repeat(40) { index -> launch(Dispatchers.Default) {
                    store.update { it + SystemPromptEntry(id = "p$index", title = "Prompt $index") }
                } }
            }
            val saved = store.systemPrompts.first()
            assertEquals(40, saved.size)
            assertEquals(40, saved.map { it.id }.toSet().size)
            val active = store.activeSystemPromptId.first()
            assertEquals(saved.first().id, active)
            coroutineScope {
                saved.forEach { entry -> launch(Dispatchers.Default) {
                    store.update { rows -> rows.map { if (it.id == entry.id) it.copy(title = "Updated") else it } }
                } }
            }
            assertTrue(store.systemPrompts.first().all { it.title == "Updated" })
            store.update { it.filterNot { entry -> entry.id == active } }
            assertEquals(store.systemPrompts.first().first().id, store.activeSystemPromptId.first())
            store.setActive(null)
            store.update { it.map { entry -> entry.copy(title = "Renamed") } }
            assertNull(store.activeSystemPromptId.first())
            store.update { emptyList() }
            assertTrue(store.systemPrompts.first().isEmpty())
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }

    @Test fun invalidStoredCatalogCannotBeOverwrittenByCrud() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, storage =
            OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                temporary.root.resolve("invalid.preferences_pb").absolutePath.toPath()
            })
        val store = SettingsSystemPromptStore(dataStore, Json)
        try {
            dataStore.edit { it[SYSTEM_PROMPTS_JSON] = "not-json" }
            assertTrue(runCatching { store.update { it + SystemPromptEntry(title = "New") } }.isFailure)
            assertEquals("not-json", dataStore.data.first()[SYSTEM_PROMPTS_JSON])
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }

    @Test fun firstInstallAndRepeatedInitializationPreserveCustomizedTemplates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, storage =
            OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                temporary.root.resolve("defaults.preferences_pb").absolutePath.toPath()
            })
        val store = SettingsSystemPromptStore(dataStore, Json)
        try {
            store.initialize(Locale.ENGLISH, 100)
            val initial = store.systemPrompts.first().single()
            assertEquals(initial.id, store.activeSystemPromptId.first())
            store.update { listOf(initial.copy(title = "Customized")) }
            store.initialize(Locale.CHINESE, 200)
            assertEquals("Customized", store.systemPrompts.first().single().title)
            assertEquals(100L, dataStore.data.first()[FIRST_LAUNCH_TIME])
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }
}
