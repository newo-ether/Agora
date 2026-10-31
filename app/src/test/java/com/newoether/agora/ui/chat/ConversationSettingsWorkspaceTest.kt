package com.newoether.agora.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.util.Constants

class ConversationSettingsWorkspaceTest {
    private val global = ConversationSettings(contextWindow = 32768, codeExecutionEnabled = false,
        googleSearchEnabled = true, thinkingEnabled = true, thinkingLevel = "medium", thinkingBudgetEnabled = false,
        thinkingBudgetTokens = 4096, openAiServiceTierEnabled = true, openAiServiceTier = "priority",
        webSearchEnabled = true, shellEnabled = false, lowContextModeEnabled = true)
    @Test
    fun pureProjectionPreservesOverridesProviderAvailabilityAndGlobalFeatureGates() {
        val override = ConversationSettings(thinkingEnabled = false, thinkingLevel = "high", thinkingBudgetTokens = 8192,
            webSearchEnabled = false, shellEnabled = true, openAiWebSearchEnabled = false, contextWindow = 65536)
        val openai = resolveEffectiveConversationControls("a", override, global, Constants.PROVIDER_OPENAI, true, emptyList())
        assertEquals("a", openai.settingsOwnerId)
        assertEquals(false, openai.thinkingEnabled)
        assertEquals("high", openai.thinkingLevel)
        assertEquals(8192, openai.thinkingBudgetTokens)
        assertEquals(true, openai.openAiWebSearchAvailable)
        assertEquals(false, openai.openAiWebSearchEnabled)
        assertEquals("fast", openai.openAiServiceTierState.tier)
        assertEquals(false, openai.webSearchEnabled)
        assertEquals(false, openai.shellEnabled)
        assertEquals(true, openai.showLowContextMode)
        // The global Low Context default is for on-device models only.
        assertEquals(false, openai.lowContextModeEnabled)
        assertEquals(65536, openai.contextWindow)
        val local = resolveEffectiveConversationControls(null, null, global, Constants.PROVIDER_LOCAL, false, emptyList())
        assertNull(local.settingsOwnerId)
        assertEquals(true, local.showLowContextMode)
        assertEquals(true, local.lowContextModeEnabled)
        assertEquals(false, local.openAiWebSearchAvailable)
        assertEquals(true, local.googleSearchEnabled)
    }
    @Test
    fun generationParameterTransformNormalizesLegacyContextAndPreservesCurrentToolValues() {
        val current = ConversationSettings(thinkingEnabled = false, webSearchEnabled = true, maxTokens = 12345)
        val changed = current.withGenerationParameters(ConversationSettings(contextWindow = 20, maxTokens = 12345, thinkingEnabled = true))
        assertEquals(20480, changed.contextWindow)
        assertEquals(12345, changed.maxTokens)
        assertEquals(false, changed.thinkingEnabled)
        assertEquals(true, changed.webSearchEnabled)
        val reset = changed.withGenerationParameters(ConversationSettings())
        assertEquals(current.copy(maxTokens = null), reset)
    }
    @Test
    fun newChatOwnsSettingsWhilePreviousConversationIdRemainsDuringFade() {
        assertNull(
            conversationSettingsOwnerId(
                isNewChatMode = true,
                currentConversationId = "previous-conversation",
            ),
        )
    }

    @Test
    fun ordinaryConversationOwnsItsSettings() {
        assertEquals(
            "conversation",
            conversationSettingsOwnerId(
                isNewChatMode = false,
                currentConversationId = "conversation",
            ),
        )
    }
}
