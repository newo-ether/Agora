package com.newoether.agora.ui.chat.message

import androidx.compose.runtime.staticCompositionLocalOf
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType

internal val LocalHtmlPreviewStreaming = staticCompositionLocalOf { false }

/** A closing delimiter at the live EOF can still grow into an invalid closing line. */
internal fun completedHtmlFence(content: String, node: ASTNode, streaming: Boolean): Boolean {
    val end = node.findChildOfType(MarkdownTokenTypes.CODE_FENCE_END) ?: return false
    if (!streaming) return true
    return content.indexOf('\n', end.endOffset).let { it >= 0 } ||
        content.indexOf('\r', end.endOffset).let { it >= 0 }
}
