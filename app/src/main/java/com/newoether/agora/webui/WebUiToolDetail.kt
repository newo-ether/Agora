package com.newoether.agora.webui

import android.content.res.Resources
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.ui.chat.message.StreamingJsonArray
import com.newoether.agora.ui.chat.message.StreamingJsonNode
import com.newoether.agora.ui.chat.message.StreamingJsonObject
import com.newoether.agora.ui.chat.message.StreamingJsonParser
import com.newoether.agora.ui.chat.message.StreamingJsonScalar
import com.newoether.agora.ui.chat.message.StreamingJsonStatus
import com.newoether.agora.ui.chat.message.ToolDetailBody
import com.newoether.agora.ui.chat.message.ToolDetailCodeRow
import com.newoether.agora.ui.chat.message.ToolDetailPath
import com.newoether.agora.ui.chat.message.ToolKind
import com.newoether.agora.ui.chat.message.emptyContainerLiteral
import com.newoether.agora.ui.chat.message.isImageGenerationSegment
import com.newoether.agora.ui.chat.message.persistenceAwareJsonSource
import com.newoether.agora.ui.chat.message.toolDetailPresentation
import com.newoether.agora.ui.chat.message.visibleJsonRoots
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire projection only. Content decisions and JSON grammar belong to the shared Compose owners. */
internal fun Resources.webToolDetail(segment: MessageSegment): WebToolDetail {
    val detail = toolDetailPresentation(segment)
    return WebToolDetail(
        kind = detail.kind.name,
        argumentsLabel = detail.argumentsLabel,
        resultLabel = detail.resultLabel,
        arguments = detail.arguments?.let { webToolDocument(it) },
        codeArgument = detail.codeArgument?.let { code ->
            WebToolCodeArgument(code.name, code.text, code.rows?.map(::webToolCodeRow))
        },
        mcpDevice = detail.mcpDevice.takeIf { detail.kind == ToolKind.MCP },
        body = when (val body = detail.body) {
            is ToolDetailBody.Active -> WebToolBody.Active(body.text, body.output)
            is ToolDetailBody.Failed -> WebToolBody.Failed(body.text, body.output)
            is ToolDetailBody.Stopped -> WebToolBody.Stopped(body.text)
            is ToolDetailBody.Muted -> WebToolBody.Muted(body.text)
            is ToolDetailBody.JsonContent -> WebToolBody.Documents(body.values.map { webToolDocument(it) })
            is ToolDetailBody.Shell -> WebToolBody.Shell(body.status, body.device, body.error, body.output)
            is ToolDetailBody.Paths -> WebToolBody.Paths(body.values.map(::webToolPath))
            is ToolDetailBody.Grep -> WebToolBody.Grep(body.groups.map { group ->
                WebToolMatchGroup(webToolPath(group.path), group.rows.map(::webToolCodeRow))
            })
            is ToolDetailBody.FileContent -> WebToolBody.FileContent(
                body.path?.let(::webToolPath), body.lineCount, body.content, body.emptyText, body.truncationText,
                body.lines?.map(::webToolCodeRow),
            )
            is ToolDetailBody.Edit -> WebToolBody.Edit(
                path = body.path?.let(::webToolPath),
                editCount = body.editCount,
                replaceAllLabel = body.replaceAllLabel,
                hunks = body.hunks.map(::webToolDiffHunk),
                status = body.status,
                files = body.files.map { file -> WebToolEditFile(
                    webToolPath(file.path), file.operationLabel, file.hunks.map(::webToolDiffHunk),
                    file.moveTo?.let(::webToolPath),
                ) },
            )
            is ToolDetailBody.Search -> WebToolBody.Search(body.results.map {
                WebToolSearchResult(it.title, it.url, it.safeUrl, it.snippet)
            })
        },
        images = segment.toolImages.mapIndexedNotNull { index, image ->
            if (image.path.isBlank() && !image.unavailable) null
            else WebToolImage(index, image.width, image.height, image.unavailable)
        },
        squareCrop = segment.isImageGenerationSegment(),
        imageLabel = getString(R.string.tool_view_image),
        imageFailedLabel = getString(R.string.attachment_copy_failed_image),
        supplemental = detail.supplemental.map { webToolDocument(it) },
    )
}

private fun webToolPath(path: ToolDetailPath) = WebToolPath(path.name, path.directory)

private fun webToolDiffHunk(hunk: com.newoether.agora.ui.chat.message.ToolDetailDiffHunk) =
    WebToolDiffHunk(hunk.index, hunk.replaceAll, hunk.lines.map { WebToolDiffLine(it.kind.name.lowercase(), it.text) })

private fun webToolCodeRow(row: ToolDetailCodeRow): WebToolCodeRow = when (row) {
    ToolDetailCodeRow.Gap -> WebToolCodeRow(gap = true)
    is ToolDetailCodeRow.Line -> WebToolCodeRow(
        number = row.number,
        text = row.text,
        context = !row.emphasized,
        // Half-open [start, end) pairs in UTF-16 units, matching JavaScript string indices.
        highlights = row.highlights.map { listOf(it.first, it.last + 1) },
    )
}

internal fun Resources.webToolDocument(text: String): WebToolDocument {
    val source = persistenceAwareJsonSource(text)
    val document = StreamingJsonParser.parse(source)
    if (document.status == StreamingJsonStatus.INVALID) return WebToolDocument(text = text)
    return WebToolDocument(
        roots = visibleJsonRoots(document.roots).map { webToolNode(it) },
        marker = text.substring(source.length).trimStart().takeIf(String::isNotEmpty),
    )
}

private fun Resources.webToolNode(node: StreamingJsonNode): WebToolNode {
    val emptyText = if (node.emptyContainerLiteral != null) getString(R.string.tool_json_empty_container) else null
    return when (node) {
        is StreamingJsonObject -> WebToolNode.Object(node.entries.map {
            WebToolEntry(it.key, it.keyComplete, it.value?.let { value -> webToolNode(value) })
        }, node.complete, emptyText)
        is StreamingJsonArray -> WebToolNode.Array(node.values.map { webToolNode(it) }, node.complete, emptyText)
        is StreamingJsonScalar -> WebToolNode.Scalar(node.content, node.kind.name, node.complete)
    }
}

@Serializable
internal data class WebToolDetail(
    val kind: String,
    val argumentsLabel: String,
    val resultLabel: String,
    val arguments: WebToolDocument?,
    val codeArgument: WebToolCodeArgument?,
    val mcpDevice: String?,
    val body: WebToolBody,
    val images: List<WebToolImage>,
    val squareCrop: Boolean,
    val imageLabel: String,
    val imageFailedLabel: String,
    val supplemental: List<WebToolDocument> = emptyList(),
)

@Serializable
internal sealed interface WebToolBody {
    @Serializable @SerialName("active")
    data class Active(val text: String, val output: String?) : WebToolBody
    @Serializable @SerialName("failed")
    data class Failed(val text: String, val output: String?) : WebToolBody
    @Serializable @SerialName("stopped")
    data class Stopped(val text: String) : WebToolBody
    @Serializable @SerialName("muted")
    data class Muted(val text: String) : WebToolBody
    @Serializable @SerialName("documents")
    data class Documents(val values: List<WebToolDocument>) : WebToolBody
    @Serializable @SerialName("shell")
    data class Shell(val status: String, val device: String, val error: String?, val output: String?) : WebToolBody
    @Serializable @SerialName("paths")
    data class Paths(val values: List<WebToolPath>) : WebToolBody
    @Serializable @SerialName("grep")
    data class Grep(val groups: List<WebToolMatchGroup>) : WebToolBody
    @Serializable @SerialName("file")
    data class FileContent(
        val path: WebToolPath?, val lineCount: String?, val content: String,
        val emptyText: String, val truncationText: String?, val lines: List<WebToolCodeRow>? = null,
    ) : WebToolBody
    @Serializable @SerialName("edit")
    data class Edit(
        val path: WebToolPath?, val editCount: String?, val replaceAllLabel: String,
        val hunks: List<WebToolDiffHunk>, val status: String,
        val files: List<WebToolEditFile> = emptyList(),
    ) : WebToolBody
    @Serializable @SerialName("search")
    data class Search(val results: List<WebToolSearchResult>) : WebToolBody
}

@Serializable
internal data class WebToolCodeArgument(val name: String, val text: String, val rows: List<WebToolCodeRow>?)
@Serializable
internal data class WebToolPath(val name: String, val directory: String?)
@Serializable
internal data class WebToolMatchGroup(val path: WebToolPath, val rows: List<WebToolCodeRow>)
@Serializable
internal data class WebToolCodeRow(
    val number: Int? = null,
    val text: String = "",
    val context: Boolean = false,
    val highlights: List<List<Int>> = emptyList(),
    val gap: Boolean = false,
)
@Serializable
internal data class WebToolEditFile(
    val path: WebToolPath, val operationLabel: String, val hunks: List<WebToolDiffHunk>, val moveTo: WebToolPath?,
)
@Serializable
internal data class WebToolDiffHunk(val index: Int?, val replaceAll: Boolean, val lines: List<WebToolDiffLine>)
@Serializable
internal data class WebToolDiffLine(val kind: String, val text: String)
@Serializable
internal data class WebToolSearchResult(val title: String, val url: String?, val safeUrl: String?, val snippet: String?)
@Serializable
internal data class WebToolImage(
    val index: Int, val width: Int?, val height: Int?, val unavailable: Boolean = false,
)
@Serializable
internal data class WebToolDocument(
    val text: String? = null,
    val roots: List<WebToolNode> = emptyList(),
    val marker: String? = null,
)
@Serializable
internal sealed interface WebToolNode {
    @Serializable @SerialName("object")
    data class Object(
        val entries: List<WebToolEntry>, val complete: Boolean, val emptyText: String? = null,
    ) : WebToolNode
    @Serializable @SerialName("array")
    data class Array(
        val values: List<WebToolNode>, val complete: Boolean, val emptyText: String? = null,
    ) : WebToolNode
    @Serializable @SerialName("scalar")
    data class Scalar(val content: String, val kind: String, val complete: Boolean) : WebToolNode
}
@Serializable
internal data class WebToolEntry(val key: String, val keyComplete: Boolean, val value: WebToolNode?)
