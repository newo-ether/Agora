package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SecretCrypto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Explicit-save draft of one remote Provider detail page.
 *
 * Fields that do not apply to the edited Provider carry its current stored value and are written
 * back unchanged. [apiKeys] is the complete ordered key list for this Provider only.
 */
data class ProviderEditorDraft(
    val name: String,
    val baseUrl: String,
    val protocol: CustomEndpointProtocol?,
    val responsesApiEnabled: Boolean,
    val cacheEnabled: Boolean,
    val cacheTtl: String,
    val apiKeys: List<ApiKeyEntry>,
    val activeKeyId: String?,
)

enum class ProviderEditorCommitResult { SAVED, NAME_CONFLICT, URL_REQUIRED, MISSING, FAILED }

/**
 * Draft for the Add Provider editor. Prompt cache starts enabled, matching
 * [CustomProviderConfig.anthropicCacheEnabled]'s own default so a created Provider does not
 * silently lose caching compared with a decoded config.
 */
fun newProviderEditorDraft(cacheTtl: String): ProviderEditorDraft = ProviderEditorDraft(
    name = "",
    baseUrl = "",
    protocol = CustomEndpointProtocol.OPENAI,
    responsesApiEnabled = false,
    cacheEnabled = true,
    cacheTtl = cacheTtl,
    apiKeys = emptyList(),
    activeKeyId = null,
)

/** Commits a Provider editor draft as one DataStore edit, so readers never see a partial save. */
internal class ProviderEditorStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val providerList: ProviderListStore,
) {
    suspend fun commit(originalName: String, draft: ProviderEditorDraft): ProviderEditorCommitResult {
        var result = ProviderEditorCommitResult.FAILED
        dataStore.edit { prefs -> result = prefs.apply(originalName, draft) }
        return result
    }

    /**
     * Creates one custom Provider from the same full editor draft the edit route saves, in a
     * single DataStore edit: the config, its URL, keys, cache fields and list membership appear
     * together, so no reader ever observes a Provider without its credentials.
     */
    suspend fun create(draft: ProviderEditorDraft): ProviderEditorCommitResult {
        var result = ProviderEditorCommitResult.FAILED
        dataStore.edit { prefs -> result = prefs.createInPlace(draft) }
        return result
    }

    private fun MutablePreferences.createInPlace(draft: ProviderEditorDraft): ProviderEditorCommitResult {
        val name = draft.name.trim()
        val customs = runCatching {
            json.decodeFromString<List<CustomProviderConfig>>(this[CUSTOM_PROVIDERS_JSON] ?: "[]")
        }.getOrNull() ?: return ProviderEditorCommitResult.FAILED
        if (CustomProviderNamePolicy.hasConflict(name, customs.map { it.name })) {
            return ProviderEditorCommitResult.NAME_CONFLICT
        }
        if (draft.baseUrl.isBlank()) return ProviderEditorCommitResult.URL_REQUIRED
        // Everything below is the only place that can still refuse a create, and by then the config
        // would already be appended; refuse first so a failed save never leaves a Provider behind.
        if (draft.cacheTtl != "5m" && draft.cacheTtl != "1h") return ProviderEditorCommitResult.FAILED
        val storedKeysReadable = this[API_KEYS_JSON]?.let { raw ->
            runCatching { json.decodeFromString<List<ApiKeyEntry>>(SecretCrypto.decrypt(raw)) }.isSuccess
        } ?: true
        if (!storedKeysReadable) return ProviderEditorCommitResult.FAILED
        val created = CustomProviderConfig(
            name = name,
            protocol = draft.protocol ?: CustomEndpointProtocol.OPENAI,
            id = CustomProviderIdentityPolicy.newId(),
        )
        this[CUSTOM_PROVIDERS_JSON] = json.encodeToString(customs + created)
        // The config now exists, so the ordinary commit owns the URL, keys, cache fields and the
        // materialized Provider list for this name.
        return apply(name, draft.copy(name = name))
    }

    private fun MutablePreferences.apply(
        originalName: String,
        draft: ProviderEditorDraft,
    ): ProviderEditorCommitResult {
        val isBuiltIn = originalName in BUILT_IN_REMOTE_PROVIDERS
        val customs = runCatching {
            json.decodeFromString<List<CustomProviderConfig>>(this[CUSTOM_PROVIDERS_JSON] ?: "[]")
        }.getOrNull() ?: return ProviderEditorCommitResult.FAILED
        val custom = customs.firstOrNull { it.name == originalName }
        if (!isBuiltIn && custom == null) return ProviderEditorCommitResult.MISSING
        val newName = if (custom != null) draft.name.trim() else originalName
        if (
            custom != null && CustomProviderNamePolicy.hasConflict(
                name = newName,
                existingNames = customs.map { it.name },
                currentName = originalName,
            )
        ) return ProviderEditorCommitResult.NAME_CONFLICT
        val url = draft.baseUrl.trim()
        if (custom != null && url.isBlank()) return ProviderEditorCommitResult.URL_REQUIRED
        if (draft.cacheTtl != "5m" && draft.cacheTtl != "1h") return ProviderEditorCommitResult.FAILED
        // Undecodable keys are never overwritten: failing closed keeps the encrypted blob intact.
        val allKeys = this[API_KEYS_JSON]?.let { raw ->
            runCatching {
                json.decodeFromString<List<ApiKeyEntry>>(SecretCrypto.decrypt(raw))
            }.getOrNull() ?: return ProviderEditorCommitResult.FAILED
        }.orEmpty()

        // Membership is fixed before credentials change; a legacy-derived list would otherwise
        // drop a built-in whose last key this save removes.
        if (this[PROVIDER_LIST_JSON] == null) {
            this[PROVIDER_LIST_JSON] = json.encodeToString(resolvedProviderList(json))
        }
        // A built-in picked from the Add page is unlisted until here: Save is what joins the list.
        if (isBuiltIn) providerList.addBuiltInInPlace(this, originalName)
        val renamed = newName != originalName

        val urls = decodeMap(PROVIDER_BASE_URLS)
        val previousUrl = urls.remove(originalName).orEmpty()
        if (url.isNotBlank()) urls[newName] = url
        this[PROVIDER_BASE_URLS] = json.encodeToString(urls)

        val endpointChanged = previousUrl != url || (custom != null && draft.protocol != custom.protocol)
        val resolutionsRaw = this[CUSTOM_ENDPOINT_RESOLUTIONS_JSON]
        if (resolutionsRaw != null) {
            val resolutions = runCatching {
                json.decodeFromString<MutableMap<String, CustomEndpointResolution>>(resolutionsRaw)
            }.getOrNull()
            if (resolutions != null) {
                val previous = resolutions.remove(originalName)
                if (previous != null && !endpointChanged) resolutions[newName] = previous
                this[CUSTOM_ENDPOINT_RESOLUTIONS_JSON] = json.encodeToString(resolutions)
            }
        }

        if (custom != null) {
            val updated = custom.copy(
                name = newName,
                protocol = draft.protocol ?: custom.protocol,
                responsesApiEnabled = draft.responsesApiEnabled,
                anthropicCacheEnabled = draft.cacheEnabled,
                anthropicCacheTtl = draft.cacheTtl,
            )
            this[CUSTOM_PROVIDERS_JSON] =
                json.encodeToString(customs.map { if (it.name == originalName) updated else it })
        } else {
            when (originalName) {
                Constants.PROVIDER_OPENAI -> this[OPENAI_RESPONSES_API_ENABLED] = draft.responsesApiEnabled
                Constants.PROVIDER_ANTHROPIC -> {
                    this[ANTHROPIC_CACHE_ENABLED] = draft.cacheEnabled
                    this[ANTHROPIC_CACHE_TTL] = draft.cacheTtl
                }
            }
        }

        if (renamed) {
            renameProviderModelReferences(json, originalName, newName)
            providerList.renameInPlace(this, originalName, newName)
        }

        val ownKeys = draft.apiKeys.map { it.copy(provider = newName) }
        val keys = allKeys.filter { it.provider != originalName && it.provider != newName } + ownKeys
        this[API_KEYS_JSON] = SecretCrypto.encrypt(json.encodeToString(keys))
        val activeIds = decodeMap(ACTIVE_API_KEY_IDS_JSON)
        activeIds.remove(originalName)
        val activeId = draft.activeKeyId?.takeIf { id -> ownKeys.any { it.id == id } }
            ?: ownKeys.firstOrNull()?.id
        if (activeId != null) activeIds[newName] = activeId else activeIds.remove(newName)
        if (activeIds.isEmpty()) remove(ACTIVE_API_KEY_IDS_JSON)
        else this[ACTIVE_API_KEY_IDS_JSON] = json.encodeToString(activeIds)
        return ProviderEditorCommitResult.SAVED
    }

    private fun Preferences.decodeMap(
        key: Preferences.Key<String>,
    ): MutableMap<String, String> = runCatching {
        json.decodeFromString<MutableMap<String, String>>(this[key] ?: "{}")
    }.getOrDefault(mutableMapOf())
}
