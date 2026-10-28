package com.newoether.agora.ui.chat.message

import android.content.res.Resources
import com.newoether.agora.R
import com.newoether.agora.model.CitationPolicy
import com.newoether.agora.model.MessageSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Content decisions shared by Compose and the WebUI; lifecycle stays in ToolPresentationResolver. */
internal data class ToolDetailPresentation(
    val kind: ToolKind,
    val arguments: String?,
    val argumentsLabel: String,
    val resultLabel: String,
    val mcpDevice: String?,
    val body: ToolDetailBody,
    /** Long string argument pulled out of [arguments] and shown as code. */
    val codeArgument: ToolDetailCodeArgument? = null,
    /** Native result metadata not consumed by a specialized body remains visible as JSON. */
    val supplemental: List<String> = emptyList(),
)

/** [rows] is set when the value is file content and gets a line-number gutter. */
internal data class ToolDetailCodeArgument(
    val name: String,
    val text: String,
    val rows: List<ToolDetailCodeRow.Line>?,
)

internal sealed interface ToolDetailBody {
    data class Active(val text: String, val output: String?) : ToolDetailBody
    data class Failed(val text: String, val output: String?) : ToolDetailBody
    data class Stopped(val text: String) : ToolDetailBody
    data class Muted(val text: String) : ToolDetailBody
    data class JsonContent(val values: List<String>) : ToolDetailBody
    data class Shell(val status: String, val device: String, val error: String?, val output: String?) : ToolDetailBody
    data class Paths(val values: List<ToolDetailPath>) : ToolDetailBody
    data class Grep(val groups: List<ToolDetailMatchGroup>) : ToolDetailBody
    /** [lines] is set for line-mode reads so the number gutter is rendered outside the text. */
    data class FileContent(
        val path: ToolDetailPath?,
        val lineCount: String?,
        val content: String,
        val emptyText: String,
        val truncationText: String?,
        val lines: List<ToolDetailCodeRow.Line>? = null,
    ) : ToolDetailBody
    data class Edit(
        val path: ToolDetailPath?,
        val editCount: String?,
        val replaceAllLabel: String,
        val hunks: List<ToolDetailDiffHunk>,
        val status: String,
        val files: List<ToolDetailEditFile> = emptyList(),
    ) : ToolDetailBody
    data class Search(val results: List<ToolDetailSearchResult>) : ToolDetailBody
}

internal data class ToolDetailSearchResult(
    val title: String,
    val url: String?,
    val safeUrl: String?,
    val snippet: String?,
)

internal fun Resources.toolDetailPresentation(segment: MessageSegment): ToolDetailPresentation {
    if (segment.remoteToolSource != null) return remoteToolDetailPresentation(segment)
    val presentation = ToolPresentationResolver.resolve(segment)
    val body = toolDetailBody(presentation)
    val codeSource = codeArgumentSource(presentation)
    val code = codeSource?.let { codeArgument(presentation.kind, it) }
    val arguments = if (code != null) {
        JsonObject(codeSource - code.name).takeIf { it.isNotEmpty() }?.toString()
    } else {
        // The diff already shows every edit argument; repeating them as JSON adds only noise.
        presentation.rawArguments?.takeIf { it.isNotBlank() && it != "{}" && body !is ToolDetailBody.Edit }
    }
    return ToolDetailPresentation(
        kind = presentation.kind,
        arguments = arguments,
        codeArgument = code,
        argumentsLabel = getString(R.string.arguments_label),
        resultLabel = getString(R.string.result_label),
        mcpDevice = presentation.device?.takeIf(String::isNotBlank),
        body = body,
        supplemental = if (presentation.kind == ToolKind.MCP && presentation.state == ToolPresentationState.FAILED) {
            mcpResultValues(presentation).filterNot { it == presentation.errorMessage }
        } else emptyList(),
    )
}

/** Failed MCP calls retain their raw evidence without changing the recorded lifecycle. */
private fun mcpResultValues(presentation: ToolPresentation): List<String> = listOfNotNull(
    presentation.rawTextResult?.takeIf(String::isNotBlank),
    presentation.rawStructuredResult?.takeIf(String::isNotBlank),
).ifEmpty { listOfNotNull(presentation.rawResult?.takeIf(String::isNotBlank)) }

/**
 * Shell commands and written file, memory or skill content read better as code than as an escaped
 * JSON string. Content that becomes a whole file gets line numbers; appended or prepended active
 * memory does not start at line 1, so it stays unnumbered. Patch calls render as a diff instead.
 */
private fun codeArgument(kind: ToolKind, arguments: JsonObject): ToolDetailCodeArgument? {
    val numbered = when (kind) {
        ToolKind.SHELL_EXECUTE -> false
        ToolKind.FILE_WRITE, ToolKind.MEMORY_CREATE, ToolKind.SKILL_CREATE -> true
        ToolKind.MEMORY_EDIT, ToolKind.SKILL_EDIT ->
            if (arguments.option("operation") == "replace") true else return null
        ToolKind.MEMORY_UPDATE_ACTIVE -> when (arguments.option("mode") ?: "replace") {
            "replace" -> true
            "append", "prepend" -> false
            else -> return null
        }
        else -> return null
    }
    val key = if (kind == ToolKind.SHELL_EXECUTE) "command" else "content"
    val text = (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return ToolDetailCodeArgument(
        name = key,
        text = text,
        rows = if (numbered) numberedCodeLines(text) else null,
    )
}

/** Memory and skill patches render like file_edit: one hunk under the file name. */
private fun Resources.memoryPatchBody(presentation: ToolPresentation): ToolDetailBody? {
    val arguments = presentation.arguments ?: return null
    val isPatch = when (presentation.kind) {
        ToolKind.MEMORY_EDIT, ToolKind.SKILL_EDIT -> arguments.option("operation") == "patch"
        ToolKind.MEMORY_UPDATE_ACTIVE -> arguments.option("mode") == "patch"
        else -> false
    }
    if (!isPatch) return null
    val hunk = memoryPatchHunk(arguments) ?: return null
    return ToolDetailBody.Edit(
        path = arguments.string("name")?.takeIf { presentation.kind != ToolKind.MEMORY_UPDATE_ACTIVE }
            ?.let { toolDetailPath(it) },
        editCount = null,
        replaceAllLabel = getString(R.string.tool_edit_replace_all),
        hunks = listOf(hunk),
        status = toolSummary(presentation),
    )
}

/** Mirrors the providers, which trim and lowercase operation / mode before dispatching. */
private fun JsonObject.option(key: String): String? =
    string(key)?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

/**
 * The resolver only parses arguments once a result exists, because file payloads can be large.
 * A shell command is capped at 64KB, so a running command is parsed here once its JSON is complete.
 */
private fun codeArgumentSource(presentation: ToolPresentation): JsonObject? {
    presentation.arguments?.let { return it }
    if (presentation.kind != ToolKind.SHELL_EXECUTE) return null
    val raw = presentation.rawArguments?.takeIf { it.length <= MAX_LIVE_COMMAND_ARGUMENT_CHARS } ?: return null
    return runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

private const val MAX_LIVE_COMMAND_ARGUMENT_CHARS = 96 * 1024

private fun Resources.toolDetailBody(presentation: ToolPresentation): ToolDetailBody {
    if (presentation.kind in setOf(ToolKind.SHELL_EXECUTE, ToolKind.SHELL_JOB_GET, ToolKind.SHELL_JOB_WAIT, ToolKind.SHELL_JOB_STOP)) {
        return ToolDetailBody.Shell(
            status = shellExecutionSummary(presentation),
            device = presentation.device?.takeIf(String::isNotBlank) ?: getString(R.string.tool_unknown_device),
            error = presentation.errorMessage?.takeIf { presentation.state == ToolPresentationState.FAILED && it.isNotBlank() },
            output = shellOutputText(presentation) ?: getString(R.string.tool_no_output).takeUnless {
                presentation.state == ToolPresentationState.FAILED
            },
        )
    }
    return when (presentation.state) {
        ToolPresentationState.CALLING -> ToolDetailBody.Active(toolSummary(presentation), presentation.liveOutput)
        ToolPresentationState.RUNNING,
        ToolPresentationState.BACKGROUND_RUNNING -> ToolDetailBody.Active(
            toolSummary(presentation), presentation.liveOutput ?: (presentation.result as? JsonObject).string("output"),
        )
        ToolPresentationState.FAILED -> ToolDetailBody.Failed(
            presentation.errorMessage ?: getString(R.string.tool_call_failed), presentation.liveOutput,
        )
        ToolPresentationState.STOPPED -> ToolDetailBody.Stopped(stoppedToolSummary(presentation))
        ToolPresentationState.EMPTY,
        ToolPresentationState.COMPLETED -> completedToolDetailBody(presentation)
    }
}

private fun Resources.completedToolDetailBody(presentation: ToolPresentation): ToolDetailBody {
    val result = presentation.result as? JsonObject
    val base = presentation.arguments.string("path")
    return when (presentation.kind) {
        ToolKind.FILE_GLOB -> {
            val paths = (result?.get("files") as? JsonArray).orEmpty().map { value ->
                toolDetailPath((value as? JsonPrimitive)?.contentOrNull ?: value.toString(), base)
            }
            if (paths.isEmpty()) ToolDetailBody.Muted(getString(R.string.tool_found_no_files))
            else ToolDetailBody.Paths(paths)
        }
        // Results saved before file_grep JSON was size-bounded can be clipped and no longer parse.
        // Their match list survives only in the model-facing text; show that, not "No matches".
        ToolKind.FILE_GREP -> if (result == null && !presentation.rawResult.isNullOrBlank()) {
            ToolDetailBody.JsonContent(listOf(presentation.rawResult))
        } else {
            val groups = grepGroups(
                matches = result?.get("matches") as? JsonArray,
                base = base,
                unknownPath = getString(R.string.file_path_unknown),
                highlighter = grepHighlighter(presentation.arguments),
            )
            if (groups.isEmpty()) ToolDetailBody.Muted(getString(R.string.tool_found_no_matches))
            else ToolDetailBody.Grep(groups)
        }
        ToolKind.FILE_READ -> {
            val path = result.string("path") ?: presentation.subject
            val content = result.string("content").orEmpty()
            val lineMode = result?.containsKey("start_line") == true
            val truncationText = if (result.string("truncated")?.toBooleanStrictOrNull() == true) {
                val nextLine = result.long("next_start_line")
                if (lineMode && nextLine != null) {
                    getString(R.string.tool_read_file_truncated_line, nextLine)
                } else {
                    // next_offset accounts for split UTF-8 bytes; older results lack it.
                    val nextOffset = result.long("next_offset") ?: ((result.long("offset") ?: 0L) +
                        (result.long("returned_bytes") ?: content.toByteArray(Charsets.UTF_8).size.toLong()))
                    getString(R.string.tool_read_file_truncated, nextOffset)
                }
            } else null
            ToolDetailBody.FileContent(
                path = path?.let { toolDetailPath(it) },
                lineCount = result.int("lines")?.let { getString(R.string.tool_line_count, it) },
                content = content,
                emptyText = if (path == null) getString(R.string.tool_read_file_empty_default)
                    else getString(R.string.tool_read_file_empty, path),
                truncationText = truncationText,
                lines = if (lineMode) parseNumberedFileLines(content) else null,
            )
        }
        ToolKind.FILE_EDIT -> {
            val hunks = fileEditHunks(presentation.arguments)
            if (hunks.isNullOrEmpty()) rawToolDetailBody(presentation) else ToolDetailBody.Edit(
                path = (result.string("path") ?: base)?.let { toolDetailPath(it) },
                editCount = hunks.size.takeIf { it > 1 }?.let { getString(R.string.tool_edit_count, it) },
                replaceAllLabel = getString(R.string.tool_edit_replace_all),
                hunks = hunks,
                status = toolSummary(presentation),
            )
        }
        ToolKind.MEMORY_EDIT,
        ToolKind.SKILL_EDIT,
        ToolKind.MEMORY_UPDATE_ACTIVE -> memoryPatchBody(presentation) ?: rawToolDetailBody(presentation)
        ToolKind.MEMORY_READ,
        ToolKind.SKILL_READ -> storedFileReadBody(presentation) ?: rawToolDetailBody(presentation)
        ToolKind.WEB_SEARCH -> {
            val results = (result?.get("results") as? JsonArray).orEmpty().mapIndexed { index, value ->
                val item = value as? JsonObject
                val url = item.string("url") ?: item.string("href")
                ToolDetailSearchResult(
                    title = item.string("title") ?: getString(R.string.tool_web_result, index + 1),
                    url = url,
                    safeUrl = CitationPolicy.safeHttpUrl(url),
                    snippet = item.string("snippet") ?: item.string("description")
                        ?: item.string("content") ?: item.string("body"),
                )
            }
            if (results.isEmpty()) ToolDetailBody.Muted(toolSummary(presentation))
            else ToolDetailBody.Search(results)
        }
        ToolKind.MCP -> {
            val values = mcpResultValues(presentation)
            if (values.isEmpty()) rawToolDetailBody(presentation) else ToolDetailBody.JsonContent(values)
        }
        else -> rawToolDetailBody(presentation)
    }
}

/**
 * Memory and skill reads return the stored Markdown as-is, so they render like file_read: a
 * single file (or the active memory) gets a line-number gutter from line 1, while a multi-file
 * read keeps its `--- name ---` separators in one unnumbered code block.
 */
private fun Resources.storedFileReadBody(presentation: ToolPresentation): ToolDetailBody? {
    val content = presentation.rawTextResult ?: presentation.rawResult ?: return null
    val activeMemory = presentation.toolName == "active_memory_read"
    // The resolver sets a read's subject only when exactly one file was requested.
    val name = presentation.subject?.takeUnless { activeMemory }
    val lines = if (activeMemory || name != null) numberedCodeLines(content) else null
    return ToolDetailBody.FileContent(
        path = name?.let { toolDetailPath(it) },
        lineCount = lines?.size?.takeIf { it > 0 }?.let { getString(R.string.tool_line_count, it) },
        content = content,
        emptyText = getString(R.string.tool_read_file_empty_default),
        truncationText = null,
        lines = lines,
    )
}

// Shell/file tools give the model plain text but keep the JSON envelope as the structured
// result; the detail sheet renders that JSON so fields stay highlighted and wrap cleanly.
private fun Resources.rawToolDetailBody(presentation: ToolPresentation): ToolDetailBody {
    val raw = presentation.rawStructuredResult?.takeIf(String::isNotBlank) ?: presentation.rawResult
    return if (raw.isNullOrEmpty()) ToolDetailBody.Muted(toolSummary(presentation))
    else ToolDetailBody.JsonContent(listOf(raw))
}

internal fun shellOutputText(presentation: ToolPresentation): String? {
    val result = presentation.result as? JsonObject
    val completedOutput = result.string("output")?.takeIf(String::isNotBlank)
        ?: listOfNotNull(
            result.string("stdout")?.takeIf(String::isNotBlank),
            result.string("stderr")?.takeIf(String::isNotBlank),
        ).takeIf(List<String>::isNotEmpty)?.joinToString("\n")
    if (completedOutput != null) return completedOutput
    return presentation.liveOutput?.takeIf(String::isNotBlank)?.takeUnless { output ->
        output.startsWith("Connecting to ") || output == "Starting durable background job"
    }
}

private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject?.int(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject?.long(key: String): Long? = string(key)?.toLongOrNull()
