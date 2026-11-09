package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.CitationPolicy
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

internal data class CitationMarkdownLinkWrapper(
    val startIndex: Int,
    val endIndex: Int,
    val safeUrl: String,
)

internal fun parenthesizedCitationLinkWrappers(
    markdown: String,
): List<CitationMarkdownLinkWrapper> = runCatching {
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
    buildList { root.collectParenthesizedCitationLinkWrappers(markdown, this) }
}.getOrDefault(emptyList())

private fun ASTNode.collectParenthesizedCitationLinkWrappers(
    markdown: String,
    target: MutableList<CitationMarkdownLinkWrapper>,
) {
    if (type == MarkdownElementTypes.INLINE_LINK) {
        val destination = findDescendant(MarkdownElementTypes.LINK_DESTINATION)
        val wrapperStart = startOffset - 1
        val wrapperEnd = endOffset + 1
        if (
            destination != null &&
            wrapperStart >= 0 &&
            wrapperEnd <= markdown.length &&
            markdown[wrapperStart] == '(' &&
            markdown[wrapperEnd - 1] == ')'
        ) {
            CitationPolicy.safeHttpUrl(
                markdown.substring(destination.startOffset, destination.endOffset),
            )?.let { safeUrl ->
                target += CitationMarkdownLinkWrapper(wrapperStart, wrapperEnd, safeUrl)
            }
        }
        return
    }
    children.forEach { child ->
        child.collectParenthesizedCitationLinkWrappers(markdown, target)
    }
}

private fun ASTNode.findDescendant(type: org.intellij.markdown.IElementType): ASTNode? {
    if (this.type == type) return this
    return children.firstNotNullOfOrNull { child -> child.findDescendant(type) }
}
