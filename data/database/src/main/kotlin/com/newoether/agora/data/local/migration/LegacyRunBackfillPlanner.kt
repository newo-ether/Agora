package com.newoether.agora.data.local.migration

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import java.nio.charset.StandardCharsets
import java.util.PriorityQueue
import java.util.UUID

/**
 * Minimal v16 message data needed to plan the v17 Run backfill.
 *
 * The planner never changes a legacy message. Synthetic tool/result rows are identified by their
 * persisted ID convention unless the caller supplies the flag explicitly.
 */
data class LegacyMessageRecord(
    val id: String,
    val parentId: String?,
    val participant: Participant,
    val status: MessageStatus,
    val timestamp: Long,
    val isSynthetic: Boolean = id.startsWith("tool_") || id.startsWith("result_"),
)

data class PlannedLegacyRun(
    val id: String,
    val conversationId: String,
    val parentRunId: String?,
    val boundaryMessageId: String?,
    val status: RunStatus,
    val endReason: RunEndReason,
    val startedAt: Long,
    val endedAt: Long,
    val legacyAmbiguous: Boolean,
)

data class PlannedMessageAssignment(
    val messageId: String,
    val runId: String,
    val runSequence: Long,
    /** Set only for visible user input. Model and synthetic tool/result rows keep this null. */
    val consumedAtPass: Int?,
)

data class LegacyRunBackfillPlan(
    val runs: List<PlannedLegacyRun>,
    val assignments: List<PlannedMessageAssignment>,
)

/**
 * Converts a legacy message forest into a conservative, deterministic Run plan.
 *
 * Every visible user message begins a Run. Model and synthetic tool/result descendants remain in
 * that Run until another visible user boundary is reached. Historical model siblings therefore
 * remain losslessly grouped and are marked ambiguous rather than being split by cloning their
 * shared input.
 */
object LegacyRunBackfillPlanner {
    private val messageOrder = compareBy<LegacyMessageRecord> { it.timestamp }.thenBy { it.id }

    fun plan(
        conversationId: String,
        messages: List<LegacyMessageRecord>,
    ): LegacyRunBackfillPlan {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }

        val recordsById = messages.associateBy { it.id }
        require(recordsById.size == messages.size) { "Legacy message IDs must be unique" }
        if (messages.isEmpty()) return LegacyRunBackfillPlan(emptyList(), emptyList())

        val childrenByParent = messages
            .filter { it.parentId in recordsById }
            .groupBy { checkNotNull(it.parentId) }
        val orderedMessages = stableTopologyOrder(messages, recordsById, childrenByParent)
        val runBuilders = linkedMapOf<String, MutableRun>()
        val assignmentsByMessage = mutableMapOf<String, PlannedMessageAssignment>()
        val nextSequenceByRun = mutableMapOf<String, Long>()

        for (message in orderedMessages) {
            val parentAssignment = message.parentId?.let(assignmentsByMessage::get)
            val startsRun = message.participant == Participant.USER && !message.isSynthetic
            val runId = when {
                startsRun -> deterministicRunId(conversationId, "user", message.id)
                parentAssignment != null -> parentAssignment.runId
                else -> deterministicRunId(conversationId, "orphan", message.id)
            }

            val builder = runBuilders.getOrPut(runId) {
                MutableRun(
                    id = runId,
                    conversationId = conversationId,
                    parentRunId = if (startsRun) parentAssignment?.runId else null,
                    boundaryMessageId = message.id.takeIf { startsRun },
                    legacyAmbiguous = !startsRun ||
                        (message.parentId != null && message.parentId !in recordsById),
                )
            }
            builder.messages += message

            val sequence = nextSequenceByRun.getOrDefault(runId, 0L)
            assignmentsByMessage[message.id] = PlannedMessageAssignment(
                messageId = message.id,
                runId = runId,
                runSequence = sequence,
                consumedAtPass = 0.takeIf {
                    message.participant == Participant.USER && !message.isSynthetic
                },
            )
            nextSequenceByRun[runId] = sequence + 1
        }

        // Multiple visible children in one Run represent historical executions that share a
        // boundary message. Splitting them would require cloning that boundary and its media.
        for (children in childrenByParent.values) {
            children.asSequence()
                .filterNot { it.isSynthetic }
                .groupBy { checkNotNull(assignmentsByMessage[it.id]).runId }
                .filterValues { it.size > 1 }
                .keys
                .forEach { runId -> checkNotNull(runBuilders[runId]).legacyAmbiguous = true }
        }

        val runs = runBuilders.values.map { it.toPlannedRun() }
        val assignments = orderedMessages.map { checkNotNull(assignmentsByMessage[it.id]) }
        check(assignments.size == messages.size)
        check(assignments.all { it.runId in runBuilders })

        return LegacyRunBackfillPlan(runs = runs, assignments = assignments)
    }

    /**
     * Converts legacy message-level selections into Run-level selections without losing the
     * selected path inside an ambiguous legacy Run.
     *
     * A selection is derived independently for the root and for every Run boundary, so switching
     * to an alternate parent Run later still restores that branch's previously selected child.
     */
    fun selectedRunBranches(
        messages: List<LegacyMessageRecord>,
        plan: LegacyRunBackfillPlan,
        selectedMessageBranches: Map<String?, String>,
    ): Map<String?, String> {
        if (messages.isEmpty() || plan.runs.isEmpty()) return emptyMap()

        val assignmentByMessage = plan.assignments.associateBy { it.messageId }
        require(messages.all { it.id in assignmentByMessage }) {
            "Every legacy message must have a planned Run assignment"
        }
        val childrenByParent = messages.groupBy { it.parentId }
        val selectedRuns = linkedMapOf<String?, String>()

        chooseChild(childrenByParent[null].orEmpty(), selectedMessageBranches[null])
            ?.let { root -> selectedRuns[null] = assignmentByMessage.getValue(root.id).runId }

        for (run in plan.runs) {
            var cursor = run.boundaryMessageId ?: continue
            val visited = mutableSetOf<String>()
            while (visited.add(cursor)) {
                val child = chooseChild(
                    childrenByParent[cursor].orEmpty(),
                    selectedMessageBranches[cursor],
                ) ?: break
                val childRunId = assignmentByMessage.getValue(child.id).runId
                if (childRunId != run.id) {
                    selectedRuns[run.id] = childRunId
                    break
                }
                cursor = child.id
            }
        }

        return selectedRuns
    }

    private fun stableTopologyOrder(
        messages: List<LegacyMessageRecord>,
        recordsById: Map<String, LegacyMessageRecord>,
        childrenByParent: Map<String, List<LegacyMessageRecord>>,
    ): List<LegacyMessageRecord> {
        val remainingParents = messages.associate { message ->
            message.id to if (message.parentId in recordsById) 1 else 0
        }.toMutableMap()
        val ready = PriorityQueue(messageOrder)
        messages.filterTo(ready) { remainingParents.getValue(it.id) == 0 }
        val ordered = ArrayList<LegacyMessageRecord>(messages.size)
        val visited = mutableSetOf<String>()

        while (ready.isNotEmpty()) {
            val message = ready.remove()
            if (!visited.add(message.id)) continue
            ordered += message
            for (child in childrenByParent[message.id].orEmpty()) {
                val remaining = remainingParents.getValue(child.id) - 1
                remainingParents[child.id] = remaining
                if (remaining == 0) ready += child
            }
        }

        // Corrupt parent cycles have no topological root. Retain every row deterministically and
        // let the first cycle member seed an ambiguous orphan Run rather than dropping data.
        messages.asSequence()
            .filterNot { it.id in visited }
            .sortedWith(messageOrder)
            .forEach(ordered::add)

        return ordered
    }

    private fun deterministicRunId(
        conversationId: String,
        kind: String,
        boundaryId: String,
    ): String = UUID.nameUUIDFromBytes(
        "legacy-run:$conversationId:$kind:$boundaryId"
            .toByteArray(StandardCharsets.UTF_8)
    ).toString()

    private fun chooseChild(
        children: List<LegacyMessageRecord>,
        selectedId: String?,
    ): LegacyMessageRecord? {
        if (children.isEmpty()) return null
        val ordered = children.sortedWith(messageOrder)
        val visible = ordered.filterNot { it.isSynthetic }
        val candidates = visible.ifEmpty { ordered }
        return candidates.firstOrNull { it.id == selectedId } ?: candidates.last()
    }

    private data class MutableRun(
        val id: String,
        val conversationId: String,
        val parentRunId: String?,
        val boundaryMessageId: String?,
        var legacyAmbiguous: Boolean,
        val messages: MutableList<LegacyMessageRecord> = mutableListOf(),
    ) {
        fun toPlannedRun(): PlannedLegacyRun {
            val statusAndReason = when {
                messages.any {
                    it.participant == Participant.ERROR || it.status == MessageStatus.ERROR
                } -> RunStatus.FAILED to RunEndReason.PROVIDER_ERROR

                messages.any {
                    it.status == MessageStatus.STOPPED ||
                        it.status == MessageStatus.TRANSCRIBING ||
                        it.status == MessageStatus.SENDING ||
                        it.status == MessageStatus.THINKING ||
                        it.status == MessageStatus.TOOL_CALLING
                } -> RunStatus.STOPPED to RunEndReason.PROCESS_RECOVERED

                else -> RunStatus.COMPLETED to RunEndReason.MODEL_COMPLETED
            }
            return PlannedLegacyRun(
                id = id,
                conversationId = conversationId,
                parentRunId = parentRunId,
                boundaryMessageId = boundaryMessageId,
                status = statusAndReason.first,
                endReason = statusAndReason.second,
                startedAt = messages.minOf { it.timestamp },
                endedAt = messages.maxOf { it.timestamp },
                legacyAmbiguous = legacyAmbiguous,
            )
        }
    }
}
