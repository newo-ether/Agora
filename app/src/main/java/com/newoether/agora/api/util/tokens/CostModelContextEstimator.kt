package com.newoether.agora.api.util.tokens

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.api.util.imageArtifactOccurrences
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants

/** Fixed cost grouped the way the context indicator presents it. */
data class FixedContextComposition(val systemPromptTokens: Int, val toolTokens: Int)

/**
 * Walks a conversation and totals what a provider will see, asking a [ContextCostModel] for prices.
 *
 * This class owns the structure of a request — which rows and segments a provider is actually sent,
 * and which are display-only — and nothing else. It knows no provider names, no tokenizers and no
 * image formats; swapping any of those means swapping the cost model, not editing the walk.
 */
class CostModelContextEstimator(val costs: ContextCostModel) {

    fun estimate(
        messages: List<ChatMessage>,
        includeAssistantReasoning: Boolean = false,
    ): Int {
        val raw = messages.fold(0L) { total, message ->
            (total + messageRaw(message, includeAssistantReasoning))
                .coerceAtMost(Int.MAX_VALUE.toLong())
        }
        return costs.safetyMargin.apply(raw)
    }

    /** Provider-visible cost that exists even when the conversation history is empty. */
    fun estimateFixed(
        systemPrompt: String?,
        tools: List<ToolDefinition>,
        initialUserPrompt: String? = null,
        codeExecutionEnabled: Boolean = false,
        googleSearchEnabled: Boolean = false,
        openAiWebSearchEnabled: Boolean = false,
    ): Int {
        val parts = fixedRawParts(
            systemPrompt = systemPrompt,
            tools = tools,
            initialUserPrompt = initialUserPrompt,
            codeExecutionEnabled = codeExecutionEnabled,
            googleSearchEnabled = googleSearchEnabled,
            openAiWebSearchEnabled = openAiWebSearchEnabled,
        )
        return costs.safetyMargin.apply(
            (parts.prompt + parts.tools).coerceAtMost(Int.MAX_VALUE.toLong()),
        )
    }

    /**
     * The same fixed cost as [estimateFixed], split by where it comes from so the UI can show what
     * fills the context window. The safety margin is applied per part, so the two parts can differ
     * from [estimateFixed] by a token of rounding.
     */
    fun estimateFixedComposition(
        systemPrompt: String?,
        tools: List<ToolDefinition>,
        initialUserPrompt: String? = null,
        codeExecutionEnabled: Boolean = false,
        googleSearchEnabled: Boolean = false,
        openAiWebSearchEnabled: Boolean = false,
    ): FixedContextComposition {
        val parts = fixedRawParts(
            systemPrompt = systemPrompt,
            tools = tools,
            initialUserPrompt = initialUserPrompt,
            codeExecutionEnabled = codeExecutionEnabled,
            googleSearchEnabled = googleSearchEnabled,
            openAiWebSearchEnabled = openAiWebSearchEnabled,
        )
        return FixedContextComposition(
            systemPromptTokens = costs.safetyMargin.apply(parts.prompt),
            toolTokens = costs.safetyMargin.apply(parts.tools),
        )
    }

    /** Margin-adjusted cost of one standalone string. */
    fun estimateText(text: String): Int = costs.safetyMargin.apply(costs.text.count(text))

    /** Raw, margin-free cost of one image, for callers that aggregate before applying the margin. */
    fun imageTokens(image: ImageDescriptor): Long = costs.image.tokens(image)

    private data class FixedRawParts(val prompt: Long, val tools: Long)

    private fun fixedRawParts(
        systemPrompt: String?,
        tools: List<ToolDefinition>,
        initialUserPrompt: String?,
        codeExecutionEnabled: Boolean,
        googleSearchEnabled: Boolean,
        openAiWebSearchEnabled: Boolean,
    ): FixedRawParts {
        var prompt = costs.envelope.requestOverhead.toLong() +
            costs.envelope.perMessage +
            textRaw(systemPrompt.orEmpty())
        initialUserPrompt?.takeIf(String::isNotBlank)?.let { text ->
            prompt += costs.envelope.perMessage + textRaw(text)
        }
        var toolCost = 0L
        tools.forEach { tool ->
            toolCost += costs.envelope.perToolDefinition
            toolCost += textRaw(tool.type)
            toolCost += textRaw(tool.function.name)
            toolCost += textRaw(tool.function.description)
            val externalSchema = tool.function.parameters.schema
            if (externalSchema != null) {
                toolCost += textRaw(externalSchema.toString())
            } else {
                toolCost += textRaw(tool.function.parameters.type)
                tool.function.parameters.properties.toSortedMap().forEach { (name, property) ->
                    toolCost += textRaw(name)
                    toolCost += toolPropertyRaw(property)
                }
                tool.function.parameters.required.sorted().forEach { required ->
                    toolCost += textRaw(required)
                }
            }
        }
        val nativeTools = listOfNotNull(
            "code_execution".takeIf { codeExecutionEnabled },
            "google_search".takeIf { googleSearchEnabled },
            "web_search".takeIf { openAiWebSearchEnabled },
        )
        nativeTools.forEach { nativeTool ->
            toolCost += costs.envelope.perToolDefinition + textRaw(nativeTool)
        }
        // A provider that injects a tool-use preamble only charges for it when tools are present.
        if (tools.isNotEmpty() || nativeTools.isNotEmpty()) {
            toolCost += costs.envelope.toolSetOverhead
        }
        return FixedRawParts(
            prompt = prompt.coerceAtMost(Int.MAX_VALUE.toLong()),
            tools = toolCost.coerceAtMost(Int.MAX_VALUE.toLong()),
        )
    }

    private fun messageRaw(
        message: ChatMessage,
        includeAssistantReasoning: Boolean,
    ): Long {
        val isToolProtocol = message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            message.id.startsWith(Constants.RESULT_MSG_PREFIX)
        // Provider adapters serialize tool protocol payload from segments/toolCall and ignore the
        // mirrored Room text field. Counting both made result-heavy contexts look up to 2x larger.
        var total = costs.envelope.perMessage.toLong() +
            if (isToolProtocol) 0L else textRaw(message.text)
        // Provider adapters serialize images on ordinary USER rows. Tool and generated images
        // reach this estimator through the API-only USER projection, never their source row.
        if (!isToolProtocol && message.participant == Participant.USER) {
            total += imageTokensTotal(message)
        }
        if (!isToolProtocol && includeAssistantReasoning && message.participant != Participant.USER) {
            message.segments.orEmpty()
                .asSequence()
                .filter { it.type == "thought" }
                .forEach { segment -> total += textRaw(segment.content) }
        }
        if (isToolProtocol) {
            total += toolProtocolRaw(message)
        }
        return total
    }

    private fun toolProtocolRaw(message: ChatMessage): Long {
        var total = 0L
        message.segments.orEmpty()
            .asSequence()
            .filter { it.type == "thought" }
            .forEach { segment ->
                total += textRaw(segment.content)
                total += textRaw(segment.signature.orEmpty())
            }
        val segments = message.segments.orEmpty().filter { it.type == "tool" }
        if (segments.isNotEmpty()) {
            segments.forEach { segment ->
                total += costs.envelope.perToolCall
                total += textRaw(segment.toolName.orEmpty())
                total += textRaw(segment.toolArgs.orEmpty())
                total += textRaw(segment.toolResult.orEmpty())
                total += textRaw(segment.signature.orEmpty())
            }
            segments.firstOrNull { it.responseOutputItems.isNotEmpty() }
                ?.responseOutputItems
                .orEmpty()
                .forEach { item -> total += textRaw(item.toString()) }
        } else {
            message.toolCall?.let { call ->
                total += costs.envelope.perToolCall
                total += textRaw(call.toolName)
                total += textRaw(call.arguments)
                total += textRaw(call.result)
                total += textRaw(call.signature.orEmpty())
                call.responseOutputItems.forEach { item -> total += textRaw(item.toString()) }
            }
        }
        return total
    }

    /** One estimate per provider-visible occurrence, including repeated paths. No image is decoded. */
    fun imageEstimates(messages: List<ChatMessage>): List<ImageTokenEstimate> = messages
        .filter {
            it.participant == Participant.USER &&
                !it.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
                !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
        }
        .flatMap { message ->
            message.images.zip(message.imageArtifactOccurrences()).mapNotNull { (path, artifact) ->
                if (path.isBlank() || artifact?.unavailable == true) return@mapNotNull null
                costs.image.estimate(ImageDescriptor(
                    pixelWidth = artifact?.width, pixelHeight = artifact?.height,
                ))
            }
        }

    /** Measured dimensions belong to each transmitted image, never an item's first page. */
    private fun imageTokensTotal(message: ChatMessage): Long =
        imageEstimates(listOf(message)).sumOf(ImageTokenEstimate::tokens)

    private fun textRaw(text: String): Long = costs.text.count(text)

    // A property is priced with everything it nests, because the whole schema is what the provider
    // is sent: an array's element schema and an object's own fields and field names all travel.
    private fun toolPropertyRaw(property: ToolProperty): Long =
        textRaw(property.type) +
            textRaw(property.description) +
            (property.items?.let(::toolPropertyRaw) ?: 0L) +
            property.properties.orEmpty().entries.sumOf { (name, nested) ->
                textRaw(name) + toolPropertyRaw(nested)
            } +
            property.required.orEmpty().sumOf(::textRaw)
}
