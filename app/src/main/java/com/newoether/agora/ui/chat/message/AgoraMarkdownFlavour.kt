package com.newoether.agora.ui.chat.message

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.StrikeThroughDelimiterParser
import org.intellij.markdown.parser.sequentialparsers.DelimiterParser
import org.intellij.markdown.parser.sequentialparsers.EmphasisLikeParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.TokensCache
import org.intellij.markdown.parser.sequentialparsers.impl.EmphStrongDelimiterParser

/**
 * GFM with one CJK emphasis fix.
 *
 * CommonMark's right-flanking rule refuses to close `**` or `_` when the character before the run
 * is punctuation while a letter follows. Latin prose rarely puts punctuation straight before a
 * closing run, but Chinese does exactly that: `比如**中文。**不会加粗`. The text then renders as
 * raw delimiters even though every reader expects bold. The run still has to be a real closing
 * run, so the fix only accepts CJK punctuation followed by a CJK letter.
 */
internal class AgoraMarkdownFlavour : GFMFlavourDescriptor() {
    override val sequentialParserManager: SequentialParserManager
        get() {
            val base = super.sequentialParserManager
            return object : SequentialParserManager() {
                override fun getParserSequence(): List<SequentialParser> {
                    val parsers: List<SequentialParser> = base.getParserSequence()
                    return parsers.map { parser ->
                        if (parser is EmphasisLikeParser) {
                            // Same family and order as GFM; only the emphasis flanking rule differs.
                            EmphasisLikeParser(
                                CjkEmphStrongDelimiterParser(),
                                StrikeThroughDelimiterParser(),
                            )
                        } else {
                            parser
                        }
                    }
                }
            }
        }
}

/** [EmphStrongDelimiterParser] with a CJK-aware closing rule; the pairing logic is unchanged. */
private class CjkEmphStrongDelimiterParser : DelimiterParser() {
    override fun scan(
        tokens: TokensCache,
        iterator: TokensCache.Iterator,
        delimiters: MutableList<Info>,
    ): Int {
        if (iterator.type != MarkdownTokenTypes.EMPH) return 0
        var stepsToAdvance = 1
        var rightIterator = iterator
        val marker = getType(rightIterator)
        for (index in 0 until maxAdvance) {
            if (rightIterator.rawLookup(1) != MarkdownTokenTypes.EMPH ||
                getType(rightIterator.advance()) != marker
            ) {
                break
            }
            rightIterator = rightIterator.advance()
            stepsToAdvance += 1
        }
        val (canOpen, canClose) = canOpenClose(
            tokens, iterator, rightIterator, canSplitText = marker == '*',
        )
        for (index in 0 until stepsToAdvance) {
            delimiters.add(
                Info(
                    tokenType = MarkdownTokenTypes.EMPH,
                    position = iterator.index + index,
                    length = stepsToAdvance,
                    canOpen = canOpen,
                    canClose = canClose,
                    marker = marker,
                ),
            )
        }
        return stepsToAdvance
    }

    override fun process(
        tokens: TokensCache,
        iterator: TokensCache.Iterator,
        delimiters: MutableList<Info>,
        result: SequentialParser.ParsingResultBuilder,
    ) {
        var isStrong = false
        for (index in delimiters.indices.reversed()) {
            if (isStrong) {
                isStrong = false
                continue
            }
            val opener = delimiters[index]
            if (opener.tokenType != MarkdownTokenTypes.EMPH || opener.closerIndex == -1) continue
            isStrong = EmphStrongDelimiterParser.areAdjacentSameMarkers(
                delimiters, index, opener.closerIndex,
            )
            val closer = delimiters[opener.closerIndex]
            result.withNode(
                if (isStrong) {
                    SequentialParser.Node(
                        range = opener.position - 1..closer.position + 2,
                        type = MarkdownElementTypes.STRONG,
                    )
                } else {
                    SequentialParser.Node(
                        range = opener.position..closer.position + 1,
                        type = MarkdownElementTypes.EMPH,
                    )
                },
            )
        }
    }

    override fun canOpenClose(
        tokens: TokensCache,
        left: TokensCache.Iterator,
        right: TokensCache.Iterator,
        canSplitText: Boolean,
    ): Pair<Boolean, Boolean> {
        val (canOpen, canClose) = super.canOpenClose(tokens, left, right, canSplitText)
        if (canClose) return canOpen to true
        // A closing run may sit right after CJK punctuation when CJK text continues; that is the
        // ordinary shape of Chinese emphasis and never an intra-word Latin delimiter.
        val closesCjk = left.charLookup(-1) != getType(left) &&
            !isWhitespace(left, -1) &&
            !isWhitespace(right, 1) &&
            isCjkPunctuation(left.charLookup(-1)) &&
            isCjkLetter(right.charLookup(1))
        return canOpen to closesCjk
    }
}

private fun isCjkPunctuation(char: Char?): Boolean {
    val code = char?.code ?: return false
    return code in 0x00B7..0x00B7 ||
        code in 0x2010..0x2027 ||
        code in 0x2030..0x205E ||
        code in 0x3000..0x303F ||
        code in 0xFE10..0xFE1F ||
        code in 0xFE30..0xFE4F ||
        code in 0xFF01..0xFF20 ||
        code in 0xFF3B..0xFF40 ||
        code in 0xFF5B..0xFF65
}

private fun isCjkLetter(char: Char?): Boolean {
    val code = char?.code ?: return false
    return code in 0x3005..0x3007 ||
        code in 0x3040..0x30FF ||
        code in 0x3400..0x4DBF ||
        code in 0x4E00..0x9FFF ||
        code in 0xAC00..0xD7AF ||
        code in 0xF900..0xFAFF
}
