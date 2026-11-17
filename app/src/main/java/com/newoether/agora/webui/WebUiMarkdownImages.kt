package com.newoether.agora.webui

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.ui.chat.message.MarkdownDocumentReferenceHandler
import com.newoether.agora.ui.chat.message.markdownImageOccurrences
import com.newoether.agora.ui.chat.message.markdownImageLink
import com.newoether.agora.ui.chat.message.markdownImageSnapshotKey
import com.newoether.agora.ui.chat.message.markdownReferenceDefinitions
import com.newoether.agora.ui.chat.message.mergeAdjacentSegments
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

/** Resolve a fragment's image ordinal against the complete durable document, never a caller path. */
internal fun ChatMessage.shellMarkdownImageOccurrence(
    link: String,
    sourceStart: Int,
    ordinal: Int,
    document: String,
): String? {
    if (sourceStart < 0 || ordinal < 0) return null
    val source = when {
        document == "answer" -> text
        document.startsWith("segment:") -> {
            val index = document.removePrefix("segment:").toIntOrNull() ?: return null
            mergeAdjacentSegments(segments.orEmpty()).filter { it.type != "answer" }
                .getOrNull(index)?.takeIf { it.type == "thought" }?.content ?: return null
        }
        else -> return null
    }
    if (sourceStart > source.length) return null
    val target = markdownImageSnapshotKey(link) ?: return null
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
    val references = MarkdownDocumentReferenceHandler(markdownReferenceDefinitions(root, source))
    val occurrence = markdownImageOccurrences(source, root, references, document).entries
        .asSequence().filter { it.key.startOffset >= sourceStart }.drop(ordinal).firstOrNull()
        ?: return null
    return occurrence.value.takeIf {
        markdownImageLink(source, occurrence.key, references)?.let(::markdownImageSnapshotKey) == target
    }
}
