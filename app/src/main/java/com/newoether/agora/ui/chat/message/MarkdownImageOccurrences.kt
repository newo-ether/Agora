package com.newoether.agora.ui.chat.message

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.mikepenz.markdown.model.ReferenceLinkHandler
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

/** Document identity and occurrence order survive block promotion and repeated destinations. */
internal val LocalMarkdownImageDocument = staticCompositionLocalOf { "answer" }
internal val LocalMarkdownImageOrdinalOffset = staticCompositionLocalOf { 0 }
internal val LocalMarkdownImageOccurrences = compositionLocalOf<Map<ASTNode, String>> { emptyMap() }

internal fun markdownImageOccurrences(
    content: String,
    root: ASTNode,
    references: ReferenceLinkHandler?,
    document: String,
    ordinalOffset: Int = 0,
): Map<ASTNode, String> = buildMap {
    var ordinal = ordinalOffset
    fun visit(node: ASTNode) {
        if (node.type == MarkdownElementTypes.IMAGE) {
            val link = markdownImageLink(content, node, references)
            if (link != null && markdownImageSnapshotKey(link) != null) {
                put(node, "$document:${ordinal++}")
            }
        } else node.children.forEach(::visit)
    }
    visit(root)
}

internal fun shellMarkdownImageCount(source: String, beforeOffset: Int = source.length): Int {
    if (!source.contains("shell://", ignoreCase = true) &&
        !source.contains("http://", ignoreCase = true) && !source.contains("https://", ignoreCase = true)) return 0
    val root = org.intellij.markdown.parser.MarkdownParser(
        org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor(),
    ).buildMarkdownTreeFromString(source)
    val references = MarkdownDocumentReferenceHandler(markdownReferenceDefinitions(root, source))
    return markdownImageOccurrences(source, root, references, "answer").keys.count { it.startOffset < beforeOffset }
}
