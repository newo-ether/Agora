package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.api.util.projectGenerationStatusesForApi
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.ProviderContextTopologySnapshot
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal data class DurableSelectedContextRequest(
    val conversationId: String,
    val anchorMessageId: String? = null,
    val followSelectedBranch: Boolean = false,
    val includeStoredTranscriptions: Boolean,
)

internal data class DurableSelectedContext(
    val messages: List<ChatMessage>,
    val entities: List<MessageEntity>,
)

internal class DurableContextLoadException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun selectedVisibleContextMessageIds(
    snapshot: ProviderContextTopologySnapshot,
): List<String> = ProviderContextTopologyPlanner.selectedVisibleMessageIds(snapshot)

/**
 * Resolves one canonical selected path from payload-free topology and materializes only that path.
 *
 * The repository keeps topology and payload reads inside one immutable Room transaction. This
 * reader owns no token policy: Provider preparation remains the only rollout authority.
 */
internal class DurableSelectedContextLoader(
    private val conversations: ConversationRepository,
    private val generationErrorFormatter: (String) -> String,
    private val appContext: Context?,
) {
    constructor(
        conversations: ConversationRepository,
        generationErrorFormatter: (String) -> String,
    ) : this(conversations, generationErrorFormatter, null)

    suspend fun load(request: DurableSelectedContextRequest): DurableSelectedContext {
        val rows = conversations.withProviderContextSnapshot {
            val snapshot = conversations.getProviderContextTopologySnapshot(request.conversationId)
                ?: return@withProviderContextSnapshot emptyList<InterruptedToolRounds.Row>()
            val plannedRows = ProviderContextTopologyPlanner.plan(
                snapshot = snapshot,
                anchorMessageId = request.anchorMessageId,
                followSelectedBranch = request.followSelectedBranch,
            )
            if (plannedRows.isEmpty()) {
                return@withProviderContextSnapshot emptyList<InterruptedToolRounds.Row>()
            }

            val ids = plannedRows.map { it.row.id }
            val fetched = conversations.getContextMessagesByIds(ids).associateBy(MessageEntity::id)
            if (fetched.size != ids.size) {
                throw DurableContextLoadException(
                    "Provider context changed while loading message rows",
                )
            }
            plannedRows.map { planned ->
                val topology = planned.row
                val entity = fetched[topology.id]
                    ?: throw DurableContextLoadException("A Provider context message disappeared")
                validateEntity(request.conversationId, topology, entity)
                InterruptedToolRounds.Row(
                    entity = entity.copy(
                        modelName = entity.modelName ?: planned.inheritedModelName,
                    ),
                    stripAggregateToolSegments = planned.stripAggregateToolSegments,
                )
            }
        }
        // Media reads happen only after the selected snapshot transaction has released its lock.
        val entities = InterruptedToolRounds.materialize(rows)
        val projected = projectProviderMessages(
            entities = entities,
            includeStoredTranscriptions = request.includeStoredTranscriptions,
        )
        val messages = appContext?.let { context ->
            withContext(Dispatchers.IO) {
                val hydration = ImageArtifactHydration(context)
                projected.map(hydration::hydrate)
            }
        } ?: projected
        return DurableSelectedContext(
            messages = projectGenerationStatusesForApi(messages, generationErrorFormatter),
            entities = entities,
        )
    }

    private fun validateEntity(
        conversationId: String,
        topology: MessageContextTopology,
        entity: MessageEntity,
    ) {
        if (
            entity.conversationId != conversationId ||
            entity.parentId != topology.parentId ||
            entity.status != topology.status ||
            entity.participant != topology.participant ||
            entity.timestamp != topology.timestamp ||
            entity.modelName != topology.modelName ||
            entity.runId != topology.runId ||
            entity.runSequence != topology.runSequence ||
            entity.consumedAtPass != topology.consumedAtPass
        ) {
            throw DurableContextLoadException(
                "Provider context topology changed while loading message rows",
            )
        }
    }
}

private object ProviderContextTopologyPlanner {
    fun selectedVisibleMessageIds(
        snapshot: ProviderContextTopologySnapshot,
    ): List<String> = selectedVisiblePath(snapshot).map(MessageContextTopology::id)

    fun plan(
        snapshot: ProviderContextTopologySnapshot,
        anchorMessageId: String?,
        followSelectedBranch: Boolean,
    ): List<ApiPathAssemblyRow<MessageContextTopology>> {
        val ancestorPath = if (followSelectedBranch) {
            selectedVisiblePath(snapshot)
        } else {
            explicitAncestorPath(snapshot.messages, anchorMessageId)
        }
        if (ancestorPath.isEmpty()) return emptyList()

        val boundaryIndex = ancestorPath.indexOfLast { row ->
            row.id.startsWith(Constants.COMPACT_MSG_PREFIX) &&
                row.status == MessageStatus.SUCCESS
        }
        return ApiPathAssembler.planTopology(
            ancestorPath = ancestorPath.drop(boundaryIndex.coerceAtLeast(0)),
            allMessages = snapshot.messages,
        )
    }

    private fun selectedVisiblePath(
        snapshot: ProviderContextTopologySnapshot,
    ): List<MessageContextTopology> {
        val selections = decodeSelections(snapshot.selectedBranchesJson)
        return try {
            resolveSelectedPath(
                allMessages = snapshot.messages,
                streamingMessage = null,
                selectedChildren = selections,
                idOf = MessageContextTopology::id,
                parentIdOf = MessageContextTopology::parentId,
                timestampOf = MessageContextTopology::timestamp,
                isSynthetic = { it.isProtocolRow() },
            )
        } catch (error: IllegalStateException) {
            throw DurableContextLoadException(
                "Selected Provider context contains a cycle",
                error,
            )
        }
    }

    private fun explicitAncestorPath(
        rows: List<MessageContextTopology>,
        anchorMessageId: String?,
    ): List<MessageContextTopology> {
        if (anchorMessageId == null) return emptyList()
        val byId = rows.associateBy(MessageContextTopology::id)
        val path = ArrayDeque<MessageContextTopology>()
        val visited = mutableSetOf<String>()
        var currentId: String? = anchorMessageId
        while (currentId != null) {
            if (!visited.add(currentId)) {
                throw DurableContextLoadException("Provider context ancestry contains a cycle")
            }
            val row = byId[currentId]
                ?: throw DurableContextLoadException("Provider context ancestry is incomplete")
            path.addFirst(row)
            currentId = row.parentId
        }
        return path.toList()
    }

    private fun decodeSelections(raw: String?): Map<String?, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            Json.decodeFromString<Map<String, String>>(raw)
                .mapKeys { (key, _) -> key.takeUnless { it == "null" } }
        }.getOrDefault(emptyMap())
    }

    private fun MessageContextTopology.isProtocolRow(): Boolean =
        id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            id.startsWith(Constants.RESULT_MSG_PREFIX)
}
