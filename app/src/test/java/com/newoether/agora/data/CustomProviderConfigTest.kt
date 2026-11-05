package com.newoether.agora.data

import com.newoether.agora.util.Constants
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderConfigTest {
    @Test
    fun cacheResolutionUsesProtocolAndStableIdentity() {
        val provider = CustomProviderConfig("Relay", CustomEndpointProtocol.ANTHROPIC,
            id = "custom-provider-00000000-0000-4000-8000-000000000001", anthropicCacheTtl = "5m")
        val providers = listOf(provider)
        for (reference in listOf(provider.name, provider.providerId)) {
            assertTrue(isAnthropicProtocolProvider(reference, providers))
            assertTrue(isAnthropicCacheEnabledForProvider(reference, false, providers))
            assertEquals("5m", anthropicCacheTtlForProvider(reference, "1h", providers))
            assertFalse(isAnthropicCacheEnabledForProvider(reference, true, listOf(provider.copy(anthropicCacheEnabled = false))))
            assertFalse(isAnthropicCacheEnabledForProvider(reference, true, listOf(provider.copy(protocol = CustomEndpointProtocol.OPENAI))))
        }
        assertTrue(isAnthropicCacheEnabledForProvider("Anthropic", true, providers))
        assertFalse(isAnthropicCacheEnabledForProvider("Anthropic", false, providers))
        assertFalse(isAnthropicProtocolProvider("OpenAI", providers))
        val legacy = Json.decodeFromString<CustomProviderConfig>("""{"name":"Legacy","protocol":"anthropic"}""")
        assertTrue(legacy.anthropicCacheEnabled)
        assertEquals("1h", legacy.anthropicCacheTtl)
        assertEquals(provider, Json.decodeFromString<CustomProviderConfig>(Json.encodeToString(provider)))
    }
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun promptCacheKeyFollowsCacheSwitchOnOpenAiProtocolOnly() {
        val openAi = CustomProviderConfig(
            "sub2api", CustomEndpointProtocol.OPENAI,
            id = "custom-provider-00000000-0000-4000-8000-000000000002",
        )
        val anthropic = CustomProviderConfig("Claude relay", CustomEndpointProtocol.ANTHROPIC)
        val google = CustomProviderConfig("Gemini relay", CustomEndpointProtocol.GOOGLE)
        val providers = listOf(openAi, anthropic, google)

        for (reference in listOf(openAi.name, openAi.providerId)) {
            assertTrue(supportsPromptCacheSetting(reference, providers))
            assertTrue(isPromptCacheKeyEnabledForProvider(reference, providers))
            assertFalse(
                isPromptCacheKeyEnabledForProvider(
                    reference, listOf(openAi.copy(anthropicCacheEnabled = false)),
                ),
            )
            // The OpenAI switch must not leak into Anthropic cache_control.
            assertFalse(isAnthropicCacheEnabledForProvider(reference, true, providers))
        }
        assertTrue(isPromptCacheKeyEnabledForProvider("OpenAI", providers))
        assertTrue(isPromptCacheKeyEnabledForProvider(Constants.PROVIDER_MUNITO, providers))
        assertTrue(supportsPromptCacheSetting("Claude relay", providers))
        assertFalse(isPromptCacheKeyEnabledForProvider("Claude relay", providers))
        assertFalse(supportsPromptCacheSetting("Gemini relay", providers))
        assertFalse(isPromptCacheKeyEnabledForProvider("Gemini relay", providers))
        assertFalse(isPromptCacheKeyEnabledForProvider("missing", providers))
        assertFalse(isPromptCacheSettingEnabledForProvider("Anthropic", false, providers))
        assertFalse(
            isPromptCacheSettingEnabledForProvider(
                "sub2api", true, listOf(openAi.copy(anthropicCacheEnabled = false)),
            ),
        )
    }

    @Test
    fun legacyConfigWithoutProtocolDefaultsToOpenAi() {
        val config = json.decodeFromString<CustomProviderConfig>("""{"name":"Legacy"}""")

        assertEquals("Legacy", config.name)
        assertEquals(CustomEndpointProtocol.OPENAI, config.protocol)
        assertEquals("", config.id)
        assertEquals(emptySet<String>(), config.legacyNames)
        assertEquals(false, config.responsesApiEnabled)
    }

    @Test
    fun responsesApiSettingRoundTripsWithStableProviderIdentity() {
        val original = CustomProviderConfig(
            name = "Relay X",
            id = "custom-provider-00000000-0000-4000-8000-000000000001",
            responsesApiEnabled = true,
        )

        assertEquals(
            original,
            json.decodeFromString<CustomProviderConfig>(json.encodeToString(original)),
        )
    }

    @Test
    fun responsesApiRuntimeGateRequiresBuiltInOrCustomOpenAiProtocol() {
        val providers = listOf(
            CustomProviderConfig(
                name = "OpenAI relay",
                protocol = CustomEndpointProtocol.OPENAI,
                responsesApiEnabled = true,
            ),
            CustomProviderConfig(
                name = "Google relay",
                protocol = CustomEndpointProtocol.GOOGLE,
                responsesApiEnabled = true,
            ),
            CustomProviderConfig(
                name = "Unknown relay",
                protocol = CustomEndpointProtocol.UNKNOWN,
                responsesApiEnabled = true,
            ),
        )

        assertEquals(true, isResponsesApiEnabledForProvider("OpenAI", true, providers))
        assertEquals(false, isResponsesApiEnabledForProvider("OpenAI", false, providers))
        assertEquals(true, isResponsesApiEnabledForProvider("OpenAI relay", false, providers))
        assertEquals(
            true,
            isResponsesApiEnabledForProvider(providers[0].providerId, false, providers),
        )
        assertEquals(false, isResponsesApiEnabledForProvider("Google relay", true, providers))
        assertEquals(false, isResponsesApiEnabledForProvider("Unknown relay", true, providers))
        assertEquals(false, isResponsesApiEnabledForProvider("missing", true, providers))
    }

    @Test
    fun supportedProtocolsRoundTrip() {
        CustomEndpointProtocol.selectable.forEach { protocol ->
            val encoded = json.encodeToString(
                CustomProviderConfig(name = "Endpoint", protocol = protocol),
            )
            val decoded = json.decodeFromString<CustomProviderConfig>(encoded)

            assertEquals(protocol, decoded.protocol)
        }
    }

    @Test
    fun stableIdentityAndMigrationMarkersRoundTrip() {
        val original = CustomProviderConfig(
            name = "Relay X",
            protocol = CustomEndpointProtocol.ANTHROPIC,
            id = "custom-provider-00000000-0000-4000-8000-000000000001",
            legacyNames = setOf("Old Relay"),
        )

        assertEquals(
            original,
            json.decodeFromString<CustomProviderConfig>(json.encodeToString(original)),
        )
    }

    @Test
    fun unknownProtocolDecodesFailClosedWithoutDroppingProviderList() {
        val configs = json.decodeFromString<List<CustomProviderConfig>>(
            """[{"name":"Future","protocol":"future-api"},{"name":"Legacy"}]""",
        )

        assertEquals(CustomEndpointProtocol.UNKNOWN, configs[0].protocol)
        assertEquals(CustomEndpointProtocol.OPENAI, configs[1].protocol)
    }
}
