package com.newoether.agora.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenAiPromptCacheSerializationTest {
    private val wireJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun chatAndResponsesRequestsSerializePromptCacheKey() {
        val chat = encode(
            OpenAiChatRequest(
                model = "gpt-test",
                messages = emptyList(),
                promptCacheKey = "conversation-id",
            ),
        )
        val responses = encode(
            OpenAiResponsesRequest(
                model = "gpt-test",
                input = emptyList(),
                promptCacheKey = "conversation-id",
            ),
        )

        assertEquals("conversation-id", chat["prompt_cache_key"]!!.jsonPrimitive.content)
        assertEquals("conversation-id", responses["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun chatAndResponsesRequestsOmitNullPromptCacheKey() {
        val chat = encode(OpenAiChatRequest(model = "gpt-test", messages = emptyList()))
        val responses = encode(OpenAiResponsesRequest(model = "gpt-test", input = emptyList()))

        assertFalse(chat.containsKey("prompt_cache_key"))
        assertFalse(responses.containsKey("prompt_cache_key"))
    }

    private fun encode(request: OpenAiChatRequest) =
        Json.parseToJsonElement(
            wireJson.encodeToString(OpenAiChatRequest.serializer(), request),
        ).jsonObject

    private fun encode(request: OpenAiResponsesRequest) =
        Json.parseToJsonElement(
            wireJson.encodeToString(OpenAiResponsesRequest.serializer(), request),
        ).jsonObject
}
