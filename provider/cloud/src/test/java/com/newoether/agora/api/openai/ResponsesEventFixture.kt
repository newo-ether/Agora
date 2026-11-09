package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiError
import com.newoether.agora.api.OpenAiResponseEnvelope
import com.newoether.agora.api.OpenAiResponseOutputItem
import com.newoether.agora.api.OpenAiResponseStreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal abstract class ResponsesEventFixture {
    protected fun responsesRouter() =
        OpenAiResponsesEventRouter(Json { ignoreUnknownKeys = true })

    protected fun responseItem(item: OpenAiResponseOutputItem) =
        Json.encodeToJsonElement(OpenAiResponseOutputItem.serializer(), item).jsonObject

    protected fun responseCallItem(
        itemId: String,
        callId: String,
        name: String,
        arguments: String? = null,
    ) = OpenAiResponseOutputItem(
        id = itemId,
        type = "function_call",
        callId = callId,
        name = name,
        arguments = arguments,
    )

    protected fun responseEvent(
        type: String,
        sequence: Int,
        delta: String? = null,
        arguments: String? = null,
        name: String? = null,
        itemId: String? = null,
        outputIndex: Int? = null,
        contentIndex: Int? = null,
        summaryIndex: Int? = null,
        item: OpenAiResponseOutputItem? = null,
        response: OpenAiResponseEnvelope? = null,
        error: OpenAiError? = null,
    ) = OpenAiResponseStreamEvent(
        type = type,
        delta = delta,
        arguments = arguments,
        name = name,
        itemId = itemId,
        outputIndex = outputIndex,
        contentIndex = contentIndex,
        summaryIndex = summaryIndex,
        sequenceNumber = sequence,
        item = item?.let {
            Json.encodeToJsonElement(OpenAiResponseOutputItem.serializer(), it).jsonObject
        },
        response = response,
        error = error,
    )
}
