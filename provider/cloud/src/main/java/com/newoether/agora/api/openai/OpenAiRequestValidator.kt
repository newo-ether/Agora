package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiContentPart
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.api.OpenAiResponseInputContent
import com.newoether.agora.api.OpenAiResponseInputItem
import com.newoether.agora.api.OpenAiResponseTool
import com.newoether.agora.api.OpenAiResponsesRequest
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.util.requireValidRequestFormat
import com.newoether.agora.api.util.safeWireToolCallId
import com.newoether.agora.api.util.safeWireToolName
import com.newoether.agora.api.util.validateToolDefinitions
import com.newoether.agora.model.ModelId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

internal fun OpenAiChatRequest.requireValidWireFormat(provider: String) {
    val violations = mutableListOf<String>()
    if (model.isBlank()) violations += "model is blank"
    if (messages.isEmpty()) violations += "messages is empty"
    violations += validateToolDefinitions(tools)
    if (maxTokens != null && maxTokens <= 0) violations += "max_tokens must be positive"
    if (topP != null && topP !in 0f..1f) violations += "top_p is outside 0..1"

    val pendingToolIds = linkedSetOf<String>()
    val seenToolIds = mutableSetOf<String>()
    var sawNonSystem = false

    messages.forEachIndexed { index, message ->
        val location = "messages[$index]"
        if (message.role !in setOf("system", "user", "assistant", "tool")) {
            violations += "$location has invalid role ${message.role}"
        }
        validateContent(message.content, location, violations)

        when (message.role) {
            "system" -> {
                if (sawNonSystem) violations += "$location system role is not at the beginning"
                if (message.content.isNullOrEmpty() || !message.content.any(::isSubstantivePart)) {
                    violations += "$location system content is empty"
                }
                if (!message.toolCalls.isNullOrEmpty() || message.toolCallId != null) {
                    violations += "$location system message carries tool fields"
                }
            }
            "user" -> {
                sawNonSystem = true
                if (message.content.isNullOrEmpty() || !message.content.any(::isSubstantivePart)) {
                    violations += "$location user content is empty"
                }
                if (pendingToolIds.isNotEmpty()) {
                    violations += "$location interrupts pending tool results"
                }
                if (!message.toolCalls.isNullOrEmpty() || message.toolCallId != null) {
                    violations += "$location user message carries tool fields"
                }
            }
            "assistant" -> {
                sawNonSystem = true
                if (pendingToolIds.isNotEmpty()) {
                    violations += "$location starts before prior tool results are complete"
                    pendingToolIds.clear()
                }
                if (message.toolCallId != null) {
                    violations += "$location assistant message has tool_call_id"
                }
                if (
                    message.toolCalls.isNullOrEmpty() &&
                    (message.content.isNullOrEmpty() || !message.content.any(::isSubstantivePart))
                ) {
                    violations += "$location assistant content is empty"
                }
                message.toolCalls.orEmpty().forEachIndexed { callIndex, call ->
                    val callLocation = "$location.tool_calls[$callIndex]"
                    if (!call.id.matches(safeWireToolCallId)) {
                        violations += "$callLocation id is not wire-safe"
                    }
                    if (call.type != "function") violations += "$callLocation type is not function"
                    if (!call.function.name.matches(safeWireToolName)) {
                        violations += "$callLocation name is not wire-safe"
                    }
                    if (!isJsonObject(call.function.arguments)) {
                        violations += "$callLocation arguments are not a JSON object"
                    }
                    if (call.id.isNotBlank() && !seenToolIds.add(call.id)) {
                        violations += "$callLocation reuses tool call id ${call.id}"
                    }
                    if (call.id.isNotBlank() && !pendingToolIds.add(call.id)) {
                        violations += "$callLocation duplicates tool call id ${call.id}"
                    }
                }
                if (!message.toolCalls.isNullOrEmpty() && message.content != null) {
                    val hasVisibleContent = message.content.any(::isSubstantivePart)
                    if (!hasVisibleContent) {
                        violations += "$location tool-call content must be null or substantive"
                    }
                }
            }
            "tool" -> {
                sawNonSystem = true
                if (message.content.isNullOrEmpty()) {
                    violations += "$location tool content is absent"
                }
                val toolCallId = message.toolCallId
                if (toolCallId.isNullOrBlank()) {
                    violations += "$location tool_call_id is blank"
                } else if (!pendingToolIds.remove(toolCallId)) {
                    violations += "$location does not match a pending tool call"
                }
                if (!message.toolCalls.isNullOrEmpty()) {
                    violations += "$location tool message carries tool_calls"
                }
            }
        }
    }
    if (pendingToolIds.isNotEmpty()) violations += "tool calls are missing results"
    if (messages.lastOrNull()?.role !in setOf("user", "tool")) {
        violations += "history does not end in user/tool input"
    }
    requireValidRequestFormat(provider, violations)
}

/**
 * Raw Responses output items, opaque `reasoning` state included, belong to the model that produced
 * them. Replaying them through another model is wasted context at best and a rejected request at
 * worst, so the provider entry and the model both have to match, and items whose model is not
 * recorded are not replayed at all. Dropping them is safe: the fallback below rebuilds plain
 * `function_call` items from the stored tool calls.
 */
internal fun List<OpenAiMessage>.toResponsesInput(
    providerName: String? = null,
    targetModel: String? = null,
): List<JsonObject> = buildList {
    this@toResponsesInput.forEach { message ->
        if (message.role == "tool") {
            add(
                OpenAiResponseInputItem(
                    type = "function_call_output",
                    callId = message.toolCallId,
                    output = JsonPrimitive(
                        message.content.orEmpty().joinToString("") { it.text.orEmpty() },
                    ),
                ).toResponseInputJson(),
            )
            return@forEach
        }

        val content = message.content.orEmpty().map { part ->
            when (part.type) {
                "image_url" -> OpenAiResponseInputContent(
                    type = "input_image",
                    imageUrl = part.imageUrl?.url,
                    detail = "auto",
                )
                else -> OpenAiResponseInputContent(
                    type = if (message.role == "assistant") "output_text" else "input_text",
                    text = part.text.orEmpty(),
                )
            }
        }
        if (content.isNotEmpty()) {
            add(
                OpenAiResponseInputItem(
                    type = "message",
                    role = message.role,
                    content = content,
                ).toResponseInputJson(),
            )
        }
        val storedItemModel = message.responseOutputItemModel?.trim()?.takeIf(String::isNotEmpty)
        val wantedModel = targetModel?.trim()?.takeIf(String::isNotEmpty)
        val replayedResponseItems = if (
            providerName != null &&
            message.responseOutputItemProvider == providerName &&
            storedItemModel != null &&
            wantedModel != null &&
            ModelId.parse(storedItemModel).modelName.removePrefix("models/")
                .equals(wantedModel.removePrefix("models/"), ignoreCase = true)
        ) {
            message.responseOutputItems.orEmpty()
        } else {
            emptyList()
        }
        addAll(replayedResponseItems)
        if (replayedResponseItems.isEmpty()) {
            message.toolCalls.orEmpty().forEach { call ->
                add(
                    OpenAiResponseInputItem(
                        type = "function_call",
                        callId = call.id,
                        name = call.function.name,
                        arguments = call.function.arguments,
                    ).toResponseInputJson(),
                )
            }
        }
    }
}

private fun OpenAiResponseInputItem.toResponseInputJson(): JsonObject =
    validationJson.encodeToJsonElement(OpenAiResponseInputItem.serializer(), this).jsonObject

private fun JsonObject.toResponseInputItem(): OpenAiResponseInputItem {
    val type = stringField("type") ?: error("type must be a string")
    return when (type) {
        "message", "function_call", "function_call_output" ->
            validationJson.decodeFromJsonElement(OpenAiResponseInputItem.serializer(), this)
        else -> OpenAiResponseInputItem(
            type = type,
            id = stringField("id"),
            callId = stringField("call_id"),
            name = stringField("name"),
            arguments = stringField("arguments"),
            output = this["output"],
        )
    }
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.hasNonNull(name: String): Boolean =
    this[name]?.let { it !is JsonNull } == true

private fun JsonObject.hasAnyNonNull(vararg names: String): Boolean =
    names.any(::hasNonNull)

internal fun List<ToolDefinition>.toResponsesTools(): List<OpenAiResponseTool> = map { tool ->
    OpenAiResponseTool(
        name = tool.function.name,
        description = tool.function.description,
        parameters = tool.function.parameters,
    )
}

internal fun OpenAiResponsesRequest.requireValidWireFormat(provider: String) {
    val violations = mutableListOf<String>()
    if (model.isBlank()) violations += "model is blank"
    if (input.isEmpty()) violations += "input is empty"
    if (maxOutputTokens != null && maxOutputTokens <= 0) {
        violations += "max_output_tokens must be positive"
    }
    if (topP != null && topP !in 0f..1f) violations += "top_p is outside 0..1"
    tools.orEmpty().forEachIndexed { index, tool ->
        when (tool.type) {
            "web_search" -> if (tool.name != null || tool.description != null || tool.parameters != null) {
                violations += "tools[$index] hosted web search has function fields"
            }
            "function" -> if (!tool.name.orEmpty().matches(safeWireToolName)) {
                violations += "tools[$index] name is not wire-safe"
            }
            else -> violations += "tools[$index] has unsupported type ${tool.type}"
        }
    }

    val pending = linkedSetOf<String>()
    val seen = mutableSetOf<String>()
    input.forEachIndexed { index, rawItem ->
        val item = runCatching { rawItem.toResponseInputItem() }.getOrElse { error ->
            violations += "input[$index] is not a supported Responses item: ${error.message}"
            return@forEachIndexed
        }
        val location = "input[$index]"
        when (item.type) {
            "message" -> {
                if (item.role !in setOf("system", "user", "assistant", "developer")) {
                    violations += "$location has invalid message role ${item.role}"
                }
                if (pending.isNotEmpty()) violations += "$location interrupts pending tool results"
                if (item.content.isNullOrEmpty()) violations += "$location content is empty"
                item.content.orEmpty().forEachIndexed { contentIndex, content ->
                    validateResponseContent(
                        content = content,
                        role = item.role,
                        location = "$location.content[$contentIndex]",
                        violations = violations,
                    )
                }
                if (
                    item.id != null && item.role != "assistant" ||
                    item.summary != null || item.encryptedContent != null ||
                    item.callId != null || item.name != null || item.arguments != null ||
                    item.output != null
                ) {
                    violations += "$location message carries unrelated fields"
                }
            }
            "reasoning" -> {
                if (item.id.isNullOrBlank()) violations += "$location reasoning id is blank"
                if (
                    item.role != null || item.content != null || item.callId != null ||
                    item.name != null || item.arguments != null || item.output != null
                ) {
                    violations += "$location opaque item carries unrelated fields"
                }
            }
            "function_call" -> {
                if (item.callId.isNullOrBlank() || !item.callId.matches(safeWireToolCallId)) {
                    violations += "$location call_id is not wire-safe"
                } else {
                    if (!seen.add(item.callId)) violations += "$location reuses call_id ${item.callId}"
                    pending += item.callId
                }
                if (item.name.isNullOrBlank() || !item.name.matches(safeWireToolName)) {
                    violations += "$location name is not wire-safe"
                }
                if (item.arguments == null || !isJsonObject(item.arguments)) {
                    violations += "$location arguments are not a JSON object"
                }
                if (
                    item.summary != null || item.encryptedContent != null || item.role != null ||
                    item.content != null || item.output != null
                ) {
                    violations += "$location function call carries unrelated fields"
                }
            }
            "function_call_output" -> {
                val callId = item.callId
                if (callId.isNullOrBlank()) {
                    violations += "$location call_id is blank"
                } else if (!pending.remove(callId)) {
                    violations += "$location does not match a pending function call"
                }
                if (item.output == null) violations += "$location output is absent"
                if (
                    item.summary != null || item.encryptedContent != null || item.role != null ||
                    item.content != null || item.name != null || item.arguments != null
                ) {
                    violations += "$location function output carries unrelated fields"
                }
            }
            else -> {
                if (item.id.isNullOrBlank()) {
                    violations += "$location opaque item id is blank"
                }
                if (
                    item.role != null || item.content != null || item.summary != null ||
                    item.encryptedContent != null || item.callId != null || item.name != null ||
                    item.arguments != null || item.output != null
                ) {
                    violations += "$location opaque item carries unrelated fields"
                }
            }
        }
    }
    if (pending.isNotEmpty()) violations += "function calls are missing outputs"
    val lastItem = input.lastOrNull()?.let { raw ->
        runCatching { raw.toResponseInputItem() }.getOrNull()
    }
    if (lastItem?.let { item ->
            item.type == "message" && item.role == "user" ||
                item.type == "function_call_output"
        } != true
    ) {
        violations += "input does not end in user/function output"
    }
    requireValidRequestFormat(provider, violations)
}

private fun validateResponseContent(
    content: OpenAiResponseInputContent,
    role: String?,
    location: String,
    violations: MutableList<String>,
) {
    when (content.type) {
        "input_text" -> {
            if (role == "assistant") violations += "$location assistant text must use output_text"
            if (content.text == null || content.imageUrl != null || content.detail != null) {
                violations += "$location is not valid input_text"
            }
        }
        "output_text" -> {
            if (role != "assistant") violations += "$location $role text must use input_text"
            if (content.text == null || content.imageUrl != null || content.detail != null) {
                violations += "$location is not valid output_text"
            }
        }
        "input_image" -> {
            if (role == "assistant") violations += "$location assistant message cannot contain input_image"
            if (content.imageUrl.isNullOrBlank() || content.text != null || content.detail.isNullOrBlank()) {
                violations += "$location is not valid input_image"
            }
        }
        else -> violations += "$location has unsupported type ${content.type}"
    }
}

private val validationJson = Json { ignoreUnknownKeys = true }

private fun isJsonObject(raw: String): Boolean =
    runCatching { validationJson.parseToJsonElement(raw) is JsonObject }.getOrDefault(false)

private fun validateContent(
    parts: List<OpenAiContentPart>?,
    location: String,
    violations: MutableList<String>,
) {
    if (parts == null) return
    if (parts.isEmpty()) {
        violations += "$location content is empty"
        return
    }
    parts.forEachIndexed { index, part ->
        val partLocation = "$location.content[$index]"
        when (part.type) {
            "text" -> if (part.text == null || part.imageUrl != null) {
                violations += "$partLocation is not a valid text part"
            }
            "image_url" -> if (part.imageUrl?.url.isNullOrBlank() || part.text != null) {
                violations += "$partLocation is not a valid image_url part"
            }
            else -> violations += "$partLocation has unsupported type ${part.type}"
        }
    }
}

private fun isSubstantivePart(part: OpenAiContentPart): Boolean = when (part.type) {
    "text" -> !part.text.isNullOrBlank()
    "image_url" -> !part.imageUrl?.url.isNullOrBlank()
    else -> false
}
