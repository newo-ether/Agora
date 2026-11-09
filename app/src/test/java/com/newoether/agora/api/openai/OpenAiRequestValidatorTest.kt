package com.newoether.agora.api.openai

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.captureProviderRequest
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.projectGenerationInputMessages
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiRequestValidatorTest {
    @Test
    fun compactApiOnlyInvocationEndsResponsesInputWithUser() {
        val projected = projectGenerationInputMessages(
            messages = listOf(
                ChatMessage(id = "user", text = "question", participant = Participant.USER),
                ChatMessage(id = "assistant", text = "answer", participant = Participant.MODEL),
            ),
            includeImages = true,
            userPrepend = null,
            userPostpend = null,
            initialUserPrompt = "Create the compact context summary now.",
        )
        val request = captureProviderRequest(
            provider = CustomOpenAiProvider("OpenAI", "https://example.invalid/v1"),
            messages = projected,
            config = ProviderConfig(apiKey = "", modelId = "gpt-test", responsesApiEnabled = true),
        )
        val input = request.getValue("input").jsonArray.map { it.jsonObject }

        assertEquals("user", input.last()["role"]?.jsonPrimitive?.content)
    }
}
