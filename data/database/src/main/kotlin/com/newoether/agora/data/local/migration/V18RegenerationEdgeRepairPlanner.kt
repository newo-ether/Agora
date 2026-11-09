package com.newoether.agora.data.local.migration

import com.newoether.agora.model.Participant
import com.newoether.agora.model.ModelConstants

internal data class V18RunRecord(
    val id: String,
    val parentRunId: String?,
    val legacyAmbiguous: Boolean,
)

internal data class V18MessageRecord(
    val id: String,
    val parentId: String?,
    val participant: Participant,
    val runId: String,
    val runSequence: Long,
)

/**
 * Repairs the partial result produced by the rejected first v17 -> v18 data migration.
 *
 * That migration already converted the Run to an output-only regeneration Run and reparented its
 * first assistant to the canonical user, but it could leave additional assistant siblings pointing
 * at the deleted cloned user. Requiring both markers keeps this forward repair narrow:
 *
 *  - the Run was explicitly marked ambiguous and attached below the canonical user's Run; and
 *  - the same Run already has an ordinary assistant rooted at that canonical user.
 *
 * Historical orphan edges outside that exact partial-conversion shape remain untouched.
 */
internal object V18RegenerationEdgeRepairPlanner {
    fun plan(
        runs: List<V18RunRecord>,
        messages: List<V18MessageRecord>,
    ): Map<String, String> {
        val runsById = runs.associateBy { it.id }
        val messagesByRun = messages.groupBy { it.runId }
        val messageIds = messages.mapTo(hashSetOf()) { it.id }
        val updates = linkedMapOf<String, String>()

        for (run in runs) {
            if (!run.legacyAmbiguous) continue
            val parentRunId = run.parentRunId ?: continue
            if (runsById[parentRunId] == null) continue

            val canonicalUser = messagesByRun[parentRunId]
                .orEmpty()
                .asSequence()
                .filter(::isOrdinary)
                .filter { it.participant == Participant.USER && it.runSequence == 0L }
                .minWithOrNull(messageOrder)
                ?: continue
            val runMessages = messagesByRun[run.id].orEmpty()
            val hasRepairedAssistantRoot = runMessages.any {
                isOrdinary(it) &&
                    it.participant == Participant.MODEL &&
                    it.parentId == canonicalUser.id
            }
            if (!hasRepairedAssistantRoot) continue

            runMessages
                .asSequence()
                .filter(::isOrdinary)
                .filter { it.participant == Participant.MODEL }
                .filter { it.parentId != null && it.parentId !in messageIds }
                .sortedWith(messageOrder)
                .forEach { updates[it.id] = canonicalUser.id }
        }
        return updates
    }

    private val messageOrder =
        compareBy<V18MessageRecord> { it.runSequence }
            .thenBy { it.id }

    private fun isOrdinary(message: V18MessageRecord): Boolean =
        !message.id.startsWith(ModelConstants.TOOL_MSG_PREFIX) &&
            !message.id.startsWith(ModelConstants.RESULT_MSG_PREFIX)
}
