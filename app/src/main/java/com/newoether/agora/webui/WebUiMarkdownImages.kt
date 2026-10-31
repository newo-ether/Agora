package com.newoether.agora.webui

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.ui.chat.message.MarkdownDocumentReferenceHandler
import com.newoether.agora.ui.chat.message.markdownImageLink
import com.newoether.agora.ui.chat.message.markdownReferenceDefinitions
import com.newoether.agora.ui.chat.message.parseShellMediaLink
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Only parsed image destinations grant device reads; prose, code and ordinary links do not. */
internal fun ChatMessage.ownsShellMarkdownImage(link: String): Boolean {
    val target = parseShellMediaLink(link) ?: return false
    return (listOf(text) + segments.orEmpty().filter { it.type == "answer" || it.type == "thought" }
        .map { it.content }).distinct().any { source ->
        if (!source.contains("shell://", ignoreCase = true)) return@any false
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        val references = MarkdownDocumentReferenceHandler(markdownReferenceDefinitions(root, source))
        fun contains(node: ASTNode): Boolean =
            if (node.type == MarkdownElementTypes.IMAGE) {
                markdownImageLink(source, node, references)?.let(::parseShellMediaLink)?.key == target.key
            } else node.children.any(::contains)
        contains(root)
    }
}
