package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SecretCrypto
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderListStoreTest {
    private val openAi = Constants.PROVIDER_OPENAI
    private val anthropic = Constants.PROVIDER_ANTHROPIC
    private val ollama = Constants.PROVIDER_OLLAMA
    private val google = Constants.PROVIDER_GOOGLE

    @Before
    fun mockCrypto() {
        mockkObject(SecretCrypto)
        every { SecretCrypto.encrypt(any()) } answers { firstArg<String>() }
        every { SecretCrypto.decrypt(any()) } answers { firstArg<String>() }
    }

    @After
    fun unmockCrypto() {
        unmockkObject(SecretCrypto)
    }

    @Test
    fun missingListDerivesConfiguredBuiltInsInCatalogOrderAndAppendsCustoms() {
        val derived = resolveProviderList(
            stored = null,
            customNames = listOf("Relay"),
            configuredBuiltIns = setOf(anthropic, google),
        )
        assertEquals(listOf(google, anthropic, "Relay"), derived)
    }

    @Test
    fun storedListKeepsOrderDropsUnknownAndAppendsUnlistedCustoms() {
        val resolved = resolveProviderList(
            stored = listOf("Relay", "Gone", openAi),
            customNames = listOf("Relay", "Second"),
            configuredBuiltIns = setOf(anthropic),
        )
        assertEquals(listOf("Relay", openAi, "Second"), resolved)
    }

    @Test
    fun ollamaCountsAsConfiguredOnlyThroughItsBaseUrl() {
        assertEquals(
            setOf(openAi, ollama),
            legacyConfiguredBuiltIns(
                apiKeyProviders = setOf(openAi),
                baseUrls = mapOf(ollama to "http://host:11434", anthropic to "https://relay"),
            ),
        )
        assertTrue(legacyConfiguredBuiltIns(setOf(ollama), emptyMap()).isEmpty())
    }

    @Test
    fun legacyInstallWithKeyDerivesMembership() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.edit { it[API_KEYS_JSON] = testJson.encodeToString(listOf(key(openAi))) }
        val store = ProviderListStore(dataStore, testJson)
        assertEquals(listOf(openAi), store.providerList.first())
        store.materialize()
        assertEquals(listOf(openAi), dataStore.data.first().storedProviderList(testJson))
    }

    @Test
    fun removingBuiltInParksItsEnabledModelsAndAddingRestoresThem() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = ProviderListStore(dataStore, testJson)
        store.addBuiltIn(openAi)
        store.addBuiltIn(anthropic)
        dataStore.edit {
            it[ENABLED_MODELS] = setOf("$openAi:gpt-5", "$anthropic:claude", "Local:tiny")
            it[API_KEYS_JSON] = testJson.encodeToString(listOf(key(openAi)))
        }

        store.removeBuiltIn(openAi)
        var prefs = dataStore.data.first()
        assertEquals(listOf(anthropic), store.providerList.first())
        assertEquals(setOf("$anthropic:claude", "Local:tiny"), prefs[ENABLED_MODELS])
        assertEquals(mapOf(openAi to setOf("$openAi:gpt-5")), store.hiddenProviderModels.first())
        // Configuration survives removal.
        assertTrue(prefs[API_KEYS_JSON]!!.contains("sk-test"))

        store.addBuiltIn(openAi)
        prefs = dataStore.data.first()
        assertEquals(listOf(anthropic, openAi), store.providerList.first())
        assertEquals(setOf("$openAi:gpt-5", "$anthropic:claude", "Local:tiny"), prefs[ENABLED_MODELS])
        assertNull(prefs[HIDDEN_PROVIDER_MODELS_JSON])
    }

    @Test
    fun reorderAcceptsOnlyPermutationsOfTheCurrentList() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = ProviderListStore(dataStore, testJson)
        store.addBuiltIn(openAi)
        store.addBuiltIn(anthropic)

        store.reorder(listOf(anthropic))
        assertEquals(listOf(openAi, anthropic), store.providerList.first())
        store.reorder(listOf(anthropic, google))
        assertEquals(listOf(openAi, anthropic), store.providerList.first())
        store.reorder(listOf(anthropic, openAi))
        assertEquals(listOf(anthropic, openAi), store.providerList.first())
    }

    @Test
    fun removingLastKeyOfLegacyDerivedBuiltInKeepsItListed() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.edit { it[API_KEYS_JSON] = testJson.encodeToString(listOf(key(openAi))) }
        val listStore = ProviderListStore(dataStore, testJson)
        val editor = ProviderEditorStore(dataStore, testJson, listStore)

        val result = editor.commit(openAi, draft(openAi, apiKeys = emptyList()))

        assertEquals(ProviderEditorCommitResult.SAVED, result)
        assertEquals(listOf(openAi), listStore.providerList.first())
        assertFalse(dataStore.data.first()[API_KEYS_JSON]!!.contains("sk-test"))
    }

    @Test
    fun builtInCommitWritesKeysActiveKeyAndUrlTogether() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val listStore = ProviderListStore(dataStore, testJson)
        listStore.addBuiltIn(openAi)
        val editor = ProviderEditorStore(dataStore, testJson, listStore)
        val first = key(openAi, id = "a")
        val second = key(openAi, id = "b")

        val result = editor.commit(
            openAi,
            draft(openAi, baseUrl = " https://relay.example/v1 ", apiKeys = listOf(first, second), activeKeyId = "b"),
        )

        val prefs = dataStore.data.first()
        assertEquals(ProviderEditorCommitResult.SAVED, result)
        assertEquals(
            mapOf(openAi to "https://relay.example/v1"),
            testJson.decodeFromString<Map<String, String>>(prefs[PROVIDER_BASE_URLS]!!),
        )
        assertEquals(
            listOf("a", "b"),
            testJson.decodeFromString<List<ApiKeyEntry>>(prefs[API_KEYS_JSON]!!).map { it.id },
        )
        assertEquals(
            mapOf(openAi to "b"),
            testJson.decodeFromString<Map<String, String>>(prefs[ACTIVE_API_KEY_IDS_JSON]!!),
        )
    }

    @Test
    fun savingAPickedButUnlistedBuiltInAddsItInTheSameCommit() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val listStore = ProviderListStore(dataStore, testJson)
        listStore.addBuiltIn(openAi)
        dataStore.edit { prefs ->
            prefs[HIDDEN_PROVIDER_MODELS_JSON] =
                testJson.encodeToString(mapOf(anthropic to setOf("$anthropic:claude")))
        }
        val editor = ProviderEditorStore(dataStore, testJson, listStore)

        // The Add page only opens the editor; membership waits for this commit.
        val result = editor.commit(anthropic, draft(anthropic, apiKeys = listOf(key(anthropic))))

        assertEquals(ProviderEditorCommitResult.SAVED, result)
        assertEquals(listOf(openAi, anthropic), listStore.providerList.first())
        val prefs = dataStore.data.first()
        assertEquals(setOf("$anthropic:claude"), prefs[ENABLED_MODELS])
        assertNull(prefs.hiddenProviderModels(testJson)[anthropic])
    }

    @Test
    fun invalidCommitLeavesPreferencesUntouched() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val listStore = ProviderListStore(dataStore, testJson)
        listStore.addBuiltIn(openAi)
        val editor = ProviderEditorStore(dataStore, testJson, listStore)
        val before = dataStore.data.first()

        val result = editor.commit(openAi, draft(openAi, cacheTtl = "2h", apiKeys = listOf(key(openAi))))

        assertEquals(ProviderEditorCommitResult.FAILED, result)
        assertEquals(before, dataStore.data.first())
        assertEquals(
            ProviderEditorCommitResult.MISSING,
            editor.commit("NoSuchRelay", draft("NoSuchRelay")),
        )
    }

    @Test
    fun importWithoutListAfterReplaceResetDerivesLegacyMembership() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = ProviderListStore(dataStore, testJson)
        store.addBuiltIn(google)
        dataStore.edit { prefs ->
            prefs[API_KEYS_JSON] = testJson.encodeToString(listOf(key(anthropic)))
            clearPortableSettings(prefs)
            prefs[ENABLED_MODELS] = setOf("$anthropic:claude", "$openAi:gpt-5")
        }

        store.restoreImported(importedList = null, importedHidden = emptyMap(), replace = true)

        val prefs = dataStore.data.first()
        assertEquals(listOf(anthropic), prefs.storedProviderList(testJson))
        assertEquals(setOf("$anthropic:claude"), prefs[ENABLED_MODELS])
        assertEquals(mapOf(openAi to setOf("$openAi:gpt-5")), prefs.hiddenProviderModels(testJson))
    }

    @Test
    fun importedListRestoresParkedModelsOfListedBuiltIns() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = ProviderListStore(dataStore, testJson)

        store.restoreImported(
            importedList = listOf(openAi),
            importedHidden = mapOf(
                openAi to setOf("$openAi:gpt-5"),
                anthropic to setOf("$anthropic:claude"),
                "Relay" to setOf("relay-model"),
            ),
            replace = true,
        )

        val prefs = dataStore.data.first()
        assertEquals(listOf(openAi), prefs.storedProviderList(testJson))
        assertEquals(setOf("$openAi:gpt-5"), prefs[ENABLED_MODELS])
        assertEquals(mapOf(anthropic to setOf("$anthropic:claude")), prefs.hiddenProviderModels(testJson))
    }

    @Test
    fun mergeImportAppendsNewEntriesAfterTheCurrentOrder() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val store = ProviderListStore(dataStore, testJson)
        store.addBuiltIn(anthropic)

        store.restoreImported(importedList = listOf(openAi, anthropic), importedHidden = emptyMap(), replace = false)

        assertEquals(listOf(anthropic, openAi), store.providerList.first())
    }

    @Test
    fun createWritesConfigUrlKeysAndMembershipInOneEdit() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val listStore = ProviderListStore(dataStore, testJson)
        val editor = ProviderEditorStore(dataStore, testJson, listStore)
        val createdKey = key("Relay", id = "a")

        val result = editor.create(
            draft(
                name = " Relay ",
                baseUrl = " https://relay.example/v1 ",
                apiKeys = listOf(createdKey),
                activeKeyId = "a",
            ).copy(protocol = CustomEndpointProtocol.ANTHROPIC),
        )

        assertEquals(ProviderEditorCommitResult.SAVED, result)
        val prefs = dataStore.data.first()
        val config = testJson.decodeFromString<List<CustomProviderConfig>>(prefs[CUSTOM_PROVIDERS_JSON]!!).single()
        assertEquals("Relay", config.name)
        assertEquals(CustomEndpointProtocol.ANTHROPIC, config.protocol)
        assertTrue(CustomProviderIdentityPolicy.isStableId(config.id))
        assertEquals(
            "https://relay.example/v1",
            testJson.decodeFromString<Map<String, String>>(prefs[PROVIDER_BASE_URLS]!!)["Relay"],
        )
        assertEquals("Relay", testJson.decodeFromString<List<ApiKeyEntry>>(prefs[API_KEYS_JSON]!!).single().provider)
        assertEquals(listOf("Relay"), listStore.providerList.first())
    }

    @Test
    fun createRejectsADuplicateNameOrABlankUrlWithoutWritingAnything() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        val listStore = ProviderListStore(dataStore, testJson)
        val editor = ProviderEditorStore(dataStore, testJson, listStore)
        assertEquals(
            ProviderEditorCommitResult.SAVED,
            editor.create(draft(name = "Relay", baseUrl = "https://relay.example/v1")),
        )
        val before = dataStore.data.first()

        assertEquals(
            ProviderEditorCommitResult.NAME_CONFLICT,
            editor.create(draft(name = " relay ", baseUrl = "https://other.example/v1")),
        )
        assertEquals(ProviderEditorCommitResult.NAME_CONFLICT, editor.create(draft(name = openAi, baseUrl = "https://x.example")))
        assertEquals(ProviderEditorCommitResult.URL_REQUIRED, editor.create(draft(name = "Second", baseUrl = " ")))
        assertEquals(before, dataStore.data.first())
    }

    private fun key(provider: String, id: String = "k-$provider") =
        ApiKeyEntry(id = id, name = "main", key = "sk-test", provider = provider)

    private fun draft(
        name: String,
        baseUrl: String = "",
        cacheTtl: String = "1h",
        apiKeys: List<ApiKeyEntry> = emptyList(),
        activeKeyId: String? = null,
    ) = ProviderEditorDraft(
        name = name,
        baseUrl = baseUrl,
        protocol = null,
        responsesApiEnabled = false,
        cacheEnabled = true,
        cacheTtl = cacheTtl,
        apiKeys = apiKeys,
        activeKeyId = activeKeyId,
    )

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
