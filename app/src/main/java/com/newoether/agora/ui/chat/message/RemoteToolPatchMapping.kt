package com.newoether.agora.ui.chat.message

import kotlinx.serialization.json.*

/** Complete patches only: malformed, truncated or unsupported sections keep the original fallback. */
internal fun remotePatchBody(patch: String): ToolDetailBody.Edit? {
    val lines = patch.replace("\r\n", "\n").trimEnd('\n').split('\n')
    if (lines.firstOrNull() != "*** Begin Patch" || lines.lastOrNull() != "*** End Patch") return null
    var index = 1
    val files = mutableListOf<ToolDetailEditFile>()
    while (index < lines.lastIndex) {
        val header = lines[index++]
        val operation = when {
            header.startsWith("*** Update File: ") -> ToolDetailEditOperation.UPDATE
            header.startsWith("*** Add File: ") -> ToolDetailEditOperation.ADD
            header.startsWith("*** Delete File: ") -> ToolDetailEditOperation.DELETE
            else -> return null
        }
        val path = header.substringAfter(" File: ").takeIf(String::isNotBlank) ?: return null
        val moveTo = if (lines.getOrNull(index)?.startsWith("*** Move to: ") == true) {
            if (operation != ToolDetailEditOperation.UPDATE) return null
            lines[index++].substringAfter("*** Move to: ").takeIf(String::isNotBlank) ?: return null
        } else null
        val rows = mutableListOf<String>()
        while (index < lines.lastIndex && !lines[index].startsWith("*** ")) rows += lines[index++]
        val endOfFile = lines.getOrNull(index) == "*** End of File"
        if (endOfFile) {
            if (operation != ToolDetailEditOperation.UPDATE) return null
            index++
        }
        val hunks = when (operation) {
            ToolDetailEditOperation.UPDATE -> remoteUpdateHunks(rows, unified = false) ?: return null
            ToolDetailEditOperation.ADD -> {
                if (rows.any { !it.startsWith('+') }) return null
                literalHunks(rows.map { it.drop(1) }, ToolDetailDiffKind.ADDED)
            }
            ToolDetailEditOperation.DELETE -> {
                if (rows.isNotEmpty()) return null
                emptyList()
            }
            ToolDetailEditOperation.MOVE -> return null
        }
        files += ToolDetailEditFile(toolDetailPath(path),
            if (moveTo != null) ToolDetailEditOperation.MOVE else operation, hunks, moveTo?.let(::toolDetailPath))
    }
    return remoteEditFiles(files)
}

/** Codex add/delete diffs are literal file contents; only updates carry unified diff rows. */
internal fun remoteFileChanges(changes: JsonArray): ToolDetailBody.Edit? {
    val files = changes.map { value ->
        val change = value as? JsonObject ?: return null
        if (change.keys.any { it !in setOf("path", "kind", "diff", "move_path") }) return null
        val path = change.remoteString("path")?.takeIf(String::isNotBlank) ?: return null
        val nativeKind = change["kind"]
        val kindObject = nativeKind as? JsonObject
        if (kindObject?.keys?.any { it !in setOf("type", "move_path") } == true) return null
        val operation = when (kindObject?.remoteString("type")
            ?: (nativeKind as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "add" -> ToolDetailEditOperation.ADD
            "delete" -> ToolDetailEditOperation.DELETE
            "update" -> ToolDetailEditOperation.UPDATE
            else -> return null
        }
        val movePaths = listOfNotNull(change["move_path"], kindObject?.get("move_path")).filterNot { it is JsonNull }
        val destinations = movePaths.map {
            (it as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank) ?: return null
        }.distinct()
        if (destinations.size > 1 || destinations.isNotEmpty() && operation != ToolDetailEditOperation.UPDATE) return null
        val moveTo = destinations.singleOrNull()
        val diff = change.remoteString("diff") ?: return null
        val rows = if (diff.isEmpty()) emptyList() else diff.replace("\r\n", "\n").removeSuffix("\n").split('\n')
        val hunks = when (operation) {
            ToolDetailEditOperation.ADD -> literalHunks(rows, ToolDetailDiffKind.ADDED)
            ToolDetailEditOperation.DELETE -> literalHunks(rows, ToolDetailDiffKind.REMOVED)
            ToolDetailEditOperation.UPDATE -> if (diff.isEmpty() && moveTo != null) emptyList()
                else remoteUpdateHunks(rows, unified = true) ?: return null
            ToolDetailEditOperation.MOVE -> return null
        }
        ToolDetailEditFile(toolDetailPath(path), if (moveTo != null) ToolDetailEditOperation.MOVE else operation,
            hunks, moveTo?.let(::toolDetailPath))
    }
    return remoteEditFiles(files)
}

private fun remoteEditFiles(files: List<ToolDetailEditFile>): ToolDetailBody.Edit? =
    files.takeIf { it.isNotEmpty() }?.let { ToolDetailBody.Edit(null, null, "", emptyList(), "", it) }

private fun literalHunks(rows: List<String>, kind: ToolDetailDiffKind): List<ToolDetailDiffHunk> =
    if (rows.isEmpty()) emptyList() else listOf(ToolDetailDiffHunk(null, false, rows.map { ToolDetailDiffLine(kind, it) }))

/** The parser consumes protocol boundaries once; both frontends receive only actual code rows. */
private fun remoteUpdateHunks(rows: List<String>, unified: Boolean): List<ToolDetailDiffHunk>? {
    var index = 0
    if (unified) {
        if (rows.getOrNull(index)?.startsWith("diff --git ") == true) index++
        if (rows.getOrNull(index)?.startsWith("index ") == true) index++
        if (rows.getOrNull(index)?.startsWith("--- ") == true) {
            index++
            if (rows.getOrNull(index)?.startsWith("+++ ") != true) return null
            index++
        }
    }
    val hunks = mutableListOf<ToolDetailDiffHunk>()
    var current: MutableList<ToolDetailDiffLine>? = null
    for (row in rows.drop(index)) {
        if (row == "@@" || row.startsWith("@@ ")) {
            if (current?.isEmpty() == true) return null
            current?.let { hunks += ToolDetailDiffHunk(null, false, it.toList()) }
            current = mutableListOf()
            continue
        }
        val target = current ?: return null
        if (unified && row == "\\ No newline at end of file") {
            if (target.isEmpty()) return null
            continue
        }
        val kind = when (row.firstOrNull()) {
            '+' -> ToolDetailDiffKind.ADDED
            '-' -> ToolDetailDiffKind.REMOVED
            ' ' -> ToolDetailDiffKind.CONTEXT
            else -> return null
        }
        target += ToolDetailDiffLine(kind, row.drop(1))
    }
    val last = current?.takeIf { it.isNotEmpty() } ?: return null
    hunks += ToolDetailDiffHunk(null, false, last.toList())
    return hunks.mapIndexed { hunkIndex, hunk -> hunk.copy(index = (hunkIndex + 1).takeIf { hunks.size > 1 }) }
}
