package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.remapToolRoundReferences
import com.newoether.agora.data.repository.toStreamCheckpoint
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ToolRoundReferencePersistenceTest {
    private val continuation = Json.parseToJsonElement(
        """{"type":"reasoning","encrypted_content":"${"x".repeat(128)}"}""",
    ).jsonObject

    private fun tool(index: Int) = MessageSegment(
        type = "tool", toolName = "file_read", toolCallId = "call-$index", toolArgs = "{}",
        toolResult = "r".repeat(1900), toolStructuredResult = "s".repeat(1900),
        toolState = ToolExecutionStates.SUCCEEDED,
    )

    private fun root(segments: List<MessageSegment>, status: MessageStatus = MessageStatus.SENDING) =
        ChatMessage(id = "root", text = "", participant = Participant.MODEL,
            status = status, runId = "run", segments = segments)

    private fun row(id: String, segments: List<MessageSegment>, parentId: String? = "root") = MessageEntity(
        id = id, conversationId = "conversation", parentId = parentId, text = "",
        participant = Participant.MODEL, status = MessageStatus.SUCCESS, timestamp = 1,
        runId = "run", toolCallJson = Json.encodeToString(segments),
    )

    @Test
    fun `many small committed bodies cannot consume active continuation budget`() {
        val historical = (0 until 157).map(::tool)
        val current = tool(157).copy(toolResult = null, toolStructuredResult = null,
            responseOutputItems = listOf(continuation), responseOutputItemProvider = "provider")
        assertTrue(runCatching {
            MessagePersistenceGuard.encodeSegmentsBounded(historical + current)
        }.isFailure)
        val referenced = historical.mapIndexed { index, segment ->
            segment.copy(toolRoundId = "${Constants.TOOL_MSG_PREFIX}$index")
        }
        val checkpoint = root(referenced + current).toStreamCheckpoint()
        assertTrue(checkpoint.toolCallJson!!.toByteArray().size < 30_000)
        val stored = Json.decodeFromString<List<MessageSegment>>(checkpoint.toolCallJson!!)
        assertTrue(stored.dropLast(1).all { it.toolArgs == null && it.toolResult == null })
        assertEquals(current, stored.last())
        val rounds = historical.mapIndexed { index, segment ->
            row("${Constants.TOOL_MSG_PREFIX}$index", listOf(segment))
        }
        val reloaded = row("root", stored).copy(status = MessageStatus.SENDING)
            .toUiChatMessage(rounds) { it }
        assertEquals(referenced + current, reloaded.segments)
        assertEquals(checkpoint.toolCallJson, reloaded.toStreamCheckpoint().toolCallJson)
    }

    @Test
    fun `references preserve interleaved order and stop only unfinished tools`() {
        val completed = tool(0)
        val roundId = "${Constants.TOOL_MSG_PREFIX}first"
        val active = tool(1).copy(toolResult = null, toolState = ToolExecutionStates.RUNNING)
        val timeline = listOf(MessageSegment("thought", "before"),
            completed.copy(toolRoundId = roundId), MessageSegment("answer", "between"), active,
            MessageSegment("error", "failure"))
        for (status in listOf(MessageStatus.STOPPED, MessageStatus.ERROR, MessageStatus.SUCCESS)) {
            val checkpoint = root(timeline, status).toStreamCheckpoint()
            val entity = row("root", emptyList()).copy(status = status, toolCallJson = checkpoint.toolCallJson)
            val projected = entity.toUiChatMessage(listOf(row(roundId, listOf(completed)))) { it }
            assertEquals(timeline.map { it.type }, projected.segments!!.map { it.type })
            assertEquals(completed.copy(toolRoundId = roundId), projected.segments!![1])
            assertEquals(if (status == MessageStatus.STOPPED) ToolExecutionStates.STOPPED else
                ToolExecutionStates.RUNNING, projected.segments!![3].toolState)
        }
    }

    @Test
    fun `reference resolution keeps the persisted segment when the durable body is unusable`() {
        // Refused Compact rounds never persist, so their references dangle forever; hydration
        // must degrade to the persisted segment instead of failing (cold-start crash 2026-11-23).
        val id = "${Constants.TOOL_MSG_PREFIX}round"
        val reference = MessageSegment("tool", toolCallId = "call-0", toolRoundId = id,
            toolName = "file_read", toolArgs = "{}")
        val entity = row("root", listOf(reference))
        val valid = row(id, listOf(tool(0)))
        for (rounds in listOf(emptyList(), listOf(valid.copy(runId = "other")),
            listOf(valid.copy(conversationId = "other")),
            listOf(valid.copy(status = MessageStatus.ERROR)),
            listOf(valid.copy(toolCallJson = Json.encodeToString(listOf(tool(0), tool(0))))))) {
            val projected = entity.toUiChatMessage(rounds) { it }
            assertEquals(listOf(reference), projected.segments)
        }
    }

    @Test
    fun `fork remaps only round identity and archive media transport keeps reference`() {
        val id = "${Constants.TOOL_MSG_PREFIX}round"
        val ref = MessageSegment("tool", toolCallId = "call-0", toolRoundId = id)
        val raw = Json.encodeToString(listOf(ref))
        val remapped = remapToolRoundReferences(raw, mapOf(id to "${id}-fork"))!!
        assertEquals(listOf(ref.copy(toolRoundId = "${id}-fork")),
            Json.decodeFromString<List<MessageSegment>>(remapped))
        // A round without a forked counterpart loses its reference but keeps its inline data.
        val dropped = remapToolRoundReferences(raw, emptyMap())!!
        assertEquals(listOf(ref.copy(toolRoundId = null)),
            Json.decodeFromString<List<MessageSegment>>(dropped))
        val exported = com.newoether.agora.data.NativeBackupMediaPolicy.rewriteToolImagePathsForExport(
            raw, { null },
        )
        val restored = com.newoether.agora.data.NativeBackupMediaPolicy.restoreToolImagePaths(
            exported, 5, { null },
        )
        assertEquals(listOf(ref), Json.decodeFromString<List<MessageSegment>>(restored!!))
    }

    @Test
    fun `provider replay uses durable continuation exactly once and pairs interrupted calls`() {
        val roundId = "${Constants.TOOL_MSG_PREFIX}round"
        val completed = tool(0).copy(responseOutputItems = listOf(continuation),
            responseOutputItemProvider = "provider")
        val round = row(roundId, listOf(completed))
        val result = row("${Constants.RESULT_MSG_PREFIX}round", listOf(tool(0)), roundId)
            .copy(participant = Participant.USER, text = "done")
        val pending = tool(1).copy(toolResult = null, toolState = ToolExecutionStates.RUNNING)
        val checkpoint = root(listOf(tool(0).copy(toolRoundId = roundId), pending),
            MessageStatus.STOPPED).toStreamCheckpoint()
        val entity = row("root", emptyList(), null).copy(status = MessageStatus.STOPPED,
            toolCallJson = checkpoint.toolCallJson)
        val messages = projectProviderMessages(
            ApiPathAssembler.assemble(listOf(entity), listOf(entity, round, result)), false,
        )
        assertEquals(1, messages.count { it.id == roundId })
        assertEquals(1, messages.flatMap { it.segments.orEmpty() }
            .sumOf { it.responseOutputItems.size })
        assertTrue(messages.last().segments.orEmpty().none { it.type == "tool" })
        val interrupted = messages.single { it.id.startsWith("${Constants.TOOL_MSG_PREFIX}interrupted-") }
        assertEquals("call-1", interrupted.toolCall?.toolCallId)
        assertEquals(InterruptedToolRounds.STOPPED_RESULT, interrupted.toolCall?.result)
        assertEquals(listOf(continuation), messages.single { it.id == roundId }
            .segments!!.single().responseOutputItems)
    }

    @Test
    fun `process recovery preserves reference identities and restores committed terminal tools`() {
        val id = "${Constants.TOOL_MSG_PREFIX}round"
        val checkpoint = root(listOf(tool(0).copy(toolRoundId = id),
            tool(1).copy(toolResult = null, toolState = ToolExecutionStates.RUNNING))).toStreamCheckpoint()
        val recoveredJson = com.newoether.agora.model.RunRecoveryPolicy
            .stopIncompleteToolsJson(checkpoint.toolCallJson!!)!!
        val entity = row("root", emptyList()).copy(toolCallJson = recoveredJson,
            status = MessageStatus.STOPPED)
        val projected = entity.toUiChatMessage(listOf(row(id, listOf(tool(0))))) { it }
        assertEquals(id, projected.segments!![0].toolRoundId)
        assertEquals(ToolExecutionStates.SUCCEEDED, projected.segments!![0].toolState)
        assertEquals(ToolExecutionStates.STOPPED, projected.segments!![1].toolState)
    }

    @Test
    fun `reference overflow fails explicitly rather than losing timeline`() {
        val refs = (0 until 20).map {
            MessageSegment("tool", toolCallId = "call-$it", toolRoundId = "tool_$it")
        }
        assertTrue(runCatching { MessagePersistenceGuard.encodeSegmentsBounded(refs, 100) }.isFailure)
    }
}
