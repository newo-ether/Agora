package com.newoether.agora.api.util

internal data class ImplicitThinkingCloseBoundary(
    val startIndex: Int,
    val endExclusive: Int,
)

/**
 * Tool-call openers that end implicit thinking without being consumed.
 *
 * The marker itself must survive into the answer channel because
 * [com.newoether.agora.api.openai.StreamingTextToolCallParser] needs the literal tag to turn the
 * payload into an executable call. Thinking models that were opened by their chat template rely on
 * this: without it the call is displayed as reasoning and never runs (issue #163).
 */
internal val implicitThinkingToolCallOpeners = listOf(
    "<tool_" + "call>",
    "<invoke",
    "<antml:invoke",
)

internal val implicitThinkingCloseMarkers = listOf(
    "</think>",
    "</thinking>",
    "</reasoning>",
    "</analysis>",
    "</thought>",
    "<|end|>",
    "<|channel|>final<|message|>",
    "<|start|>assistant<|channel|>final<|message|>",
    "<channel|>",
)

/**
 * Finds the first supported close marker outside inline, fenced, and indented Markdown code.
 *
 * Persisted compatibility uses the same close-marker vocabulary as the streaming parser while
 * remaining synchronous and allocation-light for Room projections.
 */
internal fun findImplicitThinkingClose(content: String): ImplicitThinkingCloseBoundary? {
    var inlineTicks = 0
    var fenceCharacter: Char? = null
    var fenceLength = 0
    var linePrefix = true
    var lineIndent = 0
    var indentedCodeLine = false
    var index = 0

    fun countRun(character: Char): Int {
        var end = index
        while (end < content.length && content[end] == character) end++
        return end - index
    }

    fun updateLineState(character: Char) {
        if (character == '\n') {
            linePrefix = true
            lineIndent = 0
            indentedCodeLine = false
        } else if (linePrefix && character == ' ' && lineIndent < 4) {
            lineIndent++
            if (lineIndent == 4) indentedCodeLine = true
        } else if (linePrefix && character == '\t') {
            indentedCodeLine = true
            linePrefix = false
        } else {
            linePrefix = false
        }
    }

    fun consume(length: Int) {
        repeat(length) { offset -> updateLineState(content[index + offset]) }
        index += length
    }

    while (index < content.length) {
        if (inlineTicks == 0 && fenceCharacter == null && !indentedCodeLine) {
            implicitThinkingCloseMarkers.firstOrNull { marker ->
                content.regionMatches(
                    thisOffset = index,
                    other = marker,
                    otherOffset = 0,
                    length = marker.length,
                    ignoreCase = true,
                )
            }?.let { marker ->
                return ImplicitThinkingCloseBoundary(index, index + marker.length)
            }
        }

        val character = content[index]
        val activeFence = fenceCharacter
        when {
            activeFence != null -> {
                if (linePrefix && lineIndent <= 3 && character == activeFence) {
                    val run = countRun(character)
                    if (run >= fenceLength) {
                        fenceCharacter = null
                        fenceLength = 0
                    }
                    consume(run)
                } else {
                    consume(1)
                }
            }
            inlineTicks > 0 -> {
                if (character == '`') {
                    val run = countRun(character)
                    if (run == inlineTicks) inlineTicks = 0
                    consume(run)
                } else {
                    consume(1)
                }
            }
            !indentedCodeLine && (character == '`' || character == '~') -> {
                val run = countRun(character)
                if (linePrefix && lineIndent <= 3 && run >= 3) {
                    fenceCharacter = character
                    fenceLength = run
                } else if (character == '`') {
                    inlineTicks = run
                }
                consume(run)
            }
            else -> consume(1)
        }
    }
    return null
}
