package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.newoether.agora.model.ModelId
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SecretCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Ordered user Provider list: built-in remote names and custom Provider names. */
internal val PROVIDER_LIST_JSON = stringPreferencesKey("provider_list_json")

/** Enabled model IDs parked while their built-in Provider is removed from the list. */
internal val HIDDEN_PROVIDER_MODELS_JSON = stringPreferencesKey("hidden_provider_models_json")

/** Remote built-in Provider catalog in canonical order. Local is managed outside the list. */
val BUILT_IN_REMOTE_PROVIDERS: List<String> = listOf(
    Constants.PROVIDER_GOOGLE,
    Constants.PROVIDER_OPENAI,
    Constants.PROVIDER_CHATGPT,
    Constants.PROVIDER_ANTHROPIC,
    Constants.PROVIDER_DEEPSEEK,
    Constants.PROVIDER_DEEPSEEK_ACCOUNT,
    Constants.PROVIDER_QWEN,
    Constants.PROVIDER_GROQ,
    Constants.PROVIDER_OLLAMA,
    Constants.PROVIDER_OPEN_ROUTER,
    Constants.PROVIDER_OPENCODE_GO,
)

/**
 * Pure projection of the persisted Provider list.
 *
 * A missing list is a pre-list installation or a REPLACE import without the field: it derives
 * the configured built-ins in catalog order. Unknown names are dropped and every custom Provider
 * is always listed; customs absent from the stored order are appended in their stored order.
 */
internal fun resolveProviderList(
    stored: List<String>?,
    customNames: List<String>,
    configuredBuiltIns: Set<String>,
): List<String> {
    val builtIns = BUILT_IN_REMOTE_PROVIDERS.toSet()
    val customs = customNames.toSet()
    val base = stored ?: BUILT_IN_REMOTE_PROVIDERS.filter { it in configuredBuiltIns }
    val result = LinkedHashSet<String>()
    base.forEach { name -> if (name in builtIns || name in customs) result += name }
    result += customNames
    return result.toList()
}

/** Legacy configured test used before the list existed: a key, or a URL for Ollama. */
internal fun legacyConfiguredBuiltIns(
    apiKeyProviders: Set<String>,
    baseUrls: Map<String, String>,
): Set<String> = BUILT_IN_REMOTE_PROVIDERS.filterTo(linkedSetOf()) { name ->
    if (name == Constants.PROVIDER_OLLAMA) !baseUrls[name].isNullOrBlank() else name in apiKeyProviders
}

/** Built-in Provider that owns a prefixed model ID; custom IDs use immutable custom IDs. */
internal fun builtInProviderOfModel(modelId: String): String? =
    modelId.takeIf { ':' in it }
        ?.let { ModelId.parse(it).providerName }
        ?.takeIf { it in BUILT_IN_REMOTE_PROVIDERS }

internal class ProviderListStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    val providerList: Flow<List<String>> = dataStore.data.map { it.resolvedProviderList(json) }

    val hiddenProviderModels: Flow<Map<String, Set<String>>> =
        dataStore.data.map { it.hiddenProviderModels(json) }

    /** Appends a built-in and restores its parked enabled models in the same edit. */
    suspend fun addBuiltIn(name: String) {
        require(name in BUILT_IN_REMOTE_PROVIDERS) { "Not a built-in remote Provider" }
        dataStore.edit { prefs -> addBuiltInInPlace(prefs, name) }
    }

    /**
     * Membership half of [addBuiltIn], usable inside a larger edit: a Provider detail save that
     * adds an unlisted built-in writes the list, its parked models and its fields in one commit.
     */
    fun addBuiltInInPlace(prefs: MutablePreferences, name: String) {
        require(name in BUILT_IN_REMOTE_PROVIDERS) { "Not a built-in remote Provider" }
        val list = prefs.resolvedProviderList(json)
        if (name in list) return
        prefs[PROVIDER_LIST_JSON] = json.encodeToString(list + name)
        val parked = prefs.hiddenProviderModels(json).toMutableMap()
        val restored = parked.remove(name).orEmpty()
        if (restored.isNotEmpty()) prefs[ENABLED_MODELS] = prefs[ENABLED_MODELS].orEmpty() + restored
        prefs.writeHiddenProviderModels(parked, json)
    }

    /** Removes a built-in from the list. Credentials, URLs and catalogs are retained. */
    suspend fun removeBuiltIn(name: String) {
        require(name in BUILT_IN_REMOTE_PROVIDERS) { "Not a built-in remote Provider" }
        dataStore.edit { prefs ->
            val list = prefs.resolvedProviderList(json)
            if (name !in list) return@edit
            prefs[PROVIDER_LIST_JSON] = json.encodeToString(list - name)
            prefs.parkEnabledModelsOfUnlistedBuiltIns(list - name, json)
        }
    }

    /** Accepts only a permutation of the current list, so a stale drag cannot add or drop rows. */
    suspend fun reorder(order: List<String>) {
        dataStore.edit { prefs ->
            val current = prefs.resolvedProviderList(json)
            if (order.size != current.size || order.toSet() != current.toSet()) return@edit
            if (order != current) prefs[PROVIDER_LIST_JSON] = json.encodeToString(order)
        }
    }

    /** Persists the derived legacy list so later credential edits cannot change membership. */
    suspend fun materialize() {
        dataStore.edit { prefs ->
            if (prefs[PROVIDER_LIST_JSON] == null) {
                prefs[PROVIDER_LIST_JSON] = json.encodeToString(prefs.resolvedProviderList(json))
            }
        }
    }

    /**
     * Import boundary, run after custom Providers, URLs and enabled models are restored. A null
     * [importedList] is an archive without the field: the current list is kept, or derived by the
     * legacy rule after a REPLACE reset, and then persisted. Every built-in the result omits has
     * its enabled models parked.
     */
    suspend fun restoreImported(
        importedList: List<String>?,
        importedHidden: Map<String, Set<String>>,
        replace: Boolean,
    ) {
        dataStore.edit { prefs ->
            val current = prefs.resolvedProviderList(json)
            val list = when {
                importedList == null -> current
                replace -> importedList
                else -> current + importedList.filterNot { it in current }
            }
            prefs[PROVIDER_LIST_JSON] = json.encodeToString(list.distinct())
            val parked = if (replace) linkedMapOf() else prefs.hiddenProviderModels(json).toMutableMap()
            importedHidden.forEach { (provider, models) ->
                if (provider in BUILT_IN_REMOTE_PROVIDERS) parked[provider] = parked[provider].orEmpty() + models
            }
            val resolved = prefs.resolvedProviderList(json)
            // A parked set whose Provider ends up listed belongs back in the enabled models.
            resolved.forEach { provider ->
                parked.remove(provider)?.let { prefs[ENABLED_MODELS] = prefs[ENABLED_MODELS].orEmpty() + it }
            }
            prefs.writeHiddenProviderModels(parked, json)
            prefs.parkEnabledModelsOfUnlistedBuiltIns(resolved, json)
        }
    }

    suspend fun renameCustom(oldName: String, newName: String) {
        dataStore.edit { prefs -> renameInPlace(prefs, oldName, newName) }
    }

    /** Renames a custom entry in place so the stored order survives a rename. */
    fun renameInPlace(prefs: MutablePreferences, oldName: String, newName: String) {
        val stored = prefs.storedProviderList(json) ?: return
        if (oldName !in stored) return
        prefs[PROVIDER_LIST_JSON] = json.encodeToString(stored.map { if (it == oldName) newName else it })
    }
}

internal fun Preferences.storedProviderList(json: Json): List<String>? =
    this[PROVIDER_LIST_JSON]?.let { raw ->
        runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
    }

internal fun Preferences.resolvedProviderList(json: Json): List<String> {
    val customNames = runCatching {
        json.decodeFromString<List<CustomProviderConfig>>(this[CUSTOM_PROVIDERS_JSON] ?: "[]")
    }.map { CustomProviderNamePolicy.sanitize(it).accepted.map(CustomProviderConfig::name) }
        .getOrDefault(emptyList())
    val stored = storedProviderList(json)
    val configured = if (stored == null) {
        val keyProviders = runCatching {
            json.decodeFromString<List<ApiKeyEntry>>(SecretCrypto.decrypt(this[API_KEYS_JSON] ?: "[]"))
        }.getOrDefault(emptyList()).mapTo(hashSetOf(), ApiKeyEntry::provider)
        val urls = runCatching {
            json.decodeFromString<Map<String, String>>(this[PROVIDER_BASE_URLS] ?: "{}")
        }.getOrDefault(emptyMap())
        legacyConfiguredBuiltIns(keyProviders, urls)
    } else {
        emptySet()
    }
    return resolveProviderList(stored, customNames, configured)
}

internal fun Preferences.hiddenProviderModels(json: Json): Map<String, Set<String>> =
    runCatching {
        json.decodeFromString<Map<String, Set<String>>>(this[HIDDEN_PROVIDER_MODELS_JSON] ?: "{}")
    }.getOrDefault(emptyMap())

private fun MutablePreferences.writeHiddenProviderModels(parked: Map<String, Set<String>>, json: Json) {
    val nonEmpty = parked.filterValues(Set<String>::isNotEmpty)
    if (nonEmpty.isEmpty()) remove(HIDDEN_PROVIDER_MODELS_JSON)
    else this[HIDDEN_PROVIDER_MODELS_JSON] = json.encodeToString(nonEmpty)
}

/** Moves enabled models owned by unlisted built-ins into the parked map. */
private fun MutablePreferences.parkEnabledModelsOfUnlistedBuiltIns(list: List<String>, json: Json) {
    val enabled = this[ENABLED_MODELS].orEmpty()
    val moved = enabled.filter { model ->
        builtInProviderOfModel(model)?.let { it !in list } == true
    }
    if (moved.isEmpty()) return
    this[ENABLED_MODELS] = enabled - moved.toSet()
    val parked = hiddenProviderModels(json).toMutableMap()
    moved.groupBy { builtInProviderOfModel(it)!! }.forEach { (provider, models) ->
        parked[provider] = parked[provider].orEmpty() + models
    }
    writeHiddenProviderModels(parked, json)
}
