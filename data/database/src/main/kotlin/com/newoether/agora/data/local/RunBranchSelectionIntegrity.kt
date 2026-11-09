package com.newoether.agora.data.local

/**
 * Validates the durable parent-Run -> selected-child-Run projection against the Run tree.
 *
 * The map is only a selection projection; removing an invalid entry never deletes a Run or a
 * message. With no explicit selection, the existing deterministic branch fallback applies.
 */
internal object RunBranchSelectionIntegrity {
    fun retainValidEdges(
        selections: Map<String?, String>,
        runs: List<RunEntity>,
    ): Map<String?, String> {
        if (selections.isEmpty()) return emptyMap()
        val runsById = runs.associateBy(RunEntity::id)
        return selections.filterTo(linkedMapOf()) { (parentRunId, childRunId) ->
            val child = runsById[childRunId] ?: return@filterTo false
            when (parentRunId) {
                null -> child.parentRunId == null
                else -> parentRunId != childRunId &&
                    parentRunId in runsById &&
                    child.parentRunId == parentRunId
            }
        }
    }
}
