package com.newoether.agora.api.util

/**
 * Incremental lexer for model-emitted reasoning delimiters.
 *
 * It deliberately operates before Markdown rendering, so it also tracks fenced and inline code:
 * reserved-looking tags inside code are literal user-visible text, never protocol delimiters.
 * Every possible chunk boundary is supported by retaining only a suffix that may still become a
 * marker or Markdown delimiter on the next feed.
 */
internal class IncrementalThinkingParser(
    private val startInThinking: Boolean = false,
) {
    private data class Marker(
        val start: String,
        val ends: List<String>,
        val implicit: Boolean = false,
    )

    private sealed interface CodeState {
        data object None : CodeState
        data class Inline(val ticks: Int) : CodeState
        data class Fence(val character: Char, val length: Int) : CodeState
    }

    private val channelEnds = listOf(
        "<|end|>",
        "<|channel|>final<|message|>",
        "<|start|>assistant<|channel|>final<|message|>",
    )
    private val markers = listOf(
        Marker("<think>", listOf("</think>")),
        Marker("<thinking>", listOf("</thinking>")),
        Marker("<reasoning>", listOf("</reasoning>")),
        Marker("<analysis>", listOf("</analysis>")),
        Marker("<thought>", listOf("</thought>")),
        Marker("<|channel|>thought<|message|>", channelEnds),
        Marker("<|channel|>reasoning<|message|>", channelEnds),
        Marker("<|channel|>analysis<|message|>", channelEnds),
        Marker("<|start|>assistant<|channel|>thought<|message|>", channelEnds),
        Marker("<|start|>assistant<|channel|>reasoning<|message|>", channelEnds),
        Marker("<|start|>assistant<|channel|>analysis<|message|>", channelEnds),
        // Legacy local templates seen in older llama.cpp model cards.
        Marker("<|channel>thought\n", listOf("<channel|>")),
        Marker("<|channel>analysis\n", listOf("<channel|>")),
        Marker("<|channel>thought ", listOf("<channel|>")),
        Marker("<|channel>analysis ", listOf("<channel|>")),
    )
    private val implicitMarker = Marker(
        start = "",
        ends = implicitThinkingCloseMarkers,
        implicit = true,
    )
    private val markerStack = ArrayDeque<Marker>()
    private var implicitBoundaryClosed = false
    private var codeState: CodeState = CodeState.None
    private var pending = ""
    private var linePrefix = true
    private var lineIndent = 0
    private var indentedCodeLine = false
    private var lastThinkingEnabled = true

    init {
        seedImplicitMarker()
    }

    val inThinking: Boolean get() = markerStack.isNotEmpty()
    val pendingContent: String get() = pending

    suspend fun feed(
        content: String,
        thinkingEnabled: Boolean,
        onText: suspend (String) -> Unit,
        onThought: suspend (String) -> Unit,
    ) {
        if (content.isEmpty()) return
        lastThinkingEnabled = thinkingEnabled
        pending += content
        drain(
            final = false,
            thinkingEnabled = thinkingEnabled,
            onText = onText,
            onThought = onThought,
        )
    }

    suspend fun flush(
        thinkingEnabled: Boolean = lastThinkingEnabled,
        onText: suspend (String) -> Unit,
        onThought: suspend (String) -> Unit,
    ) {
        drain(
            final = true,
            thinkingEnabled = thinkingEnabled,
            onText = onText,
            onThought = onThought,
        )
        pending = ""
    }

    fun reset() {
        markerStack.clear()
        implicitBoundaryClosed = false
        seedImplicitMarker()
        codeState = CodeState.None
        pending = ""
        linePrefix = true
        lineIndent = 0
        indentedCodeLine = false
        lastThinkingEnabled = true
    }

    private suspend fun drain(
        final: Boolean,
        thinkingEnabled: Boolean,
        onText: suspend (String) -> Unit,
        onThought: suspend (String) -> Unit,
    ) {
        val text = StringBuilder()
        val thought = StringBuilder()
        var index = 0

        suspend fun emitText() {
            if (text.isNotEmpty()) {
                onText(text.toString())
                text.clear()
            }
        }

        suspend fun emitThought() {
            if (thought.isNotEmpty()) {
                if (thinkingEnabled) onThought(thought.toString())
                thought.clear()
            }
        }

        suspend fun switchDestination() {
            if (inThinking) emitText() else emitThought()
        }

        fun appendLiteral(value: String) {
            if (inThinking) thought.append(value) else text.append(value)
            value.forEach(::updateLineState)
        }

        while (index < pending.length) {
            val remainder = pending.substring(index)

            if (codeState == CodeState.None && !indentedCodeLine) {
                val closing = markerStack.lastOrNull()
                if (closing != null) {
                    val closeMatch = endMatch(remainder, closing.ends)
                    if (closeMatch.partial && !final) break
                    if (closeMatch.length != null) {
                        emitThought()
                        val closedMarker = markerStack.removeLast()
                        if (closedMarker.implicit) implicitBoundaryClosed = true
                        index += closeMatch.length
                        resetMarkdownStateAtSegmentBoundary()
                        switchDestination()
                        continue
                    }
                    if (closing.implicit) {
                        val opener = toolCallOpenerMatch(remainder)
                        if (opener.partial && !final) break
                        if (opener.matched) {
                            // A tool call inside an unclosed reasoning block ends the thought
                            // channel but stays in the stream: the tagged parser needs the literal
                            // opener to turn the payload into an executable call.
                            emitThought()
                            markerStack.removeLast()
                            implicitBoundaryClosed = true
                            resetMarkdownStateAtSegmentBoundary()
                            switchDestination()
                            continue
                        }
                    }
                    val nested = findStartMatch(remainder)
                    if (nested.partial && !final) break
                    nested.marker?.let { marker ->
                        if (!closing.implicit) markerStack.addLast(marker)
                        index += marker.start.length
                        resetMarkdownStateAtSegmentBoundary()
                        continue
                    }
                } else if (!implicitBoundaryClosed) {
                    val start = findStartMatch(remainder)
                    if (start.partial && !final) break
                    start.marker?.let { marker ->
                        emitText()
                        markerStack.addLast(marker)
                        index += marker.start.length
                        resetMarkdownStateAtSegmentBoundary()
                        continue
                    }
                }
            }

            val codeRun = markdownDelimiterRun(index, final)
            if (codeRun == null && !final && isPotentialMarkdownDelimiter(index)) break
            if (codeRun != null) {
                appendLiteral(pending.substring(index, index + codeRun.length))
                codeState = codeRun.nextState
                index += codeRun.length
                continue
            }

            appendLiteral(pending[index].toString())
            index++
        }

        emitText()
        emitThought()
        pending = pending.substring(index)
        if (final && pending.isNotEmpty()) {
            if (inThinking) {
                if (thinkingEnabled) onThought(pending)
            } else {
                onText(pending)
            }
            pending.forEach(::updateLineState)
            pending = ""
        }
    }

    private fun seedImplicitMarker() {
        if (startInThinking) markerStack.addLast(implicitMarker)
    }

    private enum class Match { NONE, PARTIAL, FULL }

    private data class StartMatch(val marker: Marker? = null, val partial: Boolean = false)
    private data class EndMatch(val length: Int? = null, val partial: Boolean = false)

    private fun findStartMatch(remainder: String): StartMatch {
        for (marker in markers) {
            when (markerMatch(remainder, marker.start)) {
                Match.FULL -> return StartMatch(marker = marker)
                Match.PARTIAL -> return StartMatch(partial = true)
                Match.NONE -> Unit
            }
        }
        return StartMatch()
    }

    private fun markerMatch(remainder: String, marker: String): Match {
        if (!remainder.startsWith(marker, ignoreCase = true)) {
            return if (
                remainder.length < marker.length &&
                marker.startsWith(remainder, ignoreCase = true)
            ) {
                Match.PARTIAL
            } else {
                Match.NONE
            }
        }
        return Match.FULL
    }

    private fun endMatch(remainder: String, endings: List<String>): EndMatch {
        var partial = false
        for (ending in endings) {
            when (markerMatch(remainder, ending)) {
                Match.FULL -> return EndMatch(length = ending.length)
                Match.PARTIAL -> partial = true
                Match.NONE -> Unit
            }
        }
        return EndMatch(partial = partial)
    }

    private data class OpenerMatch(val matched: Boolean = false, val partial: Boolean = false)

    private fun toolCallOpenerMatch(remainder: String): OpenerMatch {
        var partial = false
        for (opener in implicitThinkingToolCallOpeners) {
            when (markerMatch(remainder, opener)) {
                Match.FULL -> return OpenerMatch(matched = true)
                Match.PARTIAL -> partial = true
                Match.NONE -> Unit
            }
        }
        return OpenerMatch(partial = partial)
    }

    private data class CodeRun(val length: Int, val nextState: CodeState)

    private fun markdownDelimiterRun(index: Int, final: Boolean): CodeRun? {
        val character = pending[index]
        val state = codeState
        return when (state) {
            CodeState.None -> {
                if (indentedCodeLine) return null
                if (character != '`' && character != '~') return null
                val run = countRun(index, character)
                if (!final && index + run == pending.length) return null
                when {
                    linePrefix && lineIndent <= 3 && run >= 3 ->
                        CodeRun(run, CodeState.Fence(character, run))
                    character == '`' -> CodeRun(run, CodeState.Inline(run))
                    else -> null
                }
            }
            is CodeState.Inline -> {
                if (character != '`') return null
                val run = countRun(index, character)
                if (!final && index + run == pending.length) return null
                CodeRun(run, if (run == state.ticks) CodeState.None else state)
            }
            is CodeState.Fence -> {
                if (!linePrefix || lineIndent > 3 || character != state.character) return null
                val run = countRun(index, character)
                if (!final && index + run == pending.length) return null
                CodeRun(run, if (run >= state.length) CodeState.None else state)
            }
        }
    }

    private fun isPotentialMarkdownDelimiter(index: Int): Boolean {
        if (indentedCodeLine) return false
        val character = pending[index]
        return when (val state = codeState) {
            CodeState.None -> character == '`' || (linePrefix && character == '~')
            is CodeState.Inline -> character == '`'
            is CodeState.Fence ->
                linePrefix && lineIndent <= 3 && character == state.character
        }
    }

    private fun countRun(index: Int, character: Char): Int {
        var end = index
        while (end < pending.length && pending[end] == character) end++
        return end - index
    }

    private fun updateLineState(character: Char) {
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

    private fun resetMarkdownStateAtSegmentBoundary() {
        codeState = CodeState.None
        linePrefix = true
        lineIndent = 0
        indentedCodeLine = false
    }
}
