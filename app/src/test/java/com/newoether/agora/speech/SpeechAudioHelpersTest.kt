package com.newoether.agora.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechAudioHelpersTest {
    @Test
    fun wavHeaderDescribesPcm() {
        val pcm = ByteArray(320) { it.toByte() }
        val wav = encodeWav(pcm)
        assertEquals(44 + pcm.size, wav.size)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size, header.getInt(40))
        assertEquals(pcm[0], wav[44])
    }

    @Test
    fun frameLevelMapsDbfsToUnitRange() {
        assertEquals(0f, pcmFrameLevel(ShortArray(160), 160), 0f)
        assertEquals(0f, pcmFrameLevel(ShortArray(4) { 1000 }, 0), 0f)
        val full = pcmFrameLevel(ShortArray(160) { Short.MAX_VALUE }, 160)
        assertEquals(1f, full, 0.001f)
        // -20 dBFS sits two thirds of the way up the -60..0 range.
        val quiet = pcmFrameLevel(ShortArray(160) { 3277 }, 160)
        assertEquals(2f / 3f, quiet, 0.01f)
    }

    @Test
    fun vocabularySplitsOnCommonSeparators() {
        assertEquals(
            listOf("Agora", "Houdini", "以太", "Kiro", "C4D"),
            speechVocabularyTerms(" Agora, Houdini，以太\nKiro; Agora；C4D ,, "),
        )
        assertTrue(speechVocabularyTerms("  ").isEmpty())
    }

    @Test
    fun languageTagsNormalize() {
        assertEquals("zh", SpeechLanguages.primary("zh-CN"))
        assertEquals("en", SpeechLanguages.primary("EN_us"))
        assertEquals("", SpeechLanguages.primary(" "))
        assertEquals("zh-CN", SpeechLanguages.androidTag("zh"))
        assertEquals("yue-Hant-HK", SpeechLanguages.androidTag("yue"))
        assertEquals("ja", SpeechLanguages.androidTag("ja"))
        assertEquals("", SpeechLanguages.androidTag(""))
    }

    @Test
    fun presetLookupFallsBackToCustom() {
        assertEquals("openai", SpeechCloudPresets.byId("openai").id)
        assertEquals(SpeechCloudPresets.CUSTOM_ID, SpeechCloudPresets.byId("missing").id)
    }
}
