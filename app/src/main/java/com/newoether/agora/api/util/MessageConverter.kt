package com.newoether.agora.api.util

import com.newoether.agora.api.OpenAiContentPart
import com.newoether.agora.api.OpenAiImageUrl
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.api.OpenAiRequestFunction
import com.newoether.agora.api.OpenAiRequestToolCall
import com.newoether.agora.api.util.tokens.ContextCostModel
import com.newoether.agora.api.util.tokens.CostModelContextEstimator
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import java.security.MessageDigest

fun buildToolCallId(toolName: String, arguments: String, prefix: String = Constants.TOOL_CALL_ID_PREFIX): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = "$toolName:$arguments"
    val hash = digest.digest(input.toByteArray())
    val shortHash = hash.take(8).joinToString("") { "%02x".format(it) }
    val safeName = toolName
        .trim()
        .replace(Regex("[^A-Za-z0-9_-]+"), "_")
        .trim('_')
        .take(32)
        .ifBlank { "tool" }
    return "$prefix${safeName}_$shortHash"
}

/** Maps an image file path to its MIME type. Providers reject a mislabeled payload
 *  (e.g. a webp sent as image/jpeg), so cover every format the pickers accept. */
fun imageMimeType(imagePath: String): String = when {
    imagePath.endsWith(".png", ignoreCase = true) -> "image/png"
    imagePath.endsWith(".webp", ignoreCase = true) -> "image/webp"
    imagePath.endsWith(".gif", ignoreCase = true) -> "image/gif"
    else -> "image/jpeg"
}


/**
 * [forwardAssistantReasoning] replays each assistant turn's stored chain of thought as
 * `reasoning_content`. DeepSeek thinking mode requires it for every earlier turn once a request
 * carries tools; other providers ignore the field, so they keep the default.
 */
internal fun convertToOpenAiMessages(
    messages: List<ChatMessage>,
    systemPrompt: String? = null,
    includeImages: Boolean = true,
    base64Files: Base64FileRegistry,
    forwardAssistantReasoning: Boolean = false,
): List<OpenAiMessage> {
    val apiMessages = mutableListOf<OpenAiMessage>()

    if (!systemPrompt.isNullOrBlank()) {
        apiMessages.add(
            OpenAiMessage(
                role = "system",
                content = listOf(OpenAiContentPart(type = "text", text = systemPrompt))
            )
        )
    }

    apiMessages.addAll(messages.flatMap { msg ->
        val entries = mutableListOf<OpenAiMessage>()

        // tool_ messages: assistant turn with tool_calls only
        // (tool results come from the following result_ messages)
        if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
            val toolSegs = msg.segments?.filter { it.type == "tool" }
            val thoughtContent = msg.segments?.filter { it.type == "thought" }
                ?.joinToString("\n") { it.content }
            if (!toolSegs.isNullOrEmpty()) {
                val toolCalls = toolSegs.map { seg ->
                    val tid = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}")
                    OpenAiRequestToolCall(
                        id = tid,
                        function = OpenAiRequestFunction(name = seg.toolName ?: "", arguments = seg.toolArgs ?: "{}")
                    )
                }
                entries.add(OpenAiMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = toolCalls,
                    reasoningContent = thoughtContent?.ifEmpty { null },
                    responseOutputItems = toolSegs.firstOrNull {
                        it.responseOutputItems.isNotEmpty()
                    }?.responseOutputItems,
                    responseOutputItemProvider = toolSegs.firstOrNull {
                        it.responseOutputItems.isNotEmpty()
                    }?.responseOutputItemProvider,
                    responseOutputItemModel = msg.modelName,
                ))
            } else if (msg.toolCall != null) {
                val tc = msg.toolCall!!
                val toolId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments)
                entries.add(OpenAiMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(OpenAiRequestToolCall(
                        id = toolId,
                        function = OpenAiRequestFunction(name = tc.toolName, arguments = tc.arguments)
                    )),
                    reasoningContent = thoughtContent?.ifEmpty { null },
                    responseOutputItems = tc.responseOutputItems.ifEmpty { null },
                    responseOutputItemProvider = tc.responseOutputItemProvider,
                    responseOutputItemModel = msg.modelName,
                ))
            }
            return@flatMap entries
        }

        // result_ messages carry the tool result(s)
        if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
            val toolSegs = msg.segments?.filter { it.type == "tool" }
            if (!toolSegs.isNullOrEmpty()) {
                for (seg in toolSegs) {
                    val toolId = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}")
                    entries.add(OpenAiMessage(
                        role = "tool",
                        content = listOf(OpenAiContentPart(type = "text", text = seg.toolResult ?: "")),
                        toolCallId = toolId
                    ))
                }
            } else if (msg.toolCall != null) {
                val tc = msg.toolCall!!
                val toolId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments)
                entries.add(OpenAiMessage(
                    role = "tool",
                    content = listOf(OpenAiContentPart(type = "text", text = tc.result)),
                    toolCallId = toolId
                ))
            }
            return@flatMap entries
        }

        // Normal message: text + images
        val parts = mutableListOf<OpenAiContentPart>()
        if (msg.text.isNotEmpty()) {
            parts.add(OpenAiContentPart(type = "text", text = msg.text))
        }

        if (includeImages && msg.participant == Participant.USER) {
            for (imagePath in msg.images) {
                val placeholder = base64Files.register(imagePath) ?: continue
                parts.add(
                    OpenAiContentPart(
                        type = "image_url",
                        imageUrl = OpenAiImageUrl(
                            url = "data:${imageMimeType(imagePath)};base64,$placeholder",
                        ),
                    ),
                )
            }
        }

        if (parts.isEmpty()) {
            parts.add(OpenAiContentPart(type = "text", text = "[Attachment unavailable]"))
        }

        entries.add(OpenAiMessage(
            role = if (msg.participant == Participant.USER) "user" else "assistant",
            content = parts,
            reasoningContent = if (forwardAssistantReasoning && msg.participant != Participant.USER) {
                msg.segments
                    ?.filter { it.type == "thought" }
                    ?.joinToString("\n") { it.content }
                    ?.takeIf { it.isNotBlank() }
            } else {
                null
            },
        ))
        entries
    })

    return sanitizeToolCallOrdering(apiMessages)
}

/**
 * Enforces the OpenAI tool-calling invariant some gateways validate hard (failing with
 * "Input[N] interrupts pending tool results"):
 *  1. every `tool` message must immediately answer a `tool_call_id` still pending from the
 *     most recent assistant `tool_calls` turn; and
 *  2. every pending call must be answered before any other role follows.
 *
 * Orphan results are demoted to user text (their content is preserved); unanswered pending
 * calls get a synthetic "[tool result not recorded]" answer so the transcript stays complete.
 * Well-formed sequences pass through byte-identical.
 */
internal fun sanitizeToolCallOrdering(
    messages: List<OpenAiMessage>,
): List<OpenAiMessage> {
    var pending = mutableSetOf<String>()
    val output = mutableListOf<OpenAiMessage>()
    for (message in messages) {
        val insertSynthetic = pending.isNotEmpty() && !(
            message.role == "tool" && message.toolCallId in pending
            )
        if (insertSynthetic) {
            for (callId in pending) {
                output.add(
                    OpenAiMessage(
                        role = "tool",
                        content = listOf(
                            OpenAiContentPart(type = "text", text = "[tool result not recorded]"),
                        ),
                        toolCallId = callId,
                    ),
                )
            }
            pending = mutableSetOf()
        }
        when {
            message.role == "assistant" && !message.toolCalls.isNullOrEmpty() -> {
                pending = message.toolCalls.mapNotNull { it.id?.takeIf(String::isNotBlank) }.toMutableSet()
                output.add(message)
            }
            message.role == "tool" && message.toolCallId in pending -> {
                pending.remove(message.toolCallId)
                output.add(message)
            }
            message.role == "tool" -> {
                // Orphan result: no pending call matches it. Demote to user text so the
                // gateway never sees an unanswered tool_call_id.
                val text = message.content?.filter { it.type == "text" }
                    ?.joinToString("\n") { it.text.orEmpty() }.orEmpty()
                output.add(
                    OpenAiMessage(
                        role = "user",
                        content = listOf(
                            OpenAiContentPart(
                                type = "text",
                                text = "[tool result for ${message.toolCallId.orEmpty()}] $text",
                            ),
                        ),
                    ),
                )
            }
            else -> output.add(message)
        }
    }
    if (pending.isNotEmpty()) {
        for (callId in pending) {
            output.add(
                OpenAiMessage(
                    role = "tool",
                    content = listOf(
                        OpenAiContentPart(type = "text", text = "[tool result not recorded]"),
                    ),
                    toolCallId = callId,
                ),
            )
        }
    }
    return output
}

fun limitContext(
    messages: List<ChatMessage>,
    contextTokenBudget: Int,
    includeAssistantReasoning: Boolean = false,
    costs: ContextCostModel = ContextCostModel.Default,
): List<ChatMessage> {
    if (messages.isEmpty()) return emptyList()
    val estimator = CostModelContextEstimator(costs)

    // A tool call and all of its results are one protocol unit. Truncating the flat list can leave
    // either an orphan result or an unanswered assistant tool call, so window complete units only.
    val units = protocolAtomicUnits(messages)

    val selected = ArrayDeque<List<ChatMessage>>()
    var estimatedTokens = 0L
    var hasNormalUserAnchor = false
    val tokenBudget = contextTokenBudget.coerceAtLeast(1).toLong()
    for (unit in units.asReversed()) {
        val unitCost = estimator.estimate(
            unit,
            includeAssistantReasoning = includeAssistantReasoning,
        ).toLong()
        if (
            selected.isNotEmpty() &&
            hasNormalUserAnchor &&
            estimatedTokens + unitCost > tokenBudget
        ) break
        selected.addFirst(unit)
        estimatedTokens = (estimatedTokens + unitCost).coerceAtMost(Int.MAX_VALUE.toLong())
        if (unit.any {
                it.participant == Participant.USER && !it.isToolProtocolMessage()
            }
        ) hasNormalUserAnchor = true
        // Always retain a legal user anchor and at least the newest complete protocol unit, even
        // when that single input already exceeds the configured estimate.
        if (hasNormalUserAnchor && estimatedTokens >= tokenBudget) break
    }

    val flattened = selected.flatten()
    // All supported chat protocols accept a user-led suffix. Starting at an assistant/tool turn
    // after truncation is ambiguous and is a common source of provider-side 400 responses.
    val firstNormalUser = flattened.indexOfFirst {
        it.participant == Participant.USER && !it.isToolProtocolMessage()
    }
    return if (firstNormalUser >= 0) flattened.drop(firstNormalUser) else emptyList()
}
