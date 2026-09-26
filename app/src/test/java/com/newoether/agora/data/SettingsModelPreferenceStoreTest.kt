package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelPreferenceStoreTest {
    @Test
    fun cacheDefaultsAndIndependentFieldsSurviveRestart() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        assertTrue(store.anthropicCacheEnabled.first())
        assertEquals("1h", store.anthropicCacheTtl.first())
        store.saveAnthropicCacheTtl("5m")
        store.saveAnthropicCacheEnabled(false)
        val restarted = SettingsModelPreferenceStore(dataStore, testJson)
        assertFalse(restarted.anthropicCacheEnabled.first())
        assertEquals("5m", restarted.anthropicCacheTtl.first())
        restarted.saveAnthropicCacheEnabled(true)
        assertEquals("5m", restarted.anthropicCacheTtl.first())
        val before = dataStore.data.first()
        assertTrue(runCatching { restarted.saveAnthropicCacheTtl("invalid") }.isFailure)
        assertEquals(before, dataStore.data.first())
    }
    @Test
    fun customCacheUpdatesUseStableIdentityPreserveOtherFieldsAndNeverRecreateDeletedProviders() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        store.saveCustomProviders(listOf(
            CustomProviderConfig("Relay", CustomEndpointProtocol.ANTHROPIC),
            CustomProviderConfig("Other", CustomEndpointProtocol.ANTHROPIC),
        ))
        val original = store.customProviders.first()
        val id = original.first().providerId
        store.updateCustomProviderCache(id, ttl = "5m")
        store.updateCustomProviderCache(id, enabled = false)
        val saved = store.customProviders.first()
        assertFalse(saved.first().anthropicCacheEnabled)
        assertEquals("5m", saved.first().anthropicCacheTtl)
        assertEquals(original.last(), saved.last())
        store.saveCustomProviders(saved.map { it.copy(name = it.name + " renamed", protocol = CustomEndpointProtocol.OPENAI) })
        store.updateCustomProviderCache(id, enabled = true)
        val restarted = SettingsModelPreferenceStore(dataStore, testJson)
        assertEquals("5m", restarted.customProviders.first().first().anthropicCacheTtl)
        assertTrue(restarted.customProviders.first().first().anthropicCacheEnabled)
        assertEquals(CustomEndpointProtocol.OPENAI, restarted.customProviders.first().first().protocol)
        store.saveCustomProviders(emptyList())
        store.updateCustomProviderCache(id, enabled = false)
        assertTrue(store.customProviders.first().isEmpty())
        val before = dataStore.data.first()
        assertTrue(runCatching { store.updateCustomProviderCache(id, ttl = "invalid") }.isFailure)
        assertEquals(before, dataStore.data.first())
    }
    @Test
    fun providerNameMigrationPreservesExistingPresentationOnceWithoutChangingAliases() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.edit {
            it[MODEL_ALIASES_JSON] = testJson.encodeToString(
                mapOf("OpenAI:manual" to "Work", "OpenAI:blank" to "  "),
            )
            it[SELECTED_MODEL] = "OpenAI:automatic"
        }
        val original = dataStore.data.first()
        assertTrue(modelProviderNamesMigration.shouldMigrate(original))
        val migrated = modelProviderNamesMigration.migrate(original)
        assertEquals(original[MODEL_ALIASES_JSON], migrated[MODEL_ALIASES_JSON])
        assertEquals(original[SELECTED_MODEL], migrated[SELECTED_MODEL])
        val store = SettingsModelPreferenceStore(InMemoryPreferencesDataStore(migrated), testJson)
        assertEquals(mapOf("OpenAI:manual" to false), store.modelProviderNames.first())
        assertFalse(modelProviderNamesMigration.shouldMigrate(migrated))
        store.updateModelAlias("OpenAI:manual", "")
        store.updateModelAlias("OpenAI:automatic", "New alias")
        assertEquals(mapOf("OpenAI:manual" to false), store.modelProviderNames.first())
        assertEquals(migrated, modelProviderNamesMigration.migrate(migrated))
    }

    @Test
    fun freshModelsShowProviderEvenWhenCreatedWithAnAlias() = runTest {
        val migrated = modelProviderNamesMigration.migrate(emptyPreferences())
        val dataStore = InMemoryPreferencesDataStore(migrated)
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        store.addCustomModel("Gateway:new", "My alias")
        assertTrue(store.modelProviderNames.first()["Gateway:new"] != false)
        assertFalse(modelProviderNamesMigration.shouldMigrate(dataStore.data.first()))
        assertEquals(mapOf("Gateway:new" to "My alias"), store.modelAliases.first())
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun aliasAndProviderSwitchPublishTogetherAndAliasOnlyUpdatesPreserveTheSwitch() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        val observed = mutableListOf<Preferences>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            dataStore.data.toList(observed)
        }
        store.updateModelAlias("OpenAI:model", "Work", showProviderName = false)
        assertEquals(2, observed.size)
        val saved = SettingsModelPreferenceStore(InMemoryPreferencesDataStore(observed.last()), testJson)
        assertEquals(mapOf("OpenAI:model" to "Work"), saved.modelAliases.first())
        assertEquals(mapOf("OpenAI:model" to false), saved.modelProviderNames.first())
        store.updateModelAlias("OpenAI:model", "")
        assertTrue(store.modelAliases.first().isEmpty())
        assertEquals(false, store.modelProviderNames.first()["OpenAI:model"])
        store.updateModelAlias("OpenAI:model", "", showProviderName = true)
        assertEquals(true, store.modelProviderNames.first()["OpenAI:model"])
    }

    @Test
    fun customModelEditorSavesAliasAndProviderChoiceWithCreationAndIdentityChanges() = runTest {
        val store = SettingsModelPreferenceStore(InMemoryPreferencesDataStore(), testJson)
        store.addCustomModel("Gateway:old", "Work", showProviderName = false)
        assertEquals(mapOf("Gateway:old" to false), store.modelProviderNames.first())
        store.replaceCustomModel("Gateway:old", "Gateway:old", "Renamed", showProviderName = true)
        assertEquals(mapOf("Gateway:old" to "Renamed"), store.modelAliases.first())
        assertEquals(mapOf("Gateway:old" to true), store.modelProviderNames.first())
        store.replaceCustomModel("Gateway:old", "Gateway:new", "", showProviderName = false)
        assertEquals(setOf("Gateway:new"), store.customModels.first())
        assertTrue(store.modelAliases.first().isEmpty())
        assertEquals(mapOf("Gateway:new" to false), store.modelProviderNames.first())
    }

    @Test
    fun malformedProviderPreferencesDoNotPartiallySaveAnAlias() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        dataStore.edit {
            it[MODEL_ALIASES_JSON] = "{\"OpenAI:model\":\"Original\"}"
            it[MODEL_PROVIDER_NAMES_JSON] = "malformed"
        }
        val before = dataStore.data.first()
        val result = runCatching { store.updateModelAlias("OpenAI:model", "Changed", false) }
        assertTrue(result.isFailure)
        assertEquals(before, dataStore.data.first())
    }

    @Test
    fun providerPreferencesMergeAndReplacePreserveExplicitFalse() = runTest {
        val store = SettingsModelPreferenceStore(InMemoryPreferencesDataStore(), testJson)
        store.saveModelProviderNames(mapOf("OpenAI:a" to false))
        store.saveModelProviderNames(mapOf("OpenAI:b" to true), replace = false)
        assertEquals(mapOf("OpenAI:a" to false, "OpenAI:b" to true), store.modelProviderNames.first())
        store.saveModelProviderNames(mapOf("OpenAI:b" to false))
        assertEquals(mapOf("OpenAI:b" to false), store.modelProviderNames.first())
        store.saveModelProviderNames(emptyMap())
        assertTrue(store.modelProviderNames.first().isEmpty())
    }

    @Test
    fun providerVisibilityFollowsModelReplacementAndDeletionWithoutChangingAnotherModel() = runTest {
        val store = SettingsModelPreferenceStore(InMemoryPreferencesDataStore(), testJson)
        store.addCustomModel("Gateway:old", "Alias")
        store.saveModelProviderNames(mapOf("Gateway:old" to false, "Other:keep" to false))
        store.replaceCustomModel("Gateway:old", "Gateway:old", "Renamed")
        assertEquals(false, store.modelProviderNames.first()["Gateway:old"])
        store.replaceCustomModel("Gateway:old", "Gateway:new", "")
        assertEquals(mapOf("Gateway:new" to false, "Other:keep" to false), store.modelProviderNames.first())
        store.replaceCustomModel("Gateway:new", null, "")
        assertEquals(mapOf("Other:keep" to false), store.modelProviderNames.first())
        store.addCustomModel("Gateway:new", "Recreated")
        assertTrue(store.modelProviderNames.first()["Gateway:new"] != false)
    }

    @Test
    fun providerVisibilityRemapsLegacyKeysPreservesCanonicalChoicesAndSurvivesRestart() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        val id = "custom-provider-00000000-0000-4000-8000-000000000001"
        store.saveCustomProviders(
            listOf(CustomProviderConfig("Relay", id = id, legacyNames = setOf("Relay"))),
        )
        store.saveModelProviderNames(mapOf("Relay:a" to false, "Relay:b" to false, "$id:b" to true))
        store.normalizeCustomProviderIdentities()
        assertEquals(mapOf("$id:a" to false, "$id:b" to true), store.modelProviderNames.first())
        val restarted = SettingsModelPreferenceStore(dataStore, testJson)
        restarted.saveCustomProviders(listOf(CustomProviderConfig("Renamed", id = id)))
        restarted.normalizeCustomProviderIdentities()
        assertEquals(store.modelProviderNames.first(), restarted.modelProviderNames.first())
        restarted.saveModelProviderNames(mapOf("Other:c" to false), replace = false)
        restarted.removeModelAliasesForProvider(id)
        assertEquals(mapOf("Other:c" to false), restarted.modelProviderNames.first())
    }

    @Test
    fun initializedResetAndLaterAliasRestoreCannotReapplyLegacyMigration() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        store.saveModelProviderNames(emptyMap())
        store.saveModelAliases(mapOf("OpenAI:model" to "Imported old alias"))
        assertFalse(modelProviderNamesMigration.shouldMigrate(dataStore.data.first()))
        assertTrue(store.modelProviderNames.first()["OpenAI:model"] != false)
    }

    @Test
    fun localModelIdentityChangesCarryVisibilityAndDeletionRestoresNewModelDefault() = runTest {
        val store = SettingsModelPreferenceStore(InMemoryPreferencesDataStore(), testJson)
        val model = LocalChatModelConfig(id = "device-model", modelId = "old", alias = "Local alias")
        store.saveLocalChatModels(listOf(model))
        store.saveModelProviderNames(mapOf("Local:old" to false, "OpenAI:keep" to false))
        store.saveLocalChatModels(listOf(model.copy(modelId = "new")))
        assertEquals(mapOf("Local:new" to false, "OpenAI:keep" to false), store.modelProviderNames.first())
        store.saveLocalChatModels(emptyList())
        assertEquals(mapOf("OpenAI:keep" to false), store.modelProviderNames.first())
    }

    @Test
    fun blankProviderBaseUrlRestoresDefaultByRemovingOverride() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)

        store.saveProviderBaseUrl("Anthropic", "https://relay.example/v1")
        assertEquals(
            mapOf("Anthropic" to "https://relay.example/v1"),
            store.providerBaseUrls.first(),
        )

        store.saveProviderBaseUrl("Anthropic", "  ")
        assertTrue(store.providerBaseUrls.first().isEmpty())
        assertEquals("{}", dataStore.data.first()[PROVIDER_BASE_URLS])
    }

    @Test
    fun addingCustomModelCommitsSelectionEnablementAndAliasTogether() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)

        store.addCustomModel("Gateway:model", "  Alias  ")

        assertEquals(setOf("Gateway:model"), store.customModels.first())
        assertEquals(setOf("Gateway:model"), store.enabledModels.first())
        assertEquals("Gateway:model", store.selectedModel.first())
        assertEquals(mapOf("Gateway:model" to "Alias"), store.modelAliases.first())
    }

    @Test
    fun replacingCustomModelRemapsEveryModelReferenceAtomically() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        store.addCustomModel("Gateway:old", "Old alias")
        dataStore.edit { preferences ->
            preferences[TITLE_GENERATION_MODEL] = "Gateway:old"
            preferences[CONTEXT_COMPACT_MODEL] = "Gateway:old"
            preferences[IMAGE_TRANSCRIPTION_ENABLED_MODELS] = setOf("Gateway:old")
        }

        store.replaceCustomModel("Gateway:old", "Gateway:new", "New alias")

        val preferences = dataStore.data.first()
        assertFalse("Gateway:old" in store.customModels.first())
        assertEquals(setOf("Gateway:new"), store.customModels.first())
        assertEquals(setOf("Gateway:new"), store.enabledModels.first())
        assertEquals("Gateway:new", store.selectedModel.first())
        assertEquals(mapOf("Gateway:new" to "New alias"), store.modelAliases.first())
        assertEquals("Gateway:new", preferences[TITLE_GENERATION_MODEL])
        assertEquals("Gateway:new", preferences[CONTEXT_COMPACT_MODEL])
        assertEquals(
            setOf("Gateway:new"),
            preferences[IMAGE_TRANSCRIPTION_ENABLED_MODELS],
        )
    }

    @Test
    fun invalidatingModelCachesClearsDerivedModelsEndpointsAndFingerprint() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        store.saveAvailableModels("Anthropic", listOf("claude"))
        store.saveCustomEndpointResolution(
            provider = "Gateway",
            resolution = CustomEndpointResolution(
                protocol = CustomEndpointProtocol.OPENAI,
                configuredBaseUrl = "https://gateway.example",
                effectiveBaseUrl = "https://gateway.example/v1",
            ),
        )
        store.saveLastModelsFetchFingerprint("fingerprint")

        store.invalidatePortableModelCaches()

        assertTrue(store.availableModels.first().isEmpty())
        assertTrue(store.customEndpointResolutions.first().isEmpty())
        assertEquals("", store.lastModelsFetchFingerprint.first())
    }

    @Test
    fun legacyProviderIdentityAndEveryDataStoreModelReferenceMigrateAtomically() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        dataStore.edit { preferences ->
            preferences[CUSTOM_PROVIDERS_JSON] = testJson.encodeToString(
                listOf(CustomProviderConfig("Relay X")),
            )
            preferences[CUSTOM_MODELS] = setOf("Relay X:custom")
            preferences[ENABLED_MODELS] = setOf("Relay X:catalog", "OpenAI:gpt-5")
            preferences[SELECTED_MODEL] = "Relay X:catalog"
            preferences[TITLE_GENERATION_MODEL] = "Relay X:catalog"
            preferences[CONTEXT_COMPACT_MODEL] = "Relay X:custom"
            preferences[IMAGE_TRANSCRIPTION_MODEL] = "Relay X:catalog"
            preferences[IMAGE_TRANSCRIPTION_ENABLED_MODELS] = setOf("Relay X:catalog")
            preferences[MODEL_ALIASES_JSON] = testJson.encodeToString(
                mapOf("Relay X:catalog" to "Alias"),
            )
            preferences[AVAILABLE_MODELS_JSON] = testJson.encodeToString(
                mapOf("Relay X" to listOf("Relay X:catalog")),
            )
            preferences[PROVIDER_BASE_URLS] = testJson.encodeToString(
                mapOf("Relay X" to "https://relay.invalid"),
            )
        }

        val migrations = store.normalizeCustomProviderIdentities()
        val provider = store.customProviders.first().single()
        val prefix = "${provider.id}:"

        assertTrue(CustomProviderIdentityPolicy.isStableId(provider.id))
        assertEquals(setOf("Relay X"), provider.legacyNames)
        assertEquals(
            listOf(CustomProviderIdentityMigration("Relay X", provider.id)),
            migrations,
        )
        assertEquals(setOf(prefix + "custom"), store.customModels.first())
        assertEquals(setOf(prefix + "catalog", "OpenAI:gpt-5"), store.enabledModels.first())
        assertEquals(prefix + "catalog", store.selectedModel.first())
        assertEquals(mapOf(prefix + "catalog" to "Alias"), store.modelAliases.first())
        assertEquals(
            mapOf("Relay X" to listOf(prefix + "catalog")),
            store.availableModels.first(),
        )
        assertEquals(
            mapOf("Relay X" to "https://relay.invalid"),
            store.providerBaseUrls.first(),
        )

        store.clearLegacyCustomProviderNames(migrations)
        assertTrue(store.customProviders.first().single().legacyNames.isEmpty())
        assertEquals(emptyList<CustomProviderIdentityMigration>(), store.normalizeCustomProviderIdentities())
    }

    @Test
    fun staleLegacyProviderWriteCannotForkModelAliasesOntoANewIdentity() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        dataStore.edit { preferences ->
            preferences[CUSTOM_PROVIDERS_JSON] = testJson.encodeToString(
                listOf(CustomProviderConfig("Relay X")),
            )
            preferences[MODEL_ALIASES_JSON] = testJson.encodeToString(
                mapOf("Relay X:model" to "My Alias"),
            )
        }

        store.normalizeCustomProviderIdentities()
        val firstId = store.customProviders.first().single().id
        store.saveCustomProviders(listOf(CustomProviderConfig("Relay X")))
        store.normalizeCustomProviderIdentities()

        assertEquals(firstId, store.customProviders.first().single().id)
        assertEquals(mapOf("$firstId:model" to "My Alias"), store.modelAliases.first())
    }

    @Test
    fun orphanedAliasFromIntermediateIdentityBuildMovesToItsUniqueCurrentProvider() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        val orphanId = "custom-provider-00000000-0000-4000-8000-000000000001"
        val currentId = "custom-provider-00000000-0000-4000-8000-000000000002"
        dataStore.edit { preferences ->
            preferences[CUSTOM_PROVIDERS_JSON] = testJson.encodeToString(
                listOf(CustomProviderConfig(name = "Relay X", id = currentId)),
            )
            preferences[ENABLED_MODELS] = setOf("$currentId:model")
            preferences[MODEL_ALIASES_JSON] = testJson.encodeToString(
                mapOf("$orphanId:model" to "My Alias"),
            )
        }

        store.normalizeCustomProviderIdentities()

        assertEquals(mapOf("$currentId:model" to "My Alias"), store.modelAliases.first())
    }

    @Test
    fun stableProviderWithoutLegacyNamesHasNoPendingMigration() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        val providerId = "custom-provider-00000000-0000-4000-8000-000000000002"
        dataStore.edit { preferences ->
            preferences[CUSTOM_PROVIDERS_JSON] = testJson.encodeToString(
                listOf(CustomProviderConfig(name = "Relay X", id = providerId)),
            )
            preferences[ENABLED_MODELS] = setOf("$providerId:model")
            preferences[MODEL_ALIASES_JSON] = testJson.encodeToString(
                mapOf("Relay X:model" to "My Alias"),
            )
        }

        val migrations = store.normalizeCustomProviderIdentities()

        assertEquals(mapOf("Relay X:model" to "My Alias"), store.modelAliases.first())
        assertEquals(emptyList<CustomProviderIdentityMigration>(), migrations)
    }

    @Test
    fun localCatalogSynchronizationCannotOverwriteOrDeleteUnrelatedAliases() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        val providerId = "custom-provider-00000000-0000-4000-8000-000000000002"
        store.saveModelAliases(
            mapOf(
                "$providerId:model" to "Custom alias",
                "Local:old" to "Old local alias",
            ),
        )

        store.synchronizeLocalModelAliases(mapOf("Local:new" to "New local alias"))

        assertEquals(
            mapOf(
                "$providerId:model" to "Custom alias",
                "Local:old" to "Old local alias",
                "Local:new" to "New local alias",
            ),
            store.modelAliases.first(),
        )
    }

    @Test
    fun aliasMutationReadsLatestDurableMapInsteadOfReplacingItWithAStaleSnapshot() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = SettingsModelPreferenceStore(dataStore, testJson)
        val providerId = "custom-provider-00000000-0000-4000-8000-000000000002"
        store.saveModelAliases(mapOf("$providerId:model" to "Preserved"))

        store.updateModelAlias("OpenAI:gpt-5", "Work")

        assertEquals(
            mapOf(
                "$providerId:model" to "Preserved",
                "OpenAI:gpt-5" to "Work",
            ),
            store.modelAliases.first(),
        )
    }

    @Test
    fun freshInstallCustomAliasSurvivesStartupSynchronizationRenameAndRestartNormalization() =
        runTest {
            val dataStore = InMemoryPreferencesDataStore()
            val store = SettingsModelPreferenceStore(dataStore, testJson)
            val providerId = "custom-provider-00000000-0000-4000-8000-000000000002"
            store.saveCustomProviders(
                listOf(CustomProviderConfig(name = "Relay X", id = providerId)),
            )
            store.addCustomModel("$providerId:model", "My alias")

            store.synchronizeLocalModelAliases(mapOf("Local:device" to "On device"))
            store.normalizeCustomProviderIdentities()
            store.saveCustomProviders(
                listOf(CustomProviderConfig(name = "Renamed relay", id = providerId)),
            )
            store.normalizeCustomProviderIdentities()

            assertEquals(
                mapOf(
                    "$providerId:model" to "My alias",
                    "Local:device" to "On device",
                ),
                store.modelAliases.first(),
            )
        }

    private class InMemoryPreferencesDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }

    private companion object {
        val testJson = Json { ignoreUnknownKeys = true }
    }
}
