package com.newoether.agora.webui

import com.newoether.agora.ui.chat.message.shellEditHunks
import com.newoether.agora.ui.chat.message.toolDetailPath
import com.newoether.agora.viewmodel.ShellActionDetail
import com.newoether.agora.viewmodel.ShellConfirmationController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One shell approval, projected the way the phone draws it: a plain command, a written file, or the
 * edit hunks with their numbered pills. The browser renders `detail` with the same tool rows the
 * chat already uses, so an approval never falls back to a bare code block.
 */
internal fun webShellInteraction(
    pending: ShellConfirmationController.PendingShellCommand,
): JsonObject = buildJsonObject {
    put("kind", "shell")
    put("id", pending.id.toString())
    put("server", pending.server)
    put("summary", pending.summary)
    put("description", pending.description)
    pending.detail?.let { put("detail", webShellActionDetail(it)) }
}

/** Mirrors the phone's write preview cap. */
private const val ApprovalWritePreviewMaxChars = 8_000

private fun webShellActionDetail(detail: ShellActionDetail): JsonObject = when (detail) {
    is ShellActionDetail.Edit -> buildJsonObject {
        put("type", "edit")
        put("path", webApprovalPath(detail.path))
        put("hunks", buildJsonArray {
            shellEditHunks(detail.edits).forEach { hunk ->
                addJsonObject {
                    hunk.index?.let { put("index", it) }
                    put("replaceAll", hunk.replaceAll)
                    put("lines", buildJsonArray {
                        hunk.lines.forEach { line ->
                            addJsonObject {
                                put("kind", line.kind.name.lowercase())
                                put("text", line.text)
                            }
                        }
                    })
                }
            }
        })
    }
    is ShellActionDetail.Write -> buildJsonObject {
        put("type", "write")
        put("path", webApprovalPath(detail.path))
        put("content", detail.content.take(ApprovalWritePreviewMaxChars))
    }
}

private fun webApprovalPath(path: String): JsonObject {
    val projected = toolDetailPath(path)
    return buildJsonObject {
        put("name", projected.name)
        projected.directory?.let { put("directory", it) }
    }
}
