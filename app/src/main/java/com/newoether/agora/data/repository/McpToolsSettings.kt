package com.newoether.agora.data.repository

import com.newoether.agora.data.DEFAULT_MCP_TOOLS_ENABLED
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.mcpToolsEnabled
import com.newoether.agora.data.saveMcpToolsEnabled
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Hot, eagerly published view of the global MCP tools switch. */
internal fun SettingsRepository.mcpToolsEnabledFlow(): StateFlow<Boolean> =
    publication.hot(settingsManager.mcpToolsEnabled, DEFAULT_MCP_TOOLS_ENABLED)

/** Persists the global MCP tools switch. */
fun SettingsRepository.setMcpToolsEnabled(enabled: Boolean) = scope.launch {
    settingsManager.saveMcpToolsEnabled(enabled)
}

internal fun SettingsRepository.replaceMcpServer(server: McpServerConfig) = scope.launch {
    settingsManager.saveMcpServers(
        mcpServers.value.map { if (it.id == server.id) server else it },
    )
}
