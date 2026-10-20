package com.newoether.agora.data

import com.newoether.agora.model.CitationRecord

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class GptChatImporter {
    private companion object {
        const val CLIENT_ROOT = "client-created-root"
    }

    @Serializable
    data class GptConversation(
        @SerialName("conversation_id") val conversationId: String = "",
        val title: String = "",
        @SerialName("create_time") val createTime: Double = 0.0,
        @SerialName("update_time") val updateTime: Double = 0.0,
        @SerialName("current_node") val currentNode: String? = null,
        val mapping: Map<String, GptMappingNode> = emptyMap()
    )

    @Serializable
    data class GptMappingNode(
        val id: String = "",
        val message: GptMessage? = null,
        val parent: String? = null
    )

    @Serializable
    data class GptMessage(
        val id: String = "",
        val author: GptAuthor? = null,
        val content: GptContent? = null,
        @SerialName("create_time") val createTime: Double? = null,
        val metadata: GptMetadata? = null
    )

    @Serializable
    data class GptAuthor(
        val role: String = "",
        val name: String? = null
    )

    @Serializable
    data class GptContent(
        @SerialName("content_type") val contentType: String = "",
        val parts: List<JsonElement>? = null,
        val text: String? = null,
        val result: String? = null,
        @SerialName("content") val reasoningText: String? = null,
        val language: String? = null,
        val thoughts: List<GptThought>? = null
    )

    @Serializable
    data class GptThought(
        val content: String = "",
        val summary: String = ""
    )

    @Serializable
    data class GptMetadata(
        @SerialName("model_slug") val modelSlug: String? = null,
        @SerialName("parent_id") val parentId: String? = null,
        @SerialName("is_complete") val isComplete: Boolean? = null,
        @SerialName("content_references") val contentReferences: List<JsonElement> = emptyList(),
        val citations: List<JsonElement> = emptyList(),
        @SerialName("finish_details") val finishDetails: GptFinishDetails? = null,
        @SerialName("finished_duration_sec") val finishedDurationSec: Int? = null
    )

    @Serializable
    data class GptFinishDetails(
        val type: String = ""
    )

    data class ConversationSummary(
        val uuid: String,
        val title: String,
        val messageCount: Int
    )

    data class ImportPreview(
        val conversations: List<ConversationSummary> = emptyList(),
        val conversationCount: Int,
        val totalMessageCount: Int,
        val userMessageCount: Int,
        val assistantMessageCount: Int,
        val hasAttachments: Boolean
    )

    data class ImportResult(
        val conversationsImported: Int = 0,
        val messagesImported: Int = 0,
        val thoughtsMessageCount: Int = 0,
        val errors: List<String> = emptyList()
    )

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun buildMessageGraph(
        conversation: GptConversation,
    ): Triple<List<GptMessage>, Map<String, String?>, List<String>> {
        val mapping = conversation.mapping
        if (mapping.isEmpty()) return Triple(emptyList(), emptyMap(), emptyList())

        val validity = mutableMapOf<String, Boolean>()
        for (start in mapping.keys.sorted()) {
            if (start == CLIENT_ROOT || start in validity) continue
            val path = mutableListOf<String>()
            val positions = mutableMapOf<String, Int>()
            var cursor: String? = start
            var valid = true
            while (cursor != null && cursor != CLIENT_ROOT) {
                val nodeId = cursor
                val cachedValidity = validity[nodeId]
                if (cachedValidity != null) {
                    valid = cachedValidity
                    break
                }
                val node = mapping[nodeId]
                if (node == null || nodeId in positions) {
                    valid = false
                    break
                }
                positions[nodeId] = path.size
                path += nodeId
                cursor = node.parent
            }
            path.forEach { validity[it] = valid }
        }

        val validNodeIds = validity.filterValues { it }.keys
        val childrenByParent = validNodeIds
            .mapNotNull { nodeId ->
                val parent = mapping.getValue(nodeId).parent
                    ?.takeUnless { it == CLIENT_ROOT }
                parent?.let { it to nodeId }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, children) -> children.sorted() }
        val ready = java.util.PriorityQueue<String>()
        validNodeIds.filterTo(ready) { nodeId ->
            mapping.getValue(nodeId).parent.let { it == null || it == CLIENT_ROOT }
        }
        val orderedNodeIds = ArrayList<String>(validNodeIds.size)
        while (ready.isNotEmpty()) {
            val nodeId = ready.remove()
            orderedNodeIds += nodeId
            childrenByParent[nodeId].orEmpty().forEach(ready::add)
        }

        val currentNodePath = if (
            conversation.currentNode != null && validity[conversation.currentNode] == true
        ) {
            val path = mutableListOf<String>()
            var cursor: String? = conversation.currentNode
            while (cursor != null && cursor != CLIENT_ROOT) {
                path += cursor
                cursor = mapping.getValue(cursor).parent
            }
            path.asReversed()
        } else {
            emptyList()
        }

        val canonicalNodeByMessageId = linkedMapOf<String, String>()
        for (nodeId in currentNodePath + orderedNodeIds) {
            val messageId = mapping.getValue(nodeId).message?.id.orEmpty()
            if (messageId.isNotBlank()) canonicalNodeByMessageId.putIfAbsent(messageId, nodeId)
        }

        fun nearestAncestorMessageId(nodeId: String, messageId: String): String? {
            var parentNodeId = mapping.getValue(nodeId).parent
            while (parentNodeId != null && parentNodeId != CLIENT_ROOT) {
                val parent = mapping[parentNodeId] ?: return null
                val parentMessageId = parent.message?.id.orEmpty()
                if (
                    parentMessageId.isNotBlank() &&
                    parentMessageId != messageId &&
                    canonicalNodeByMessageId[parentMessageId] == parentNodeId
                ) {
                    return parentMessageId
                }
                parentNodeId = parent.parent
            }
            return null
        }

        val parentByMessageId = linkedMapOf<String, String?>()
        val messages = canonicalNodeByMessageId.map { (messageId, nodeId) ->
            parentByMessageId[messageId] = nearestAncestorMessageId(nodeId, messageId)
            checkNotNull(mapping.getValue(nodeId).message)
        }

        val currentMessageIds = currentNodePath.mapNotNull { nodeId ->
            mapping.getValue(nodeId).message?.id
                ?.takeIf { it in canonicalNodeByMessageId }
        }.distinct()
        return Triple(messages, parentByMessageId, currentMessageIds)
    }

    /**
     * Streams and parses a ChatGPT export without ever holding the whole file
     * in memory. [openStream] is a factory so the source can be re-read when a
     * ZIP archive must be probed before its entries are decoded.
     *
     * Accepts either a raw `conversations.json` array or a ChatGPT ZIP export
     * containing `conversations.json` (optionally split into `conversations-N.json`).
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun extractAndParse(openStream: () -> InputStream): Result<List<GptConversation>> {
        return try {
            BufferedInputStream(openStream()).use { input ->
                if (isZip(input)) {
                    val zipInput = ZipInputStream(input)
                    val allConversations = mutableListOf<GptConversation>()
                    var entry = zipInput.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory &&
                            (entry.name == "conversations.json" ||
                                entry.name.matches(Regex("conversations-\\d+\\.json")))
                        ) {
                            allConversations += jsonParser.decodeFromStream<List<GptConversation>>(
                                NonClosingInputStream(zipInput)
                            )
                        }
                        zipInput.closeEntry()
                        entry = zipInput.nextEntry
                    }
                    if (allConversations.isNotEmpty()) Result.success(allConversations)
                    else Result.failure(Exception("No conversation data found in ZIP archive"))
                } else {
                    val list = jsonParser.decodeFromStream<List<GptConversation>>(input)
                    if (list.isNotEmpty()) Result.success(list)
                    else Result.failure(Exception("No conversations found in JSON"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun preview(conversations: List<GptConversation>): ImportPreview {
        val summaries = conversations
            .sortedByDescending { it.updateTime }
            .map { conv ->
                val messages = buildMessageGraph(conv).first
                ConversationSummary(
                    uuid = conv.conversationId,
                    title = conv.title.ifEmpty { "Untitled" },
                    messageCount = messages.size
                )
            }
        val allMessages = conversations.flatMap { buildMessageGraph(it).first }
        return ImportPreview(
            conversations = summaries,
            conversationCount = conversations.size,
            totalMessageCount = allMessages.size,
            userMessageCount = allMessages.count { it.author?.role == "user" },
            assistantMessageCount = allMessages.count { it.author?.role == "assistant" },
            hasAttachments = allMessages.any { msg ->
                msg.content?.contentType == "multimodal_text" ||
                msg.content?.parts?.any { part ->
                    part is kotlinx.serialization.json.JsonObject &&
                    part.jsonObject["content_type"]?.jsonPrimitive?.content == "image_asset_pointer"
                } == true
            }
        )
    }

    fun toImportFormat(conversations: List<GptConversation>, selectedIds: Set<String>? = null): ClaudeChatImporter.ImportConversations {
        val chatEntities = mutableListOf<ClaudeChatImporter.ImportChatEntity>()
        val messageEntities = mutableListOf<ClaudeChatImporter.ImportMessageEntity>()

        val filtered = if (selectedIds != null) {
            conversations.filter { it.conversationId in selectedIds }
        } else {
            conversations
        }

        for (conv in filtered) {
            val (messages, rawParentMap, currentMessageIds) = buildMessageGraph(conv)
            if (messages.isEmpty()) continue

            // First pass: build intermediate message data
            data class RawMsg(
                val id: String,
                val rawParentId: String?,
                val text: String,
                val citations: List<CitationRecord>,
                val thoughts: String?,
                val thoughtTitle: String?,
                val contentType: String,
                val participant: String,
                val status: String,
                val timestamp: Long,
                val thoughtTimeMs: Long?,
                val modelName: String?,
                val authorRole: String
            )
            val rawMessages = messages.map { msg ->
                val role = msg.author?.role ?: "user"
                val participant = when (role) {
                    "assistant" -> "MODEL"
                    "tool" -> "MODEL"
                    "system" -> "MODEL"
                    else -> "USER"
                }
                val contentType = msg.content?.contentType ?: "text"
                val text = when (contentType) {
                    "text" -> msg.content?.parts?.joinToString("") { extractTextFromPart(it) } ?: ""
                    "multimodal_text" -> msg.content?.parts?.joinToString("") { extractTextFromPart(it) } ?: ""
                    "code" -> msg.content?.text ?: ""
                    "execution_output" -> msg.content?.text ?: msg.content?.result ?: ""
                    "tether_quote" -> msg.content?.text ?: msg.content?.result ?: ""
                    "tether_browsing_display" -> msg.content?.text ?: msg.content?.result ?: ""
                    "user_editable_context" -> msg.content?.parts?.joinToString("") { extractTextFromPart(it) } ?: ""
                    else -> msg.content?.parts?.joinToString("") { extractTextFromPart(it) } ?: ""
                }
                val importedText = projectChatGptCitations(
                    rawText = text,
                    references = msg.metadata?.let { it.contentReferences + it.citations }.orEmpty(),
                )
                val thoughts = when (contentType) {
                    "thoughts" -> msg.content?.thoughts?.joinToString("\n\n") { it.content }
                    else -> null
                }
                val thoughtTitle = when (contentType) {
                    "thoughts" -> msg.content?.thoughts?.firstOrNull()?.summary
                    "reasoning_recap" -> msg.content?.reasoningText
                    else -> null
                }
                val thoughtTimeMs = when (contentType) {
                    "reasoning_recap" -> msg.metadata?.finishedDurationSec?.let { it * 1000L }
                    else -> null
                }
                val modelName = msg.metadata?.modelSlug?.let { "OpenAI:$it" }
                val timestamp = if (msg.createTime != null) (msg.createTime * 1000).toLong() else System.currentTimeMillis()
                val status = when {
                    msg.metadata?.isComplete == false -> "STOPPED"
                    msg.metadata?.finishDetails?.type == "stop" -> "SUCCESS"
                    msg.metadata?.finishDetails?.type != null -> "STOPPED"
                    else -> "SUCCESS"
                }
                RawMsg(
                    msg.id,
                    rawParentMap[msg.id],
                    importedText.text,
                    importedText.citations,
                    thoughts,
                    thoughtTitle,
                    contentType,
                    participant,
                    status,
                    timestamp,
                    thoughtTimeMs,
                    modelName,
                    role,
                )
            }

            val rawById = rawMessages.associateBy { it.id }

            // ChatGPT stores thinking and tool results as separate nodes. Project only the
            // ephemeral ancestors on each retained message's own path; siblings never share data.
            val mergeTypes = setOf("thoughts", "reasoning_recap", "execution_output", "tether_quote", "tether_browsing_display", "code")
            fun RawMsg.isEphemeral(): Boolean = contentType in mergeTypes || authorRole == "tool"
            val removedIds = rawMessages.filter { it.isEphemeral() }.mapTo(mutableSetOf()) { it.id }

            val fallbackTexts = mutableMapOf<String, String>()
            for (message in rawMessages) {
                if (message.isEphemeral() || message.text.isNotBlank()) continue
                if (message.contentType == "multimodal_text") {
                    fallbackTexts[message.id] = "[Image]"
                }
            }

            val mergedThoughts = mutableMapOf<String, String>()
            val mergedThoughtTitle = mutableMapOf<String, String?>()
            val mergedThoughtTimeMs = mutableMapOf<String, Long>()
            val mergedTextSuffix = mutableMapOf<String, StringBuilder>()
            val mergedCitations = mutableMapOf<String, MutableList<CitationRecord>>()

            fun mergeInto(target: RawMsg, ephemeral: RawMsg) {
                if (ephemeral.citations.isNotEmpty()) {
                    mergedCitations.getOrPut(target.id) { mutableListOf() }
                        .addAll(ephemeral.citations.map { it.copy(anchors = emptyList()) })
                }
                when (ephemeral.contentType) {
                    "thoughts" -> {
                        if (!ephemeral.thoughts.isNullOrBlank()) {
                            mergedThoughts[target.id] = ephemeral.thoughts
                            mergedThoughtTitle[target.id] = ephemeral.thoughtTitle
                        }
                    }
                    "reasoning_recap" -> {
                        if (ephemeral.thoughtTimeMs != null && ephemeral.thoughtTimeMs > 0) {
                            mergedThoughtTimeMs[target.id] = ephemeral.thoughtTimeMs
                        }
                        if (!ephemeral.thoughtTitle.isNullOrBlank()) {
                            mergedThoughtTitle[target.id] = ephemeral.thoughtTitle
                        }
                    }
                    "code" -> {
                        if (ephemeral.text.isNotBlank()) {
                            val existing = mergedThoughts[target.id]
                            mergedThoughts[target.id] = if (existing != null) {
                                "$existing\n\n${ephemeral.text}"
                            } else {
                                ephemeral.text
                            }
                        }
                    }
                    else -> {
                        if (ephemeral.text.isNotBlank()) {
                            val suffix = mergedTextSuffix.getOrPut(target.id) { StringBuilder() }
                            if (suffix.isNotEmpty()) suffix.append("\n\n")
                            suffix.append(ephemeral.text)
                        }
                    }
                }
            }

            for (target in rawMessages.filterNot { it.isEphemeral() }) {
                val ancestors = mutableListOf<RawMsg>()
                var parentId = target.rawParentId
                while (parentId != null && parentId in removedIds) {
                    val parent = rawById[parentId] ?: break
                    ancestors += parent
                    parentId = parent.rawParentId
                }
                ancestors.asReversed().forEach { mergeInto(target, it) }
            }

            val skippedIds = rawMessages.asSequence()
                .filterNot { it.isEphemeral() }
                .filter { message ->
                    val effectiveText = fallbackTexts[message.id] ?: message.text
                    effectiveText.isBlank() &&
                        mergedTextSuffix[message.id].isNullOrEmpty() &&
                        (mergedThoughts[message.id] ?: message.thoughts).isNullOrBlank()
                }
                .mapTo(mutableSetOf()) { it.id }
            val allRemoved = removedIds + skippedIds

            // Cascade parent references through all removed messages.
            fun cascadeParent(id: String?): String? {
                var pid = id
                while (pid != null && pid in allRemoved) {
                    pid = rawById[pid]?.rawParentId
                }
                return pid
            }

            // Second pass: emit only real messages
            var convMsgCount = 0
            for (rm in rawMessages) {
                if (rm.id in allRemoved) continue
                val parentId = cascadeParent(rm.rawParentId)
                val baseText = fallbackTexts[rm.id] ?: rm.text
                val suffix = mergedTextSuffix[rm.id]?.toString()
                val finalText = if (suffix != null && baseText.isNotBlank()) "$baseText\n\n$suffix"
                    else if (suffix != null) suffix
                    else baseText
                val finalThoughts = mergedThoughts[rm.id] ?: rm.thoughts
                val finalThoughtTitle = mergedThoughtTitle[rm.id] ?: rm.thoughtTitle
                val finalThoughtTimeMs = mergedThoughtTimeMs[rm.id]
                val citationJson = encodeImportedCitations(
                    rm.citations + mergedCitations[rm.id].orEmpty(),
                    finalText,
                )
                messageEntities.add(
                    ClaudeChatImporter.ImportMessageEntity(
                        id = rm.id,
                        conversationId = conv.conversationId,
                        parentId = parentId,
                        text = finalText,
                        images = emptyList(),
                        thoughts = finalThoughts,
                        thoughtTitle = finalThoughtTitle,
                        tokenCount = 0,
                        status = rm.status,
                        participant = rm.participant,
                        timestamp = rm.timestamp,
                        thoughtTimeMs = finalThoughtTimeMs,
                        modelName = rm.modelName,
                        toolCallJson = citationJson,
                        attachmentMeta = null
                    )
                )
                convMsgCount++
            }

            val selectedBranches = linkedMapOf<String?, String>()
            for (messageId in currentMessageIds) {
                if (messageId in allRemoved) continue
                val message = rawById[messageId] ?: continue
                selectedBranches[cascadeParent(message.rawParentId)] = messageId
            }

            if (convMsgCount > 0) {
                chatEntities.add(
                    ClaudeChatImporter.ImportChatEntity(
                        id = conv.conversationId,
                        title = conv.title.ifEmpty { "Untitled" },
                        lastUpdated = (conv.updateTime * 1000).toLong(),
                        selectedBranchesJson = selectedBranches
                            .takeIf { it.isNotEmpty() }
                            ?.let { Json.encodeToString(it.mapKeys { entry -> entry.key ?: "null" }) },
                        systemPromptId = null,
                        modelId = null
                    )
                )
            }
        }

        return ClaudeChatImporter.ImportConversations(chatEntities, messageEntities)
    }

    private fun extractTextFromPart(part: JsonElement): String {
        return try {
            when {
                part is kotlinx.serialization.json.JsonPrimitive && part.isString -> part.content
                part is kotlinx.serialization.json.JsonObject -> {
                    val obj = part
                    val partType = obj["content_type"]?.jsonPrimitive?.content ?: ""
                    when (partType) {
                        "image_asset_pointer" -> ""
                        "tether_quote", "tether_browsing_display" -> obj["text"]?.jsonPrimitive?.content ?: ""
                        else -> obj["text"]?.jsonPrimitive?.content ?: ""
                    }
                }
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

}
