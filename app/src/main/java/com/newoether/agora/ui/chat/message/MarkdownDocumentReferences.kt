package com.newoether.agora.ui.chat.message

import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.parser.LinkMap

/** Document metadata, collected from parsed definitions without rewriting source or stable trees. */
internal fun markdownReferenceDefinitions(root: ASTNode, content: String): Map<String, String> {
    val definitions = linkedMapOf<String, String>()
    fun visit(node: ASTNode) {
        if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
            val label = node.findChildOfType(MarkdownElementTypes.LINK_LABEL)
                ?.getUnescapedTextInNode(content) ?: return
            val destination = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                ?.getUnescapedTextInNode(content) ?: return
            definitions.putIfAbsent(normalizeReferenceLabel(label), destination.removeSurrounding("<", ">"))
        } else {
            node.children.forEach(::visit)
        }
    }
    visit(root)
    return definitions.toMap()
}

private fun normalizeReferenceLabel(label: String): String = LinkMap.Builder.normalizeLabel(label).toString()

/** Inline links already carry their URLs. Their annotation-time store calls cannot define labels. */
internal class MarkdownDocumentReferenceHandler(
    private val definitions: Map<String, String>,
) : ReferenceLinkHandler {
    override fun store(label: String, destination: String?) = Unit
    override fun find(label: String): String = definitions[normalizeReferenceLabel(label)].orEmpty()
}

/** Preserve existing inter-block spacing, but never emit metadata or trailing separator padding. */
internal fun markdownVisibleBlocks(root: ASTNode, preserveTrailingSpacing: Boolean = false): List<ASTNode> {
    val visible = mutableListOf<ASTNode>()
    val separators = mutableListOf<ASTNode>()
    var afterMetadata = false
    root.children.forEach { child ->
        val node = markdownVisibleNode(child)
        when {
            node == null -> afterMetadata = true
            isStructuralMarkdownNode(node) -> {
                if (visible.isNotEmpty() && !afterMetadata) separators += node
            }
            else -> {
                visible += separators
                separators.clear()
                visible += node
                afterMetadata = false
            }
        }
    }
    if (preserveTrailingSpacing && visible.isNotEmpty()) visible += separators
    return visible
}

private fun isStructuralMarkdownNode(node: ASTNode): Boolean =
    node.type == MarkdownTokenTypes.EOL || node.type == MarkdownTokenTypes.WHITE_SPACE

/** A read-only render projection keeps source offsets and original parent relationships intact. */
private class MarkdownRenderNode(
    private val original: ASTNode,
    override val children: List<ASTNode>,
) : ASTNode by original

private fun markdownVisibleNode(node: ASTNode): ASTNode? {
    if (node.type == MarkdownElementTypes.LINK_DEFINITION) return null
    val children = node.children
    if (children.isEmpty()) return node
    val projected = children.mapNotNull(::markdownVisibleNode)
    if (projected.size == children.size && projected.indices.all { projected[it] === children[it] }) {
        return node
    }
    // A removed definition must not leave its separator lines at the end of a quote or list item.
    val trimmed = projected.dropLastWhile(::isStructuralMarkdownNode)
    val container = node.type == MarkdownElementTypes.BLOCK_QUOTE ||
        node.type == MarkdownElementTypes.LIST_ITEM || node.type == MarkdownElementTypes.ORDERED_LIST ||
        node.type == MarkdownElementTypes.UNORDERED_LIST
    if (container && trimmed.none { child ->
            !isStructuralMarkdownNode(child) && child.type != MarkdownTokenTypes.LIST_BULLET &&
                child.type != MarkdownTokenTypes.LIST_NUMBER &&
                child.type != MarkdownTokenTypes.BLOCK_QUOTE &&
                !(child.type == MarkdownElementTypes.BLOCK_QUOTE && child.children.isEmpty())
        }) return null
    return MarkdownRenderNode(node, trimmed)
}
