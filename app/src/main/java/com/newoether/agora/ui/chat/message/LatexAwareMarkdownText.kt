package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import com.mikepenz.markdown.compose.LocalImageTransformer
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.model.MarkdownAnnotatorConfig
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import com.newoether.agora.ui.components.LatexImageTransformer
import com.newoether.agora.ui.components.latexSourceForLink
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

/** One inline image slot in a paragraph's annotated text, in text order. */
internal data class InlineImageSlot(
    val link: String,
    val start: Int,
    val end: Int,
    val node: ASTNode?,
    /** The formula's original source, or null when the image is not a formula. */
    val latexSource: String?,
)

internal fun inlineImageSlots(content: AnnotatedString, node: ASTNode): List<InlineImageSlot> {
    val imageNodes = mutableListOf<ASTNode>()
    fun visit(n: ASTNode) {
        if (n.type == MarkdownElementTypes.IMAGE) imageNodes += n
        n.children.forEach(::visit)
    }
    visit(node)
    return content.getStringAnnotations(0, content.length)
        .filter { it.item.startsWith("${MARKDOWN_TAG_IMAGE_URL}_") }
        .sortedBy { it.start }
        .mapIndexed { index, annotation ->
            val link = annotation.item.removePrefix("${MARKDOWN_TAG_IMAGE_URL}_")
            InlineImageSlot(link, annotation.start, annotation.end, imageNodes.getOrNull(index), latexSourceForLink(link))
        }
}

/**
 * Which slots leave the line. A formula leaves it only when it cannot fit the paragraph's full
 * width or is taller than the renderer's block threshold. A formula that merely looks wide is an
 * ordinary inline element and wraps like a long word, so it is never pulled out of its sentence.
 * Every other image leaves the line too once the paragraph splits, because text segments can no
 * longer map their images back to AST nodes.
 *
 * A promoted formula keeps the paragraph's inline text style: it moves onto its own line so it can
 * be selected and scrolled as one unit, not because it becomes display math.
 */
internal fun blockImageSlots(
    slots: List<InlineImageSlot>,
    maxWidthPx: Float,
    lineHeightPx: Float,
    formulaSize: (String) -> androidx.compose.ui.geometry.Size?,
): List<InlineImageSlot> {
    fun leavesLine(slot: InlineImageSlot): Boolean {
        if (slot.latexSource == null) return false
        val size = formulaSize(slot.link) ?: return false
        return size.width > maxWidthPx ||
            (lineHeightPx > 0f && size.height > lineHeightPx * MarkdownAnnotatorConfig.BLOCK_FALLBACK_LINE_MULTIPLIER)
    }
    if (slots.none(::leavesLine)) return emptyList()
    return slots.filter { slot -> slot.latexSource == null || leavesLine(slot) }
}

/**
 * Paragraph text that moves a formula onto its own selectable, scrollable line ([DisplayLatexBlock])
 * only when that formula cannot fit the paragraph or is taller than a block. Everything else is
 * ordinary inline content: it uses TeX text style and wraps with the sentence. When split, search
 * positions are not reported for that paragraph.
 */
@Composable
internal fun LatexAwareMarkdownText(
    content: AnnotatedString,
    node: ASTNode,
    modifier: Modifier,
    style: TextStyle,
    sourceContent: String,
    promoteWideLatex: Boolean,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val transformer = LocalImageTransformer.current as? LatexImageTransformer
    val slots = remember(content, node, promoteWideLatex, transformer) {
        if (!promoteWideLatex || transformer == null) emptyList() else inlineImageSlots(content, node)
    }
    if (transformer == null || slots.none { it.latexSource != null }) {
        AnimatedMarkdownText(content, node, modifier, style, sourceContent, onTextLayout)
        return
    }
    val density = LocalDensity.current
    val lineHeightPx = with(density) {
        val lineHeight = if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize
        if (lineHeight.isSpecified) lineHeight.toPx() else 0f
    }
    BoxWithConstraints(modifier) {
        val blocks = if (constraints.hasBoundedWidth) {
            blockImageSlots(slots, constraints.maxWidth.toFloat(), lineHeightPx, transformer::formulaSize)
        } else {
            emptyList()
        }
        if (blocks.isEmpty()) {
            AnimatedMarkdownText(content, node, Modifier, style, sourceContent, onTextLayout)
            return@BoxWithConstraints
        }
        val components = LocalMarkdownComponents.current
        val typography = LocalMarkdownTypography.current
        Column {
            var cursor = 0
            blocks.forEach { block ->
                if (block.start > cursor) {
                    AnimatedMarkdownText(content.subSequence(cursor, block.start), node, Modifier, style, sourceContent)
                }
                if (block.latexSource != null) {
                    // Keep the original inline link so the promoted line is typeset in text style.
                    DisplayLatexBlock(block.link, block.latexSource, style)
                } else if (block.node != null) {
                    components.image(MarkdownComponentModel(sourceContent, block.node, typography))
                }
                cursor = block.end
            }
            if (cursor < content.length) {
                AnimatedMarkdownText(content.subSequence(cursor, content.length), node, Modifier, style, sourceContent)
            }
        }
    }
}
