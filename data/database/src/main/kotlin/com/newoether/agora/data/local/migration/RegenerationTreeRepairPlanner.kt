package com.newoether.agora.data.local.migration

import com.newoether.agora.model.Participant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

data class V17RunRecord(
    val id: String,
    val parentRunId: String?,
    val startedAt: Long,
)

data class V17MessageRecord(
    val id: String,
    val parentId: String?,
    val participant: Participant,
    val timestamp: Long,
    val runId: String,
    val runSequence: Long,
    val inputFingerprint: String,
)

data class RegenerationTreeRepairPlan(
    val runParentUpdates: Map<String, String>,
    val deletedMessageIds: Set<String>,
    val messageParentUpdates: Map<String, String>,
    val runSequenceUpdates: Map<String, Long>,
    val messageSelections: Map<String?, String>,
    val runSelections: Map<String?, String>,
    val inferredRunIds: Set<String>,
)

/**
 * Repairs the v17 shape where Regenerate and Edit were both persisted as sibling USER inputs.
 *
 * v17 did not store the initiating operation, so an exact repeated boundary payload is the only
 * durable discriminator available: the earliest sibling is the shared user anchor; later sibling
 * Runs with the same semantic input are inferred regenerations. Their leading cloned user inputs
 * are removed, their root assistant is attached to the shared user, and their Run becomes a child
 * of the anchor-owning Run. Different payloads remain genuine Edit branches.
 */
object RegenerationTreeRepairPlanner {
    fun plan(
        runs: List<V17RunRecord>,
        messages: List<V17MessageRecord>,
        messageSelections: Map<String?, String>,
        runSelections: Map<String?, String>,
    ): RegenerationTreeRepairPlan {
        val runsById = runs.associateBy { it.id }
        val messagesByRun = messages.groupBy { it.runId }
        val boundaryByRun = messagesByRun.mapNotNull { (runId, runMessages) ->
            runMessages
                .asSequence()
                .filter(::isOrdinaryUser)
                .filter { it.runSequence == 0L }
                .minWithOrNull(messageOrder)
                ?.let { runId to it }
        }.toMap()
        val rootOutputByRun = messagesByRun.mapNotNull { (runId, runMessages) ->
            runMessages
                .asSequence()
                .filter(::isOrdinaryModel)
                .minWithOrNull(messageOrder)
                ?.let { runId to it }
        }.toMap()

        data class BoundaryGroupKey(
            val parentRunId: String?,
            val parentMessageId: String?,
            val fingerprint: String,
        )

        val groups = boundaryByRun.entries.groupBy { (runId, boundary) ->
            BoundaryGroupKey(
                parentRunId = runsById[runId]?.parentRunId,
                parentMessageId = boundary.parentId,
                fingerprint = boundary.inputFingerprint,
            )
        }

        val runParentUpdates = linkedMapOf<String, String>()
        val deletedMessageIds = linkedSetOf<String>()
        val messageParentUpdates = linkedMapOf<String, String>()
        val runSequenceUpdates = linkedMapOf<String, Long>()
        val inferredRunIds = linkedSetOf<String>()
        val repairedMessageSelections = messageSelections.toMutableMap()
        val repairedRunSelections = runSelections.toMutableMap()

        data class InferredRepair(
            val runId: String,
            val selectedOutputId: String,
            val removedIds: Set<String>,
        )

        for ((_, entries) in groups) {
            if (entries.size < 2) continue
            val ordered = entries.sortedWith(
                compareBy<Map.Entry<String, V17MessageRecord>> {
                    runsById[it.key]?.startedAt ?: Long.MAX_VALUE
                }
                    .thenBy { it.value.timestamp }
                    .thenBy { it.key }
            )
            val (anchorRunId, anchorInput) = ordered.first().toPair()
            val anchorRun = runsById[anchorRunId] ?: continue
            val inferred = ordered.drop(1).mapNotNull { entry ->
                val inferredRunId = entry.key
                val rootOutput = rootOutputByRun[inferredRunId] ?: return@mapNotNull null
                val runMessages = messagesByRun[inferredRunId].orEmpty().sortedWith(messageOrder)
                val leadingInputs = runMessages.filter {
                    it.runSequence < rootOutput.runSequence && isOrdinaryUser(it)
                }
                if (leadingInputs.isEmpty()) return@mapNotNull null

                val removedIds = leadingInputs.mapTo(linkedSetOf()) { it.id }
                deletedMessageIds += removedIds
                val retainedMessages = runMessages.filter { it.id !in removedIds }
                val reparentedMessages = retainedMessages.filter {
                    it.parentId != null && it.parentId in removedIds
                }
                reparentedMessages.forEach { message ->
                    messageParentUpdates[message.id] = anchorInput.id
                }
                runParentUpdates[inferredRunId] = anchorRunId
                inferredRunIds += inferredRunId

                retainedMessages
                    .sortedWith(messageOrder)
                    .forEachIndexed { index, message ->
                        runSequenceUpdates[message.id] = index.toLong()
                    }

                val selectedOutputId = leadingInputs
                    .asReversed()
                    .mapNotNull { input -> messageSelections[input.id] }
                    .firstOrNull { selectedId ->
                        retainedMessages.any { it.id == selectedId }
                    }
                    ?: reparentedMessages.maxWithOrNull(messageOrder)?.id
                    ?: rootOutput.id
                InferredRepair(inferredRunId, selectedOutputId, removedIds)
            }
            if (inferred.isEmpty()) continue

            val selectedBoundaryId = repairedMessageSelections[anchorInput.parentId]
            val selectedByMessage = inferred.firstOrNull { repair ->
                selectedBoundaryId != null && selectedBoundaryId in repair.removedIds
            }
            val selectedByRunId = repairedRunSelections[anchorRun.parentRunId]
            val selectedByRun = inferred.firstOrNull { it.runId == selectedByRunId }
            val selected = selectedByMessage ?: selectedByRun

            for (removedId in inferred.flatMap { it.removedIds }) {
                repairedMessageSelections.remove(removedId)
            }
            repairedMessageSelections.entries.removeAll { (parentId, childId) ->
                (parentId != null && parentId in deletedMessageIds) ||
                    childId in deletedMessageIds
            }

            if (selected != null) {
                repairedMessageSelections[anchorInput.parentId] = anchorInput.id
                repairedMessageSelections[anchorInput.id] = selected.selectedOutputId
                repairedRunSelections[anchorRun.parentRunId] = anchorRunId
                repairedRunSelections[anchorRunId] = selected.runId
            } else if (repairedMessageSelections[anchorInput.parentId] == anchorInput.id) {
                rootOutputByRun[anchorRunId]?.let { originalOutput ->
                    repairedMessageSelections[anchorInput.id] = originalOutput.id
                    // The original output remains selected inside anchorRunId itself. There is no
                    // child Run to select here. Persisting anchorRunId -> anchorRunId creates a
                    // self-edge that cannot exist in the Run tree and later destabilizes branch
                    // restore/delete/fork operations.
                    repairedRunSelections.remove(anchorRunId)
                }
            }
        }

        return RegenerationTreeRepairPlan(
            runParentUpdates = runParentUpdates,
            deletedMessageIds = deletedMessageIds,
            messageParentUpdates = messageParentUpdates,
            runSequenceUpdates = runSequenceUpdates,
            messageSelections = repairedMessageSelections,
            runSelections = repairedRunSelections,
            inferredRunIds = inferredRunIds,
        )
    }

    private val messageOrder =
        compareBy<V17MessageRecord> { it.runSequence }
            .thenBy { it.timestamp }
            .thenBy { it.id }

    private fun isOrdinaryUser(message: V17MessageRecord): Boolean =
        message.participant == Participant.USER && !isSynthetic(message)

    private fun isOrdinaryModel(message: V17MessageRecord): Boolean =
        message.participant == Participant.MODEL && !isSynthetic(message)

    private fun isSynthetic(message: V17MessageRecord): Boolean =
        message.id.startsWith("tool_") || message.id.startsWith("result_")
}

fun regenerationInputFingerprint(
    text: String,
    imageCount: Int,
    attachmentMeta: String?,
): String {
    val normalizedAttachment = if (attachmentMeta.isNullOrBlank()) {
        ""
    } else {
        runCatching {
            sanitizeAttachmentElement(Json.parseToJsonElement(attachmentMeta)).toString()
        }.getOrDefault(attachmentMeta)
    }
    val bytes = "$text\u0000$imageCount\u0000$normalizedAttachment".toByteArray()
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun sanitizeAttachmentElement(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element
            .filterKeys { it != "originalUri" }
            .mapValues { (_, value) -> sanitizeAttachmentElement(value) }
    )
    is JsonArray -> JsonArray(element.map(::sanitizeAttachmentElement))
    else -> element
}
