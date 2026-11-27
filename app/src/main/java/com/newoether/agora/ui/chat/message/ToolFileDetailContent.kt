package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.theme.MonoFamily
import com.newoether.agora.util.NoAutoScrollSelectionContainer

/** Above this many rows the gutter is drawn inline in one Text to keep large reads cheap. */
private const val MAX_ROW_COMPOSABLES = 400
private val DiffAddedColor = Color(0xFF2DA44E)

/** File name in the primary color with the directory as small grey text, middle-ellipsized. */
@Composable
internal fun ToolPathHeader(path: ToolDetailPath, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = path.name,
            style = ChatType.thoughtCodeLarge.copy(textDirection = TextDirection.Ltr),
            fontFamily = MonoFamily,
            color = MaterialTheme.colorScheme.primary,
        )
        path.directory?.let {
            Text(
                text = it,
                style = ChatType.micro.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

/** A string argument shown as code under its key name. */
@Composable
internal fun ToolCodeArgument(code: ToolDetailCodeArgument) {
    Text(
        text = code.name,
        style = ChatType.micro,
        fontFamily = MonoFamily,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
    Spacer(Modifier.height(4.dp))
    val rows = code.rows
    if (rows.isNullOrEmpty()) TerminalOutput(code.text) else CodeRows(rows)
}

@Composable
internal fun FileGlobResult(body: ToolDetailBody.Paths) {
    NoAutoScrollSelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            body.values.forEach { ToolPathHeader(it) }
        }
    }
}

@Composable
internal fun FileGrepResult(body: ToolDetailBody.Grep) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        body.groups.forEach { group ->
            Column {
                ToolPathHeader(group.path)
                Spacer(Modifier.height(6.dp))
                CodeRows(group.rows)
            }
        }
    }
}

@Composable
internal fun FileReadResult(body: ToolDetailBody.FileContent) {
    if (body.path != null || body.lineCount != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            body.path?.let { ToolPathHeader(it, modifier = Modifier.weight(1f, fill = false)) }
            body.lineCount?.let { MetaPill(it) }
        }
        Spacer(Modifier.height(8.dp))
    }
    when {
        body.content.isEmpty() -> ToolMutedContent(body.emptyText)
        body.lines != null -> CodeRows(body.lines)
        else -> TerminalOutput(body.content)
    }
    body.truncationText?.let {
        Spacer(Modifier.height(8.dp))
        ToolMutedContent(it)
    }
}

@Composable
internal fun FileEditResult(body: ToolDetailBody.Edit) {
    if (body.files.isEmpty()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            body.path?.let { ToolPathHeader(it, modifier = Modifier.weight(1f, fill = false)) }
            body.editCount?.let { MetaPill(it) }
        }
        Spacer(Modifier.height(8.dp))
        FileEditHunks(body.hunks, body.replaceAllLabel)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            body.files.forEach { file ->
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToolPathHeader(file.path, modifier = Modifier.weight(1f))
                        MetaPill(file.operationLabel)
                    }
                    file.moveTo?.let {
                        Spacer(Modifier.height(6.dp))
                        ToolPathHeader(it)
                    }
                    if (file.hunks.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FileEditHunks(file.hunks, body.replaceAllLabel)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    ToolMutedContent(body.status)
}

@Composable
internal fun FileEditHunks(hunks: List<ToolDetailDiffHunk>, replaceAllLabel: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        hunks.forEach { hunk ->
            Column {
                if (hunk.index != null || hunk.replaceAll) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        hunk.index?.let { MetaPill("#$it") }
                        if (hunk.replaceAll) MetaPill(replaceAllLabel, emphasized = true)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                DiffLines(hunk.lines)
            }
        }
    }
}

@Composable
private fun DiffLines(lines: List<ToolDetailDiffLine>) {
    val removed = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        NoAutoScrollSelectionContainer {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                lines.forEach { line ->
                    val (sign, tint) = when (line.kind) {
                        ToolDetailDiffKind.REMOVED -> "-" to removed
                        ToolDetailDiffKind.ADDED -> "+" to DiffAddedColor
                        ToolDetailDiffKind.CONTEXT -> " " to Color.Transparent
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tint.copy(alpha = if (tint == Color.Transparent) 0f else 0.12f))
                            .padding(horizontal = 8.dp),
                    ) {
                        DisableSelection {
                            Text(
                                text = sign,
                                style = ChatType.thoughtCodeLarge.copy(textDirection = TextDirection.Ltr),
                                fontFamily = MonoFamily,
                                color = if (line.kind == ToolDetailDiffKind.CONTEXT) muted else tint,
                                modifier = Modifier.width(14.dp),
                            )
                        }
                        Text(
                            text = line.text,
                            style = ChatType.thoughtCodeLarge,
                            fontFamily = MonoFamily,
                            color = if (line.kind == ToolDetailDiffKind.CONTEXT) muted
                                else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Line-numbered code: a right-aligned, non-selectable grey gutter beside the text, so wrapped
 * lines stay aligned with the text column. Grep context rows are dimmed and gaps show an ellipsis.
 */
@Composable
internal fun CodeRows(rows: List<ToolDetailCodeRow>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (rows.size > MAX_ROW_COMPOSABLES) {
            InlineGutterText(rows)
            return@Surface
        }
        val digits = remember(rows) {
            rows.maxOfOrNull { (it as? ToolDetailCodeRow.Line)?.number?.toString()?.length ?: 1 } ?: 1
        }
        val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        NoAutoScrollSelectionContainer {
            Column(modifier = Modifier.padding(10.dp)) {
                rows.forEach { row ->
                    when (row) {
                        ToolDetailCodeRow.Gap -> DisableSelection {
                            Text(text = "\u22ef", style = ChatType.thoughtCodeLarge.copy(textDirection = TextDirection.Ltr), color = gutterColor)
                        }
                        is ToolDetailCodeRow.Line -> Row(modifier = Modifier.fillMaxWidth()) {
                            DisableSelection {
                                Text(
                                    text = row.number?.toString() ?: "\u2014",
                                    style = ChatType.thoughtCodeLarge,
                                    fontFamily = MonoFamily,
                                    color = gutterColor.copy(alpha = if (row.emphasized) 0.5f else 0.3f),
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.widthIn(min = (digits * 8).dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = highlighted(row, highlight),
                                style = ChatType.thoughtCodeLarge.copy(textDirection = TextDirection.Ltr),
                                fontFamily = MonoFamily,
                                color = if (row.emphasized) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineGutterText(rows: List<ToolDetailCodeRow>) {
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val text = remember(rows, gutterColor) {
        val digits = rows.maxOfOrNull { (it as? ToolDetailCodeRow.Line)?.number?.toString()?.length ?: 1 } ?: 1
        buildAnnotatedString {
            rows.forEachIndexed { index, row ->
                if (index > 0) append('\n')
                when (row) {
                    ToolDetailCodeRow.Gap -> withStyle(SpanStyle(color = gutterColor)) { append("\u22ef") }
                    is ToolDetailCodeRow.Line -> {
                        withStyle(SpanStyle(color = gutterColor)) {
                            append((row.number?.toString() ?: "\u2014").padStart(digits))
                            append("  ")
                        }
                        append(row.text)
                    }
                }
            }
        }
    }
    NoAutoScrollSelectionContainer {
        Text(
            text = text,
            style = ChatType.thoughtCodeLarge.copy(textDirection = TextDirection.Ltr),
            fontFamily = MonoFamily,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(10.dp),
        )
    }
}

private fun highlighted(row: ToolDetailCodeRow.Line, background: Color): AnnotatedString {
    if (row.highlights.isEmpty()) return AnnotatedString(row.text)
    return buildAnnotatedString {
        append(row.text)
        row.highlights.forEach { range ->
            val end = (range.last + 1).coerceAtMost(row.text.length)
            if (range.first in 0 until end) addStyle(SpanStyle(background = background), range.first, end)
        }
    }
}
