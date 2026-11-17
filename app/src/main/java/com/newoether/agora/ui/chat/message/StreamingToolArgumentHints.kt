package com.newoether.agora.ui.chat.message

private const val MAX_TOOL_ARGUMENT_HINT_PREFIX_CHARS = 4_096
internal const val MAX_TOOL_SUMMARY_SUBJECT_CHARS = 120

internal data class StreamingToolArgumentHints(
    val subject: String?,
    val server: String?,
    val count: Int? = null,
    val operation: String? = null,
    val destination: String? = null,
)

/**
 * Extracts only the small pieces of an unfinished argument document needed by the compact tool
 * card. Tool payloads such as file content can be very large, so the UI must not strictly parse
 * the entire growing JSON buffer on every stream snapshot.
 *
 * Tool schemas put their identifying field before large payload fields. Parsing a bounded JSON
 * prefix therefore keeps command/path/query text live while making the per-frame cost constant.
 * [StreamingJsonParser] preserves open string leaves, including split escapes, without inventing
 * missing JSON syntax.
 */
internal object StreamingToolArgumentHintResolver {
    fun resolve(
        kind: ToolKind,
        rawArguments: String?,
    ): StreamingToolArgumentHints {
        val source = rawArguments
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_TOOL_ARGUMENT_HINT_PREFIX_CHARS)
            ?: return StreamingToolArgumentHints(subject = null, server = null)
        val root = StreamingJsonParser.parse(source).root as? StreamingJsonObject
            ?: return StreamingToolArgumentHints(subject = null, server = null)

        val read = kind == ToolKind.MEMORY_READ || kind == ToolKind.SKILL_READ
        val namesArray = root.entries.firstOrNull { it.keyComplete && it.key == "names" }
            ?.value as? StreamingJsonArray
        val names = namesArray?.values?.mapNotNull { node ->
            (node as? StreamingJsonScalar)?.content?.takeIf {
                if (kind == ToolKind.MEMORY_READ) it.isNotEmpty() else it.isNotBlank()
            }
        }.orEmpty()
        val namesSelected = read && namesArray != null && (!namesArray.complete ||
            if (kind == ToolKind.MEMORY_READ) namesArray.values.isNotEmpty() else names.isNotEmpty())
        val subject = when (kind) {
            ToolKind.MEMORY_READ,
            ToolKind.SKILL_READ -> if (namesSelected) {
                names.singleOrNull()?.takeIf { namesArray.complete }
            } else root.scalar("name")
            ToolKind.MEMORY_CREATE,
            ToolKind.MEMORY_EDIT,
            ToolKind.MEMORY_DELETE,
            ToolKind.SKILL_CREATE,
            ToolKind.SKILL_EDIT,
            ToolKind.SKILL_DELETE -> root.scalar("name")
            ToolKind.WEB_SEARCH,
            ToolKind.MCP_SEARCH,
            ToolKind.CONVERSATION_SEARCH -> root.scalar("query")
            ToolKind.WEB_FETCH -> root.scalar("url")
            ToolKind.CONVERSATION_READ -> root.scalar("conversation_id")
            ToolKind.SHELL_EXECUTE -> root.scalar("command")
            ToolKind.SHELL_JOB_GET,
            ToolKind.SHELL_JOB_WAIT,
            ToolKind.SHELL_JOB_STOP -> root.scalar("job_id")
            ToolKind.FILE_READ,
            ToolKind.FILE_WRITE,
            ToolKind.FILE_EDIT,
            ToolKind.IMAGE_VIEW -> root.scalar("path")
            ToolKind.FILE_TRANSFER -> fileTransferEndpoint(root.scalar("source_server"), root.scalar("source_path"))
            ToolKind.FILE_GLOB,
            ToolKind.FILE_GREP -> root.scalar("pattern")
            ToolKind.IMAGE_GENERATE -> root.scalar("prompt")
            ToolKind.TASK_CREATE -> root.scalar("name")
            ToolKind.TASK_DELETE -> root.scalar("id_or_name")
                ?: root.scalar("name")
                ?: root.scalar("task_id")
            else -> null
        }

        return StreamingToolArgumentHints(
            subject = normalizeToolSummarySubject(subject),
            server = normalizeToolSummarySubject(root.scalar("server")),
            count = if (namesSelected && namesArray.complete) names.size else null,
            operation = root.scalar("operation"),
            destination = normalizeToolSummarySubject(if (kind == ToolKind.FILE_TRANSFER) {
                fileTransferEndpoint(root.scalar("destination_server"), root.scalar("destination_path"))
            } else root.scalar("new_name")),
        )
    }

    private fun StreamingJsonObject.scalar(key: String): String? =
        entries
            .firstOrNull { it.keyComplete && it.key == key }
            ?.value
            .let { it as? StreamingJsonScalar }
            ?.content

}

internal fun normalizeToolSummarySubject(
    value: String?,
    maxCharacters: Int = MAX_TOOL_SUMMARY_SUBJECT_CHARS,
): String? {
    require(maxCharacters > 0)
    val subject = value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return if (subject.length > maxCharacters) subject.take(maxCharacters - 1) + "\u2026" else subject
}
