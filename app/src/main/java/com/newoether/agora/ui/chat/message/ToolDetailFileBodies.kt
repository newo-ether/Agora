package com.newoether.agora.ui.chat.message

import com.newoether.agora.tool.parseFileEditArgs
import com.newoether.agora.tool.parseFileMultiEditArgs
import com.newoether.agora.util.ShellFileEdit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * File-tool detail models shared by Compose and the WebUI. Everything here is pure so the
 * dedent, context merge, highlight and diff decisions are made once and tested once.
 */
internal data class ToolDetailPath(val name: String, val directory: String?)

internal sealed interface ToolDetailCodeRow {
    /** [emphasized] is false for grep context lines; [highlights] are ranges in [text]. */
    data class Line(
        val number: Int?,
        val text: String,
        val emphasized: Boolean = true,
        val highlights: List<IntRange> = emptyList(),
    ) : ToolDetailCodeRow
    /** Marks skipped lines between two non-adjacent rows. */
    data object Gap : ToolDetailCodeRow
}

internal data class ToolDetailMatchGroup(val path: ToolDetailPath, val rows: List<ToolDetailCodeRow>)

internal enum class ToolDetailDiffKind { CONTEXT, REMOVED, ADDED }
internal data class ToolDetailDiffLine(val kind: ToolDetailDiffKind, val text: String)
internal data class ToolDetailDiffHunk(val index: Int?, val replaceAll: Boolean, val lines: List<ToolDetailDiffLine>)

/** One complete file operation; protocol metadata never becomes a code row. */
internal enum class ToolDetailEditOperation { UPDATE, ADD, DELETE, MOVE }
internal data class ToolDetailEditFile(
    val path: ToolDetailPath,
    val operation: ToolDetailEditOperation,
    val hunks: List<ToolDetailDiffHunk>,
    val moveTo: ToolDetailPath? = null,
    val operationLabel: String = "",
)

private const val DIFF_CONTEXT_LINES = 3
private const val MAX_HIGHLIGHTS_PER_LINE = 50

/** Splits [path] into file name and parent directory, shown relative to [base] when it is a prefix. */
internal fun toolDetailPath(path: String, base: String? = null): ToolDetailPath {
    val trimmed = path.trimEnd('/', '\\').ifEmpty { path }
    val cut = trimmed.lastIndexOfAny(charArrayOf('/', '\\'))
    if (cut < 0) return ToolDetailPath(trimmed, null)
    val name = trimmed.substring(cut + 1)
    var directory = trimmed.substring(0, cut).ifEmpty { trimmed.substring(0, cut + 1) }
    // Keep a drive root readable ("F:" -> "F:\").
    if (directory.length == 2 && directory[1] == ':') directory += trimmed[cut]
    val normalizedBase = base?.trimEnd('/', '\\')?.takeIf(String::isNotEmpty)
    if (normalizedBase != null && directory.startsWith(normalizedBase, ignoreCase = true)) {
        val rest = directory.substring(normalizedBase.length)
        if (rest.isEmpty()) return ToolDetailPath(name, null)
        if (rest[0] == '/' || rest[0] == '\\') return ToolDetailPath(name, rest.substring(1))
    }
    return ToolDetailPath(name, directory)
}

/** Parses line-mode file_read content (`%6d\t<text>` per line); null when any line does not fit. */
internal fun parseNumberedFileLines(content: String): List<ToolDetailCodeRow.Line>? {
    if (content.isEmpty()) return emptyList()
    return content.split('\n').map { raw ->
        val tab = raw.indexOf('\t')
        if (tab <= 0) return null
        val number = raw.substring(0, tab).trim().toIntOrNull() ?: return null
        ToolDetailCodeRow.Line(number, raw.substring(tab + 1))
    }
}

/** Numbers raw text from line 1; a trailing newline does not add an empty last line. */
internal fun numberedCodeLines(text: String): List<ToolDetailCodeRow.Line> {
    if (text.isEmpty()) return emptyList()
    return text.removeSuffix("\n").split('\n').mapIndexed { index, line ->
        ToolDetailCodeRow.Line(index + 1, line.removeSuffix("\r"))
    }
}

/** Builds a highlighter from file_grep arguments; null when the pattern is absent or does not compile. */
internal fun grepHighlighter(arguments: JsonObject?): ((String) -> List<IntRange>)? {
    val pattern = (arguments?.get("pattern") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)
        ?: return null
    val ignoreCase = (arguments["case_insensitive"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true
    val regex = runCatching {
        if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
    }.getOrNull() ?: return null
    return { text ->
        runCatching {
            regex.findAll(text).filter { !it.range.isEmpty() }.take(MAX_HIGHLIGHTS_PER_LINE).map { it.range }.toList()
        }.getOrDefault(emptyList())
    }
}

/**
 * Groups file_grep matches by path, merges before/after context by line number (a match wins over
 * context for the same line), inserts gaps between non-adjacent lines when context is present,
 * strips the group's common leading whitespace and computes highlights on the dedented text.
 */
internal fun grepGroups(
    matches: JsonArray?,
    base: String?,
    unknownPath: String,
    highlighter: ((String) -> List<IntRange>)?,
): List<ToolDetailMatchGroup> {
    data class Raw(val number: Int?, val text: String, val match: Boolean)
    val byPath = LinkedHashMap<String, MutableList<Raw>>()
    var hasContext = false
    matches.orEmpty().forEach { value ->
        val item = value as? JsonObject ?: return@forEach
        // Group by the raw path so a real file literally named like the placeholder stays separate.
        val path = (item["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val line = (item["line"] as? JsonPrimitive)?.intOrNull
        val rows = byPath.getOrPut(path) { mutableListOf() }
        val before = item.stringList("before")
        val after = item.stringList("after")
        if (line != null) {
            if (before.isNotEmpty() || after.isNotEmpty()) hasContext = true
            before.forEachIndexed { i, text -> rows += Raw(line - before.size + i, text, false) }
            rows += Raw(line, (item["content"] as? JsonPrimitive)?.contentOrNull.orEmpty(), true)
            after.forEachIndexed { i, text -> rows += Raw(line + 1 + i, text, false) }
        } else {
            rows += Raw(null, (item["content"] as? JsonPrimitive)?.contentOrNull.orEmpty(), true)
        }
    }
    return byPath.map { (path, raws) ->
        // Numbered rows merge by line; unnumbered rows (older servers) keep their order at the end.
        val numbered = sortedMapOf<Int, Raw>()
        raws.filter { it.number != null }.forEach { raw ->
            val existing = numbered[raw.number!!]
            if (existing == null || (raw.match && !existing.match)) numbered[raw.number] = raw
        }
        val ordered = numbered.values + raws.filter { it.number == null }
        val indent = commonIndent(ordered.map { it.text })
        val rows = mutableListOf<ToolDetailCodeRow>()
        var previous: Int? = null
        ordered.forEach { raw ->
            if (hasContext && previous != null && raw.number != null && raw.number > previous + 1) {
                rows += ToolDetailCodeRow.Gap
            }
            val text = raw.text.removePrefix(indent)
            rows += ToolDetailCodeRow.Line(
                number = raw.number,
                text = text,
                emphasized = raw.match,
                highlights = if (raw.match) highlighter?.invoke(text).orEmpty() else emptyList(),
            )
            previous = raw.number ?: previous
        }
        ToolDetailMatchGroup(
            if (path.isBlank()) ToolDetailPath(unknownPath, null) else toolDetailPath(path, base),
            rows,
        )
    }
}

/**
 * Builds git-style hunks from file_edit / file_multi_edit arguments; null when they cannot be parsed.
 * Display follows the arguments rather than the tool name, so calls made before the tools were split
 * (file_edit carrying edits) still render.
 */
internal fun fileEditHunks(arguments: JsonObject?): List<ToolDetailDiffHunk>? {
    arguments ?: return null
    val edits = (parseFileMultiEditArgs(arguments).getOrNull() ?: parseFileEditArgs(arguments).getOrNull())
        ?: return null
    return shellEditHunks(edits)
}

/** Builds the diff shown by prompts that already parsed their edits. */
internal fun shellEditHunks(edits: List<ShellFileEdit>): List<ToolDetailDiffHunk> =
    edits.mapIndexed { index, edit -> diffHunk(if (edits.size > 1) index + 1 else null, edit) }

/** Builds one hunk from memory / skill patch arguments; null without a non-empty old_string. */
internal fun memoryPatchHunk(arguments: JsonObject?): ToolDetailDiffHunk? {
    val old = (arguments?.get("old_string") as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf(String::isNotEmpty) ?: return null
    val new = (arguments["new_string"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    return diffHunk(null, ShellFileEdit(old, new))
}

private fun diffHunk(index: Int?, edit: ShellFileEdit): ToolDetailDiffHunk {
    val old = edit.oldString.diffLines()
    val new = edit.newString.diffLines()
    var prefix = 0
    while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < old.size - prefix && suffix < new.size - prefix &&
        old[old.size - 1 - suffix] == new[new.size - 1 - suffix]
    ) suffix++
    val lines = buildList {
        old.subList(maxOf(0, prefix - DIFF_CONTEXT_LINES), prefix).forEach { add(ToolDetailDiffLine(ToolDetailDiffKind.CONTEXT, it)) }
        old.subList(prefix, old.size - suffix).forEach { add(ToolDetailDiffLine(ToolDetailDiffKind.REMOVED, it)) }
        new.subList(prefix, new.size - suffix).forEach { add(ToolDetailDiffLine(ToolDetailDiffKind.ADDED, it)) }
        old.subList(old.size - suffix, minOf(old.size, old.size - suffix + DIFF_CONTEXT_LINES))
            .forEach { add(ToolDetailDiffLine(ToolDetailDiffKind.CONTEXT, it)) }
    }
    return ToolDetailDiffHunk(index, edit.replaceAll, lines)
}

/** An empty string is zero lines, so a pure deletion shows only removed lines. */
private fun String.diffLines(): List<String> =
    if (isEmpty()) emptyList() else replace("\r\n", "\n").split('\n')

private fun commonIndent(lines: List<String>): String {
    var indent: String? = null
    for (line in lines) {
        if (line.isBlank()) continue
        val lead = line.takeWhile { it == ' ' || it == '\t' }
        indent = if (indent == null) lead else indent.commonPrefixWith(lead)
        if (indent.isEmpty()) return ""
    }
    return indent.orEmpty()
}

private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
