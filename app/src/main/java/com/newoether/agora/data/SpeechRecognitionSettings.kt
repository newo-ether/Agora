package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.newoether.agora.util.SecretCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.UUID

/** How a configured recognizer produces text. LOCAL is reserved for on-device ONNX models. */
@Serializable
enum class SpeechEngineType { CLOUD, SYSTEM, LOCAL }

/** Wire protocol of a cloud recognizer; presets map onto exactly one of these. */
object SpeechProtocols {
    const val OPENAI = "openai"
    const val GEMINI = "gemini"
    const val DASHSCOPE = "dashscope"
    const val ELEVENLABS = "elevenlabs"
    const val DEEPGRAM = "deepgram"

    val all = setOf(OPENAI, GEMINI, DASHSCOPE, ELEVENLABS, DEEPGRAM)
}

/**
 * One recognizer the user can select. [apiKey] is a secret and never leaves the device through
 * portable settings; native backups carry it separately in [NativeBackupSecrets].
 */
@Serializable
data class SpeechRecognitionConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: SpeechEngineType,
    val protocol: String = SpeechProtocols.OPENAI,
    val presetId: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    /** BCP-47 / ISO-639 hint; blank means automatic detection. */
    val language: String = "",
    /** Optional comma or newline separated terms that bias recognition. */
    val vocabulary: String = "",
    /** System engine only: ask Android for its on-device recognizer when available. */
    val preferOffline: Boolean = false,
)

data class SpeechRecognitionSettings(
    val enabled: Boolean = false,
    val configs: List<SpeechRecognitionConfig> = emptyList(),
    val activeId: String = "",
) {
    /** The selected recognizer, falling back to the first one so a stale id never disables input. */
    val active: SpeechRecognitionConfig?
        get() = configs.firstOrNull { it.id == activeId } ?: configs.firstOrNull()

    /** The composer shows its microphone only when recognition is on and something can run. */
    val usable: Boolean get() = enabled && active != null
}

internal val SPEECH_RECOGNITION_ENABLED = booleanPreferencesKey("speech_recognition_enabled")
internal val SPEECH_RECOGNITION_CONFIGS_JSON = stringPreferencesKey("speech_recognition_configs_json")
internal val SPEECH_RECOGNITION_ACTIVE_ID = stringPreferencesKey("speech_recognition_active_id")

private val speechJson = Json { ignoreUnknownKeys = true }

internal fun decodeSpeechConfigs(stored: String?): List<SpeechRecognitionConfig> {
    if (stored.isNullOrBlank()) return emptyList()
    return runCatching {
        speechJson.decodeFromString<List<SpeechRecognitionConfig>>(SecretCrypto.decrypt(stored))
    }.getOrDefault(emptyList()).map(::normalizeSpeechConfig)
}

internal fun normalizeSpeechConfig(config: SpeechRecognitionConfig): SpeechRecognitionConfig =
    config.copy(
        protocol = config.protocol.takeIf { it in SpeechProtocols.all } ?: SpeechProtocols.OPENAI,
        baseUrl = config.baseUrl.trim().trimEnd('/'),
        model = config.model.trim(),
        language = config.language.trim(),
    )

/** Cross-device copy: no secrets, and no LOCAL entries because their model files stay on this device. */
internal fun SpeechRecognitionConfig.asPortableConfig(): SpeechRecognitionConfig? =
    takeIf { it.type != SpeechEngineType.LOCAL }?.copy(apiKey = "")

/** Publishes one preferences snapshot; every mutation is a single DataStore transaction. */
class SpeechRecognitionSettingsStore(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<SpeechRecognitionSettings> = dataStore.data.map { prefs ->
        SpeechRecognitionSettings(
            enabled = prefs[SPEECH_RECOGNITION_ENABLED] ?: false,
            configs = decodeSpeechConfigs(prefs[SPEECH_RECOGNITION_CONFIGS_JSON]),
            activeId = prefs[SPEECH_RECOGNITION_ACTIVE_ID].orEmpty(),
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[SPEECH_RECOGNITION_ENABLED] = enabled }
    }

    suspend fun setActive(id: String) {
        dataStore.edit { it[SPEECH_RECOGNITION_ACTIVE_ID] = id }
    }

    /** Inserts or replaces [config] by id. The first configured recognizer becomes active. */
    suspend fun upsert(config: SpeechRecognitionConfig) {
        dataStore.edit { prefs ->
            val current = decodeSpeechConfigs(prefs[SPEECH_RECOGNITION_CONFIGS_JSON])
            val normalized = normalizeSpeechConfig(config)
            val index = current.indexOfFirst { it.id == normalized.id }
            val next = if (index >= 0) {
                current.toMutableList().also { it[index] = normalized }
            } else {
                current + normalized
            }
            prefs.writeConfigs(next)
            if (current.none { it.id == prefs[SPEECH_RECOGNITION_ACTIVE_ID] }) {
                prefs[SPEECH_RECOGNITION_ACTIVE_ID] = normalized.id
            }
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            val next = decodeSpeechConfigs(prefs[SPEECH_RECOGNITION_CONFIGS_JSON]).filterNot { it.id == id }
            prefs.writeConfigs(next)
            if (prefs[SPEECH_RECOGNITION_ACTIVE_ID] == id) {
                prefs[SPEECH_RECOGNITION_ACTIVE_ID] = next.firstOrNull()?.id.orEmpty()
            }
        }
    }

    suspend fun replaceAll(configs: List<SpeechRecognitionConfig>, activeId: String) {
        dataStore.edit { prefs ->
            prefs.writeConfigs(configs.map(::normalizeSpeechConfig))
            prefs[SPEECH_RECOGNITION_ACTIVE_ID] = activeId
        }
    }

    suspend fun snapshot(): SpeechRecognitionSettings = settings.first()

    private fun MutablePreferences.writeConfigs(configs: List<SpeechRecognitionConfig>) {
        this[SPEECH_RECOGNITION_CONFIGS_JSON] = SecretCrypto.encrypt(speechJson.encodeToString(configs))
    }
}

/** Portable-settings bridge kept out of the archive file so that file stays inside its size budget. */
internal object SpeechRecognitionPortableSettings {
    const val ENABLED_KEY = "speechRecognitionEnabled"
    const val CONFIGS_KEY = "speechRecognitionConfigs"
    const val ACTIVE_KEY = "speechRecognitionActiveId"

    private val listSerializer = ListSerializer(SpeechRecognitionConfig.serializer())

    suspend fun export(store: SpeechRecognitionSettingsStore): Map<String, JsonElement> {
        val current = store.snapshot()
        val portable = current.configs.mapNotNull { it.asPortableConfig() }
        val active = current.activeId.takeIf { id -> portable.any { it.id == id } }
        return mapOf(
            ENABLED_KEY to JsonPrimitive(current.enabled),
            CONFIGS_KEY to speechJson.encodeToJsonElement(listSerializer, portable),
            ACTIVE_KEY to (active?.let(::JsonPrimitive) ?: JsonNull),
        )
    }

    /**
     * Restores only keys present in [obj]. [previous] is captured before a REPLACE reset so imported
     * configs keep the key of a same-id config; LOCAL configs survive because they are never exported.
     */
    suspend fun restore(
        obj: JsonObject,
        store: SpeechRecognitionSettingsStore,
        previous: SpeechRecognitionSettings,
        replace: Boolean,
    ) {
        (obj[ENABLED_KEY] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?.let { store.setEnabled(it) }
        val element = obj[CONFIGS_KEY] ?: return
        val imported = runCatching { speechJson.decodeFromJsonElement(listSerializer, element) }
            .getOrNull()
            ?.mapNotNull { it.asPortableConfig() }
            ?: return
        val previousById = previous.configs.associateBy { it.id }
        val restored = imported.map { it.copy(apiKey = previousById[it.id]?.apiKey.orEmpty()) }
        val local = previous.configs.filter { it.type == SpeechEngineType.LOCAL }
        val merged = if (replace) {
            local + restored
        } else {
            val byId = LinkedHashMap<String, SpeechRecognitionConfig>()
            previous.configs.forEach { byId[it.id] = it }
            restored.forEach { byId[it.id] = it }
            byId.values.toList()
        }
        val requested = (obj[ACTIVE_KEY] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        val active = requested?.takeIf { id -> merged.any { it.id == id } }
            ?: previous.activeId.takeIf { id -> merged.any { it.id == id } }
            ?: merged.firstOrNull()?.id.orEmpty()
        store.replaceAll(merged, active)
    }

    fun clear(prefs: MutablePreferences) {
        prefs.remove(SPEECH_RECOGNITION_ENABLED)
        // LOCAL configs are device state; keep them while dropping portable entries.
        val local = decodeSpeechConfigs(prefs[SPEECH_RECOGNITION_CONFIGS_JSON])
            .filter { it.type == SpeechEngineType.LOCAL }
        if (local.isEmpty()) {
            prefs.remove(SPEECH_RECOGNITION_CONFIGS_JSON)
            prefs.remove(SPEECH_RECOGNITION_ACTIVE_ID)
        } else {
            prefs[SPEECH_RECOGNITION_CONFIGS_JSON] = SecretCrypto.encrypt(speechJson.encodeToString(local))
            if (local.none { it.id == prefs[SPEECH_RECOGNITION_ACTIVE_ID] }) {
                prefs[SPEECH_RECOGNITION_ACTIVE_ID] = local.first().id
            }
        }
    }

    /** Secrets for native backups, keyed by config id. */
    suspend fun captureSecrets(store: SpeechRecognitionSettingsStore): Map<String, String> =
        store.snapshot().configs.filter { it.apiKey.isNotBlank() }.associate { it.id to it.apiKey }

    suspend fun restoreSecrets(
        secrets: Map<String, String>,
        store: SpeechRecognitionSettingsStore,
        replace: Boolean,
    ): Int {
        val current = store.snapshot()
        val ids = current.configs.mapTo(mutableSetOf()) { it.id }
        val next = current.configs.map { config ->
            val imported = secrets[config.id]
            when {
                imported != null -> config.copy(apiKey = imported)
                replace -> config.copy(apiKey = "")
                else -> config
            }
        }
        if (next != current.configs) store.replaceAll(next, current.activeId)
        return secrets.keys.count { it !in ids }
    }
}
