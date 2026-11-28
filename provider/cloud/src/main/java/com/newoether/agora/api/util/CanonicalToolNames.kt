package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.tool.BuiltInToolNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Request-only immutable projection, including stateless Responses function-call envelopes. */
internal fun canonicalToolNames(messages: List<ChatMessage>): List<ChatMessage> = messages.map { message ->
    message.copy(
        toolCall = message.toolCall?.let { call ->
            call.copy(
                toolName = BuiltInToolNames.canonical(call.toolName),
                responseOutputItems = canonicalResponseToolNames(call.responseOutputItems),
            )
        },
        segments = message.segments?.map { segment ->
            segment.copy(
                toolName = segment.toolName?.let(BuiltInToolNames::canonical),
                responseOutputItems = canonicalResponseToolNames(segment.responseOutputItems),
            )
        },
    )
}

private fun canonicalResponseToolNames(items: List<JsonObject>): List<JsonObject> = items.map { item ->
    val type = (item["type"] as? JsonPrimitive)?.content
    val name = (item["name"] as? JsonPrimitive)?.content
    if (type != "function_call" || name == null) item
    else {
        val canonical = BuiltInToolNames.canonical(name)
        if (canonical == name) item else JsonObject(item + ("name" to JsonPrimitive(canonical)))
    }
}
