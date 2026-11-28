package com.newoether.agora.api.ollama

import com.newoether.agora.api.util.requireValidRequestFormat
import com.newoether.agora.api.util.safeWireToolName
import com.newoether.agora.api.util.validateToolDefinitions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal fun OllamaChatRequest.requireValidWireFormat() {
    val violations = mutableListOf<String>()
    if (model.isBlank()) violations += "model is blank"
    if (messages.isEmpty()) violations += "messages is empty"
    violations += validateToolDefinitions(tools)
    when (val think = think) {
        null -> Unit
        !is JsonPrimitive -> violations += "think must be a Boolean or effort string"
        else -> if (
            think.booleanOrNull == null &&
            (!think.isString || think.content !in setOf("low", "medium", "high"))
        ) {
            violations += "think must be a Boolean or low/medium/high"
        }
    }

    val pendingToolNames = ArrayDeque<String>()
    var sawNonSystem = false
    messages.forEachIndexed { index, message ->
        val location = "messages[$index]"
        if (message.role !in setOf("system", "user", "assistant", "tool")) {
            violations += "$location has invalid role ${message.role}"
        }
        when (message.role) {
            "system" -> {
                if (sawNonSystem) violations += "$location system role is not at the beginning"
                if (message.content.isBlank()) violations += "$location system content is blank"
                if (!message.toolCalls.isNullOrEmpty() || message.toolName != null) {
                    violations += "$location system message carries tool fields"
                }
            }
            "user" -> {
                sawNonSystem = true
                if (pendingToolNames.isNotEmpty()) {
                    violations += "$location interrupts pending tool results"
                }
                if (message.content.isBlank() && message.images.isNullOrEmpty()) {
                    violations += "$location user content is empty"
                }
                if (!message.toolCalls.isNullOrEmpty() || message.toolName != null) {
                    violations += "$location user message carries tool fields"
                }
            }
            "assistant" -> {
                sawNonSystem = true
                if (pendingToolNames.isNotEmpty()) {
                    violations += "$location starts before prior tool results are complete"
                    pendingToolNames.clear()
                }
                if (message.toolName != null) violations += "$location assistant has tool_name"
                val calls = message.toolCalls.orEmpty()
                if (calls.isEmpty() && message.content.isBlank() && message.thinking.isNullOrBlank()) {
                    violations += "$location assistant content is empty"
                }
                calls.forEachIndexed { callIndex, call ->
                    val callLocation = "$location.tool_calls[$callIndex]"
                    val name = call.function?.name
                    val arguments = call.function?.arguments
                    if (call.type != "function") violations += "$callLocation type is not function"
                    if (name?.matches(safeWireToolName) != true) {
                        violations += "$callLocation name is not wire-safe"
                    }
                    if (arguments !is JsonObject) violations += "$callLocation arguments are not an object"
                    if (!name.isNullOrBlank()) pendingToolNames.addLast(name)
                }
            }
            "tool" -> {
                sawNonSystem = true
                val expectedName = pendingToolNames.removeFirstOrNull()
                if (message.toolName?.matches(safeWireToolName) != true) {
                    violations += "$location tool_name is not wire-safe"
                } else if (expectedName == null || message.toolName != expectedName) {
                    violations += "$location does not match the pending tool call"
                }
                if (!message.toolCalls.isNullOrEmpty()) {
                    violations += "$location tool result carries tool_calls"
                }
            }
        }
    }
    if (pendingToolNames.isNotEmpty()) violations += "tool calls are missing results"
    val firstConversationRole = messages.firstOrNull { it.role != "system" }?.role
    if (firstConversationRole != "user") violations += "history does not start with user"
    if (messages.lastOrNull()?.role !in setOf("user", "tool")) {
        violations += "history does not end in user/tool input"
    }
    requireValidRequestFormat("Ollama", violations)
}
