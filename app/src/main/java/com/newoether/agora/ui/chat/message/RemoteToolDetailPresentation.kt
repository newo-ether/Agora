package com.newoether.agora.ui.chat.message

import android.content.res.Resources
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment

/** Localization and fallback stay at the shared presentation boundary, after pure format parsing. */
internal fun Resources.remoteToolDetailPresentation(segment: MessageSegment): ToolDetailPresentation {
    val native = ToolPresentationResolver.resolve(segment)
    val mapped = mapRemoteToolDetail(segment.remoteToolSource, segment.toolName, segment.toolArgs,
        segment.toolResult ?: segment.toolProgress, segment.toolState)
    val body = mapped?.body
    val localized = when (body) {
        is ToolDetailBody.Active -> body.copy(text = toolSummary(native))
        is ToolDetailBody.FileContent -> body.copy(emptyText = getString(R.string.tool_read_file_empty_default))
        is ToolDetailBody.Edit -> body.copy(
            replaceAllLabel = getString(R.string.tool_edit_replace_all),
            status = toolSummary(native),
            files = body.files.map { file -> file.copy(operationLabel = getString(when (file.operation) {
                ToolDetailEditOperation.UPDATE -> R.string.tool_edit_operation_update
                ToolDetailEditOperation.ADD -> R.string.tool_edit_operation_add
                ToolDetailEditOperation.DELETE -> R.string.tool_edit_operation_delete
                ToolDetailEditOperation.MOVE -> R.string.tool_edit_operation_move
            })) },
        )
        is ToolDetailBody.Shell -> body.copy(
            status = body.status.toIntOrNull()?.let { getString(R.string.tool_shell_detail_returned_code, it) }
                ?: shellExecutionSummary(native),
            device = segment.toolTarget ?: getString(R.string.tool_unknown_device),
            output = body.output ?: getString(R.string.tool_no_output).takeUnless { native.isActive },
        )
        is ToolDetailBody.Paths -> body.takeIf { it.values.isNotEmpty() }
            ?: ToolDetailBody.Muted(getString(R.string.tool_found_no_files))
        is ToolDetailBody.Grep -> body.takeIf { it.groups.isNotEmpty() }
            ?: ToolDetailBody.Muted(getString(R.string.tool_found_no_matches))
        is ToolDetailBody.Search -> body.takeIf { it.results.isNotEmpty() }
            ?: ToolDetailBody.Muted(toolSummary(native))
        else -> body
    }
    return if (mapped != null && localized != null) mapped.copy(body = localized,
        argumentsLabel = getString(R.string.arguments_label), resultLabel = getString(R.string.result_label))
    else ToolDetailPresentation(ToolKind.UNKNOWN, segment.toolArgs,
        getString(R.string.arguments_label), getString(R.string.result_label), null,
        (segment.toolResult ?: segment.toolProgress)?.let { ToolDetailBody.JsonContent(listOf(it)) }
            ?: when (native.state) {
                ToolPresentationState.FAILED -> ToolDetailBody.Failed(toolSummary(native), null)
                ToolPresentationState.STOPPED -> ToolDetailBody.Stopped(stoppedToolSummary(native))
                ToolPresentationState.COMPLETED, ToolPresentationState.EMPTY -> ToolDetailBody.Muted(toolSummary(native))
                else -> ToolDetailBody.Active(toolSummary(native), null)
            })
}
