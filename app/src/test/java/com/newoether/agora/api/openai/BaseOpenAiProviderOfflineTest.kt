package com.newoether.agora.api.openai

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException

class BaseOpenAiProviderOfflineTest : OpenAiSseTestFixture() {
    @Test fun unavailableNetworkBecomesAnErrorEventForChatAndResponses() {
        val provider = object : BaseOpenAiProvider() {
            override val name = "Offline test"
            override val defaultBaseUrl = "https://example.invalid/v1"
            override fun retryDelayMillis(attempt: Int) = 1L
        }
        mockkObject(HttpClient)
        try {
            for (failure in listOf(UnknownHostException("Network unavailable"), ConnectException("Network unreachable"))) {
                every { HttpClient.streamPost(any(), any(), any()) } throws failure
                for (responses in listOf(false, true)) {
                    val events = collect(provider, ProviderConfig(apiKey = "", modelId = "test-model", responsesApiEnabled = responses))
                    val error = events.filterIsInstance<StreamEvent.Error>().single().error
                    assertEquals(GenerationError.Network(0, failure.message!!), error)
                }
            }
        } finally { unmockkObject(HttpClient) }
    }
}
