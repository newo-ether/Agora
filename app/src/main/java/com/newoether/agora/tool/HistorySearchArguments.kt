package com.newoether.agora.tool

import com.newoether.agora.data.local.HistorySearchFilter
import com.newoether.agora.viewmodel.GenerationContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Tool-argument alias for the calling generation's own conversation. */
internal const val CURRENT_CONVERSATION_ALIAS = "current"

/** Returns the real conversation ID, or null when "current" is used without a caller identity. */
internal fun resolveConversationAlias(raw: String, ctx: GenerationContext): String? =
    if (raw.equals(CURRENT_CONVERSATION_ALIAS, ignoreCase = true)) {
        ctx.conversationId?.takeIf { it.isNotBlank() }
    } else {
        raw
    }

internal sealed interface SearchFilterParse {
    data class Valid(val filter: HistorySearchFilter) : SearchFilterParse
    data class Invalid(val error: String, val message: String) : SearchFilterParse
}

/** Parses the optional conversation_search filters. Malformed input fails instead of widening. */
internal fun parseSearchFilter(
    args: Map<String, JsonElement>,
    ctx: GenerationContext,
    zone: ZoneId = ZoneId.systemDefault(),
): SearchFilterParse {
    fun string(key: String): String? =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
            ?.trim()?.takeIf { it.isNotEmpty() }

    val conversationId = string("conversation_id")?.let { raw ->
        resolveConversationAlias(raw, ctx) ?: return SearchFilterParse.Invalid(
            "current_unavailable", "This call has no current conversation.",
        )
    }
    val excludeCurrent = (args["exclude_current"] as? JsonPrimitive)?.let { value ->
        value.booleanOrNull ?: value.content.toBooleanStrictOrNull()
            ?: return SearchFilterParse.Invalid("invalid_exclude_current", "exclude_current must be a boolean.")
    } ?: false
    val excludeConversationId = if (excludeCurrent) {
        ctx.conversationId?.takeIf { it.isNotBlank() } ?: return SearchFilterParse.Invalid(
            "current_unavailable", "This call has no current conversation.",
        )
    } else {
        null
    }
    val participant = string("participant")?.let { raw ->
        when (raw.lowercase()) {
            "user" -> "USER"
            "model", "assistant" -> "MODEL"
            else -> return SearchFilterParse.Invalid("invalid_participant", "participant must be 'user' or 'model'.")
        }
    }
    val afterMs = string("after")?.let { raw ->
        parseHistoryTime(raw, zone) ?: return SearchFilterParse.Invalid(
            "invalid_time", "after is not an ISO 8601 date or time: $raw",
        )
    } ?: Long.MIN_VALUE
    val beforeMs = string("before")?.let { raw ->
        parseHistoryTime(raw, zone) ?: return SearchFilterParse.Invalid(
            "invalid_time", "before is not an ISO 8601 date or time: $raw",
        )
    } ?: Long.MAX_VALUE
    return SearchFilterParse.Valid(
        HistorySearchFilter(conversationId, excludeConversationId, participant, afterMs, beforeMs),
    )
}

/**
 * Accepts an offset or Z timestamp, a local date-time (space or T separator) in [zone], or a date,
 * which means the start of that day in [zone]. Returns epoch milliseconds or null when malformed.
 */
internal fun parseHistoryTime(raw: String, zone: ZoneId): Long? {
    val text = raw.trim().replaceFirst(' ', 'T')
    return try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(text).atStartOfDay(zone).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }
}

/** Echoes the applied filters so the caller can confirm what narrowed the result. */
internal fun HistorySearchFilter.toJson(): JsonObject = buildJsonObject {
    conversationId?.let { put("conversation_id", it) }
    excludeConversationId?.let { put("exclude_conversation_id", it) }
    participant?.let { put("participant", it) }
    if (afterMs != Long.MIN_VALUE) put("after", Instant.ofEpochMilli(afterMs).toString())
    if (beforeMs != Long.MAX_VALUE) put("before", Instant.ofEpochMilli(beforeMs).toString())
}
