package com.newoether.agora.api.anthropic

import com.newoether.agora.api.util.requireValidRequestFormat
import com.newoether.agora.api.util.safeWireToolCallId
import com.newoether.agora.api.util.safeWireToolName

internal fun coalesceAnthropicMessages(
    messages: List<AnthropicMessage>,
): List<AnthropicMessage> {
    val result = mutableListOf<AnthropicMessage>()
    for (message in messages) {
        val previous = result.lastOrNull()
        if (previous?.role == message.role) {
            result[result.lastIndex] = previous.copy(content = previous.content + message.content)
        } else {
            result += message
        }
    }
    return result
}

internal fun AnthropicRequest.requireValidWireFormat() {
    val violations = mutableListOf<String>()
    if (model.isBlank()) violations += "model is blank"
    if (messages.isEmpty()) violations += "messages is empty"
    if (system != null && system.isBlank()) violations += "system is blank"
    if (maxTokens <= 0) violations += "max_tokens must be positive"
    if (topP != null && topP !in 0f..1f) violations += "top_p is outside 0..1"
    validateThinking(violations)
    validateTools(violations)

    val seenToolIds = mutableSetOf<String>()
    var pendingToolIds = linkedSetOf<String>()
    var previousRole: String? = null

    messages.forEachIndexed { messageIndex, message ->
        val location = "messages[$messageIndex]"
        if (message.role !in setOf("user", "assistant")) {
            violations += "$location has invalid role ${message.role}"
        }
        if (previousRole == message.role) {
            violations += "$location repeats role ${message.role}"
        }
        previousRole = message.role
        if (message.content.isEmpty()) violations += "$location content is empty"

        val leadingResults = message.content.takeWhile { it.type == "tool_result" }
        val laterResults = message.content.drop(leadingResults.size).any { it.type == "tool_result" }
        if (laterResults) violations += "$location has tool_result after another content type"
        val leadingThinking = message.content.takeWhile { it.type == "thinking" }
        val laterThinking = message.content.drop(leadingThinking.size).any { it.type == "thinking" }
        if (laterThinking) violations += "$location has thinking after another content type"
        if (
            thinking?.type in setOf("enabled", "adaptive") &&
            message.content.any { it.type == "tool_use" } &&
            leadingThinking.isEmpty()
        ) {
            violations += "$location tool_use is missing its leading signed thinking block"
        }

        if (pendingToolIds.isNotEmpty()) {
            if (message.role != "user") {
                violations += "$location does not immediately answer pending tool_use blocks"
            }
            val returnedIds = leadingResults.mapNotNull { it.toolUseId }
            if (
                returnedIds.size != pendingToolIds.size ||
                returnedIds.distinct().size != returnedIds.size ||
                returnedIds.toSet() != pendingToolIds
            ) {
                violations += "$location does not answer every pending tool_use exactly once"
            }
            pendingToolIds.clear()
        } else if (leadingResults.isNotEmpty()) {
            violations += "$location has orphan tool_result blocks"
        }

        message.content.forEachIndexed { partIndex, part ->
            val partLocation = "$location.content[$partIndex]"
            val populated = listOfNotNull(
                part.text,
                part.thinking,
                part.signature,
                part.source,
                part.id,
                part.name,
                part.input,
                part.toolUseId,
                part.content,
            )
            when (part.type) {
                "text" -> {
                    if (part.text.isNullOrBlank() || populated.size != 1) {
                        violations += "$partLocation is not a valid nonblank text block"
                    }
                }
                "image" -> {
                    if (
                        message.role != "user" ||
                        part.source?.mediaType.isNullOrBlank() ||
                        part.source?.data.isNullOrBlank() ||
                        populated.size != 1
                    ) {
                        violations += "$partLocation is not a valid user image block"
                    }
                }
                "thinking" -> {
                    if (
                        message.role != "assistant" ||
                        part.thinking.isNullOrBlank() ||
                        part.signature.isNullOrBlank() ||
                        populated.size != 2
                    ) {
                        violations += "$partLocation is not a valid signed thinking block"
                    }
                }
                "tool_use" -> {
                    if (
                        message.role != "assistant" ||
                        part.id?.matches(safeWireToolCallId) != true ||
                        part.name?.matches(safeWireToolName) != true ||
                        part.input == null ||
                        populated.size != 3
                    ) {
                        violations += "$partLocation is not a valid tool_use block"
                    }
                    val id = part.id
                    if (!id.isNullOrBlank()) {
                        if (!seenToolIds.add(id)) {
                            violations += "$partLocation reuses tool_use id $id"
                        }
                        if (!pendingToolIds.add(id)) {
                            violations += "$partLocation duplicates tool_use id $id"
                        }
                    }
                }
                "tool_result" -> {
                    if (
                        message.role != "user" ||
                        part.toolUseId.isNullOrBlank() ||
                        part.content == null ||
                        populated.size != 2
                    ) {
                        violations += "$partLocation is not a valid tool_result block"
                    }
                }
                else -> violations += "$partLocation has unsupported type ${part.type}"
            }
        }
    }
    if (pendingToolIds.isNotEmpty()) violations += "tool_use blocks are missing tool_result blocks"
    if (messages.firstOrNull()?.role != "user") violations += "history does not start with user"
    if (messages.lastOrNull()?.role != "user") violations += "history does not end with user input"
    requireValidRequestFormat("Anthropic", violations)
}

private fun AnthropicRequest.validateThinking(violations: MutableList<String>) {
    val thinking = thinking
    if (thinking == null) {
        if (outputConfig != null) violations += "output_config requires adaptive thinking"
        return
    }
    if (thinking.type !in setOf("enabled", "adaptive", "disabled")) {
        violations += "thinking.type is invalid"
    }
    if (thinking.type == "enabled" && (thinking.budgetTokens == null || thinking.budgetTokens < 1024)) {
        violations += "enabled thinking requires budget_tokens >= 1024"
    }
    if (thinking.type == "adaptive" && thinking.budgetTokens != null) {
        violations += "adaptive thinking cannot carry budget_tokens"
    }
    if (thinking.type == "disabled" && (thinking.budgetTokens != null || thinking.display != null)) {
        violations += "disabled thinking cannot carry budget_tokens or display"
    }
    if (outputConfig != null && thinking.type !in setOf("adaptive", "disabled")) {
        violations += "output_config requires adaptive or disabled thinking"
    }
}

private fun AnthropicRequest.validateTools(violations: MutableList<String>) {
    val names = mutableSetOf<String>()
    tools.orEmpty().forEachIndexed { index, tool ->
        if (!tool.name.matches(safeWireToolName)) {
            violations += "tools[$index].name is not wire-safe"
        }
        if (!names.add(tool.name)) violations += "duplicate tool name ${tool.name}"
        if (tool.inputSchema["type"]?.toString()?.trim('"') != "object") {
            violations += "tool ${tool.name} input_schema is not an object schema"
        }
    }
}
