package com.newoether.agora.voice

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/** Revises only this session's span; user edits or cursor movement fence off further callbacks. */
internal class VoiceDraftTarget(private val state: TextFieldState, private val isCurrent: () -> Boolean) {
    private var cancelled = false
    private var expectedText: String? = null
    private var expectedSelection: TextRange? = null
    private var spanStart = 0
    private var spanEnd = 0
    fun cancel() { cancelled = true }
    fun insert(transcript: String): Boolean {
        val inserted = preview(transcript)
        cancelled = true
        return inserted
    }

    fun preview(transcript: String): Boolean {
        if (cancelled || !isCurrent() || transcript.isBlank()) return false
        if (expectedText != null && (state.text.toString() != expectedText || state.selection != expectedSelection)) {
            cancel()
            return false
        }
        state.edit {
            val content = asCharSequence()
            val start = if (expectedText == null) minOf(selection.start, selection.end) else spanStart
            val end = if (expectedText == null) maxOf(selection.start, selection.end) else spanEnd
            val spoken = transcript.trim()
            val before = if (start > 0 && !content[start - 1].isWhitespace() && spoken.first().isLetterOrDigit()) " " else ""
            val after = if (end < length && content[end].isLetterOrDigit()) " " else ""
            val insertion = before + spoken + after
            replace(start, end, insertion)
            selection = TextRange(start + insertion.length)
            spanStart = start
            spanEnd = start + insertion.length
        }
        expectedText = state.text.toString()
        expectedSelection = state.selection
        return true
    }
}
