package com.newoether.agora.voice

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import com.newoether.agora.data.ApiKeyEntry
import org.junit.Assert.*
import org.junit.Test

class VoiceTypingTest {
    @Test fun defaultOffAndInvalidFutureSettingsCannotEnableRecording() {
        assertFalse(VoiceTypingConfig.decode(null).enabled)
        assertFalse(VoiceTypingConfig.decode("broken").enabled)
        assertFalse(VoiceTypingConfig.decode("""{"enabled":true,"mode":"UNKNOWN"}""").enabled)
        assertEquals(VoiceTypingMode.ANDROID, VoiceTypingConfig().mode)
    }

    @Test fun providerCredentialsStayWithTheirCompatibleProvider() {
        val openai = ApiKeyEntry(id = "openai", name = "OpenAI", key = "openai-secret", provider = "OpenAI")
        val groq = ApiKeyEntry(id = "groq", name = "Groq", key = "groq-secret", provider = "Groq")
        val config = VoiceTypingConfig().select(VoiceTypingMode.OPENAI)
        assertEquals("openai-secret", voiceProviderKey(config, listOf(groq, openai), emptyMap()))
        assertEquals("", voiceProviderKey(config.copy(apiKeyId = groq.id), listOf(groq, openai), emptyMap()))
        assertEquals(listOf(openai), voiceKeyCandidates(config, listOf(groq, openai)))
        assertEquals("", voiceProviderKey(config.select(VoiceTypingMode.COMPATIBLE), listOf(openai), emptyMap()))
    }

    @Test fun transcriptionEndpointNormalizesPathsAndRejectsCredentialUrls() {
        val config = VoiceTypingConfig().select(VoiceTypingMode.GROQ)
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", config.transcriptionUrl().toString())
        assertEquals("https://api.openai.com/v1/audio/transcriptions", config.select(VoiceTypingMode.OPENAI).transcriptionUrl().toString())
        assertThrows(IllegalArgumentException::class.java) { config.copy(baseUrl = "https://user:secret@example.com/v1").transcriptionUrl() }
        assertThrows(IllegalArgumentException::class.java) { config.copy(baseUrl = "http://example.com/v1").transcriptionUrl() }
        assertThrows(IllegalArgumentException::class.java) { config.copy(baseUrl = "https://example.com/v1?key=secret").transcriptionUrl() }
    }

    @Test fun dictationEditsTheDraftSelectionWithoutSendingOrOverwritingOtherText() {
        val text = TextFieldState("Hello friend!", initialSelection = TextRange(6, 12))
        val target = VoiceDraftTarget(text) { true }
        assertTrue(target.insert("doctor"))
        assertEquals("Hello doctor!", text.text.toString())
        val appended = TextFieldState("Hello", initialSelection = TextRange(5))
        assertTrue(VoiceDraftTarget(appended) { true }.insert("there"))
        assertEquals("Hello there", appended.text.toString())
    }

    @Test fun lateDictationCannotModifyADismissedOrFrozenDraft() {
        val text = TextFieldState("keep this")
        val target = VoiceDraftTarget(text) { true }
        target.cancel()
        assertFalse(target.insert("late result"))
        assertFalse(VoiceDraftTarget(text) { false }.insert("late result"))
        assertEquals("keep this", text.text.toString())
    }

    @Test fun liveHypothesesAndFinalTextReplaceOnlyTheDictatedSpan() {
        val text = TextFieldState("Hello friend!", initialSelection = TextRange(6, 12))
        val target = VoiceDraftTarget(text) { true }
        assertTrue(target.preview("doc"))
        assertEquals("Hello doc!", text.text.toString())
        assertTrue(target.preview("doctor Smith"))
        assertEquals("Hello doctor Smith!", text.text.toString())
        assertTrue(target.insert("Doctor Smyth"))
        assertEquals("Hello Doctor Smyth!", text.text.toString())
        assertFalse(target.preview("duplicate"))
    }

    @Test fun manualEditsAndCancellationKeepVisibleTextAndRejectLaterRevisions() {
        val text = TextFieldState()
        val target = VoiceDraftTarget(text) { true }
        target.preview("visible words")
        text.edit { append(" edited") }
        assertFalse(target.insert("overwrite"))
        assertEquals("visible words edited", text.text.toString())
        val cancelled = VoiceDraftTarget(text) { true }
        cancelled.preview("more")
        val visible = text.text.toString()
        cancelled.cancel()
        assertFalse(cancelled.preview("late"))
        assertEquals(visible, text.text.toString())
    }

    @Test fun movingTheCursorPreventsRecognitionFromStealingItBack() {
        val text = TextFieldState()
        val target = VoiceDraftTarget(text) { true }
        target.preview("hello")
        text.edit { selection = TextRange.Zero }
        assertFalse(target.preview("hello there"))
        assertEquals("hello", text.text.toString())
        assertEquals(TextRange.Zero, text.selection)
    }
}
