package com.newoether.agora.data.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun decodeSelectionMap(raw: String?): MutableMap<String?, String> =
    raw?.let {
        runCatching {
            Json.decodeFromString<Map<String, String>>(it)
                .mapKeysTo(mutableMapOf()) { entry ->
                    if (entry.key == "null") null else entry.key
                }
        }.getOrDefault(mutableMapOf())
    } ?: mutableMapOf()

internal fun encodeSelectionMap(selections: Map<String?, String>): String =
    Json.encodeToString(selections.mapKeys { it.key ?: "null" })

/** Only the columns needed to paint navigation; no conversation-owned payloads. */
data class DrawerConversationRow(
    val id: String,
    val title: String,
    val hasUnreadGeneration: Boolean,
    val isPinned: Boolean,
)
