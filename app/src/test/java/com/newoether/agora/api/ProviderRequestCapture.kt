package com.newoether.agora.api

import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.CloudDebugLog
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.RequestBody
import okio.Buffer

/** Captures the real provider serializer at its public HTTP boundary without opening a socket. */
internal fun captureProviderRequest(
    provider: LlmProvider,
    messages: List<ChatMessage>,
    config: ProviderConfig,
): JsonObject {
    val (request, events) = runProviderUntilRequest(provider, messages, config)
    check(events.none { it is StreamEvent.Error }) { "Provider rejected the request: $events" }
    return checkNotNull(request) { "Provider did not open a request: $events" }
}

/** Exercises local schema validation through the same provider entry point used by generation. */
internal fun providerToolValidationError(tools: List<ToolDefinition>): String? {
    val (request, events) = runProviderUntilRequest(
        CustomOpenAiProvider("schema-test", "https://example.invalid/v1"),
        listOf(ChatMessage(id = "user", text = "hello", participant = Participant.USER)),
        ProviderConfig(apiKey = "", modelId = "test-model", tools = tools, thinkingEnabled = false),
    )
    if (request != null) {
        check(events.none { it is StreamEvent.Error }) { "Unexpected provider error: $events" }
        return null
    }
    val error = events.filterIsInstance<StreamEvent.Error>().single().error
    check(error is GenerationError.RequestFormat) { "Unexpected provider failure: $error" }
    return error.details
}

private fun runProviderUntilRequest(
    provider: LlmProvider,
    messages: List<ChatMessage>,
    config: ProviderConfig,
): Pair<JsonObject?, List<StreamEvent>> {
    var captured: JsonObject? = null
    val events = mutableListOf<StreamEvent>()
    val capturedRequest = CancellationException("Provider request captured")
    fun capture(body: String): Nothing {
        check(captured == null) { "Provider opened more than one request" }
        captured = Json.parseToJsonElement(body).jsonObject
        throw capturedRequest
    }
    mockkObject(HttpClient, CloudDebugLog)
    try {
        every { CloudDebugLog.d(any(), any()) } just Runs
        every { CloudDebugLog.e(any(), any()) } just Runs
        every { CloudDebugLog.w(any(), any()) } just Runs
        every { HttpClient.streamPost(any(), any(), any()) } answers {
            capture(secondArg())
        }
        every { HttpClient.streamPostBody(any(), any(), any(), any()) } answers {
            val buffer = Buffer()
            secondArg<RequestBody>().writeTo(buffer)
            capture(buffer.readUtf8())
        }
        try {
            runBlocking {
                provider.generateResponse(
                    messages,
                    config.copy(requestResolver = ProviderRequestResolver { input, current ->
                        ProviderRequestInput(input, current.systemPrompt)
                    }),
                ).collect(events::add)
            }
        } catch (cancelled: CancellationException) {
            // Coroutine stack recovery can wrap the original cancellation with a copied exception.
            if (generateSequence<Throwable>(cancelled) { it.cause }.none { it === capturedRequest }) {
                throw cancelled
            }
        }
        return captured to events
    } finally {
        unmockkObject(HttpClient, CloudDebugLog)
    }
}
