package com.newoether.agora.ui.chat.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.newoether.agora.util.isRtlContent
import org.intellij.markdown.ast.ASTNode

/** Node types that are machine-readable and stay LTR even inside an RTL message. */
private val PINNED_LTR_NODE_TYPES = listOf("CODE", "TABLE", "HTML")

/** Layout direction a Markdown block should be laid out in, taken from the block's own text. */
internal fun markdownBlockLayoutDirection(nodeTypeName: String, blockText: CharSequence?): LayoutDirection {
    val pinnedLtr = PINNED_LTR_NODE_TYPES.any { nodeTypeName.contains(it, ignoreCase = true) }
    return if (pinnedLtr || !isRtlContent(blockText)) LayoutDirection.Ltr else LayoutDirection.Rtl
}

/**
 * Lays [block] out in the direction of the Markdown source it renders.
 *
 * Lists, block quotes and horizontal edges are placed with the layout direction, which otherwise
 * follows the UI language: an Arabic message under an English UI would keep its bullets and
 * indentation on the left. Machine-readable blocks (code, tables, raw HTML) keep LTR.
 */
@Composable
internal fun MarkdownBlockDirection(
    node: ASTNode,
    content: String,
    block: @Composable () -> Unit,
) {
    val direction = remember(node.type, content, node.startOffset, node.endOffset) {
        val start = node.startOffset.coerceIn(0, content.length)
        val end = node.endOffset.coerceIn(start, content.length)
        markdownBlockLayoutDirection(node.type.toString(), content.substring(start, end))
    }
    CompositionLocalProvider(LocalLayoutDirection provides direction) { block() }
}
