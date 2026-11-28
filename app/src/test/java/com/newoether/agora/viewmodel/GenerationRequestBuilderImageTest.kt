package com.newoether.agora.viewmodel

import com.newoether.agora.api.LlmProvider
import com.newoether.agora.data.CustomEndpointProtocol
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.util.Constants
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationRequestBuilderImageTest {
    @Test fun builtInGoogleUsesNativeProtocolAndRegisteredDefaultUrl() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val selected = "${Constants.PROVIDER_GOOGLE}:image-model"
        val provider = mockk<LlmProvider>()
        every { provider.defaultBaseUrl } returns "https://generativelanguage.googleapis.com"
        every { fixture.settings.imageGenModel } returns MutableStateFlow<String?>(selected)
        every { fixture.providerRegistry.providerForModel(selected) } returns Constants.PROVIDER_GOOGLE
        every { fixture.providerRegistry.getInstanceOrNull(Constants.PROVIDER_GOOGLE) } returns provider

        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)

        assertEquals("google", snapshot.context.imageGenProtocol)
        assertEquals("https://generativelanguage.googleapis.com", snapshot.context.imageGenBaseUrl)
        assertEquals("image-model", snapshot.context.imageGenModel)
    }

    @Test fun customGoogleProtocolResolvesStableIdAndFreezesSettingsForTheAdmittedRun() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val custom = CustomProviderConfig(
            name = "Image relay", protocol = CustomEndpointProtocol.GOOGLE,
            id = "custom-provider-00000000-0000-4000-8000-000000000002",
        )
        val selected = "${custom.id}:image-model"
        val providers = MutableStateFlow(listOf(custom))
        every { fixture.settings.customProviders } returns providers
        every { fixture.settings.imageGenModel } returns MutableStateFlow<String?>(selected)
        every { fixture.providerRegistry.providerForModel(selected) } returns custom.id
        every { fixture.providerRegistry.getEffectiveBaseUrl(custom.id) } returns "https://example.test/gemini"
        every { fixture.settings.resolveActiveKey(custom.id) } returns "image-key"

        val first = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)
        providers.value = listOf(custom.copy(protocol = CustomEndpointProtocol.OPENAI))
        every { fixture.providerRegistry.getEffectiveBaseUrl(custom.id) } returns "https://example.test/v1"
        val next = fixture.builder.captureAdmissionSnapshot("conversation", "next-run", fixture.modelId)

        assertEquals("google", first.context.imageGenProtocol)
        assertEquals("https://example.test/gemini", first.context.imageGenBaseUrl)
        assertEquals("image-key", first.context.imageGenApiKey)
        assertEquals("image-model", first.context.imageGenModel)
        assertEquals("openai", next.context.imageGenProtocol)
        assertEquals("https://example.test/v1", next.context.imageGenBaseUrl)
    }

    @Test fun unauthenticatedLocalServingAdapterRetainsItsConfiguredEndpoint() = runTest {
        val fixture = RequestBuilderFixture(Constants.PROVIDER_OPENAI, false)
        val custom = CustomProviderConfig(
            name = "Local image adapter", protocol = CustomEndpointProtocol.OPENAI,
            id = "custom-provider-00000000-0000-4000-8000-000000000003",
        )
        val selected = "${custom.id}:Qwen/Qwen-Image-2.1"
        every { fixture.settings.customProviders } returns MutableStateFlow(listOf(custom))
        every { fixture.settings.imageGenModel } returns MutableStateFlow<String?>(selected)
        every { fixture.providerRegistry.providerForModel(selected) } returns custom.id
        every { fixture.providerRegistry.getEffectiveBaseUrl(custom.id) } returns "http://192.168.1.2:8000/v1"
        every { fixture.settings.resolveActiveKey(custom.id) } returns null

        val snapshot = fixture.builder.captureAdmissionSnapshot("conversation", "run", fixture.modelId)

        assertEquals("openai", snapshot.context.imageGenProtocol)
        assertEquals("", snapshot.context.imageGenApiKey)
        assertEquals("http://192.168.1.2:8000/v1", snapshot.context.imageGenBaseUrl)
    }
}
