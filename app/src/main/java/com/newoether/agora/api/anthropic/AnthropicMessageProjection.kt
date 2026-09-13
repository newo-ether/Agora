package com.newoether.agora.api.anthropic

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.util.adaptToolRoundsForProvider
import com.newoether.agora.api.util.buildToolCallId
import com.newoether.agora.api.util.encodeImageToBase64
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val projectionJson = Json { ignoreUnknownKeys = true }

/**
 * Projects Agora's durable message path onto the Anthropic Messages wire format.
 *
 * Every Anthropic-protocol transport shares this projection, so a Provider that switches to an
 * Anthropic-compatible endpoint (DeepSeek native web search) reuses the exact same conversion
 * instead of maintaining a second message converter.
 */
internal fun buildAnthropicMessages(
    messages: List<ChatMessage>,
    modelName: String,
    providerName: String,
    includeImages: Boolean,
    signedThinkingRequired: Boolean,
): List<AnthropicMessage> {
    val validatedPath = adaptToolRoundsForProvider(
        messages = messages,
        providerName = providerName,
    ) { toolMessage ->
        toolMessage.isAnthropicToolRoundCompatible(
            targetModel = modelName,
            targetProviderName = providerName,
            signedThinkingRequired = signedThinkingRequired,
        )
    }
    return coalesceAnthropicMessages(buildList {
        var index = 0
        while (index < validatedPath.size) {
            val message = validatedPath[index]
            when {
                message.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                    add(buildAssistantToolUse(message, modelName, providerName))
                    index++
                    if (
                        index < validatedPath.size &&
                        validatedPath[index].id.startsWith(Constants.RESULT_MSG_PREFIX)
                    ) {
                        val resultBlocks = mutableListOf<AnthropicContentPart>()
                        while (
                            index < validatedPath.size &&
                            validatedPath[index].id.startsWith(Constants.RESULT_MSG_PREFIX)
                        ) {
                            resultBlocks.addAll(buildToolResultBlocks(validatedPath[index]))
                            index++
                        }
                        add(AnthropicMessage(role = "user", content = resultBlocks))
                    }
                }
                message.id.startsWith(Constants.RESULT_MSG_PREFIX) -> index++
                else -> {
                    add(
                        buildNormalMessage(
                            if (includeImages) message
                            else message.copy(images = emptyList()),
                        ),
                    )
                    index++
                }
            }
        }
    })
}

/** Converts Agora's client-executed function tools into Anthropic `tools` entries. */
internal fun buildAnthropicTools(tools: List<ToolDefinition>?): List<AnthropicTool>? =
    tools?.map { definition ->
        AnthropicTool(
            name = definition.function.name,
            description = definition.function.description,
            inputSchema = JsonObject(
                mapOf(
                    "type" to JsonPrimitive(definition.function.parameters.type),
                    "properties" to JsonObject(
                        definition.function.parameters.properties.mapValues { (_, property) ->
                            val members = mutableMapOf<String, JsonElement>(
                                "type" to JsonPrimitive(property.type),
                                "description" to JsonPrimitive(property.description),
                            )
                            property.items?.let { items ->
                                members["items"] = JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive(items.type),
                                        "description" to JsonPrimitive(items.description),
                                    ),
                                )
                            }
                            JsonObject(members)
                        },
                    ),
                    "required" to JsonArray(
                        definition.function.parameters.required.map { JsonPrimitive(it) },
                    ),
                ),
            ),
        )
    }

private fun MessageSegment.signatureIsCompatibleWithAnthropic(
    sourceModel: String?,
    targetModel: String,
    targetProviderName: String,
): Boolean {
    signatureProvider?.let {
        return it.equals(Constants.PROVIDER_ANTHROPIC, ignoreCase = true) ||
            it == targetProviderName
    }
    return sourceModel == null ||
        sourceModel.equals(targetModel, ignoreCase = true) ||
        sourceModel.contains("claude", ignoreCase = true)
}

private fun ChatMessage.isAnthropicToolRoundCompatible(
    targetModel: String,
    targetProviderName: String,
    signedThinkingRequired: Boolean,
): Boolean {
    if (!signedThinkingRequired) return true
    val thoughts = segments
        ?.filter { it.type == "thought" && it.content.isNotBlank() }
        .orEmpty()
    return thoughts.isNotEmpty() && thoughts.all {
        !it.signature.isNullOrBlank() &&
            it.signatureIsCompatibleWithAnthropic(modelName, targetModel, targetProviderName)
    }
}

private fun buildAssistantToolUse(
    msg: ChatMessage,
    targetModel: String,
    providerName: String,
): AnthropicMessage {
    // With thinking enabled, Anthropic requires the assistant turn that carries tool_use to
    // replay its thinking block(s) unchanged (content + signature) — a bare tool_use turn is
    // rejected on the follow-up request. Unsigned thoughts cannot be replayed, so only signed
    // segments are included; when none exist (thinking off) the turn stays tool_use-only.
    val thinkingParts = msg.segments
        ?.filter {
            it.type == "thought" &&
                it.content.isNotEmpty() &&
                !it.signature.isNullOrBlank() &&
                it.signatureIsCompatibleWithAnthropic(msg.modelName, targetModel, providerName)
        }
        ?.map { AnthropicContentPart(type = "thinking", thinking = it.content, signature = it.signature) }
        .orEmpty()
    val toolSegs = msg.segments?.filter { it.type == "tool" }
    if (!toolSegs.isNullOrEmpty()) {
        val blocks = toolSegs.map { seg -> buildToolUseBlock(seg.toolCallId, seg.toolName, seg.toolArgs) }
        return AnthropicMessage(role = "assistant", content = thinkingParts + blocks)
    }
    val tc = msg.toolCall ?: return AnthropicMessage(role = "assistant", content = listOf(
        AnthropicContentPart(type = "text", text = "Continue")
    ))
    val block = buildToolUseBlock(tc.toolCallId, tc.toolName, tc.arguments)
    return AnthropicMessage(role = "assistant", content = thinkingParts + listOf(block))
}

private fun buildToolUseBlock(id: String?, name: String?, args: String?): AnthropicContentPart {
    val toolId = id ?: buildToolCallId(name ?: "", args ?: "{}", "tool_")
    val input = try {
        projectionJson.parseToJsonElement(args ?: "{}") as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) { JsonObject(emptyMap()) }
    return AnthropicContentPart(type = "tool_use", id = toolId, name = name ?: "", input = input)
}

private fun buildToolResultBlocks(msg: ChatMessage): List<AnthropicContentPart> {
    val toolSegs = msg.segments?.filter { it.type == "tool" }
    if (!toolSegs.isNullOrEmpty()) {
        return toolSegs.map { seg ->
            val toolId = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}", "tool_")
            AnthropicContentPart(type = "tool_result", toolUseId = toolId, content = seg.toolResult ?: "")
        }
    }
    val tc = msg.toolCall ?: return emptyList()
    val toolId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments, "tool_")
    return listOf(AnthropicContentPart(type = "tool_result", toolUseId = toolId, content = tc.result))
}

private fun buildNormalMessage(msg: ChatMessage): AnthropicMessage {
    val parts = mutableListOf<AnthropicContentPart>()
    val imagePaths = if (msg.participant == Participant.USER) msg.images else emptyList()
    for (imagePath in imagePaths) {
        val encoded = encodeImageToBase64(imagePath)
        if (encoded != null) {
            val (mimeType, base64) = encoded
            parts.add(AnthropicContentPart(
                type = "image",
                source = AnthropicImageSource(mediaType = mimeType, data = base64)
            ))
        }
    }
    // isNotBlank, NOT isNotEmpty: Anthropic rejects a whitespace-only text block with
    // 400 "text content blocks must contain non-whitespace text". Whitespace-only turns
    // are real — a stopped generation that emitted one newline, a tool-only assistant
    // turn, or mergeConsecutiveSameRole joining two blank messages with "\n".
    if (msg.text.isNotBlank()) {
        parts.add(AnthropicContentPart(type = "text", text = msg.text))
    }
    if (parts.isEmpty()) parts.add(AnthropicContentPart(type = "text", text = "Continue"))
    val role = if (msg.participant == Participant.USER) "user" else "assistant"
    return AnthropicMessage(role = role, content = parts)
}
