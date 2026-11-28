package com.newoether.agora.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val MCP_TOOLS_ENABLED = booleanPreferencesKey("mcp_tools_enabled")

internal const val DEFAULT_MCP_TOOLS_ENABLED = false

val SettingsManager.mcpToolsEnabled: Flow<Boolean>
    get() = context.dataStore.data.map { it[MCP_TOOLS_ENABLED] ?: DEFAULT_MCP_TOOLS_ENABLED }

suspend fun SettingsManager.saveMcpToolsEnabled(enabled: Boolean) {
    context.dataStore.edit { it[MCP_TOOLS_ENABLED] = enabled }
}
