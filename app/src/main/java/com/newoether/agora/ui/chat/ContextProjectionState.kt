package com.newoether.agora.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.api.util.tokens.bpe.O200kBase

/** Every value capable of changing fixed system-prompt or tool-definition token cost. */
@Composable
internal fun rememberContextProjectionInvalidationKey(
    viewModel: ChatViewModel,
    requestConfiguration: List<Any?>,
): List<Any?> {
    val tokenizerReady by O200kBase.ready.collectAsState()
    val settings = viewModel.settings
    val activePromptId by settings.activeSystemPromptId.collectAsState()
    val pendingPromptId by viewModel.pendingSystemPromptId.collectAsState()
    val systemPrompts by settings.systemPrompts.collectAsState()
    val accessSavedMemories by settings.accessSavedMemories.collectAsState()
    val accessActiveMemory by settings.accessActiveMemory.collectAsState()
    val accessSkills by settings.accessSkills.collectAsState()
    val accessSkillsModify by settings.accessSkillsModify.collectAsState()
    val accessPastConversations by settings.accessPastConversations.collectAsState()
    val imageGenEnabled by settings.imageGenEnabled.collectAsState()
    val imageGenModel by settings.imageGenModel.collectAsState()
    val automationToolsEnabled by settings.automationToolsEnabled.collectAsState()
    val todoEnabled by settings.todoEnabled.collectAsState()
    val subagentToolsEnabled by settings.subagentToolsEnabled.collectAsState()
    val enabledModels by settings.enabledModels.collectAsState()
    val subagentEnabledModels by settings.subagentEnabledModels.collectAsState()
    val subagentRecommendedModel by settings.subagentRecommendedModel.collectAsState()
    val sandboxEnabled by settings.sandboxEnabled.collectAsState()
    val sandboxSharedStorageEnabled by settings.sandboxSharedStorageEnabled.collectAsState()
    val mcpServers by viewModel.mcpServerSnapshots.collectAsState()
    val activeMemoryRevision by viewModel.memoryManager.activeMemoryRevision.collectAsState()
    val skillCatalogRevision by viewModel.skillManager.catalogRevision.collectAsState()
    return requestConfiguration + listOf(
        tokenizerReady,
        activePromptId,
        pendingPromptId,
        systemPrompts,
        accessSavedMemories,
        accessActiveMemory,
        accessSkills,
        accessSkillsModify,
        accessPastConversations,
        imageGenEnabled,
        imageGenModel,
        automationToolsEnabled,
        todoEnabled,
        subagentToolsEnabled,
        enabledModels,
        subagentEnabledModels,
        subagentRecommendedModel,
        sandboxEnabled,
        sandboxSharedStorageEnabled,
        mcpServers,
        activeMemoryRevision,
        skillCatalogRevision,
    )
}
