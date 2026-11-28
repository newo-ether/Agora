package com.newoether.agora.ui.chat

import com.newoether.agora.presentation.resolveOpenAiConversationServiceTier

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiConversationServiceTierTest {
    @Test
    fun conversationOverrideFallsBackToGlobalAndNormalizesTier() {
        val inherited = resolveOpenAiConversationServiceTier(
            globalEnabled = true,
            globalTier = OpenAiServiceTiers.FLEX,
            conversationOverride = ConversationSettings(),
            providerName = Constants.PROVIDER_OPENAI,
            builtInOpenAiResponsesEnabled = true,
            customProviders = emptyList(),
        )
        assertTrue(inherited.available)
        assertTrue(inherited.enabled)
        assertEquals(OpenAiServiceTiers.FLEX, inherited.tier)

        val overridden = resolveOpenAiConversationServiceTier(
            globalEnabled = true,
            globalTier = OpenAiServiceTiers.FLEX,
            conversationOverride = ConversationSettings(
                openAiServiceTierEnabled = false,
                openAiServiceTier = "priority",
            ),
            providerName = Constants.PROVIDER_OPENAI,
            builtInOpenAiResponsesEnabled = true,
            customProviders = emptyList(),
        )
        assertFalse(overridden.enabled)
        assertEquals(OpenAiServiceTiers.FAST, overridden.tier)
    }

    @Test
    fun availabilityFollowsSelectedProviderProtocol() {
        val customProviders = listOf(
            CustomProviderConfig(
                name = "OpenAI relay",
                protocol = CustomEndpointProtocol.OPENAI,
                responsesApiEnabled = true,
            ),
            CustomProviderConfig(
                name = "Anthropic relay",
                protocol = CustomEndpointProtocol.ANTHROPIC,
            ),
        )

        assertTrue(
            resolveOpenAiConversationServiceTier(
                false,
                OpenAiServiceTiers.AUTO,
                null,
                "OpenAI relay",
                false,
                customProviders,
            ).available,
        )
        assertFalse(
            resolveOpenAiConversationServiceTier(
                false,
                OpenAiServiceTiers.AUTO,
                null,
                "Anthropic relay",
                false,
                customProviders,
            ).available,
        )
    }

    @Test
    fun availabilityRequiresResponsesForBuiltInAndCustomProviders() {
        assertFalse(
            resolveOpenAiConversationServiceTier(
                globalEnabled = true,
                globalTier = OpenAiServiceTiers.FAST,
                conversationOverride = null,
                providerName = Constants.PROVIDER_OPENAI,
                builtInOpenAiResponsesEnabled = false,
                customProviders = emptyList(),
            ).available,
        )
        assertFalse(
            resolveOpenAiConversationServiceTier(
                globalEnabled = true,
                globalTier = OpenAiServiceTiers.FAST,
                conversationOverride = null,
                providerName = "Relay without Responses",
                builtInOpenAiResponsesEnabled = true,
                customProviders = listOf(
                    CustomProviderConfig(
                        name = "Relay without Responses",
                        protocol = CustomEndpointProtocol.OPENAI,
                        responsesApiEnabled = false,
                    ),
                ),
            ).available,
        )
    }
}
