package com.newoether.agora.data

import android.util.JsonReader
import com.newoether.agora.automation.LoopPolicy
import com.newoether.agora.data.DataImporter.ImportStrategy
import com.newoether.agora.data.NativeConversationMediaRestorer.RestoredMedia
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.ConversationSettingsImportTransferEntity
import com.newoether.agora.data.local.LoopEntity
import com.newoether.agora.data.local.MaintenanceDebtEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.local.SemanticModelSnapshot
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.data.local.withSemanticGraphMutation
import com.newoether.agora.data.local.migration.LegacyMessageRecord
import com.newoether.agora.data.local.migration.LegacyRunBackfillPlanner
import com.newoether.agora.data.local.migration.PlannedMessageAssignment
import com.newoether.agora.data.local.migration.RegenerationTreeRepairPlanner
import com.newoether.agora.data.local.migration.V17MessageRecord
import com.newoether.agora.data.local.migration.V17RunRecord
import com.newoether.agora.data.local.migration.regenerationInputFingerprint
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.service.MaintenanceDebtWorker
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID

internal fun decodeStoredSelections(raw: String?): Map<String?, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        Json.decodeFromString<Map<String, String>>(raw)
            .mapKeys { if (it.key == "null") null else it.key }
    }.getOrDefault(emptyMap())
}

internal fun encodeStoredSelections(selections: Map<String?, String>): String =
    Json.encodeToString(selections.mapKeys { it.key ?: "null" })

internal class NativeConversationGraphImporter(
    private val database: ChatDatabase,
    private val chatDao: ChatDao,
    private val importJson: Json,
    private val mediaRestorer: NativeConversationMediaRestorer,
    private val scheduleMaintenance: () -> Unit = { MaintenanceDebtWorker.schedule() },
) {
    private companion object {
        const val IMPORT_MESSAGE_BATCH_SIZE = 64
    }

    data class ConversationGraphCounts(
        val conversations: Int = 0,
        val tasks: Int = 0,
        val loops: Int = 0,
    )

    data class ConversationGraphHeaders(
        val tasks: List<TaskEntity>,
        val conversations: List<ChatEntity>,
        val runs: List<RunEntity>,
        val sourceRunIdsWereUnique: Boolean,
        val loops: List<LoopEntity>,
        val availableConversationIds: Set<String>,
        val conversationSettings: Map<String, ConversationSettings>,
    )

    private data class PlannedNativeRunGraph(
        val runs: List<RunEntity>,
        val assignments: Map<String, PlannedMessageAssignment>,
        val recoveredRunIds: Set<String> = emptySet(),
        val legacyRunSelections: Map<String, Map<String?, String>> = emptyMap(),
        val messageSelectionOverrides: Map<String, Map<String?, String>> = emptyMap(),
        val deletedMessageIds: Set<String> = emptySet(),
        val messageParentOverrides: Map<String, String> = emptyMap(),
    )

    private inline fun <reified T> JsonReader.readSerializableArray(): List<T> {
        val values = mutableListOf<T>()
        beginArray()
        while (hasNext()) {
            values.add(importJson.decodeFromJsonElement(importJson.readNativeGraphValue(this)))
        }
        endArray()
        return values
    }

    private fun countArray(reader: JsonReader): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            reader.skipValue()
            count++
        }
        reader.endArray()
        return count
    }

    /** Counts graph headers without deserializing the messages array. */
    fun countConversationGraph(stream: InputStream): ConversationGraphCounts {
        var conversations = 0
        var tasks = 0
        var loops = 0
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "conversations" -> conversations = countArray(reader)
                    "tasks" -> tasks = countArray(reader)
                    "loops" -> loops = countArray(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return ConversationGraphCounts(conversations, tasks, loops)
    }

    suspend fun readConversationGraphHeaders(
        stream: InputStream,
        strategy: ImportStrategy,
        restoredMedia: RestoredMedia,
        resolveSystemPromptId: (String?) -> String?,
    ): ConversationGraphHeaders {
        var rawConversations = emptyList<ExportChatEntity>()
        var rawRuns = emptyList<ExportRunEntity>()
        var rawTasks = emptyList<ExportTaskEntity>()
        var rawLoops = emptyList<ExportLoopEntity>()
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "conversations" -> rawConversations = reader.readSerializableArray()
                    "runs" -> rawRuns = reader.readSerializableArray()
                    "tasks" -> rawTasks = reader.readSerializableArray()
                    "loops" -> rawLoops = reader.readSerializableArray()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        val tasks = rawTasks.map { task ->
            sanitizeImportedTask(TaskEntity(
                id = task.id,
                name = task.name,
                prompt = task.prompt,
                systemPrompt = task.systemPrompt,
                systemPromptId = resolveSystemPromptId(task.systemPromptId),
                modelId = task.modelId,
                cronExpr = task.cronExpr,
                runAt = task.runAt,
                nextRunAt = task.nextRunAt,
                enabled = task.enabled,
                createdAt = task.createdAt,
                lastRunAt = task.lastRunAt,
            ))
        }
        val availableTaskIds = if (strategy == ImportStrategy.MERGE) {
            chatDao.getAllTaskIds().toMutableSet()
        } else {
            mutableSetOf()
        }.apply { addAll(tasks.map { it.id }) }

        val conversations = rawConversations.map { conversation ->
            sanitizeImportedConversation(
                ChatEntity(
                    id = conversation.id,
                    title = conversation.title,
                    lastUpdated = conversation.lastUpdated,
                    selectedBranchesJson = conversation.selectedBranchesJson,
                    systemPromptId = resolveSystemPromptId(conversation.systemPromptId),
                    modelId = conversation.modelId,
                    taskId = conversation.taskId,
                    origin = conversation.origin,
                    graduated = conversation.graduated,
                    draftText = conversation.draftText,
                    draftAttachments = mediaRestorer.restoreDraftAttachments(
                        conversation.draftAttachments,
                        restoredMedia,
                    ),
                    selectedRunBranchesJson = conversation.selectedRunBranchesJson,
                    // Unread is durable on one device, but it is not user content and must never
                    // become a false cross-device notification after restore.
                    hasUnreadGeneration = false,
                ),
                availableTaskIds,
            )
        }
        val availableConversationIds = if (strategy == ImportStrategy.MERGE) {
            chatDao.getAllConversationIds().toMutableSet()
        } else {
            mutableSetOf()
        }.apply { addAll(conversations.map { it.id }) }

        val availableRawRuns = rawRuns.filter {
            it.conversationId in availableConversationIds
        }
        val sourceRunIdsWereUnique =
            availableRawRuns.map { it.id }.distinct().size == availableRawRuns.size
        val runs = NativeRunArchivePolicy.orderByParent(
            availableRawRuns.map { NativeRunArchivePolicy.terminalize(it.toArchivedSnapshot()) }
        )

        val loops = rawLoops
            .filter { it.conversationId in availableConversationIds }
            .map { loop ->
                sanitizeImportedLoop(LoopEntity(
                    conversationId = loop.conversationId,
                    intervalMs = loop.intervalMs,
                    prompt = loop.prompt,
                    nextFireAt = loop.nextFireAt,
                    cycleCount = loop.cycleCount,
                    maxCycles = loop.maxCycles,
                    active = loop.active,
                    revision = loop.revision,
                ))
            }
        return ConversationGraphHeaders(
            tasks = tasks,
            conversations = conversations,
            runs = runs,
            sourceRunIdsWereUnique = sourceRunIdsWereUnique,
            loops = loops,
            availableConversationIds = availableConversationIds,
            conversationSettings = rawConversations.mapNotNull { conversation ->
                conversation.conversationSettings?.let { conversation.id to it }
            }.toMap(),
        )
    }

    private fun ExportMessageEntity.toMessageEntity(
        restoredMedia: RestoredMedia,
        assignment: PlannedMessageAssignment,
        recoveredRunIds: Set<String>,
        archiveVersion: Int,
    ): MessageEntity {
        val parsedParticipant = try {
            Participant.valueOf(participant)
        } catch (_: Exception) {
            Participant.MODEL
        }
        val parsedStatus = try {
            MessageStatus.valueOf(status)
        } catch (_: Exception) {
            MessageStatus.SUCCESS
        }
        val oldToNewImageIndex = mutableMapOf<Int, Int>()
        val restoredImages = if (archiveVersion >= 4) {
            buildList {
                images.forEachIndexed { oldIndex, entry ->
                    restoredMedia.archiveFiles[entry]?.uri?.let { uri ->
                        oldToNewImageIndex[oldIndex] = size
                        add(uri)
                    }
                }
            }
        } else {
            buildList {
                restoredMedia.legacyImagesByMessage[id]
                    .orEmpty()
                    .toSortedMap()
                    .forEach { (oldIndex, uri) ->
                        oldToNewImageIndex[oldIndex] = size
                        add(uri)
                    }
            }
        }
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            parentId = parentId,
            text = text,
            images = restoredImages,
            thoughts = thoughts,
            thoughtTitle = thoughtTitle,
            tokenCount = tokenCount,
            inputTokenCount = inputTokenCount,
            cachedInputTokenCount = cachedInputTokenCount,
            cacheWriteInputTokenCount = cacheWriteInputTokenCount,
            uncachedInputTokenCount = uncachedInputTokenCount,
            outputTokenCount = outputTokenCount,
            reasoningTokenCount = reasoningTokenCount,
            generationDurationMs = generationDurationMs?.takeIf { it > 0 },
            status = if (
                assignment.runId in recoveredRunIds &&
                parsedParticipant == Participant.MODEL &&
                parsedStatus in setOf(
                    MessageStatus.SENDING,
                    MessageStatus.THINKING,
                    MessageStatus.TOOL_CALLING,
                    MessageStatus.TRANSCRIBING,
                )
            ) MessageStatus.STOPPED else parsedStatus,
            participant = parsedParticipant,
            timestamp = timestamp,
            thoughtTimeMs = thoughtTimeMs,
            modelName = modelName,
            toolCallJson = NativeBackupMediaPolicy.restoreToolImagePaths(
                raw = toolCallJson,
                archiveVersion = archiveVersion,
                restoredPathForArchiveEntry = { entry ->
                    restoredMedia.archiveFiles[entry]?.absolutePath
                },
            ),
            attachmentMeta = NativeBackupMediaPolicy.restoreAttachmentMeta(
                raw = attachmentMeta,
                archiveVersion = archiveVersion,
                oldToNewImageIndex = oldToNewImageIndex,
                legacyVideoUris = restoredMedia.legacyVideosByMessage[id].orEmpty(),
                restoredUriForArchiveEntry = { entry ->
                    restoredMedia.archiveFiles[entry]?.uri
                },
            ),
            runId = assignment.runId,
            runSequence = assignment.runSequence,
            consumedAtPass = assignment.consumedAtPass,
        )
    }

    private suspend fun importMessagesFromGraph(
        stream: InputStream,
        strategy: ImportStrategy,
        availableConversationIds: Set<String>,
        restoredMedia: RestoredMedia,
        assignments: Map<String, PlannedMessageAssignment>,
        recoveredRunIds: Set<String>,
        deletedMessageIds: Set<String>,
        messageParentOverrides: Map<String, String>,
        archiveVersion: Int,
    ) {
        val batch = mutableListOf<MessageEntity>()

        suspend fun flushBatch() {
            if (batch.isEmpty()) return
            val existingIds = if (strategy == ImportStrategy.MERGE) {
                chatDao.findExistingMessageIds(batch.map { it.id }).toSet()
            } else {
                emptySet()
            }
            batch.forEach { message ->
                if (message.id !in existingIds || message.images.isNotEmpty()) {
                    chatDao.upsertMessage(message)
                }
            }
            batch.clear()
        }

        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "messages") {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    val exported = importJson.decodeFromJsonElement<ExportMessageEntity>(
                        importJson.readNativeGraphValue(reader)
                    )
                    if (exported.conversationId in availableConversationIds) {
                        if (exported.id in deletedMessageIds) continue
                        var message = exported.toMessageEntity(
                                restoredMedia,
                                checkNotNull(assignments[exported.id]) {
                                    "Message ${exported.id} has no planned Run assignment"
                                },
                                recoveredRunIds,
                                archiveVersion,
                            )
                        messageParentOverrides[exported.id]?.let { repairedParentId ->
                            message = message.copy(parentId = repairedParentId)
                        }
                        batch.add(message)
                        if (batch.size >= IMPORT_MESSAGE_BATCH_SIZE) {
                            flushBatch()
                        }
                    }
                }
                reader.endArray()
            }
            reader.endObject()
        }
        flushBatch()
    }

    private fun planNativeRunGraph(
        stream: InputStream,
        headers: ConversationGraphHeaders,
    ): PlannedNativeRunGraph {
        val messagesByConversation = mutableMapOf<String, MutableList<LegacyMessageRecord>>()
        val repairMessagesByConversation =
            mutableMapOf<String, MutableList<V17MessageRecord>>()
        val archivedOwnership = mutableListOf<ArchivedMessageRunOwnership>()
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "messages") {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    val exported = importJson.decodeFromJsonElement<ExportMessageEntity>(
                        importJson.readNativeGraphValue(reader)
                    )
                    if (exported.conversationId in headers.availableConversationIds) {
                        val participant = try {
                            Participant.valueOf(exported.participant)
                        } catch (_: Exception) {
                            Participant.MODEL
                        }
                        val status = try {
                            MessageStatus.valueOf(exported.status)
                        } catch (_: Exception) {
                            MessageStatus.SUCCESS
                        }
                        archivedOwnership += ArchivedMessageRunOwnership(
                            messageId = exported.id,
                            conversationId = exported.conversationId,
                            runId = exported.runId,
                            runSequence = exported.runSequence,
                            consumedAtPass = exported.consumedAtPass,
                        )
                        messagesByConversation.getOrPut(exported.conversationId) { mutableListOf() }
                            .add(
                                LegacyMessageRecord(
                                    id = exported.id,
                                    parentId = exported.parentId,
                                    participant = participant,
                                    status = status,
                                    timestamp = exported.timestamp,
                                )
                            )
                        val runId = exported.runId
                        val runSequence = exported.runSequence
                        if (runId != null && runSequence != null) {
                            repairMessagesByConversation
                                .getOrPut(exported.conversationId) { mutableListOf() }
                                .add(
                                    V17MessageRecord(
                                        id = exported.id,
                                        parentId = exported.parentId,
                                        participant = participant,
                                        timestamp = exported.timestamp,
                                        runId = runId,
                                        runSequence = runSequence,
                                        inputFingerprint = if (participant == Participant.USER) {
                                            regenerationInputFingerprint(
                                                exported.text,
                                                exported.images.size,
                                                exported.attachmentMeta,
                                            )
                                        } else {
                                            ""
                                        },
                                    )
                                )
                        }
                    }
                }
                reader.endArray()
            }
            reader.endObject()
        }

        val archiveOwnershipIsComplete = NativeRunArchivePolicy.hasCompleteOwnership(
            runs = headers.runs,
            ownership = archivedOwnership,
            sourceRunIdsWereUnique = headers.sourceRunIdsWereUnique,
        )
        if (archiveOwnershipIsComplete) {
            val runsByConversation = headers.runs.groupBy { it.conversationId }
            val conversationsById = headers.conversations.associateBy { it.id }
            val runParentUpdates = mutableMapOf<String, String>()
            val deletedMessageIds = mutableSetOf<String>()
            val messageParentOverrides = mutableMapOf<String, String>()
            val runSequenceOverrides = mutableMapOf<String, Long>()
            val messageSelectionOverrides =
                mutableMapOf<String, Map<String?, String>>()
            val runSelectionOverrides =
                mutableMapOf<String, Map<String?, String>>()

            for (conversationId in headers.availableConversationIds) {
                val conversation = conversationsById[conversationId] ?: continue
                val repair = RegenerationTreeRepairPlanner.plan(
                    runs = runsByConversation[conversationId].orEmpty().map {
                        V17RunRecord(it.id, it.parentRunId, it.startedAt)
                    },
                    messages = repairMessagesByConversation[conversationId].orEmpty(),
                    messageSelections = decodeStoredSelections(conversation.selectedBranchesJson),
                    runSelections = decodeStoredSelections(conversation.selectedRunBranchesJson),
                )
                if (repair.inferredRunIds.isEmpty()) continue
                runParentUpdates += repair.runParentUpdates
                deletedMessageIds += repair.deletedMessageIds
                messageParentOverrides += repair.messageParentUpdates
                runSequenceOverrides += repair.runSequenceUpdates
                messageSelectionOverrides[conversationId] = repair.messageSelections
                runSelectionOverrides[conversationId] = repair.runSelections
            }

            val repairedRuns = NativeRunArchivePolicy.orderByParent(
                headers.runs.map { run ->
                    runParentUpdates[run.id]?.let { parentRunId ->
                        run.copy(
                            parentRunId = parentRunId,
                            legacyAmbiguous = true,
                        )
                    } ?: run
                }
            )
            val assignments = archivedOwnership
                .asSequence()
                .filter { it.messageId !in deletedMessageIds }
                .associate { ownership ->
                ownership.messageId to PlannedMessageAssignment(
                    messageId = ownership.messageId,
                    runId = checkNotNull(ownership.runId),
                    runSequence = runSequenceOverrides[ownership.messageId]
                        ?: checkNotNull(ownership.runSequence),
                    consumedAtPass = ownership.consumedAtPass,
                )
            }
            return PlannedNativeRunGraph(
                runs = repairedRuns,
                assignments = assignments,
                recoveredRunIds = repairedRuns
                    .filter { it.endReason == RunEndReason.PROCESS_RECOVERED }
                    .mapTo(mutableSetOf()) { it.id },
                legacyRunSelections = runSelectionOverrides,
                messageSelectionOverrides = messageSelectionOverrides,
                deletedMessageIds = deletedMessageIds,
                messageParentOverrides = messageParentOverrides,
            )
        }

        val runs = mutableListOf<RunEntity>()
        val assignments = mutableMapOf<String, PlannedMessageAssignment>()
        val legacyRunSelections = mutableMapOf<String, Map<String?, String>>()
        val conversationsById = headers.conversations.associateBy { it.id }
        for (conversation in headers.conversations) {
            val conversationId = conversation.id
            val messages = messagesByConversation[conversationId].orEmpty()
            val plan = LegacyRunBackfillPlanner.plan(conversationId, messages)
            runs += plan.runs.map {
                RunEntity(
                    id = it.id,
                    conversationId = it.conversationId,
                    parentRunId = it.parentRunId,
                    status = it.status,
                    activeSlot = null,
                    startedAt = it.startedAt,
                    lastCheckpointAt = it.endedAt,
                    endedAt = it.endedAt,
                    endReason = it.endReason,
                    legacyAmbiguous = it.legacyAmbiguous,
                )
            }
            plan.assignments.forEach { assignments[it.messageId] = it }
            val messageSelections = conversationsById[conversationId]
                ?.selectedBranchesJson
                ?.let { raw ->
                    runCatching {
                        importJson.decodeFromString<Map<String, String>>(raw)
                            .mapKeys { if (it.key == "null") null else it.key }
                    }.getOrDefault(emptyMap())
                }
                .orEmpty()
            legacyRunSelections[conversationId] = LegacyRunBackfillPlanner.selectedRunBranches(
                messages,
                plan,
                messageSelections,
            )
        }
        return PlannedNativeRunGraph(
            runs = NativeRunArchivePolicy.orderByParent(runs),
            assignments = assignments,
            legacyRunSelections = legacyRunSelections,
        )
    }

    suspend fun importConversationGraph(
        graphSource: NativeConversationGraphSource,
        strategy: ImportStrategy,
        headers: ConversationGraphHeaders,
        restoredMedia: RestoredMedia,
        archiveVersion: Int,
        semanticSnapshot: SemanticModelSnapshot,
    ): String {
        val plannedRunGraph = graphSource.open().use { stream ->
            planNativeRunGraph(stream, headers)
        }
        val importedConversationIds = headers.conversations.mapTo(mutableSetOf()) { it.id }
        val importedSettings = headers.conversationSettings.filterKeys(importedConversationIds::contains)
        val settingsTransfer = ConversationSettingsImportTransferEntity(
            transferId = UUID.randomUUID().toString(),
            settingsJson = importJson.encodeToString(importedSettings),
            mode = if (strategy == ImportStrategy.REPLACE) {
                ConversationSettingsImportTransferEntity.MODE_REPLACE
            } else {
                ConversationSettingsImportTransferEntity.MODE_MERGE
            },
        )
        val at = System.currentTimeMillis()
        database.withSemanticGraphMutation(
            snapshot = semanticSnapshot,
            clearMessageIds = plannedRunGraph.assignments.keys,
            clearAllEmbeddings = strategy == ImportStrategy.REPLACE,
            updatedAt = at,
        ) {
            if (strategy == ImportStrategy.REPLACE) {
                chatDao.deleteAllLoops()
                chatDao.deleteAllConversations()
                chatDao.deleteAllTasks()
            }
            headers.tasks.forEach { chatDao.upsertTask(it) }
            headers.conversations.forEach { conversation ->
                val derivedRunSelections = plannedRunGraph.legacyRunSelections[conversation.id]
                val derivedMessageSelections =
                    plannedRunGraph.messageSelectionOverrides[conversation.id]
                chatDao.upsertConversation(
                    conversation.copy(
                        selectedBranchesJson = derivedMessageSelections
                            ?.let(::encodeStoredSelections)
                            ?: conversation.selectedBranchesJson,
                        selectedRunBranchesJson = derivedRunSelections
                            ?.let(::encodeStoredSelections)
                            ?: conversation.selectedRunBranchesJson,
                    )
                )
            }
            for (run in plannedRunGraph.runs) {
                if (chatDao.getRun(run.id) == null) chatDao.insertRun(run)
            }
            graphSource.open().use { stream ->
                importMessagesFromGraph(
                    stream = stream,
                    strategy = strategy,
                    availableConversationIds = headers.availableConversationIds,
                    restoredMedia = restoredMedia,
                    assignments = plannedRunGraph.assignments,
                    recoveredRunIds = plannedRunGraph.recoveredRunIds,
                    deletedMessageIds = plannedRunGraph.deletedMessageIds,
                    messageParentOverrides = plannedRunGraph.messageParentOverrides,
                    archiveVersion = archiveVersion,
                )
            }
            headers.loops.forEach { chatDao.upsertLoop(it) }
            importedSettings.keys.forEach { conversationId ->
                chatDao.deleteConversationSettingsTransfer(conversationId)
            }
            chatDao.upsertConversationSettingsImportTransfer(settingsTransfer)
            MaintenanceDebtEntity.RECONCILE_KINDS.forEach { kind ->
                database.maintenanceDebtDao().enqueue(
                    kind,
                    MaintenanceDebtEntity.RECONCILE_IDENTITY,
                    at,
                )
            }
        }
        scheduleMaintenance()
        return settingsTransfer.transferId
    }

    // Internal data classes for parsing export files
    @Serializable
    private data class ExportChatEntity(
        val id: String,
        val title: String,
        val lastUpdated: Long,
        val selectedBranchesJson: String? = null,
        val systemPromptId: String? = null,
        val modelId: String? = null,
        val taskId: String? = null,
        val origin: String = "user",
        val graduated: Boolean = false,
        val selectedRunBranchesJson: String? = null,
        val draftText: String = "",
        val draftAttachments: String? = null,
        val conversationSettings: ConversationSettings? = null,
        /** v1-v3 compatibility only; never restored across devices. */
        val hasUnreadGeneration: Boolean = false,
    )

    @Serializable
    private data class ExportRunEntity(
        val id: String,
        val conversationId: String,
        val parentRunId: String? = null,
        val status: String = "COMPLETED",
        val startedAt: Long,
        val lastCheckpointAt: Long,
        val stopRequestedAt: Long? = null,
        val endedAt: Long? = null,
        val endReason: String? = null,
        val currentPass: Int = 0,
        val legacyAmbiguous: Boolean = false,
    )

    @Serializable
    private data class ExportTaskEntity(
        val id: String,
        val name: String,
        val prompt: String,
        val systemPrompt: String? = null,
        val systemPromptId: String? = null,
        val modelId: String? = null,
        val cronExpr: String,
        /** One-shot fire instant; null for a recurring (cron) task. */
        val runAt: Long? = null,
        /** Informational only; import always clears this device-local schedule epoch. */
        val nextRunAt: Long = 0L,
        val enabled: Boolean = true,
        val createdAt: Long,
        val lastRunAt: Long? = null
    )

    @Serializable
    private data class ExportLoopEntity(
        val conversationId: String,
        val intervalMs: Long,
        val prompt: String? = null,
        val nextFireAt: Long = 0L,
        val cycleCount: Int = 0,
        /** Nullable so an explicit null from an early v2 backup can be decoded and normalized. */
        val maxCycles: Int? = LoopPolicy.DEFAULT_MAX_CYCLES,
        val active: Boolean = true,
        val revision: Long = 0L
    )

    @Serializable
    private data class ExportMessageEntity(
        val id: String,
        val conversationId: String,
        val parentId: String? = null,
        val text: String,
        val images: List<String> = emptyList(),
        val thoughts: String? = null,
        val thoughtTitle: String? = null,
        val tokenCount: Int = 0,
        val inputTokenCount: Int? = null,
        val cachedInputTokenCount: Int? = null,
        val cacheWriteInputTokenCount: Int? = null,
        val uncachedInputTokenCount: Int? = null,
        val outputTokenCount: Int? = null,
        val reasoningTokenCount: Int? = null,
        val generationDurationMs: Long? = null,
        val status: String = "SUCCESS",
        val participant: String = "MODEL",
        val timestamp: Long,
        val thoughtTimeMs: Long? = null,
        val modelName: String? = null,
        val toolCallJson: String? = null,
        val attachmentMeta: String? = null,
        val runId: String? = null,
        val runSequence: Long? = null,
        val consumedAtPass: Int? = null,
    )

    private fun ExportRunEntity.toArchivedSnapshot() = ArchivedRunSnapshot(
        id = id,
        conversationId = conversationId,
        parentRunId = parentRunId,
        status = status,
        startedAt = startedAt,
        lastCheckpointAt = lastCheckpointAt,
        stopRequestedAt = stopRequestedAt,
        endedAt = endedAt,
        endReason = endReason,
        currentPass = currentPass,
        legacyAmbiguous = legacyAmbiguous,
    )
}
