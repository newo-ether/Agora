package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageGenerationBoundaryResolverTest {
    @Test
    fun usersAndDurableRunsSeparateGenerations() {
        val messages = listOf(
            message("u0", Participant.USER, runId = "run-a"),
            message("m0", Participant.MODEL, "u0", runId = "run-a"),
            message("m1", Participant.MODEL, "m0", runId = "run-a"),
            message("m2", Participant.MODEL, "m1", runId = "run-b"),
            message("u1", Participant.USER, "m2", runId = "run-c"),
            message("m3", Participant.MODEL, "u1", runId = "run-c"),
        )

        val boundaries = MessageGenerationBoundaryResolver.resolve(messages)

        assertEquals(3, boundaries.size)
        assertEquals("u0", boundaries[0].input?.id)
        assertEquals("m0", boundaries[0].firstAssistant?.id)
        assertEquals("m1", boundaries[0].lastAssistant?.id)
        assertNull(boundaries[1].input)
        assertEquals("m2", boundaries[1].firstAssistant?.id)
        assertEquals("m2", boundaries[1].lastAssistant?.id)
        assertEquals("u1", boundaries[2].input?.id)
        assertEquals("m3", boundaries[2].lastAssistant?.id)
    }

    @Test
    fun everyMessageTypeParticipatesInTheSameRunGroup() {
        val messages = listOf(
            message("u0", Participant.USER, runId = "run-a"),
            message("m0", Participant.MODEL, "u0", runId = "run-a"),
            message("${ModelConstants.TOOL_MSG_PREFIX}0", Participant.MODEL, "m0", runId = "run-a"),
            message(
                "${ModelConstants.RESULT_MSG_PREFIX}0",
                Participant.USER,
                "${ModelConstants.TOOL_MSG_PREFIX}0",
                runId = "run-a",
            ),
            message(
                "${ModelConstants.COMPACT_MSG_PREFIX}0",
                Participant.MODEL,
                "${ModelConstants.RESULT_MSG_PREFIX}0",
                runId = "run-a",
            ),
        )

        val boundary = MessageGenerationBoundaryResolver.resolve(messages).single()

        assertEquals(messages.map { it.id }, boundary.messages.map { it.id })
        assertEquals("u0", boundary.input?.id)
        assertEquals("m0", boundary.lastAssistant?.id)
    }

    @Test
    fun everyFreshRunCreatesAnIndependentGroupIncludingCompact() {
        val messages = listOf(
            message("u0", Participant.USER, runId = "run-a"),
            message("m0", Participant.MODEL, "u0", runId = "run-a"),
            message("${ModelConstants.COMPACT_MSG_PREFIX}0", Participant.MODEL, "m0", runId = "run-b"),
            message("m1", Participant.MODEL, "${ModelConstants.COMPACT_MSG_PREFIX}0", runId = "run-c"),
        )

        val boundaries = MessageGenerationBoundaryResolver.resolve(messages)

        assertEquals(
            listOf(listOf("u0", "m0"), listOf("${ModelConstants.COMPACT_MSG_PREFIX}0"), listOf("m1")),
            boundaries.map { boundary -> boundary.messages.map { it.id } },
        )
        assertEquals(
            "${ModelConstants.COMPACT_MSG_PREFIX}0",
            MessageGenerationBoundaryResolver.containing(
                messages,
                "${ModelConstants.COMPACT_MSG_PREFIX}0",
            )?.messages?.single()?.id,
        )
    }

    @Test
    fun protocolAndCompactRowsDoNotCreateOrReplaceBoundaries() {
        val messages = listOf(
            message("u0", Participant.USER),
            message("m0", Participant.MODEL, "u0"),
            message("${ModelConstants.TOOL_MSG_PREFIX}0", Participant.MODEL, "m0"),
            message("${ModelConstants.RESULT_MSG_PREFIX}0", Participant.USER, "${ModelConstants.TOOL_MSG_PREFIX}0"),
            message("${ModelConstants.COMPACT_MSG_PREFIX}0", Participant.MODEL, "${ModelConstants.RESULT_MSG_PREFIX}0"),
        )

        val boundary = MessageGenerationBoundaryResolver.resolve(messages).single()

        assertEquals("u0", boundary.input?.id)
        assertEquals("m0", boundary.firstAssistant?.id)
        assertEquals("m0", boundary.lastAssistant?.id)
    }

    private fun message(
        id: String,
        participant: Participant,
        parentId: String? = null,
        runId: String = "",
    ) = ChatMessage(
        id = id,
        parentId = parentId,
        text = id,
        participant = participant,
        runId = runId,
    )
}
