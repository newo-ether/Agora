package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Gives every client tool call of an ended generation a result in the Provider path.
 *
 * A tool round reaches Room as a tool/result row pair only after its tools return. When the user
 * stops a generation, the process dies, or the provider fails while a call is outstanding, that
 * pair is never written; the call survives only as a segment of the visible model row. Without a
 * pair the model never learns it asked the question, so a later reply to it arrives out of nowhere.
 *
 * This fills the gap at read time instead of at stop time: the stop path does not have to race
 * the tool it is cancelling, rows interrupted before this existed are covered too, and nothing is
 * written back. The synthesised rows use the protocol id prefixes, so every Provider converter
 * treats them exactly like persisted rounds.
 */
internal object InterruptedToolRounds {
    private const val SYNTHETIC_MARKER = "interrupted-"

    internal const val STOPPED_RESULT =
        "Interrupted: the user stopped the generation before this tool returned. " +
            "The call did not complete and no result is available."
    internal const val FAILED_RESULT =
        "Interrupted: the generation ended with an error before this tool returned. " +
            "The call did not complete and no result is available."

    /**
     * One row of an assembled Provider path, before its aggregate tool segments are resolved.
     *
     * [stripAggregateToolSegments] is the assembler's decision that the row's tool segments are
     * already represented by persisted protocol rows.
     */
    data class Row(
        val entity: MessageEntity,
        val stripAggregateToolSegments: Boolean,
    )

    /**
     * Materialises [rows] into Provider-path entities, inserting a synthetic tool/result pair in
     * front of each ended model row whose tool calls have no persisted pair.
     */
    fun materialize(rows: List<Row>): List<MessageEntity> {
        val persistedCalls = rows.asSequence()
            .map(Row::entity)
            .filter { it.id.startsWith(Constants.TOOL_MSG_PREFIX) }
            .flatMap { decodeSegments(it.toolCallJson).orEmpty().asSequence() }
            .filter { it.type == TOOL_SEGMENT }
            .mapTo(hashSetOf(), ::callKey)

        val result = ArrayList<MessageEntity>(rows.size)
        for (row in rows) {
            val entity = row.entity
            if (entity.id.startsWith(Constants.COMPACT_MSG_PREFIX)) {
                // Preserve-Context Compact answers refused tool calls without persisting them, so
                // those segments are UI-only and must never become protocol rows later.
                result += entity.withAggregate(strip = true)
                continue
            }
            val unpaired = unpairedCalls(entity, persistedCalls)
            if (unpaired.isEmpty()) {
                result += entity.withAggregate(row.stripAggregateToolSegments)
                continue
            }
            result += syntheticRound(
                aggregate = entity,
                calls = unpaired,
                previousId = result.lastOrNull()?.id ?: entity.parentId,
            )
            result += entity.withAggregate(strip = true)
        }
        return result
    }

    private fun unpairedCalls(
        entity: MessageEntity,
        persistedCalls: Set<String>,
    ): List<MessageSegment> {
        if (!entity.isEndedModelRow()) return emptyList()
        return decodeSegments(entity.toolCallJson).orEmpty().filter { segment ->
            segment.type == TOOL_SEGMENT &&
                !segment.isProviderHostedTool &&
                !segment.toolName.isNullOrBlank() &&
                callKey(segment) !in persistedCalls
        }
    }

    private fun syntheticRound(
        aggregate: MessageEntity,
        calls: List<MessageSegment>,
        previousId: String?,
    ): List<MessageEntity> {
        val fallback = if (aggregate.status == MessageStatus.STOPPED) STOPPED_RESULT else FAILED_RESULT
        val closed = calls.map { call ->
            // A call whose tool finished just before the stop keeps its real result.
            if (call.toolResult != null) {
                call
            } else {
                call.copy(
                    toolResult = fallback,
                    toolState = if (aggregate.status == MessageStatus.STOPPED) {
                        ToolExecutionStates.STOPPED
                    } else {
                        ToolExecutionStates.FAILED
                    },
                )
            }
        }
        val toolId = "${Constants.TOOL_MSG_PREFIX}$SYNTHETIC_MARKER${aggregate.id}"
        val toolRow = MessageEntity(
            id = toolId,
            conversationId = aggregate.conversationId,
            parentId = previousId,
            text = "",
            status = MessageStatus.SUCCESS,
            participant = Participant.MODEL,
            timestamp = aggregate.timestamp,
            modelName = aggregate.modelName,
            toolCallJson = Json.encodeToString(closed),
            runId = aggregate.runId,
        )
        val resultRows = closed.mapIndexed { index, call ->
            MessageEntity(
                id = "${Constants.RESULT_MSG_PREFIX}$SYNTHETIC_MARKER${aggregate.id}-$index",
                conversationId = aggregate.conversationId,
                parentId = toolId,
                text = call.toolResult.orEmpty(),
                status = MessageStatus.SUCCESS,
                participant = Participant.USER,
                timestamp = aggregate.timestamp + index + 1,
                modelName = aggregate.modelName,
                toolCallJson = Json.encodeToString(
                    listOf(call.copy(responseOutputItems = emptyList(), responseOutputItemProvider = null)),
                ),
                runId = aggregate.runId,
            )
        }
        return listOf(toolRow) + resultRows
    }

    private fun MessageEntity.isEndedModelRow(): Boolean =
        participant == Participant.MODEL &&
            (status == MessageStatus.STOPPED || status == MessageStatus.ERROR) &&
            !id.startsWith(Constants.TOOL_MSG_PREFIX) &&
            !id.startsWith(Constants.RESULT_MSG_PREFIX)

    private fun MessageEntity.withAggregate(strip: Boolean): MessageEntity =
        if (strip) copy(toolCallJson = stripAggregatedToolSegments(toolCallJson)) else this

    /** Calls are matched by provider id; id-less providers fall back to name and arguments. */
    private fun callKey(segment: MessageSegment): String =
        segment.toolCallId?.takeIf(String::isNotBlank)
            ?: "${segment.toolName}\u0000${segment.toolArgs}"

    private fun decodeSegments(json: String?): List<MessageSegment>? =
        json?.let { runCatching { Json.decodeFromString<List<MessageSegment>>(it) }.getOrNull() }

    private const val TOOL_SEGMENT = "tool"
}
