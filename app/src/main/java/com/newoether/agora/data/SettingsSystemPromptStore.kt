package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

internal class SettingsSystemPromptStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    val systemPrompts = dataStore.data.map { prefs ->
        runCatching { read(prefs) }.getOrDefault(emptyList())
    }
    val activeSystemPromptId = dataStore.data.map { it[ACTIVE_SYSTEM_PROMPT_ID] }

    private fun read(prefs: Preferences): List<SystemPromptEntry> =
        json.decodeFromString(prefs[SYSTEM_PROMPTS_JSON] ?: "[]")

    suspend fun save(prompts: List<SystemPromptEntry>) {
        dataStore.edit { it[SYSTEM_PROMPTS_JSON] = json.encodeToString(prompts) }
    }

    suspend fun update(transform: (List<SystemPromptEntry>) -> List<SystemPromptEntry>) {
        dataStore.edit { prefs ->
            // Transform durable state, not a consumer's asynchronously published snapshot.
            val current = read(prefs)
            val next = transform(current)
            prefs[SYSTEM_PROMPTS_JSON] = json.encodeToString(next)
            val active = prefs[ACTIVE_SYSTEM_PROMPT_ID]
            val replacement = when {
                active == null -> next.firstOrNull { entry -> current.none { it.id == entry.id } }?.id
                current.any { it.id == active } && next.none { it.id == active } -> next.firstOrNull()?.id
                else -> active
            }
            if (replacement == null) prefs.remove(ACTIVE_SYSTEM_PROMPT_ID)
            else prefs[ACTIVE_SYSTEM_PROMPT_ID] = replacement
        }
    }

    suspend fun setActive(id: String?) {
        dataStore.edit {
            if (id == null) it.remove(ACTIVE_SYSTEM_PROMPT_ID) else it[ACTIVE_SYSTEM_PROMPT_ID] = id
        }
    }

    suspend fun initialize(locale: Locale, now: Long) {
        dataStore.edit { prefs ->
            val firstLaunchMissing = prefs[FIRST_LAUNCH_TIME] == null
            val looksLikeFreshInstall = firstLaunchMissing && prefs[ONBOARDING_COMPLETED] != true
            if (firstLaunchMissing) prefs[FIRST_LAUNCH_TIME] = now
            val current = runCatching { read(prefs) }.getOrDefault(emptyList())
            val migrated = migrateSystemPromptsOnStartup(current, locale)
            if (migrated != current) prefs[SYSTEM_PROMPTS_JSON] = json.encodeToString(migrated)
            if (looksLikeFreshInstall && migrated.isEmpty()) {
                val default = DefaultSystemPrompt.create(locale)
                prefs[SYSTEM_PROMPTS_JSON] = json.encodeToString(listOf(default))
                if (prefs[ACTIVE_SYSTEM_PROMPT_ID] == null) prefs[ACTIVE_SYSTEM_PROMPT_ID] = default.id
            }
        }
    }
}
