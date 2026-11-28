package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import com.newoether.agora.speech.SpeechCloudPresets
import com.newoether.agora.util.SecretCrypto
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SpeechRecognitionPortableSettingsTest {
    private val cloud = SpeechRecognitionConfig(
        id = "cloud",
        name = "OpenAI",
        type = SpeechEngineType.CLOUD,
        presetId = "openai",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-transcribe",
        apiKey = "sk-cloud",
    )
    private val system = SpeechRecognitionConfig(id = "system", name = "System", type = SpeechEngineType.SYSTEM)
    private val local = SpeechRecognitionConfig(id = "local", name = "SenseVoice", type = SpeechEngineType.LOCAL)

    private fun withStore(block: suspend (SpeechRecognitionSettingsStore, DataStore<Preferences>) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("agora-speech-settings").toFile()
        val job = SupervisorJob()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO),
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                directory.resolve("settings.preferences_pb").absolutePath.toPath()
            },
        )
        mockkObject(SecretCrypto)
        every { SecretCrypto.encrypt(any()) } answers { firstArg<String>() }
        every { SecretCrypto.decrypt(any()) } answers { firstArg<String>() }
        try {
            block(SpeechRecognitionSettingsStore(dataStore), dataStore)
        } finally {
            unmockkObject(SecretCrypto)
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun upsertActivatesFirstAndDeleteFallsBack() = withStore { store, _ ->
        store.upsert(cloud.copy(baseUrl = "https://api.openai.com/v1/ "))
        store.upsert(system)
        var settings = store.snapshot()
        assertEquals("cloud", settings.activeId)
        assertEquals("https://api.openai.com/v1", settings.configs.first().baseUrl)
        store.upsert(system.copy(name = "Renamed"))
        assertEquals(listOf("OpenAI", "Renamed"), store.snapshot().configs.map { it.name })
        store.delete("cloud")
        settings = store.snapshot()
        assertEquals("system", settings.activeId)
        assertFalse(settings.usable)
        store.setEnabled(true)
        assertTrue(store.snapshot().usable)
    }

    @Test
    fun exportDropsKeysAndLocalConfigs() = withStore { store, _ ->
        store.replaceAll(listOf(cloud, local, system), "local")
        store.setEnabled(true)
        val exported = SpeechRecognitionPortableSettings.export(store)
        assertEquals(JsonPrimitive(true), exported[SpeechRecognitionPortableSettings.ENABLED_KEY])
        val configs = exported[SpeechRecognitionPortableSettings.CONFIGS_KEY]!!.jsonArray
        assertEquals(listOf("cloud", "system"), configs.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(configs.none { (it.jsonObject["apiKey"]?.jsonPrimitive?.content).orEmpty().isNotEmpty() })
        // The active LOCAL entry is not portable, so no active id travels.
        assertEquals(JsonNull, exported[SpeechRecognitionPortableSettings.ACTIVE_KEY])
    }

    @Test
    fun replaceRestoreKeepsLocalAndSameIdKeys() = withStore { store, dataStore ->
        store.replaceAll(listOf(cloud, local), "cloud")
        val previous = store.snapshot()
        dataStore.edit { SpeechRecognitionPortableSettings.clear(it) }
        assertEquals(listOf("local"), store.snapshot().configs.map { it.id })

        val imported = JsonObject(
            mapOf(
                SpeechRecognitionPortableSettings.ENABLED_KEY to JsonPrimitive(true),
                SpeechRecognitionPortableSettings.CONFIGS_KEY to JsonArray(
                    listOf(
                        portable(cloud.copy(name = "Imported", apiKey = "leaked")),
                        portable(local.copy(id = "foreign-local")),
                    ),
                ),
                SpeechRecognitionPortableSettings.ACTIVE_KEY to JsonPrimitive("cloud"),
            ),
        )
        SpeechRecognitionPortableSettings.restore(imported, store, previous, replace = true)
        val restored = store.snapshot()
        assertTrue(restored.enabled)
        assertEquals(listOf("local", "cloud"), restored.configs.map { it.id })
        val restoredCloud = restored.configs.first { it.id == "cloud" }
        assertEquals("Imported", restoredCloud.name)
        assertEquals("sk-cloud", restoredCloud.apiKey)
        assertEquals("cloud", restored.activeId)
    }

    @Test
    fun mergeRestoreKeepsExistingEntries() = withStore { store, _ ->
        store.replaceAll(listOf(cloud, system), "system")
        val previous = store.snapshot()
        val imported = JsonObject(
            mapOf(
                SpeechRecognitionPortableSettings.CONFIGS_KEY to JsonArray(
                    listOf(portable(SpeechRecognitionConfig(id = "new", name = "Groq", type = SpeechEngineType.CLOUD))),
                ),
                SpeechRecognitionPortableSettings.ACTIVE_KEY to JsonPrimitive("missing"),
            ),
        )
        SpeechRecognitionPortableSettings.restore(imported, store, previous, replace = false)
        val restored = store.snapshot()
        assertEquals(listOf("cloud", "system", "new"), restored.configs.map { it.id })
        assertEquals("system", restored.activeId)
        assertFalse(restored.enabled)
    }

    @Test
    fun clearWithoutLocalRemovesEverything() = withStore { store, dataStore ->
        store.replaceAll(listOf(cloud), "cloud")
        store.setEnabled(true)
        dataStore.edit { SpeechRecognitionPortableSettings.clear(it) }
        val settings = store.snapshot()
        assertTrue(settings.configs.isEmpty())
        assertEquals("", settings.activeId)
        assertFalse(settings.enabled)
    }

    @Test
    fun secretsRoundTripAndCountOrphans() = withStore { store, _ ->
        store.replaceAll(listOf(cloud, system), "cloud")
        assertEquals(mapOf("cloud" to "sk-cloud"), SpeechRecognitionPortableSettings.captureSecrets(store))

        store.replaceAll(listOf(cloud.copy(apiKey = "old"), system.copy(apiKey = "stale")), "cloud")
        val orphans = SpeechRecognitionPortableSettings.restoreSecrets(
            mapOf("cloud" to "sk-new", "gone" to "x"),
            store,
            replace = true,
        )
        assertEquals(1, orphans)
        val configs = store.snapshot().configs.associateBy { it.id }
        assertEquals("sk-new", configs.getValue("cloud").apiKey)
        assertEquals("", configs.getValue("system").apiKey)

        store.replaceAll(listOf(cloud.copy(apiKey = "keep"), system.copy(apiKey = "keep2")), "cloud")
        SpeechRecognitionPortableSettings.restoreSecrets(mapOf("cloud" to "sk-merge"), store, replace = false)
        val merged = store.snapshot().configs.associateBy { it.id }
        assertEquals("sk-merge", merged.getValue("cloud").apiKey)
        assertEquals("keep2", merged.getValue("system").apiKey)
    }

    @Test
    fun qwenUsesInternationalPresetAndOnlyMigratesItsLegacyDefault() {
        val international = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        assertEquals(international, SpeechCloudPresets.byId("qwen").baseUrl)
        val legacy = cloud.copy(presetId = "qwen", protocol = SpeechProtocols.DASHSCOPE,
            baseUrl = " https://dashscope.aliyuncs.com/compatible-mode/v1/ ")
        assertEquals(international, normalizeSpeechConfig(legacy).baseUrl)
        val custom = legacy.copy(baseUrl = "https://example.test/dashscope/v1")
        assertEquals(custom.baseUrl, normalizeSpeechConfig(custom).baseUrl)
        val nonQwen = legacy.copy(presetId = "custom")
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", normalizeSpeechConfig(nonQwen).baseUrl)
    }

    private fun portable(config: SpeechRecognitionConfig) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(config.id),
            "name" to JsonPrimitive(config.name),
            "type" to JsonPrimitive(config.type.name),
            "apiKey" to JsonPrimitive(config.apiKey),
        ),
    )
}
