package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerVoiceTextTest {
    @Test
    fun insertsAtCursorWithSpaceBetweenLatinWords() {
        val state = TextFieldState("hello world", TextRange(5))
        state.insertTranscript(" there ")
        assertEquals("hello there world", state.text.toString())
        assertEquals(TextRange(11), state.selection)
    }

    @Test
    fun noSpaceForChineseOrAfterPunctuation() {
        val chinese = TextFieldState("你好", TextRange(2))
        chinese.insertTranscript("世界")
        assertEquals("你好世界", chinese.text.toString())

        val latinAfterChinese = TextFieldState("你好", TextRange(2))
        latinAfterChinese.insertTranscript("Agora")
        assertEquals("你好Agora", latinAfterChinese.text.toString())

        val afterSpace = TextFieldState("hi ", TextRange(3))
        afterSpace.insertTranscript("there")
        assertEquals("hi there", afterSpace.text.toString())
    }

    @Test
    fun replacesSelectionAndIgnoresBlank() {
        val state = TextFieldState("abc XYZ", TextRange(4, 7))
        state.insertTranscript("def")
        assertEquals("abc def", state.text.toString())
        state.insertTranscript("   ")
        assertEquals("abc def", state.text.toString())
    }

    @Test
    fun elapsedFormatsMinutesAndSeconds() {
        assertEquals("0:00", formatSpeechElapsed(-5))
        assertEquals("0:09", formatSpeechElapsed(9_999))
        assertEquals("1:05", formatSpeechElapsed(65_000))
        assertEquals("5:00", formatSpeechElapsed(300_000))
    }
}
