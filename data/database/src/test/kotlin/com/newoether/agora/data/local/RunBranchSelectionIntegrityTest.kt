package com.newoether.agora.data.local

import com.newoether.agora.model.RunStatus
import com.newoether.agora.model.RunEndReason
import org.junit.Assert.assertEquals
import org.junit.Test

class RunBranchSelectionIntegrityTest {
    @Test
    fun retainsOnlyEdgesThatMatchTheDurableRunTree() {
        val runs = listOf(
            run("root", null),
            run("child", "root"),
            run("grandchild", "child"),
        )

        val repaired = RunBranchSelectionIntegrity.retainValidEdges(
            selections = linkedMapOf(
                null to "root",
                "root" to "child",
                "child" to "grandchild",
                "grandchild" to "grandchild",
                "missing-parent" to "root",
            ),
            runs = runs,
        )

        assertEquals(
            linkedMapOf(null to "root", "root" to "child", "child" to "grandchild"),
            repaired,
        )
    }

    @Test
    fun invalidRootSelectionAndMissingChildAreRemoved() {
        val repaired = RunBranchSelectionIntegrity.retainValidEdges(
            selections = linkedMapOf(null to "child", "root" to "missing"),
            runs = listOf(run("root", null), run("child", "root")),
        )

        assertEquals(emptyMap<String?, String>(), repaired)
    }

    private fun run(id: String, parentRunId: String?) = RunEntity(
        id = id,
        conversationId = "conversation",
        parentRunId = parentRunId,
        status = RunStatus.COMPLETED,
        activeSlot = null,
        startedAt = 1,
        lastCheckpointAt = 1,
        endedAt = 1,
        endReason = RunEndReason.MODEL_COMPLETED,
    )
}
