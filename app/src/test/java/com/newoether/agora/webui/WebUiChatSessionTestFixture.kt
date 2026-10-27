package com.newoether.agora.webui

import com.newoether.agora.data.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

internal fun webUiChatSessionSettings(): SettingsRepository = mockk<SettingsRepository> {
    every { selectedModel } returns MutableStateFlow("default-model")
    every { enabledModels } returns MutableStateFlow(setOf("default-model", "conversation-model"))
    every { developerOptionsEnabled } returns MutableStateFlow(false)
    every { debugModelEnabled } returns MutableStateFlow(false)
    every { modelAliases } returns MutableStateFlow(emptyMap())
    every { modelProviderNames } returns MutableStateFlow(emptyMap())
    every { systemPrompts } returns MutableStateFlow(emptyList())
    every { activeSystemPromptId } returns MutableStateFlow(null)
    every { customProviders } returns MutableStateFlow(emptyList())
    every { conversationSettings } returns MutableStateFlow(emptyMap())
    every { codeExecutionEnabled } returns MutableStateFlow(false)
    every { googleSearchEnabled } returns MutableStateFlow(false)
    every { thinkingEnabled } returns MutableStateFlow(true)
    every { thinkingLevel } returns MutableStateFlow("medium")
    every { thinkingBudgetEnabled } returns MutableStateFlow(false)
    every { thinkingBudgetTokens } returns MutableStateFlow(4096)
    every { openAiServiceTierEnabled } returns MutableStateFlow(false)
    every { openAiServiceTier } returns MutableStateFlow("auto")
    every { openAiResponsesApiEnabled } returns MutableStateFlow(false)
    every { webSearchEnabled } returns MutableStateFlow(true)
    every { shellEnabled } returns MutableStateFlow(true)
    every { localLowContextModeEnabled } returns MutableStateFlow(false)
    every { maxContextWindow } returns MutableStateFlow(32768)
    every { defaultTemperature } returns MutableStateFlow(null)
    every { defaultMaxTokens } returns MutableStateFlow(null)
    every { defaultTopP } returns MutableStateFlow(null)
    every { defaultFrequencyPenalty } returns MutableStateFlow(null)
    every { defaultPresencePenalty } returns MutableStateFlow(null)
    every { contextCompactModel } returns MutableStateFlow(null)
    every { contextCompactPrompt } returns MutableStateFlow("summary")
    every { contextCompactRetainCount } returns MutableStateFlow(4)
    coEvery { awaitInitialLoad() } just Runs
}
