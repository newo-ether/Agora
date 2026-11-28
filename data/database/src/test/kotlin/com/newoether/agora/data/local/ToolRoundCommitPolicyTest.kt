package com.newoether.agora.data.local

import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolRoundCommitPolicyTest {
    @Test
    fun `insert requires exact active slot and pass`() {
        val active = run()

        assertEquals(true, ToolRoundCommitPolicy.canInsert(active, "run", expectedPass = 2))
        assertEquals(false, ToolRoundCommitPolicy.canInsert(active, "run", expectedPass = 1))
        assertEquals(false, ToolRoundCommitPolicy.canInsert(active, "other", expectedPass = 2))
        assertEquals(
            false,
            ToolRoundCommitPolicy.canInsert(
                active.copy(
                    status = RunStatus.STOPPED,
                    activeSlot = null,
                    endedAt = 20,
                    endReason = RunEndReason.USER_STOPPED,
                ),
                "run",
                expectedPass = 2,
            ),
        )
    }

    @Test
    fun `valid complete round has one assistant request and every sibling result`() {
        val round = round()

        assertEquals("run", ToolRoundCommitPolicy.requireValidShape(round))
        assertNull(ToolRoundCommitPolicy.resolveExactReplay(round, emptyList()))
    }

    @Test
    fun `exact replay is idempotent regardless of query order and assigned sequence`() {
        val proposed = round()
        val durable = proposed.mapIndexed { index, message ->
            message.copy(runSequence = 20L + index)
        }.reversed()

        val resolved = ToolRoundCommitPolicy.resolveExactReplay(proposed, durable)

        assertEquals(proposed.map { it.id }, resolved!!.map { it.id })
        assertEquals(listOf(20L, 21L, 22L), resolved.map { it.runSequence })
    }

    @Test
    fun `partial or conflicting replay fails closed`() {
        val proposed = round()

        assertThrows(IllegalStateException::class.java) {
            ToolRoundCommitPolicy.resolveExactReplay(proposed, listOf(proposed.first()))
        }
        assertThrows(IllegalStateException::class.java) {
            ToolRoundCommitPolicy.resolveExactReplay(
                proposed,
                proposed.mapIndexed { index, message ->
                    if (index == 1) message.copy(text = "different") else message
                },
            )
        }
    }

    @Test
    fun `incomplete result metadata and cross Run rows are rejected`() {
        val proposed = round()

        assertThrows(IllegalArgumentException::class.java) {
            ToolRoundCommitPolicy.requireValidShape(
                proposed.mapIndexed { index, message ->
                    if (index == 1) message.copy(toolCallJson = null) else message
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolRoundCommitPolicy.requireValidShape(
                proposed.mapIndexed { index, message ->
                    if (index == 2) message.copy(runId = "other-run") else message
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolRoundCommitPolicy.requireValidShape(proposed.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolRoundCommitPolicy.requireValidShape(
                proposed.mapIndexed { index, message ->
                    if (index == 2) {
                        message.copy(
                            toolCallJson =
                                "[{\"type\":\"tool\",\"toolCallId\":\"call-1\",\"toolResult\":\"second\"}]",
                        )
                    } else {
                        message
                    }
                },
            )
        }
    }

    private fun round(): List<MessageEntity> {
        val tool = MessageEntity(
            id = "tool",
            conversationId = "conversation",
            parentId = "model",
            text = "",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = 1,
            toolCallJson = """
                [
                  {"type":"tool","toolName":"first","toolCallId":"call-1"},
                  {"type":"tool","toolName":"second","toolCallId":"call-2"}
                ]
            """.trimIndent(),
            runId = "run",
        )
        return listOf(
            tool,
            MessageEntity(
                id = "result-1",
                conversationId = "conversation",
                parentId = tool.id,
                text = "first",
                status = MessageStatus.SUCCESS,
                participant = Participant.USER,
                timestamp = 2,
                toolCallJson =
                    "[{\"type\":\"tool\",\"toolCallId\":\"call-1\",\"toolResult\":\"first\"}]",
                runId = "run",
            ),
            MessageEntity(
                id = "result-2",
                conversationId = "conversation",
                parentId = tool.id,
                text = "second",
                status = MessageStatus.SUCCESS,
                participant = Participant.USER,
                timestamp = 3,
                toolCallJson =
                    "[{\"type\":\"tool\",\"toolCallId\":\"call-2\",\"toolResult\":\"second\"}]",
                runId = "run",
            ),
        )
    }

    private fun run() = RunEntity(
        id = "run",
        conversationId = "conversation",
        parentRunId = null,
        status = RunStatus.ACTIVE,
        activeSlot = 1,
        startedAt = 1,
        lastCheckpointAt = 2,
        currentPass = 2,
    )
}
