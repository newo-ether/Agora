package com.newoether.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where an automatically produced user message came from.
 *
 * Only messages the app sends on the user's behalf carry a source: a Task run prompt, a Loop cycle
 * prompt, or the answers to non-blocking `ask_user` questions. Typed messages, blocking answers and
 * Compact summaries have none. The message text stays the clean readable form; the source is the
 * structured record the chat header and the model-facing XML are built from.
 */
@Immutable
@Serializable
data class MessageSource(
    val kind: Kind,
    /** Question/answer pairs in asking order; only used when [kind] is [Kind.ASK_USER]. */
    val askUser: List<AskUserItem> = emptyList(),
    /** Exact outbox receipts consumed atomically with this automatic input's Run graph. */
    val subagentResults: Map<String, String> = emptyMap(),
    /** Snapshot for the compact completion bubble; original result text remains the API payload. */
    val subagentResult: SubagentResult? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("task") TASK,
        @SerialName("loop") LOOP,
        @SerialName("ask_user") ASK_USER,
        @SerialName("subagent") SUBAGENT,
    }

    /** One asked question. A null [answer] means the user left it unanswered. */
    @Immutable
    @Serializable
    data class AskUserItem(
        val question: String,
        val answer: String? = null,
    )

    /** Display-only snapshot attached to the exact completion receipt, never a status authority. */
    @Immutable
    @Serializable
    data class SubagentResult(
        val name: String,
        val status: String,
        val errorText: String? = null,
        val errorCode: String? = null,
    )

    init {
        require(kind == Kind.ASK_USER || askUser.isEmpty())
        require(kind == Kind.SUBAGENT || subagentResults.isEmpty())
        require(subagentResult == null || kind == Kind.SUBAGENT && subagentResults.size == 1)
    }

    /**
     * The clean readable text stored as the message text for an ask_user source: each question on
     * its own line followed by its answer, groups separated by a blank line.
     */
    fun askUserReadableText(): String {
        check(kind == Kind.ASK_USER)
        return askUser.joinToString("\n\n") { item -> item.question + "\n" + (item.answer ?: NO_ANSWER) }
    }

    companion object {
        val TASK = MessageSource(Kind.TASK)
        val LOOP = MessageSource(Kind.LOOP)

        /** Stored text for a skipped question; the UI shows its own localized label instead. */
        const val NO_ANSWER = "(No answer)"

        fun askUser(items: List<AskUserItem>): MessageSource {
            require(items.isNotEmpty())
            return MessageSource(Kind.ASK_USER, items)
        }

        /** The source a run started by automation stamps on its prompt; ordinary sends have none. */
        fun forAutomationRequestKind(requestKind: String): MessageSource? = when (requestKind) {
            "task" -> TASK
            "loop" -> LOOP
            "subagent" -> MessageSource(Kind.SUBAGENT)
            else -> null
        }

        private val codec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun encode(source: MessageSource?): String? = source?.let { codec.encodeToString(serializer(), it) }

        /** Unreadable or future-shaped data degrades to an ordinary message rather than failing. */
        fun decode(raw: String?): MessageSource? =
            raw?.takeIf(String::isNotBlank)?.let { runCatching { codec.decodeFromString(serializer(), it) }.getOrNull() }

        /**
         * Backup data is untrusted: only a user row can be automatic input, and anything unreadable
         * imports as a plain message. The kept value is re-encoded so stored JSON is canonical.
         */
        fun sanitizeImported(raw: String?, participant: Participant): String? =
            if (participant == Participant.USER) encode(decode(raw)) else null
    }
}

/** Legacy completion headers are recognized only against their exact stored child receipt. */
fun MessageSource.subagentResultDisplay(text: String): MessageSource.SubagentResult? {
    if (kind != MessageSource.Kind.SUBAGENT || subagentResults.isEmpty()) return null
    subagentResult?.let { return it }
    val header = text.substringBefore('\n').removeSuffix("\r")
    if (subagentResults.size == 1) {
        val childId = subagentResults.keys.single()
        for (status in listOf("completed", "failed", "stopped")) {
            val suffix = " ($childId) [$status]"
            if (header.endsWith(suffix)) {
                return MessageSource.SubagentResult(header.removeSuffix(suffix), status)
            }
        }
    }
    return MessageSource.SubagentResult(name = "", status = "")
}
