package com.newoether.agora.api.openai

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.OpenAiResponseOutputItem
import com.newoether.agora.api.OpenAiResponseStreamEvent
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.toTokenUsage
import com.newoether.agora.api.util.ToolArgumentAccumulator
import com.newoether.agora.api.util.safeWireToolCallId
import com.newoether.agora.api.util.safeWireToolName
import com.newoether.agora.model.CitationAnchor
import com.newoether.agora.model.CitationPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private val RESPONSES_THOUGHT_TITLE_BOLD = Regex("\\*\\*(.*?)\\*\\*")
private val RESPONSES_THOUGHT_TITLE_HEADING = Regex("(?m)^#+\\s*(.*)$")

private fun extractResponsesThoughtTitle(content: String): String? =
    RESPONSES_THOUGHT_TITLE_BOLD.find(content)?.groupValues?.get(1)
        ?: RESPONSES_THOUGHT_TITLE_HEADING.find(content)?.groupValues?.get(1)

private fun JsonElement?.effectiveResponseMetadata(preserveEmptyArray: Boolean = false): JsonElement? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> takeUnless { isString && content.isBlank() }
    is JsonArray -> takeUnless { isEmpty() && !preserveEmptyArray }
    is JsonObject -> takeUnless(JsonObject::isEmpty)
}

/**
 * Recovers tool calls that an OpenAI-compatible server emitted as **content text** rather than as
 * structured `delta.tool_calls` (issue #33, path B). llama.cpp and other self-hosted servers
 * frequently finish with `finish_reason == "stop"` while placing the tool call inside the message
 * `content` — the model's chat template renders it as a tagged ``{json}`` block. The structured
 * path in [BaseOpenAiProvider] only fires on `finish_reason == "tool_calls"`, so without this
 * fallback such servers never enter the tool-call phase (the JSON just shows up as answer text).
 * This parser brings them to parity with Ollama, which reads the structured field.
 *
 * Recognized forms:
 *  - One or more tagged blocks anywhere in the content (the standard form emitted by
 *    Hermes / Qwen / llama.cpp tool-aware templates). The inner JSON may use
 *    `{"name":...,"arguments":...}` or `{"name":...,"parameters":...}`, or nest them under
 *    `"function"`.
 *  - As a last resort, the *entire* trimmed content being a single JSON object or array of the
 *    same tool-call shape (some templates emit the JSON with no surrounding tags). Only attempted
 *    when the whole content is JSON, so prose answers are never misread as tool calls.
 *
 * The inner `arguments`/`parameters` value is preserved verbatim as a JSON string for the
 * downstream tool executor, matching how structured tool calls carry arguments.
 */
internal object ToolCallTextParser {

    data class ParsedCall(
        val id: String?,
        val name: String,
        val arguments: String,
    )

    // Split so the bare tag literals never appear as a contiguous substring in source tooling.
    private const val OPEN_TAG = "<tool_" + "call>"
    private const val CLOSE_TAG = "</tool_" + "call>"
    private val XML_INVOKE_BLOCK = Regex(
        """<(?:(?:antml):)?invoke\s+name\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</(?:(?:antml):)?invoke\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val XML_PARAMETER = Regex(
        """<(?:(?:antml):)?parameter\s+name\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</(?:(?:antml):)?parameter\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val XML_ID_ATTRIBUTE_NAME = Regex(
        """\b(?:id|call_id)\s*=""",
        RegexOption.IGNORE_CASE,
    )
    private val XML_ID_ATTRIBUTE = Regex(
        """\b(?:id|call_id)\s*=\s*(["'])([^"']+)\1""",
        RegexOption.IGNORE_CASE,
    )

    /** Extract tool calls from [content]; empty if none are recognized. */
    fun parse(content: String): List<ParsedCall> {
        val results = mutableListOf<ParsedCall>()
        var idx = 0
        var sawTaggedBlock = false
        var malformedTaggedBlock = false
        while (true) {
            val start = content.indexOf(OPEN_TAG, idx)
            if (start < 0) break
            sawTaggedBlock = true
            val innerStart = start + OPEN_TAG.length
            val end = content.indexOf(CLOSE_TAG, innerStart)
            if (end < 0) {
                malformedTaggedBlock = true
                break
            }
            val inner = content.substring(innerStart, end).trim()
            val parsed = parseCallJson(inner)
            if (parsed == null) malformedTaggedBlock = true else results += parsed
            idx = end + CLOSE_TAG.length
        }
        if (sawTaggedBlock) return if (malformedTaggedBlock) emptyList() else results

        // Some Anthropic relays and prompt-based tool shims serialize a native tool_use block as
        // XML in ordinary assistant text. Recover both the bare and namespaced forms:
        //   <invoke name="tool"><parameter name="arg">value</parameter></invoke>
        //   <antml:invoke ...><antml:parameter ...>...</antml:parameter></antml:invoke>
        // Without this branch the model believes it called a tool while Agora renders the markup
        // as answer text and executes nothing.
        XML_INVOKE_BLOCK.findAll(content).forEach { match ->
            parseXmlInvoke(match)?.let(results::add)
        }
        if (results.isNotEmpty()) return results

        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        // Only treat the whole content as a tool call when it is pure JSON — never parse tool
        // calls out of prose that merely happens to contain a JSON fragment.
        parseCallJson(trimmed)?.let { return listOf(it) }
        if (trimmed.startsWith("[")) {
            val array = try { Json.parseToJsonElement(trimmed).jsonArray } catch (_: Exception) { return emptyList() }
            // A multi-call payload is atomic. Never execute only the valid members of a damaged
            // array while silently discarding the rest.
            return array.map { element ->
                val obj = element as? JsonObject ?: return emptyList()
                parseCallJson(obj.toString()) ?: return emptyList()
            }
        }
        return emptyList()
    }

    private fun parseXmlInvoke(match: MatchResult): ParsedCall? {
        val name = decodeXml(match.groupValues[1]).trim()
            .takeIf { it.matches(safeWireToolName) }
            ?: return null
        val openingTag = match.value.substringBefore('>')
        val idAttributePresent = XML_ID_ATTRIBUTE_NAME.containsMatchIn(openingTag)
        val id = XML_ID_ATTRIBUTE.find(openingTag)?.groupValues?.getOrNull(2)
            ?.let(::decodeXml)
            ?.trim()
        if (idAttributePresent && (id == null || !id.matches(safeWireToolCallId))) return null
        val body = match.groupValues[2]
        val entries = linkedMapOf<String, JsonElement>()
        XML_PARAMETER.findAll(body).forEach { parameter ->
            val key = decodeXml(parameter.groupValues[1]).trim()
            if (key.isNotBlank()) {
                val value = decodeXml(parameter.groupValues[2]).trim()
                // Tool parameters are strings in this XML protocol. Preserve their text as a JSON
                // string rather than guessing numbers/booleans and changing the declared schema.
                entries[key] = JsonPrimitive(value)
            }
        }
        return ParsedCall(id, name, JsonObject(entries).toString())
    }

    private fun decodeXml(value: String): String = value
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)

    private fun parseCallJson(jsonStr: String): ParsedCall? {
        val obj = try { Json.parseToJsonElement(jsonStr).jsonObject } catch (_: Exception) { return null }
        val function = obj["function"] as? JsonObject
        val name = stringField(obj, "name")
            ?: function?.let { stringField(it, "name") }
            ?: return null
        if (!name.matches(safeWireToolName)) return null
        val idFields = listOf("id", "call_id").filter(obj::containsKey)
        val ids = idFields.map { key -> stringField(obj, key) ?: return null }
        val distinctIds = ids.distinct()
        if (distinctIds.size > 1) return null
        val id = distinctIds.singleOrNull()
        if (id != null && !id.matches(safeWireToolCallId)) return null
        val args = obj["arguments"] ?: obj["parameters"]
            ?: function?.get("arguments") ?: function?.get("parameters")
        val arguments = args?.let { normalizeArguments(it) ?: return null } ?: "{}"
        return ParsedCall(id, name, arguments)
    }

    private fun stringField(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

    /** Tool arguments must form one complete JSON object before an executable event is emitted. */
    private fun normalizeArguments(element: JsonElement): String? {
        val raw = if (element is JsonPrimitive && element.isString) element.content else element.toString()
        return (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject)?.toString()
    }

}

internal class OpenAiResponsesEventRouter(
    private val json: Json,
) {
    private data class FunctionCall(
        val outputIndex: Int,
        val streamKey: String,
        var itemId: String? = null,
        var callId: String? = null,
        var name: String? = null,
        val arguments: ToolArgumentAccumulator = ToolArgumentAccumulator(),
        var completed: Boolean = false,
    )

    private data class OutputTextPartKey(
        val itemId: String?,
        val outputIndex: Int?,
        val contentIndex: Int?,
    )

    private data class OutputTextPart(
        val key: OutputTextPartKey,
        val text: StringBuilder,
        val globalStart: Int,
        var globalEnd: Int,
        var contiguous: Boolean = true,
    )

    private val callsByOutputIndex = linkedMapOf<Int, FunctionCall>()
    private val callsByItemId = mutableMapOf<String, FunctionCall>()
    private val responseItemsByOutputIndex = linkedMapOf<Int, JsonObject>()
    private val openHostedOutputIndexes = mutableSetOf<Int>()
    private val completedCallsByOutputIndex = mutableMapOf<Int, StreamEvent.ToolCallRequest>()
    private val emittedCallIds = mutableSetOf<String>()
    private val emittedCitationKeys = mutableSetOf<String>()
    private val outputTextParts = linkedMapOf<OutputTextPartKey, OutputTextPart>()
    private val answerText = StringBuilder()
    private var lastSummaryOutputIndex: Int? = null
    private var lastSummaryIndex: Int? = null
    private var lastSequenceNumber: Int? = null
    var sawTerminalMarker: Boolean = false
        private set
    var stopReason: String? = null
        private set
    var streamError: GenerationError? = null
        private set
    var reportedError: Boolean = false
        private set

    val toolCallInFlight: Boolean
        get() = callsByOutputIndex.values.any { !it.completed }

    fun route(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        if (streamError != null || reportedError) return emptyList()
        if (sawTerminalMarker) return fail(event.type, "event received after terminal response")
        validateSequence(event)?.let { return fail(event.type, it) }
        return when (event.type) {
            "response.created", "response.in_progress",
            "response.output_text.done", "response.reasoning_text.done",
            "response.reasoning_summary_text.done", "response.content_part.added",
            "response.content_part.done" -> emptyList()
            "response.output_text.delta" -> event.delta?.takeIf(String::isNotEmpty)
                ?.let { delta ->
                    appendOutputText(event, delta)
                    listOf(StreamEvent.TextChunk(delta))
                }.orEmpty()
            "response.output_text.annotation.added" -> routeCitation(event)
            "response.reasoning_text.delta" ->
                event.delta?.takeIf(String::isNotBlank)
                    ?.let { listOf(StreamEvent.ThoughtChunk(it)) }.orEmpty()
            "response.reasoning_summary_text.delta" -> routeReasoningSummary(event)
            "response.output_item.added" -> addOutputItem(event)
            "response.function_call_arguments.delta" -> updateArguments(event)
            "response.function_call_arguments.done" -> completeArguments(event)
            "response.output_item.done" -> completeOutputItem(event)
            "response.completed" -> completeResponse(event)
            "response.failed" -> failResponse(event, "failed")
            "response.incomplete" -> failResponse(event, "incomplete")
            "error" -> failApi(event.error, "Provider reported a Responses stream error")
            else -> emptyList()
        }
    }

    private fun routeReasoningSummary(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        val delta = event.delta?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val outputIndex = event.outputIndex
        val summaryIndex = event.summaryIndex
        val startsNewPart =
            outputIndex != null &&
                summaryIndex != null &&
                lastSummaryOutputIndex != null &&
                lastSummaryIndex != null &&
                (outputIndex != lastSummaryOutputIndex || summaryIndex != lastSummaryIndex)
        if (outputIndex != null && summaryIndex != null) {
            lastSummaryOutputIndex = outputIndex
            lastSummaryIndex = summaryIndex
        }
        return listOf(
            StreamEvent.ThoughtChunk(
                thought = if (startsNewPart) "\n\n$delta" else delta,
                title = extractResponsesThoughtTitle(delta),
            ),
        )
    }

    private fun routeCitation(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        val annotation = event.annotation ?: return emptyList()
        val anchors = citationAnchors(event, annotation.startIndex, annotation.endIndex)
        val citation = when (annotation.type) {
            "url_citation" -> CitationPolicy.create(
                provider = "openai",
                kind = "url",
                title = annotation.title,
                url = annotation.url,
                anchors = anchors,
                answerText = answerText.toString(),
            )
            "file_citation", "container_file_citation" -> CitationPolicy.create(
                provider = "openai",
                kind = "file",
                title = annotation.title ?: annotation.filename,
                fileName = annotation.filename,
                providerSourceId = annotation.fileId ?: annotation.containerId,
                anchors = anchors,
                answerText = answerText.toString(),
            )
            else -> null
        } ?: return emptyList()
        val eventKey = buildString {
            append(citation.sourceId)
            citation.anchors.forEach { anchor ->
                append(':').append(anchor.startIndex).append(':').append(anchor.endIndex)
            }
        }
        if (!emittedCitationKeys.add(eventKey)) return emptyList()
        return listOf(StreamEvent.CitationUpdate(citation))
    }

    private fun outputTextPartKey(event: OpenAiResponseStreamEvent): OutputTextPartKey? =
        OutputTextPartKey(
            itemId = event.itemId?.takeIf(String::isNotBlank),
            outputIndex = event.outputIndex,
            contentIndex = event.contentIndex,
        ).takeUnless { key ->
            key.itemId == null && key.outputIndex == null && key.contentIndex == null
        }

    private fun findOutputTextPart(event: OpenAiResponseStreamEvent): OutputTextPart? {
        val key = outputTextPartKey(event) ?: return null
        outputTextParts[key]?.let { return it }
        return outputTextParts.values.filter { part ->
            (key.itemId == null || part.key.itemId == null || key.itemId == part.key.itemId) &&
                (
                    key.outputIndex == null ||
                        part.key.outputIndex == null ||
                        key.outputIndex == part.key.outputIndex
                    ) &&
                (
                    key.contentIndex == null ||
                        part.key.contentIndex == null ||
                        key.contentIndex == part.key.contentIndex
                    )
        }.singleOrNull()
    }

    private fun appendOutputText(event: OpenAiResponseStreamEvent, delta: String) {
        val key = outputTextPartKey(event)
        if (key == null) {
            answerText.append(delta)
            return
        }
        val part = findOutputTextPart(event) ?: OutputTextPart(
            key = key,
            text = StringBuilder(),
            globalStart = answerText.length,
            globalEnd = answerText.length,
        ).also { outputTextParts[key] = it }
        if (part.globalEnd != answerText.length) part.contiguous = false
        part.text.append(delta)
        answerText.append(delta)
        part.globalEnd = answerText.length
    }

    private fun citationAnchors(
        event: OpenAiResponseStreamEvent,
        startIndex: Int?,
        endIndex: Int?,
    ): List<CitationAnchor> {
        val start = startIndex ?: return emptyList()
        val end = endIndex ?: return emptyList()
        val key = outputTextPartKey(event)
        val part = when {
            key != null -> findOutputTextPart(event)
            outputTextParts.isEmpty() -> null
            else -> outputTextParts.values.singleOrNull() ?: return emptyList()
        }
        if (key != null && part == null) return emptyList()
        if (part?.contiguous == false) return emptyList()
        val scopedText = part?.text ?: answerText
        if (start < 0 || end <= start || end > scopedText.length) return emptyList()
        val globalStart = (part?.globalStart ?: 0) + start
        val globalEnd = (part?.globalStart ?: 0) + end
        if (globalEnd > answerText.length) return emptyList()
        val citedText = scopedText.substring(start, end)
        if (answerText.substring(globalStart, globalEnd) != citedText) return emptyList()
        return listOf(
            CitationAnchor(
                startIndex = globalStart,
                endIndex = globalEnd,
                citedText = citedText,
            ),
        )
    }

    private fun addOutputItem(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        val rawItem = event.item ?: return fail(event.type, "missing output item")
        val item = try {
            rawItem.toOutputItem()
        } catch (error: Exception) {
            return fail(event.type, error.localizedMessage ?: "invalid output item")
        }
        val index = event.outputIndex ?: return fail(event.type, "missing output_index")
        if (responseItemsByOutputIndex.containsKey(index)) {
            return fail(event.type, "duplicate output_index")
        }
        val itemId = item.id?.takeIf(String::isNotBlank)
        val callId = item.callId?.takeIf(String::isNotBlank)
        val name = item.name?.takeIf(String::isNotBlank)
        responseItemsByOutputIndex[index] = rawItem
        if (item.type == "web_search_call") {
            openHostedOutputIndexes += index
            return listOf(
                rawItem.toHostedWebSearchUpdate(
                    streamKey = itemId ?: "response_hosted_$index",
                    completed = false,
                ),
            )
        }
        if (item.type != "function_call") return emptyList()
        val call = FunctionCall(
            outputIndex = index,
            streamKey = itemId ?: "response_tool_$index",
            itemId = itemId,
            callId = callId,
            name = name,
        )
        call.arguments.append(item.arguments)
        callsByOutputIndex[index] = call
        itemId?.let { id ->
            if (callsByItemId.put(id, call) != null) return fail(event.type, "duplicate item id")
        }
        return listOf(call.updateEvent())
    }

    private fun updateArguments(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        val call = findCall(event) ?: return fail(event.type, "function call item was not added")
        if (call.completed) return fail(event.type, "function call already completed")
        call.arguments.append(event.delta)
        return listOf(call.updateEvent())
    }

    private fun completeArguments(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        val call = findCall(event) ?: return fail(event.type, "function call item was not added")
        if (call.completed) return fail(event.type, "function call already completed")
        event.name?.takeIf(String::isNotBlank)?.let { call.name = it }
        event.arguments?.takeIf(String::isNotBlank)?.let { finalArguments ->
            val accumulated = call.arguments.toString()
            if (accumulated.isNotEmpty() && finalArguments != accumulated) {
                call.arguments.replace(finalArguments)
            }
            if (accumulated.isEmpty()) call.arguments.append(finalArguments)
        }
        return listOf(call.updateEvent())
    }

    private fun completeOutputItem(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        val rawItem = event.item ?: return fail(event.type, "missing output item")
        val item = try {
            rawItem.toOutputItem()
        } catch (error: Exception) {
            return fail(event.type, error.localizedMessage ?: "invalid output item")
        }
        val index = event.outputIndex ?: return fail(event.type, "missing output_index")
        val addedRawItem = responseItemsByOutputIndex[index]
            ?: return fail(event.type, "output item was not added")
        val addedItem = try {
            addedRawItem.toOutputItem()
        } catch (error: Exception) {
            return fail(event.type, error.localizedMessage ?: "invalid added output item")
        }
        val addedItemId = addedItem.id?.takeIf(String::isNotBlank)
        val completedItemId = item.id?.takeIf(String::isNotBlank)
        if (addedItemId != null && completedItemId != null && completedItemId != addedItemId) {
            return fail(event.type, "output item id changed")
        }
        val addedItemType = addedItem.type?.takeIf(String::isNotBlank)
        val completedItemType = item.type?.takeIf(String::isNotBlank)
        if (addedItemType != null && completedItemType != null && completedItemType != addedItemType) {
            return fail(event.type, "output item type changed")
        }
        val effectiveItemId = completedItemId ?: addedItemId
        val effectiveItemType = completedItemType ?: addedItemType
        val retainedMetadata = buildMap<String, JsonElement> {
            effectiveItemId?.let { put("id", JsonPrimitive(it)) }
            effectiveItemType?.let { put("type", JsonPrimitive(it)) }
            (item.summary.effectiveResponseMetadata(preserveEmptyArray = effectiveItemType == "reasoning")
                ?: addedItem.summary.effectiveResponseMetadata(preserveEmptyArray = effectiveItemType == "reasoning"))
                ?.let { put("summary", it) }
            item.encryptedContent?.takeIf(String::isNotBlank)
                ?.let { put("encrypted_content", JsonPrimitive(it)) }
                ?: addedItem.encryptedContent?.takeIf(String::isNotBlank)
                    ?.let { put("encrypted_content", JsonPrimitive(it)) }
        }
        val retainedRawItem = JsonObject(
            (rawItem - setOf("id", "type", "summary", "encrypted_content")) + retainedMetadata,
        )
        if (effectiveItemType == "web_search_call") {
            if (!openHostedOutputIndexes.remove(index)) {
                return fail(event.type, "hosted output item was already completed")
            }
            responseItemsByOutputIndex[index] = retainedRawItem
            return listOf(
                retainedRawItem.toHostedWebSearchUpdate(
                    streamKey = addedItemId ?: "response_hosted_$index",
                    completed = true,
                ),
            )
        }
        responseItemsByOutputIndex[index] = retainedRawItem
        if (effectiveItemType != "function_call") return emptyList()
        val itemId = effectiveItemId
        val call = findCall(event, itemId)
            ?: return fail(event.type, "function call item was not added")
        if (call.itemId != null && itemId != null && itemId != call.itemId) {
            return fail(event.type, "function call item id changed")
        }
        if (call.itemId == null && itemId != null) {
            if (callsByItemId.put(itemId, call) != null) {
                return fail(event.type, "duplicate item id")
            }
            call.itemId = itemId
        }
        item.callId?.takeIf(String::isNotBlank)?.let { call.callId = it }
        item.name?.takeIf(String::isNotBlank)?.let { call.name = it }
        item.arguments?.takeIf(String::isNotBlank)?.let { finalArguments ->
            val accumulated = call.arguments.toString()
            if (accumulated.isNotEmpty() && finalArguments != accumulated) {
                call.arguments.replace(finalArguments)
            }
            if (accumulated.isEmpty()) call.arguments.append(finalArguments)
        }
        val retainedFields = buildMap<String, JsonElement> {
            call.itemId?.let { put("id", JsonPrimitive(it)) }
            call.callId?.let { put("call_id", JsonPrimitive(it)) }
            call.name?.let { put("name", JsonPrimitive(it)) }
            put("arguments", JsonPrimitive(call.arguments.toString().ifEmpty { "{}" }))
        }
        responseItemsByOutputIndex[index] = JsonObject(retainedRawItem + retainedFields)
        return completeCall(call, event.type)
    }

    private fun completeCall(call: FunctionCall, rawType: String): List<StreamEvent> {
        if (call.completed) return fail(rawType, "function call completed twice")
        val callId = call.callId.orEmpty()
        val name = call.name.orEmpty()
        val arguments = call.arguments.toString().ifEmpty { "{}" }
        if (!callId.matches(safeWireToolCallId)) return fail(rawType, "invalid call_id")
        if (!name.matches(safeWireToolName)) return fail(rawType, "invalid function name")
        if (runCatching { json.parseToJsonElement(arguments) is JsonObject }.getOrDefault(false).not()) {
            return fail(rawType, "function arguments are not a complete JSON object")
        }
        if (!emittedCallIds.add(callId)) return fail(rawType, "duplicate call_id")
        call.completed = true
        completedCallsByOutputIndex[call.outputIndex] = StreamEvent.ToolCallRequest(
            callId,
            name,
            arguments,
            streamKey = call.streamKey,
        )
        return emptyList()
    }

    private fun completeResponse(event: OpenAiResponseStreamEvent): List<StreamEvent> {
        if (toolCallInFlight) return fail(event.type, "response completed with an open function call")
        if (openHostedOutputIndexes.isNotEmpty()) {
            return fail(event.type, "response completed with an open hosted tool call")
        }
        val response = event.response ?: return fail(event.type, "missing response envelope")
        if (response.status != "completed") {
            return fail(event.type, "unexpected terminal status ${response.status}")
        }
        sawTerminalMarker = true
        stopReason = "completed"
        val continuationItems = responseItemsByOutputIndex
            .toSortedMap()
            .values
            .toList()
        val calls = completedCallsByOutputIndex
            .toSortedMap()
            .values
            .mapIndexed { index, call ->
                if (index == 0) call.copy(responseOutputItems = continuationItems) else call
            }
        val output = mutableListOf<StreamEvent>()
        if (calls.size == 1) output += calls.single()
        if (calls.size > 1) output += StreamEvent.ToolCallsRequest(calls)
        response.usage?.let { output += StreamEvent.UsageUpdate(it.toTokenUsage()) }
        return output
    }

    private fun failResponse(event: OpenAiResponseStreamEvent, fallback: String): List<StreamEvent> {
        val response = event.response
        response?.usage?.let { return failApi(response.error, response.incompleteDetails?.reason ?: fallback, it) }
        return failApi(response?.error, response?.incompleteDetails?.reason ?: fallback)
    }

    private fun failApi(
        error: com.newoether.agora.api.OpenAiError?,
        fallback: String,
        usage: com.newoether.agora.api.OpenAiResponseUsage? = null,
    ): List<StreamEvent> {
        sawTerminalMarker = true
        stopReason = fallback.lowercase()
        streamError = GenerationError.Api(
            code = error?.code,
            type = error?.type ?: "responses_error",
            message = error?.message?.takeIf(String::isNotBlank) ?: fallback,
        )
        return usage?.let { listOf(StreamEvent.UsageUpdate(it.toTokenUsage())) }.orEmpty()
    }

    private fun findCall(event: OpenAiResponseStreamEvent, itemId: String? = null): FunctionCall? =
        event.itemId?.takeIf(String::isNotBlank)?.let(callsByItemId::get)
            ?: itemId?.takeIf(String::isNotBlank)?.let(callsByItemId::get)
            ?: event.outputIndex?.let(callsByOutputIndex::get)

    private fun validateSequence(event: OpenAiResponseStreamEvent): String? {
        val sequence = event.sequenceNumber ?: return "missing sequence_number"
        val previous = lastSequenceNumber
        if (previous != null && sequence <= previous) return "non-increasing sequence_number"
        lastSequenceNumber = sequence
        return null
    }

    private fun JsonObject.toHostedWebSearchUpdate(
        streamKey: String,
        completed: Boolean,
    ): StreamEvent.HostedToolCallUpdate {
        val action = this["action"] as? JsonObject ?: JsonObject(emptyMap())
        val status = (this["status"] as? JsonPrimitive)?.content
        return StreamEvent.HostedToolCallUpdate(
            streamKey = streamKey,
            name = "openai_search",
            arguments = action.toString(),
            result = takeIf { completed }?.toString(),
            isError = completed && status in setOf("failed", "incomplete"),
        )
    }

    private fun JsonObject.toOutputItem(): OpenAiResponseOutputItem =
        json.decodeFromJsonElement(OpenAiResponseOutputItem.serializer(), this)

    private fun fail(rawType: String, cause: String): List<StreamEvent> {
        reportedError = true
        return listOf(StreamEvent.Error(GenerationError.SseParse(rawType, cause)))
    }

    private fun FunctionCall.updateEvent() = StreamEvent.ToolCallUpdate(
        streamKey = streamKey,
        id = callId,
        name = name.orEmpty(),
        arguments = arguments.toString(),
    )
}
