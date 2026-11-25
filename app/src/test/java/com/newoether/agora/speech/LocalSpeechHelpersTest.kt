package com.newoether.agora.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpeechHelpersTest {
    @Test
    fun joinSegmentsSpacesOnlyBetweenLatinWords() {
        assertEquals("hello world", joinSegments(listOf("hello", "world")))
        assertEquals("你好世界", joinSegments(listOf("你好", "世界")))
        assertEquals("Done. Next", joinSegments(listOf("Done.", "Next")))
        assertEquals("用Kotlin", joinSegments(listOf("用", "", "Kotlin")))
    }

    @Test
    fun pcm16ToFloatDecodesLittleEndian() {
        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0x80.toByte(), 0xFF.toByte(), 0x7F)
        val out = pcm16ToFloat(pcm)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(-1f, out[1], 1e-6f)
        assertEquals(32767f / 32768f, out[2], 1e-6f)
    }

    @Test
    fun catalogPinsEveryFile() {
        val ids = LocalSpeechModels.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        LocalSpeechModels.all.forEach { model ->
            assertTrue(model.revision.matches(Regex("[0-9a-f]{40}")))
            model.files.forEach { file ->
                assertTrue(file.path, file.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue(file.path, file.size > 0)
            }
        }
    }

    @Test
    fun sourceUrlUsesPinnedRevision() {
        val model = LocalSpeechModels.all.first()
        val url = LocalSpeechSource.HF_MIRROR.url(model, model.files.first())
        assertEquals("https://hf-mirror.com/${model.repo}/resolve/${model.revision}/${model.files.first().path}", url)
    }
}
