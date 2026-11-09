package com.newoether.agora.data.local.migration

import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V18RegenerationEdgeRepairPlannerTest {
    @Test
    fun partiallyConvertedRun_reparentsEveryAdditionalAssistantSibling() {
        val runs = listOf(
            run("source"),
            run("regen", parentRunId = "source", legacyAmbiguous = true),
        )
        val messages = listOf(
            message("user", null, Participant.USER, "source", 0),
            message("original", "user", Participant.MODEL, "source", 1),
            message("regen-0", "user", Participant.MODEL, "regen", 0),
            message("regen-1", "deleted-clone", Participant.MODEL, "regen", 1),
            message("regen-2", "deleted-clone", Participant.MODEL, "regen", 2),
        )

        assertEquals(
            mapOf("regen-1" to "user", "regen-2" to "user"),
            V18RegenerationEdgeRepairPlanner.plan(runs, messages),
        )
    }

    @Test
    fun unrelatedHistoricalOrphans_areNotReparented() {
        val runs = listOf(
            run("source"),
            run("not-ambiguous", parentRunId = "source"),
            run("no-repaired-root", parentRunId = "source", legacyAmbiguous = true),
            run("standalone", legacyAmbiguous = true),
        )
        val messages = listOf(
            message("user", null, Participant.USER, "source", 0),
            message("ordinary-orphan", "missing", Participant.MODEL, "not-ambiguous", 0),
            message("ambiguous-orphan", "missing", Participant.MODEL, "no-repaired-root", 0),
            message("standalone-orphan", "missing", Participant.MODEL, "standalone", 0),
        )

        assertTrue(V18RegenerationEdgeRepairPlanner.plan(runs, messages).isEmpty())
    }

    @Test
    fun cleanConvertedRun_andSyntheticRows_needNoRepair() {
        val runs = listOf(
            run("source"),
            run("regen", parentRunId = "source", legacyAmbiguous = true),
        )
        val messages = listOf(
            message("user", null, Participant.USER, "source", 0),
            message("regen-0", "user", Participant.MODEL, "regen", 0),
            message("tool_missing", "deleted-clone", Participant.MODEL, "regen", 1),
        )

        assertTrue(V18RegenerationEdgeRepairPlanner.plan(runs, messages).isEmpty())
    }

    private fun run(
        id: String,
        parentRunId: String? = null,
        legacyAmbiguous: Boolean = false,
    ) = V18RunRecord(id, parentRunId, legacyAmbiguous)

    private fun message(
        id: String,
        parentId: String?,
        participant: Participant,
        runId: String,
        runSequence: Long,
    ) = V18MessageRecord(id, parentId, participant, runId, runSequence)
}
