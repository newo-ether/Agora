package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Preserve the old presentation once, before any settings read or edit is admitted. */
internal val modelProviderNamesMigration = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[MODEL_PROVIDER_NAMES_JSON] == null

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (!shouldMigrate(currentData)) return currentData
        val aliases = runCatching {
            Json.decodeFromString<Map<String, String>>(currentData[MODEL_ALIASES_JSON] ?: "{}")
        }.getOrDefault(emptyMap())
        return currentData.toMutablePreferences().apply {
            this[MODEL_PROVIDER_NAMES_JSON] = Json.encodeToString(
                aliases.filterValues(String::isNotBlank).mapValues { false },
            )
        }
    }

    override suspend fun cleanUp() = Unit
}

internal class SettingsModelPreferenceStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    val selectedModel: Flow<String> = dataStore.data.map { it[SELECTED_MODEL] ?: Constants.EXAMPLE_MODEL_ID }
    val anthropicCacheEnabled: Flow<Boolean> = dataStore.data.map { it[ANTHROPIC_CACHE_ENABLED] ?: true }
    val anthropicCacheTtl: Flow<String> = dataStore.data.map { it[ANTHROPIC_CACHE_TTL] ?: "1h" }

    suspend fun saveAnthropicCacheEnabled(enabled: Boolean) {
        dataStore.edit { it[ANTHROPIC_CACHE_ENABLED] = enabled }
    }

    suspend fun saveAnthropicCacheTtl(ttl: String) {
        require(ttl == "5m" || ttl == "1h") { "Invalid Anthropic cache duration" }
        dataStore.edit { it[ANTHROPIC_CACHE_TTL] = ttl }
    }

    suspend fun updateCustomProviderCache(providerId: String, enabled: Boolean? = null, ttl: String? = null) {
        require(ttl == null || ttl == "5m" || ttl == "1h") { "Invalid Anthropic cache duration" }
        dataStore.edit { prefs ->
            val current = json.decodeFromString<List<CustomProviderConfig>>(prefs[CUSTOM_PROVIDERS_JSON] ?: "[]")
            val updated = current.map { config ->
                if (config.providerId == providerId) config.copy(
                    anthropicCacheEnabled = enabled ?: config.anthropicCacheEnabled,
                    anthropicCacheTtl = ttl ?: config.anthropicCacheTtl,
                ) else config
            }
            if (updated != current) prefs[CUSTOM_PROVIDERS_JSON] = json.encodeToString(updated)
        }
    }

    val providerBaseUrls: Flow<Map<String, String>> = dataStore.data.map { pref ->
        val jsonStr = pref[PROVIDER_BASE_URLS] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { DebugLog.e("SettingsManager", "Failed to decode providerBaseUrls", e); emptyMap() }
    }

    val customEndpointResolutions: Flow<Map<String, CustomEndpointResolution>> = dataStore.data.map { pref ->
        val jsonStr = pref[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] ?: "{}"
        try {
            json.decodeFromString<Map<String, CustomEndpointResolution>>(jsonStr)
        } catch (e: Exception) {
            DebugLog.e("SettingsManager", "Failed to decode customEndpointResolutions", e)
            emptyMap()
        }
    }

    val availableModels: Flow<Map<String, List<String>>> = dataStore.data.map { pref ->
        val jsonStr = pref[AVAILABLE_MODELS_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, List<String>>>(jsonStr) } catch (e: Exception) { DebugLog.e("SettingsManager", "Failed to decode availableModels", e); emptyMap() }
    }

    val customModels: Flow<Set<String>> =
        dataStore.data.map { it[CUSTOM_MODELS] ?: emptySet() }

    val enabledModels: Flow<Set<String>> = dataStore.data.map { it[ENABLED_MODELS] ?: emptySet() }

    val modelAliases: Flow<Map<String, String>> = dataStore.data.map { pref ->
        val jsonStr = pref[MODEL_ALIASES_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val apiKeys: Flow<List<ApiKeyEntry>> = dataStore.data.map { pref ->
        val jsonStr = com.newoether.agora.util.SecretCrypto.decrypt(pref[API_KEYS_JSON] ?: "[]")
        try { json.decodeFromString<List<ApiKeyEntry>>(jsonStr) } catch (e: Exception) { emptyList() }
    }

    val activeApiKeyIds: Flow<Map<String, String>> = dataStore.data.map { pref ->
        val jsonStr = pref[ACTIVE_API_KEY_IDS_JSON] ?: "{}"
        try { json.decodeFromString<Map<String, String>>(jsonStr) } catch (e: Exception) { emptyMap() }
    }

    val localChatModels: Flow<List<LocalChatModelConfig>> = dataStore.data.map { pref ->
        val jsonStr = pref[LOCAL_CHAT_MODELS_JSON] ?: "[]"
        try { json.decodeFromString<List<LocalChatModelConfig>>(jsonStr) } catch (e: Exception) { emptyList() }
    }
    val customProviders: Flow<List<CustomProviderConfig>> = dataStore.data.map { pref ->
        val jsonStr = pref[CUSTOM_PROVIDERS_JSON] ?: "[]"
        try {
            val decoded = json.decodeFromString<List<CustomProviderConfig>>(jsonStr)
            val sanitized = CustomProviderNamePolicy.sanitize(decoded)
            if (sanitized.rejected.isNotEmpty()) {
                DebugLog.w(
                    "SettingsManager",
                    "Quarantined invalid custom provider names: " +
                        sanitized.rejected.joinToString { it.name },
                )
            }
            sanitized.accepted
        } catch (e: Exception) {
            DebugLog.e("SettingsManager", "Failed to decode customProviders", e)
            emptyList()
        }
    }

    val lastModelsFetchFingerprint: Flow<String> = dataStore.data.map { it[LAST_MODELS_FETCH_FINGERPRINT] ?: "" }

    suspend fun saveProviderBaseUrl(provider: String, url: String) {
        // Blank = "use the provider's default base URL". Persisting "" would poison the map
        // (callers that resolve an effective URL treat "" as a real override, not as absent),
        // so a blank value removes the key entirely — "absent" is the canonical "default" state.
        // rename/delete pass "" to clear an entry, which is exactly this semantics.
        if (url.isBlank()) {
            dataStore.edit { prefs ->
                val current = prefs[PROVIDER_BASE_URLS] ?: return@edit
                val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { return@edit }
                if (map.remove(provider) != null) prefs[PROVIDER_BASE_URLS] = json.encodeToString(map)
            }
            return
        }
        dataStore.edit { prefs ->
            val current = prefs[PROVIDER_BASE_URLS] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { mutableMapOf() }
            map[provider] = url
            prefs[PROVIDER_BASE_URLS] = json.encodeToString(map)
        }
    }

    suspend fun saveProviderBaseUrls(urls: Map<String, String>) {
        val normalized = urls
            .mapValues { (_, value) -> value.trim() }
            .filterValues { it.isNotBlank() }
        dataStore.edit { prefs ->
            if (normalized.isEmpty()) {
                prefs.remove(PROVIDER_BASE_URLS)
            } else {
                prefs[PROVIDER_BASE_URLS] = json.encodeToString(normalized)
            }
        }
    }

    suspend fun saveCustomEndpointResolution(
        provider: String,
        resolution: CustomEndpointResolution?,
    ) {
        dataStore.edit { prefs ->
            val current = prefs[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] ?: "{}"
            val map = try {
                json.decodeFromString<MutableMap<String, CustomEndpointResolution>>(current)
            } catch (e: Exception) {
                mutableMapOf()
            }
            if (resolution == null) {
                map.remove(provider)
            } else {
                map[provider] = resolution
            }
            if (map.isEmpty()) {
                prefs.remove(CUSTOM_ENDPOINT_RESOLUTIONS_JSON)
            } else {
                prefs[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] = json.encodeToString(map)
            }
        }
    }

    suspend fun renameCustomEndpointResolution(oldName: String, newName: String) {
        dataStore.edit { prefs ->
            val current = prefs[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] ?: return@edit
            val map = try {
                json.decodeFromString<MutableMap<String, CustomEndpointResolution>>(current)
            } catch (e: Exception) {
                return@edit
            }
            val resolution = map.remove(oldName) ?: return@edit
            map[newName] = resolution
            prefs[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] = json.encodeToString(map)
        }
    }

    suspend fun saveSelectedModel(model: String) {
        dataStore.edit { it[SELECTED_MODEL] = model }
    }

    suspend fun saveAvailableModels(provider: String, models: List<String>) {
        dataStore.edit { prefs ->
            val current = prefs[AVAILABLE_MODELS_JSON] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, List<String>>>(current) } catch (e: Exception) { mutableMapOf() }
            map[provider] = models
            prefs[AVAILABLE_MODELS_JSON] = json.encodeToString(map)
        }
    }

    suspend fun saveCustomModels(models: Set<String>) {
        dataStore.edit { it[CUSTOM_MODELS] = models }
    }

    suspend fun addCustomModel(modelId: String, alias: String, showProviderName: Boolean = true) {
        dataStore.edit { prefs ->
            prefs[CUSTOM_MODELS] = (prefs[CUSTOM_MODELS] ?: emptySet()) + modelId
            val enabledModels = (prefs[ENABLED_MODELS] ?: emptySet()) + modelId
            prefs[ENABLED_MODELS] = enabledModels
            if (prefs[SELECTED_MODEL].isNullOrBlank()) {
                prefs[SELECTED_MODEL] = modelId
            }

            val aliases = try {
                json.decodeFromString<Map<String, String>>(
                    prefs[MODEL_ALIASES_JSON] ?: "{}",
                )
            } catch (_: Exception) {
                emptyMap()
            }.toMutableMap()
            if (alias.isBlank()) {
                aliases.remove(modelId)
            } else {
                aliases[modelId] = alias.trim()
            }
            prefs[MODEL_ALIASES_JSON] = json.encodeToString(aliases)
            prefs.setModelProviderName(modelId, showProviderName)
        }
    }

    suspend fun replaceCustomModel(
        oldModelId: String,
        newModelId: String?,
        alias: String,
        showProviderName: Boolean? = null,
    ) {
        if (oldModelId == newModelId) {
            dataStore.edit { prefs ->
                val aliases = try {
                    json.decodeFromString<Map<String, String>>(
                        prefs[MODEL_ALIASES_JSON] ?: "{}",
                    )
                } catch (_: Exception) {
                    emptyMap()
                }.replaceCustomModelAlias(oldModelId, newModelId, alias)
                prefs[MODEL_ALIASES_JSON] = json.encodeToString(aliases)
                if (showProviderName != null) prefs.setModelProviderName(oldModelId, showProviderName)
            }
            return
        }

        dataStore.edit { prefs ->
            val customModels = prefs[CUSTOM_MODELS] ?: emptySet()
            if (oldModelId !in customModels) return@edit

            prefs[CUSTOM_MODELS] =
                customModels.replaceModelReference(oldModelId, newModelId)

            val updatedEnabled =
                (prefs[ENABLED_MODELS] ?: emptySet())
                    .replaceModelReference(oldModelId, newModelId)
            prefs[ENABLED_MODELS] = updatedEnabled

            val aliases = try {
                json.decodeFromString<Map<String, String>>(
                    prefs[MODEL_ALIASES_JSON] ?: "{}",
                )
            } catch (_: Exception) {
                emptyMap()
            }.replaceCustomModelAlias(oldModelId, newModelId, alias)
            prefs[MODEL_ALIASES_JSON] = json.encodeToString(aliases)

            val visibility = json.decodeFromString<MutableMap<String, Boolean>>(
                prefs[MODEL_PROVIDER_NAMES_JSON] ?: "{}",
            )
            val previousVisibility = visibility.remove(oldModelId) ?: true
            if (newModelId != null) visibility[newModelId] = showProviderName ?: previousVisibility
            prefs[MODEL_PROVIDER_NAMES_JSON] = json.encodeToString(visibility)

            if (prefs[SELECTED_MODEL] == oldModelId) {
                prefs[SELECTED_MODEL] =
                    newModelId ?: updatedEnabled.firstOrNull().orEmpty()
            }

            val updatedTranscriptionTargets =
                (prefs[IMAGE_TRANSCRIPTION_ENABLED_MODELS] ?: emptySet())
                    .replaceModelReference(oldModelId, newModelId)
            prefs[IMAGE_TRANSCRIPTION_ENABLED_MODELS] = updatedTranscriptionTargets

            fun replaceNullableReference(key: androidx.datastore.preferences.core.Preferences.Key<String>) {
                when (
                    val updated = prefs[key].replaceModelReference(
                        oldModelId,
                        newModelId,
                    )
                ) {
                    null -> prefs.remove(key)
                    else -> prefs[key] = updated
                }
            }

            replaceNullableReference(TITLE_GENERATION_MODEL)
            replaceNullableReference(IMAGE_TRANSCRIPTION_MODEL)
            replaceNullableReference(IMAGE_GEN_MODEL)
            replaceNullableReference(CONTEXT_COMPACT_MODEL)
        }
    }

    suspend fun saveEnabledModels(models: Set<String>) {
        dataStore.edit { it[ENABLED_MODELS] = models }
    }

    suspend fun saveModelAliases(aliases: Map<String, String>) {
        dataStore.edit { it[MODEL_ALIASES_JSON] = json.encodeToString(aliases) }
    }

    val modelProviderNames: Flow<Map<String, Boolean>> = dataStore.data.map { pref ->
        json.decodeFromString(pref[MODEL_PROVIDER_NAMES_JSON] ?: "{}")
    }

    /**
     * Changes one alias against the value currently stored on disk.  Callers must not rebuild
     * the complete alias map from a StateFlow snapshot: that snapshot can predate startup
     * identity migration and would restore legacy custom-provider keys after migration commits.
     */
    suspend fun updateModelAlias(modelId: String, alias: String, showProviderName: Boolean? = null) {
        dataStore.edit { prefs ->
            val aliases = prefs.mutableModelAliasesOrNull() ?: return@edit
            if (alias.isBlank()) {
                aliases.remove(modelId)
            } else {
                aliases[modelId] = alias.trim()
            }
            prefs[MODEL_ALIASES_JSON] = json.encodeToString(aliases)
            if (showProviderName != null) prefs.setModelProviderName(modelId, showProviderName)
        }
    }

    private fun MutablePreferences.setModelProviderName(modelId: String, visible: Boolean) {
        val visibility = json.decodeFromString<MutableMap<String, Boolean>>(
            this[MODEL_PROVIDER_NAMES_JSON] ?: "{}",
        )
        visibility[modelId] = visible
        this[MODEL_PROVIDER_NAMES_JSON] = json.encodeToString(visibility)
    }

    suspend fun saveModelProviderNames(values: Map<String, Boolean>, replace: Boolean = true) {
        dataStore.edit { prefs ->
            val current = if (replace) emptyMap() else json.decodeFromString<Map<String, Boolean>>(
                prefs[MODEL_PROVIDER_NAMES_JSON] ?: "{}",
            )
            prefs[MODEL_PROVIDER_NAMES_JSON] = json.encodeToString(current + values)
        }
    }

    /**
     * Merges the currently configured Local-provider aliases without touching any other alias.
     * Deleting a local model has its own explicit key-removal path; startup synchronization must
     * not reinterpret an absent/stale local configuration as authorization to erase durable
     * alias data. The read and write occur in one DataStore transaction so this cannot overwrite
     * a concurrent custom-provider identity migration with a stale whole-map snapshot.
     */
    suspend fun synchronizeLocalModelAliases(localAliases: Map<String, String>) {
        val localPrefix = "${Constants.PROVIDER_LOCAL}:"
        val normalized = localAliases
            .filterKeys { it.startsWith(localPrefix) }
            .mapValues { (_, alias) -> alias.trim() }
        dataStore.edit { prefs ->
            val aliases = prefs.mutableModelAliasesOrNull() ?: return@edit
            aliases.putAll(normalized)
            prefs[MODEL_ALIASES_JSON] = json.encodeToString(aliases)
        }
    }

    /** Removes only display preferences owned by [providerId], preserving unrelated edits. */
    suspend fun removeModelAliasesForProvider(providerId: String) {
        val prefix = "$providerId:"
        dataStore.edit { prefs ->
            val aliases = prefs.mutableModelAliasesOrNull() ?: return@edit
            if (aliases.keys.removeAll { it.startsWith(prefix) }) {
                prefs[MODEL_ALIASES_JSON] = json.encodeToString(aliases)
            }
            val visibility = json.decodeFromString<MutableMap<String, Boolean>>(
                prefs[MODEL_PROVIDER_NAMES_JSON] ?: "{}",
            )
            visibility.keys.removeAll { it.startsWith(prefix) }
            prefs[MODEL_PROVIDER_NAMES_JSON] = json.encodeToString(visibility)
        }
    }

    private fun Preferences.mutableModelAliasesOrNull(): MutableMap<String, String>? {
        val raw = this[MODEL_ALIASES_JSON] ?: return mutableMapOf()
        return runCatching {
            json.decodeFromString<MutableMap<String, String>>(raw)
        }.getOrNull()
    }

    suspend fun saveApiKeys(keys: List<ApiKeyEntry>) {
        dataStore.edit { it[API_KEYS_JSON] = com.newoether.agora.util.SecretCrypto.encrypt(json.encodeToString(keys)) }
    }

    suspend fun saveActiveApiKeyIds(ids: Map<String, String>) {
        dataStore.edit { prefs ->
            if (ids.isEmpty()) {
                prefs.remove(ACTIVE_API_KEY_IDS_JSON)
            } else {
                prefs[ACTIVE_API_KEY_IDS_JSON] = json.encodeToString(ids)
            }
        }
    }

    suspend fun setActiveApiKeyId(provider: String, id: String?) {
        dataStore.edit { prefs ->
            val current = prefs[ACTIVE_API_KEY_IDS_JSON] ?: "{}"
            val map = try { json.decodeFromString<MutableMap<String, String>>(current) } catch (e: Exception) { mutableMapOf() }
            if (id == null) map.remove(provider) else map[provider] = id
            prefs[ACTIVE_API_KEY_IDS_JSON] = json.encodeToString(map)
        }
    }

    /**
     * Atomically rename the provider field on every API key entry for [oldProvider] to
     * [newProvider] and remap the active-key-id in the same DataStore edit. Decryption or
     * parse failures leave the raw encrypted blobs completely untouched (fail-preserving),
     * so a rename can never wipe keys due to a transient Keystore error.
     */
    suspend fun renameApiKeyProvider(oldProvider: String, newProvider: String) {
        dataStore.edit { prefs ->
            val rawKeys = prefs[API_KEYS_JSON] ?: return@edit
            val decrypted = runCatching {
                com.newoether.agora.util.SecretCrypto.decrypt(rawKeys)
            }.getOrDefault(rawKeys)
            val keys = runCatching {
                json.decodeFromString<List<ApiKeyEntry>>(decrypted)
            }.getOrNull() ?: return@edit
            val renamed = keys.map { entry ->
                if (entry.provider == oldProvider) entry.copy(provider = newProvider) else entry
            }
            if (renamed != keys) {
                prefs[API_KEYS_JSON] = com.newoether.agora.util.SecretCrypto.encrypt(
                    json.encodeToString(renamed)
                )
            }
            // Remap active-key-id in the same edit so the active key follows the rename.
            val rawIds = prefs[ACTIVE_API_KEY_IDS_JSON] ?: "{}"
            val ids = runCatching {
                json.decodeFromString<MutableMap<String, String>>(rawIds)
            }.getOrNull()
            if (ids != null && ids.containsKey(oldProvider)) {
                ids[newProvider] = ids.remove(oldProvider)!!
                prefs[ACTIVE_API_KEY_IDS_JSON] = json.encodeToString(ids)
            }
        }
    }

    /**
     * Atomically assigns immutable IDs to legacy custom providers and rewrites every DataStore
     * model reference that used the mutable provider name. Provider connection settings remain
     * name-keyed for compatibility; only model ownership moves to the stable namespace.
     *
     * Legacy names stay on the provider config until the independent Room migration succeeds.
     * That marker makes a crash between the two stores recoverable and lets background execution
     * resolve old task/conversation references in the meantime.
     */
    suspend fun normalizeCustomProviderIdentities(): List<CustomProviderIdentityMigration> {
        var migrations = emptyList<CustomProviderIdentityMigration>()
        dataStore.edit { prefs ->
            val raw = prefs[CUSTOM_PROVIDERS_JSON] ?: "[]"
            val decoded = runCatching {
                json.decodeFromString<List<CustomProviderConfig>>(raw)
            }.getOrNull() ?: return@edit
            val normalization = CustomProviderIdentityPolicy.normalize(
                decoded,
                newId = { provider -> CustomProviderIdentityPolicy.legacyId(provider.name) },
            )
            migrations = normalization.migrations
            val migrationMap = migrations.associate {
                it.legacyReference to it.providerId
            }
            val providersChanged = normalization.providers != decoded
            if (providersChanged) {
                prefs[CUSTOM_PROVIDERS_JSON] = json.encodeToString(normalization.providers)
            }

            fun remap(modelId: String): String = modelId.remapProviderReference(migrationMap)
            var modelReferencesChanged = false
            val rawCustomModels = prefs[CUSTOM_MODELS]
            val rawEnabledModels = prefs[ENABLED_MODELS]
            val rawTranscriptionModels = prefs[IMAGE_TRANSCRIPTION_ENABLED_MODELS]
            val customModels = rawCustomModels?.mapTo(linkedSetOf(), ::remap).orEmpty()
            val enabledModels = rawEnabledModels?.mapTo(linkedSetOf(), ::remap).orEmpty()
            val transcriptionModels = rawTranscriptionModels
                ?.mapTo(linkedSetOf(), ::remap)
                .orEmpty()
            if (rawCustomModels != null) prefs[CUSTOM_MODELS] = customModels
            if (rawEnabledModels != null) prefs[ENABLED_MODELS] = enabledModels
            if (rawTranscriptionModels != null) {
                prefs[IMAGE_TRANSCRIPTION_ENABLED_MODELS] = transcriptionModels
            }
            modelReferencesChanged =
                (rawCustomModels != null && rawCustomModels != customModels) ||
                (rawEnabledModels != null && rawEnabledModels != enabledModels) ||
                (rawTranscriptionModels != null &&
                    rawTranscriptionModels != transcriptionModels)
            val scalarKeys = listOf(
                SELECTED_MODEL,
                TITLE_GENERATION_MODEL,
                IMAGE_TRANSCRIPTION_MODEL,
                IMAGE_GEN_MODEL,
                CONTEXT_COMPACT_MODEL,
            )
            val scalarModels = scalarKeys.mapNotNull { key ->
                prefs[key]?.let { modelId ->
                    val remapped = remap(modelId)
                    if (remapped != modelId) modelReferencesChanged = true
                    prefs[key] = remapped
                }
                prefs[key]
            }
            val catalogs = runCatching {
                json.decodeFromString<Map<String, List<String>>>(
                    prefs[AVAILABLE_MODELS_JSON] ?: "{}",
                )
            }.getOrNull()
            val remappedCatalogs = catalogs?.mapValues { (_, models) -> models.map(::remap) }
            if (remappedCatalogs != null) {
                prefs[AVAILABLE_MODELS_JSON] = json.encodeToString(remappedCatalogs)
                if (remappedCatalogs != catalogs) modelReferencesChanged = true
            }
            val aliases = runCatching {
                json.decodeFromString<Map<String, String>>(prefs[MODEL_ALIASES_JSON] ?: "{}")
            }.getOrNull()
            val remappedAliases = aliases?.let { remapModelPreferenceKeys(it, migrationMap) }
            val repairedAliases = remappedAliases?.let {
                repairOrphanedCustomProviderPreferenceKeys(
                    aliases = it,
                    knownModelReferences = buildList {
                        addAll(customModels)
                        addAll(enabledModels)
                        addAll(transcriptionModels)
                        addAll(scalarModels)
                        remappedCatalogs?.values?.forEach(::addAll)
                    },
                    activeProviderIds = normalization.providers.mapTo(linkedSetOf()) {
                        it.providerId
                    },
                )
            }
            if (repairedAliases != null) {
                prefs[MODEL_ALIASES_JSON] = json.encodeToString(repairedAliases)
            }
            val visibility = json.decodeFromString<Map<String, Boolean>>(
                prefs[MODEL_PROVIDER_NAMES_JSON] ?: "{}",
            )
            val remappedVisibility = remapModelPreferenceKeys(visibility, migrationMap)
            prefs[MODEL_PROVIDER_NAMES_JSON] = json.encodeToString(
                repairOrphanedCustomProviderPreferenceKeys(
                    aliases = remappedVisibility,
                    knownModelReferences = customModels + enabledModels + transcriptionModels +
                        scalarModels + remappedCatalogs.orEmpty().values.flatten(),
                    activeProviderIds = normalization.providers.mapTo(linkedSetOf()) { it.providerId },
                ),
            )
            if (
                providersChanged || modelReferencesChanged ||
                repairedAliases != aliases
            ) {
                prefs.remove(LAST_MODELS_FETCH_FINGERPRINT)
            }
        }
        return migrations
    }

    /** Clears only markers whose exact Room migration was confirmed successful. */
    suspend fun clearLegacyCustomProviderNames(
        completed: List<CustomProviderIdentityMigration>,
    ) {
        if (completed.isEmpty()) return
        val completedById = completed.groupBy(
            keySelector = CustomProviderIdentityMigration::providerId,
            valueTransform = CustomProviderIdentityMigration::legacyReference,
        )
        dataStore.edit { prefs ->
            val decoded = runCatching {
                json.decodeFromString<List<CustomProviderConfig>>(
                    prefs[CUSTOM_PROVIDERS_JSON] ?: "[]",
                )
            }.getOrNull() ?: return@edit
            val updated = decoded.map { provider ->
                val names = completedById[provider.id].orEmpty().toSet()
                if (names.isEmpty()) provider else provider.copy(
                    legacyNames = provider.legacyNames - names,
                )
            }
            if (updated != decoded) {
                prefs[CUSTOM_PROVIDERS_JSON] = json.encodeToString(updated)
            }
        }
    }

    suspend fun saveLocalChatModels(models: List<LocalChatModelConfig>) {
        dataStore.edit { prefs ->
            val previous = json.decodeFromString<List<LocalChatModelConfig>>(
                prefs[LOCAL_CHAT_MODELS_JSON] ?: "[]",
            )
            val visibility = json.decodeFromString<MutableMap<String, Boolean>>(
                prefs[MODEL_PROVIDER_NAMES_JSON] ?: "{}",
            )
            val byId = models.associateBy(LocalChatModelConfig::id)
            previous.forEach { old ->
                val replacement = byId[old.id]
                if (replacement?.modelId != old.modelId) {
                    val showProviderName = visibility.remove("Local:${old.modelId}") ?: true
                    if (replacement != null) visibility["Local:${replacement.modelId}"] = showProviderName
                }
            }
            prefs[LOCAL_CHAT_MODELS_JSON] = json.encodeToString(models)
            prefs[MODEL_PROVIDER_NAMES_JSON] = json.encodeToString(visibility)
        }
    }
    suspend fun saveCustomProviders(providers: List<CustomProviderConfig>) {
        val normalized = CustomProviderIdentityPolicy.normalize(
            providers,
            newId = { provider -> CustomProviderIdentityPolicy.legacyId(provider.name) },
        )
        dataStore.edit {
            it[CUSTOM_PROVIDERS_JSON] = json.encodeToString(normalized.providers)
        }
    }

    suspend fun saveLastModelsFetchFingerprint(fingerprint: String) {
        dataStore.edit { it[LAST_MODELS_FETCH_FINGERPRINT] = fingerprint }
    }

    suspend fun invalidatePortableModelCaches() {
        dataStore.edit { prefs ->
            prefs.remove(AVAILABLE_MODELS_JSON)
            prefs.remove(CUSTOM_ENDPOINT_RESOLUTIONS_JSON)
            prefs.remove(LAST_MODELS_FETCH_FINGERPRINT)
        }
    }
}
