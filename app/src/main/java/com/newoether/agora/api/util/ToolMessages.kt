package com.newoether.agora.api.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.isContextCompact
import com.newoether.agora.util.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/** Expands protocol side chains with the same ordering/deduplication rule as ApiPathAssembler. */
fun expandSelectedToolProtocolRows(
    selectedPath: List<ChatMessage>,
    allMessages: List<ChatMessage>,
): List<ChatMessage> {
    if (selectedPath.isEmpty()) return emptyList()
    val protocolChildren = allMessages
        .asSequence()
        .filter(ChatMessage::isToolProtocolMessage)
        .groupBy(ChatMessage::parentId)
    val emitted = mutableSetOf<String>()
    val result = mutableListOf<ChatMessage>()

    fun emitProtocolSubtree(root: ChatMessage, runId: String?) {
        if (root.runId != runId || !emitted.add(root.id)) return
        result += root
        protocolChildren[root.id]
            .orEmpty()
            .asSequence()
            .filter { it.runId == runId }
            .sortedWith(compareBy<ChatMessage> { it.runSequence ?: Long.MAX_VALUE }
                .thenBy { it.timestamp }
                .thenBy { it.id })
            .forEach { emitProtocolSubtree(it, runId) }
    }

    selectedPath.forEach { message ->
        if (message.isToolProtocolMessage()) {
            if (emitted.add(message.id)) result += message
            return@forEach
        }
        protocolChildren[message.id]
            .orEmpty()
            .asSequence()
            .filter {
                it.runId == message.runId && it.id.startsWith(Constants.TOOL_MSG_PREFIX)
            }
            .sortedWith(compareBy<ChatMessage> { it.runSequence ?: Long.MAX_VALUE }
                .thenBy { it.timestamp }
                .thenBy { it.id })
            .forEach { emitProtocolSubtree(it, message.runId) }
        if (emitted.add(message.id)) result += message
    }
    return result
}

/**
 * Projects a durable terminal generation row as complete text on that same assistant turn.
 *
 * Room and UI state remain untouched. The API-only copy keeps any partial answer first, appends one
 * terminal annotation, and formats the selected persisted error detail through the same explicit
 * presentation function used by the gray error bar. Clearing UI/protocol-only payload from the
 * aggregate is safe because ApiPathAssembler emits durable tool protocol rows separately before
 * this visible model row.
 */
fun projectGenerationStatusesForApi(
    messages: List<ChatMessage>,
    generationErrorFormatter: (String) -> String,
): List<ChatMessage> {
    if (messages.none(ChatMessage::isGenerationStatusMessage)) return messages
    return messages.map { message ->
        if (message.isGenerationStatusMessage()) {
            message.asTerminalAssistantMessage(generationErrorFormatter)
        } else {
            message
        }
    }
}

private fun ChatMessage.isGenerationStatusMessage(): Boolean =
    !isToolProtocolMessage() && !isContextCompact() &&
        (participant == Participant.ERROR ||
            status == MessageStatus.ERROR ||
            status == MessageStatus.STOPPED)

private fun ChatMessage.asTerminalAssistantMessage(
    generationErrorFormatter: (String) -> String,
): ChatMessage {
    val isError = participant == Participant.ERROR || status == MessageStatus.ERROR
    val persistedError = segments
        ?.lastOrNull { it.type == "error" && it.content.isNotBlank() }
        ?.content
    val hasPersistedAnswer = segments
        ?.any { it.type == "answer" && it.content.isNotBlank() } == true
    val legacyErrorDetail = text.takeIf {
        isError && persistedError == null && !hasPersistedAnswer && it.isNotBlank()
    }
    val errorDetail = (persistedError ?: legacyErrorDetail)
        ?.let(generationErrorFormatter)
    val partialAnswer = when {
        !isError -> text
        legacyErrorDetail != null -> ""
        persistedError != null && text.trim() == persistedError.trim() -> ""
        else -> text
    }
    val terminalText = generationStatusEventText(
        isError = isError,
        errorDetail = errorDetail,
    )
    return copy(
        text = listOf(partialAnswer, terminalText)
            .filter(String::isNotBlank)
            .joinToString("\n\n"),
        images = emptyList(),
        thoughts = null,
        thoughtTitle = null,
        tokenCount = 0,
        tokenUsage = null,
        status = MessageStatus.SUCCESS,
        participant = Participant.MODEL,
        thoughtTimeMs = null,
        modelName = null,
        toolCall = null,
        segments = null,
        attachmentMeta = null,
        retryText = null,
    )
}

private fun generationStatusEventText(
    isError: Boolean,
    errorDetail: String?,
): String {
    // Deliberately machine-shaped rather than a sentence. The annotation sits at the end of an
    // assistant turn, so prose here reads as something the assistant wrote and gets imitated at the
    // end of later answers. A tag cannot be mistaken for the assistant's own wording.
    val reason = if (isError) "error" else "stopped"
    val detail = errorDetail?.takeIf(String::isNotBlank)
    return if (detail == null) {
        "<generation_interrupted reason=\"$reason\" />"
    } else {
        "<generation_interrupted reason=\"$reason\">\n$detail\n</generation_interrupted>"
    }
}

/**
 * Drops turns that would serialize to an empty/whitespace-only content block.
 *
 * Anthropic hard-rejects those with `400 messages: text content blocks must contain
 * non-whitespace text`, and other providers silently degrade on them. Such turns are
 * routine in practice: a generation stopped before its first token, an interrupted turn
 * that emitted only a newline, or two blank messages merged with "\n".
 *
 * A turn survives if it carries anything else of substance — images, or tool protocol
 * payload (tool_/result_ rows, whose content lives in segments/toolCall, not text).
 * Runs AFTER the merge so a blank fragment absorbed into a non-blank neighbor is kept.
 */
fun stripEmptyTurns(messages: List<ChatMessage>): List<ChatMessage> =
    messages.filter { msg ->
        msg.text.isNotBlank() ||
            msg.images.isNotEmpty() ||
            msg.isToolProtocolMessage() ||
            msg.toolCall != null ||
            msg.segments?.any { it.type == "tool" } == true
    }

/**
 * Builds an API-only view where assistant-generated images remain available for
 * visual follow-ups without being serialized as assistant-side image content.
 *
 * Chat completion schemas treat images as user inputs. Agora stores generated
 * images on model messages for display, so the latest generated image set is
 * projected onto the latest normal user message when images are being sent.
 */
fun projectAssistantImagesToLatestUserMessage(
    messages: List<ChatMessage>,
    includeImages: Boolean
): List<ChatMessage> {
    if (messages.isEmpty()) return messages

    val latestUserIndex = messages.indexOfLast { it.isNormalUserMessage() }
    val generatedImages = if (includeImages && latestUserIndex >= 0) {
        messages
            .asSequence()
            .take(latestUserIndex)
            .filter { it.isNormalAssistantMessage() && it.images.isNotEmpty() }
            .lastOrNull()
            ?.images
            ?.filter { it.isNotBlank() }
            .orEmpty()
    } else {
        emptyList()
    }

    var changed = false
    val projected = messages.mapIndexed { index, msg ->
        var next = msg
        if (msg.isNormalAssistantMessage() && msg.images.isNotEmpty()) {
            next = next.copy(images = emptyList())
            changed = true
        }
        if (index == latestUserIndex && generatedImages.isNotEmpty()) {
            next = next.copy(
                text = addGeneratedImageContextNote(next.text, generatedImages.size),
                images = (generatedImages + next.images).distinct()
            )
            changed = true
        }
        next
    }

    return if (changed) projected else messages
}

/**
 * Adds an API-only normal user turn after each complete tool-result batch that returned images.
 *
 * Tool-result blocks themselves remain text-only because provider wire formats disagree about
 * multimodal tool results. A following ordinary user turn is accepted by all supported
 * multimodal providers and preserves the required tool-call/result adjacency. The synthetic row
 * is never persisted or rendered.
 */
fun projectToolResultImagesToUserMessage(
    messages: List<ChatMessage>,
    includeImages: Boolean,
): List<ChatMessage> {
    if (messages.none { it.id.startsWith(Constants.RESULT_MSG_PREFIX) && it.images.isNotEmpty() }) {
        return messages
    }

    val projected = ArrayList<ChatMessage>(messages.size + 2)
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        projected += message
        if (!message.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
            index++
            continue
        }

        val batch = mutableListOf(message)
        var next = index + 1
        while (
            next < messages.size &&
            messages[next].id.startsWith(Constants.RESULT_MSG_PREFIX)
        ) {
            projected += messages[next]
            batch += messages[next]
            next++
        }
        val images = batch.flatMap(ChatMessage::images).filter(String::isNotBlank).distinct()
        if (images.isNotEmpty()) {
            val first = batch.first()
            val seed = batch.joinToString(":") { it.id }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(seed.toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }
            // Carried like regular image transcriptions: a text block on this API-only row,
            // read from the toolTranscription persisted on the batch's tool segments (the
            // round-boundary path rebuild excludes the model message, so the description must
            // travel with the result rows).
            val descriptionBlock = transcriptionDescriptionsForBatch(batch)
                ?.let { "\n\n--- Image Transcription: view_image ---\n$it" }
                .orEmpty()
            projected += ChatMessage(
                // The id must not start with tool_ / result_ / compact_: provider serializers
                // branch on those prefixes and would silently drop this API-only row (its text
                // and images would never reach the model). image_context_ is reserved-free.
                id = "image_context_$digest",
                parentId = batch.last().id,
                text = if (includeImages) {
                    "[Tool visual result: inspect the attached image${if (images.size == 1) "" else "s"} before continuing.]$descriptionBlock"
                } else if (descriptionBlock.isNotBlank()) {
                    "[Tool visual result: the image description follows.]$descriptionBlock"
                } else {
                    "[Tool visual result unavailable: the current model does not support image input.]"
                },
                images = if (includeImages) images else emptyList(),
                participant = Participant.USER,
                status = MessageStatus.SUCCESS,
                timestamp = batch.last().timestamp,
                runId = first.runId,
                runSequence = first.runSequence,
            )
        }
        index = next
    }
    return projected
}

private fun ChatMessage.isNormalUserMessage(): Boolean =
    participant == Participant.USER && !isToolProtocolMessage()

/** Tool-image transcription travels on the result rows' tool segments (toolTranscription). */
private fun transcriptionDescriptionsForBatch(
    batch: List<ChatMessage>,
): String? = batch
    .flatMap { it.segments.orEmpty() }
    .mapNotNull { it.toolTranscription?.takeIf(String::isNotBlank) }
    .distinct()
    .joinToString("\n\n")
    .takeIf(String::isNotBlank)

private fun ChatMessage.isNormalAssistantMessage(): Boolean =
    participant == Participant.MODEL && !isToolProtocolMessage()

internal fun ChatMessage.isToolProtocolMessage(): Boolean =
    id.startsWith(Constants.TOOL_MSG_PREFIX) || id.startsWith(Constants.RESULT_MSG_PREFIX)

private fun addGeneratedImageContextNote(text: String, imageCount: Int): String {
    val note = if (imageCount == 1) {
        "[Visual context: the first attached image was generated by the assistant earlier in this conversation.]"
    } else {
        "[Visual context: the first $imageCount attached images were generated by the assistant earlier in this conversation.]"
    }
    return if (text.isBlank()) note else "$note\n\n$text"
}

/**
 * Merges consecutive non-tool messages that share the same participant.
 * This handles orphans left by message deletion (e.g. two user messages
 * in a row after removing an assistant reply) and keeps the message list
 * compliant with providers that require strict role alternation.
 *
 * Tool messages (tool_/result_) pass through unchanged — they are validated
 * separately by [validateToolMessages].
 */
fun mergeConsecutiveSameRole(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.isEmpty()) return messages
    val result = mutableListOf<ChatMessage>()
    var i = 0
    while (i < messages.size) {
        val current = messages[i]
        val isTool = current.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            current.id.startsWith(Constants.RESULT_MSG_PREFIX)
        if (isTool) {
            result.add(current)
            i++
            continue
        }
        // Find consecutive messages with the same participant
        var j = i + 1
        while (j < messages.size) {
            val next = messages[j]
            val nextIsTool = next.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
                next.id.startsWith(Constants.RESULT_MSG_PREFIX)
            if (nextIsTool || next.participant != current.participant) break
            j++
        }
        if (j == i + 1) {
            // No merge needed
            result.add(current)
        } else {
            // Merge messages[i..j-1] into one
            val merged = messages.subList(i, j)
            val mergedText = merged.joinToString("\n\n") { it.text }
            val mergedImages = merged.flatMap { it.images }
            result.add(current.copy(text = mergedText, images = mergedImages))
        }
        i = j
    }
    return result
}

/**
 * Validates and canonicalizes complete tool_ / result_ protocol rounds.
 *
 * Rules enforced:
 *  - Every tool_ message must be immediately followed by one result per emitted tool call
 *  - Every result_ message must be immediately preceded by a tool_ message
 *  - Each result_ segment's toolCallId matches the corresponding tool_use segment
 *
 * Missing legacy IDs may be synthesized only when every result is unambiguously paired by
 * cardinality and position. Explicit conflicting IDs, duplicate IDs, malformed arguments, and
 * extra/missing results are never "repaired" by relabeling or truncation: the whole round is
 * replayed as ordinary context instead, so an invalid protocol sequence cannot reach a provider
 * and a result can never be attached to the wrong call.
 */
fun validateToolMessages(messages: List<ChatMessage>): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    val seenToolCallIds = mutableSetOf<String>()
    var i = 0
    while (i < messages.size) {
        val msg = messages[i]
        when {
            msg.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                val resultMessages = mutableListOf<ChatMessage>()
                var j = i + 1
                while (j < messages.size && messages[j].id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                    resultMessages.add(messages[j])
                    j++
                }
                val normalizedCalls = normalizeToolCalls(msg, seenToolCallIds)
                val normalizedResults = normalizedCalls?.let { calls ->
                    normalizeToolResults(calls, resultMessages)
                }
                if (normalizedCalls != null && normalizedResults != null) {
                    result.add(normalizedCalls.message)
                    result.addAll(normalizedResults)
                    seenToolCallIds.addAll(normalizedCalls.callIds)
                    i = j
                } else {
                    result += toolRoundAsPlainContext(
                        toolMessage = msg,
                        resultMessages = resultMessages,
                        reason = "the stored tool round was incomplete or damaged",
                    )
                    i = j
                }
            }
            msg.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
                result += toolRoundAsPlainContext(
                    toolMessage = null,
                    resultMessages = listOf(msg),
                    reason = "the stored tool result had no matching tool call",
                )
                i++
            }
            else -> {
                result.add(msg)
                i++
            }
        }
    }
    return result
}

private data class NormalizedToolCalls(
    val message: ChatMessage,
    val segments: List<MessageSegment>,
    val callIds: List<String>,
)

private val toolJson = Json { ignoreUnknownKeys = true }
private val safeToolCallId = safeWireToolCallId

private fun normalizeToolCalls(
    toolMsg: ChatMessage,
    alreadySeenIds: Set<String>,
): NormalizedToolCalls? {
    val sourceSegments = toolMsg.segments
        ?.filter { it.type == "tool" }
        .orEmpty()
        .ifEmpty {
            val toolCall = toolMsg.toolCall ?: return null
            listOf(
                MessageSegment(
                    type = "tool",
                    toolName = toolCall.toolName,
                    toolArgs = toolCall.arguments,
                    toolResult = toolCall.result,
                    toolCallId = toolCall.toolCallId,
                    signature = toolCall.signature,
                )
            )
        }
    if (sourceSegments.isEmpty()) return null

    val explicitIds = sourceSegments.map { it.toolCallId?.takeIf(String::isNotBlank) }
    if (
        explicitIds.filterNotNull().any { !it.matches(safeToolCallId) } ||
        explicitIds.filterNotNull().distinct().size != explicitIds.filterNotNull().size ||
        explicitIds.filterNotNull().any(alreadySeenIds::contains)
    ) {
        return null
    }

    val reserved = alreadySeenIds.toMutableSet()
    val normalized = sourceSegments.mapIndexed { index, segment ->
        val name = segment.toolName
            ?.takeIf { it.matches(safeWireToolName) }
            ?: return null
        val arguments = normalizeArgumentsOrNull(segment.toolArgs) ?: return null
        val callId = segment.toolCallId?.takeIf(String::isNotBlank)
            ?: buildToolCallId("$name:${toolMsg.id}:$index", arguments)
        if (!reserved.add(callId)) return null
        segment.copy(
            toolName = name,
            toolArgs = arguments,
            toolCallId = callId,
            // Results belong only to the following result_ row in the API representation.
            toolResult = null,
        )
    }
    val normalizedToolCall = toolMsg.toolCall?.takeIf { normalized.size == 1 }?.copy(
        toolName = normalized.single().toolName.orEmpty(),
        arguments = normalized.single().toolArgs.orEmpty(),
        toolCallId = normalized.single().toolCallId,
    )
    var normalizedIndex = 0
    val rebuiltSegments = toolMsg.segments
        ?.map { segment ->
            if (segment.type == "tool") normalized[normalizedIndex++] else segment
        }
        ?: normalized
    return NormalizedToolCalls(
        message = toolMsg.copy(
            // Signed thought blocks are protocol state for Anthropic/Gemini. Replace only tool
            // segments and preserve every non-tool segment in its original order.
            segments = rebuiltSegments,
            toolCall = normalizedToolCall,
        ),
        segments = normalized,
        callIds = normalized.map { checkNotNull(it.toolCallId) },
    )
}

/**
 * Replaces provider-specific tool rounds that cannot safely be replayed with ordinary user
 * context. This is the last-resort compatibility path for opaque thought signatures: the model
 * still sees what ran and what it returned, while no foreign signature reaches the target API.
 */
fun adaptToolRoundsForProvider(
    messages: List<ChatMessage>,
    providerName: String,
    isCompatible: (ChatMessage) -> Boolean,
): List<ChatMessage> {
    if (messages.none(ChatMessage::isToolProtocolMessage)) return messages
    val result = mutableListOf<ChatMessage>()
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        if (!message.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
            if (!message.id.startsWith(Constants.RESULT_MSG_PREFIX)) result += message
            index++
            continue
        }

        val resultMessages = mutableListOf<ChatMessage>()
        var end = index + 1
        while (
            end < messages.size &&
            messages[end].id.startsWith(Constants.RESULT_MSG_PREFIX)
        ) {
            resultMessages += messages[end++]
        }
        if (isCompatible(message)) {
            result += message
            result += resultMessages
        } else {
            result += toolRoundAsPlainContext(
                toolMessage = message,
                resultMessages = resultMessages,
                reason = "its opaque protocol metadata is not compatible with $providerName",
            )
        }
        index = end
    }
    return stripEmptyTurns(mergeConsecutiveSameRole(result))
}

/**
 * Normalizes every result payload against the assistant's call IDs by position. A synthetic result
 * row normally carries one payload, but legacy imports can carry several segments in one row; both
 * forms are counted correctly. Returns null unless every tool call has exactly one usable result.
 * Extra result rows/segments reject the whole round rather than being dropped.
 */
private fun normalizeToolResults(
    calls: NormalizedToolCalls,
    resultMessages: List<ChatMessage>
): List<ChatMessage>? {
    data class ResultRef(
        val message: ChatMessage,
        val segment: MessageSegment?,
        val explicitId: String?,
    )

    val resultRefs = resultMessages.flatMap { message ->
        val segments = message.segments
            ?.filter { it.type == "tool" }
            .orEmpty()
        if (segments.isNotEmpty()) {
            segments.map { segment ->
                ResultRef(message, segment, segment.toolCallId?.takeIf(String::isNotBlank))
            }
        } else {
            listOf(
                ResultRef(
                    message,
                    null,
                    message.toolCall?.toolCallId?.takeIf(String::isNotBlank),
                )
            )
        }
    }
    if (resultRefs.size != calls.segments.size) return null

    val explicitResultIds = resultRefs.map(ResultRef::explicitId)
    val hasExplicitResultIds = explicitResultIds.any { it != null }
    if (
        explicitResultIds.filterNotNull().any { !it.matches(safeToolCallId) } ||
        (hasExplicitResultIds && explicitResultIds.any { it == null }) ||
        explicitResultIds.filterNotNull().distinct().size != explicitResultIds.filterNotNull().size ||
        (hasExplicitResultIds && explicitResultIds.filterNotNull().toSet() != calls.callIds.toSet())
    ) {
        return null
    }

    val callById = calls.segments.associateBy { checkNotNull(it.toolCallId) }
    val normalized = mutableListOf<ChatMessage>()
    var callIndex = 0
    for (resultMsg in resultMessages) {
        val toolSegments = resultMsg.segments
            ?.filter { it.type == "tool" }
            .orEmpty()
        if (toolSegments.isNotEmpty()) {
            val kept = toolSegments.map { segment ->
                val call = if (hasExplicitResultIds) {
                    segment.toolCallId?.let(callById::get) ?: return null
                } else {
                    calls.segments.getOrNull(callIndex) ?: return null
                }
                callIndex++
                segment.copy(
                    toolName = call.toolName,
                    toolArgs = call.toolArgs,
                    toolCallId = call.toolCallId,
                    toolResult = nonEmptyToolResult(segment.toolResult ?: resultMsg.text),
                    signature = call.signature,
                    signatureProvider = call.signatureProvider,
                )
            }
            normalized += resultMsg.copy(
                segments = kept,
                toolCall = resultMsg.toolCall?.takeIf { kept.size == 1 }?.copy(
                    toolCallId = kept.single().toolCallId
                ),
            )
        } else {
            val toolCall = resultMsg.toolCall
            val call = if (hasExplicitResultIds) {
                toolCall?.toolCallId?.let(callById::get) ?: return null
            } else {
                calls.segments.getOrNull(callIndex) ?: return null
            }
            callIndex++
            val result = nonEmptyToolResult(toolCall?.result ?: resultMsg.text)
            normalized += resultMsg.copy(
                segments = listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = call.toolName,
                        toolArgs = call.toolArgs,
                        toolResult = result,
                        toolCallId = call.toolCallId,
                        signature = call.signature,
                        signatureProvider = call.signatureProvider,
                    )
                ),
                toolCall = toolCall?.copy(
                    toolName = call.toolName.orEmpty(),
                    arguments = call.toolArgs.orEmpty(),
                    result = result,
                    toolCallId = call.toolCallId,
                ),
            )
        }
    }
    return normalized.takeIf { callIndex == calls.segments.size }
}

private fun normalizeArgumentsOrNull(arguments: String?): String? {
    val parsed = runCatching {
        toolJson.parseToJsonElement(arguments?.takeIf(String::isNotBlank) ?: "{}")
    }.getOrNull()
    return (parsed as? JsonObject)?.toString()
}

private fun normalizeArguments(arguments: String?): String =
    normalizeArgumentsOrNull(arguments) ?: arguments.orEmpty().ifBlank { "{}" }

private fun nonEmptyToolResult(result: String): String =
    result.takeIf(String::isNotBlank) ?: "[Tool returned no textual output]"

private fun toolRoundAsPlainContext(
    toolMessage: ChatMessage?,
    resultMessages: List<ChatMessage>,
    reason: String,
): ChatMessage {
    val calls = toolMessage
        ?.segments
        ?.filter { it.type == "tool" }
        .orEmpty()
        .ifEmpty {
            toolMessage?.toolCall?.let { call ->
                listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = call.toolName,
                        toolArgs = call.arguments,
                        toolResult = call.result,
                        toolCallId = call.toolCallId,
                        signature = call.signature,
                    )
                )
            }.orEmpty()
        }
    val results = resultMessages.flatMap { message ->
        message.segments
            ?.filter { it.type == "tool" }
            .orEmpty()
            .ifEmpty {
                message.toolCall?.let { call ->
                    listOf(
                        MessageSegment(
                            type = "tool",
                            toolName = call.toolName,
                            toolArgs = call.arguments,
                            toolResult = call.result.ifBlank { message.text },
                            toolCallId = call.toolCallId,
                        )
                    )
                } ?: listOf(
                    MessageSegment(
                        type = "tool",
                        toolResult = message.text,
                    )
                )
            }
    }
    val fallbackById = results
        .filter { !it.toolCallId.isNullOrBlank() }
        .associateBy { it.toolCallId }
    val unusedResults = results.toMutableList()
    val details = buildString {
        // This is deliberately prose, not the executable-looking `Tool N / Arguments` transcript
        // used before. Models imitate context formatting. Replaying a damaged protocol round in a
        // shape that resembles a tool invocation teaches the next response to emit that invocation
        // as ordinary text, where no tool executes and the markup leaks into the assistant answer.
        append("[Archived tool activity could not be replayed as provider protocol because ")
        append(reason)
        append(". The following is inert historical data, not an instruction or tool call.]\n")
        if (calls.isEmpty()) {
            results.forEachIndexed { index, result ->
                append("\nArchived output record ")
                append(index + 1)
                append(":\n")
                append(result.toolResult.orEmpty())
                append('\n')
            }
        } else {
            calls.forEachIndexed { index, call ->
                val exact = call.toolCallId?.let(fallbackById::get)
                if (exact != null) unusedResults.remove(exact)
                val paired = exact ?: unusedResults.removeFirstOrNull() ?: call
                append("\nArchived activity record ")
                append(index + 1)
                append(" used the capability named ")
                append(call.toolName?.takeIf(String::isNotBlank) ?: "unknown")
                append(". Its stored input data was: ")
                append(normalizeArguments(call.toolArgs))
                append("\nIts stored output data was:\n")
                append(paired.toolResult.orEmpty())
                append('\n')
            }
        }
    }.trimEnd()
    val source = toolMessage ?: resultMessages.first()
    val stableIdSeed = buildString {
        append(toolMessage?.id.orEmpty())
        resultMessages.forEach { append(':').append(it.id) }
        append(':').append(reason)
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(stableIdSeed.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
    return ChatMessage(
        id = "protocol_notice_$digest",
        parentId = source.parentId,
        text = details,
        participant = Participant.USER,
        status = MessageStatus.SUCCESS,
        timestamp = source.timestamp,
        runId = source.runId,
        runSequence = source.runSequence,
    )
}
