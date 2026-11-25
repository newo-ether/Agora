package com.newoether.agora.speech

import com.newoether.agora.data.SpeechEngineType
import com.newoether.agora.data.SpeechProtocols
import com.newoether.agora.data.SpeechRecognitionConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class CloudSpeechProtocolTest {
    private val wav = byteArrayOf(1, 2, 3, 4)
    private val encoder: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }

    private fun config(
        protocol: String,
        baseUrl: String,
        model: String,
        language: String = "",
        vocabulary: String = "",
    ) = SpeechRecognitionConfig(
        name = "test",
        type = SpeechEngineType.CLOUD,
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
        apiKey = "sk-test",
        language = language,
        vocabulary = vocabulary,
    )

    private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

    private fun Request.jsonBody() = Json.parseToJsonElement(bodyText()).jsonObject

    @Test
    fun gptTranscribeUsesArrayHints() {
        val request = CloudSpeechProtocol.buildRequest(
            config(SpeechProtocols.OPENAI, "https://api.openai.com/v1/", "gpt-transcribe", "zh-CN", "Agora, Houdini"),
            wav,
            encoder,
        )
        assertEquals("https://api.openai.com/v1/audio/transcriptions", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
        val body = request.bodyText()
        assertTrue(body.contains("name=\"languages[]\""))
        assertTrue(body.contains("name=\"keywords[]\""))
        assertTrue(body.contains("Houdini"))
        assertFalse(body.contains("name=\"prompt\""))
        assertTrue(body.contains("name=\"response_format\""))
    }

    @Test
    fun whisperCompatibleServersUseLanguageAndPrompt() {
        val request = CloudSpeechProtocol.buildRequest(
            config(SpeechProtocols.OPENAI, "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", "en", "Agora;Kotlin"),
            wav,
            encoder,
        )
        val body = request.bodyText()
        assertTrue(body.contains("name=\"language\""))
        assertTrue(body.contains("Agora, Kotlin"))
        assertFalse(body.contains("languages[]"))
    }

    @Test
    fun openAiOmitsAuthorizationWithoutKey() {
        val request = CloudSpeechProtocol.buildRequest(
            config(SpeechProtocols.OPENAI, "http://127.0.0.1:8000/v1", "whisper").copy(apiKey = ""),
            wav,
            encoder,
        )
        assertNull(request.header("Authorization"))
    }

    @Test
    fun blankBaseUrlIsRejected() {
        try {
            CloudSpeechProtocol.buildRequest(config(SpeechProtocols.OPENAI, " ", "whisper-1"), wav, encoder)
            fail("expected failure")
        } catch (_: SpeechRecognitionException) {
        }
    }

    @Test
    fun geminiTranscribeModelUsesInteractions() {
        val request = CloudSpeechProtocol.buildRequest(
            config(SpeechProtocols.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-3.5-transcribe", "zh-CN", "Agora"),
            wav,
            encoder,
        )
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", request.url.toString())
        assertEquals("sk-test", request.header("x-goog-api-key"))
        val json = request.jsonBody()
        assertEquals("models/gemini-3.5-transcribe", json["model"]!!.jsonPrimitive.content)
        val audio = json["input"]!!.jsonArray[0].jsonObject
        assertEquals(encoder(wav), audio["data"]!!.jsonPrimitive.content)
        val transcription = json["generation_config"]!!.jsonObject["transcription_config"]!!.jsonObject
        assertEquals("zh-CN", transcription["language_codes"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("Agora", transcription["custom_vocabulary"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun generalGeminiModelUsesGenerateContent() {
        val request = CloudSpeechProtocol.buildRequest(
            config(SpeechProtocols.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-3-flash"),
            wav,
            encoder,
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash:generateContent",
            request.url.toString(),
        )
        val parts = request.jsonBody()["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("audio/wav", parts[1].jsonObject["inline_data"]!!.jsonObject["mime_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun dashScopeSendsDataUriAndContext() {
        val request = CloudSpeechProtocol.buildRequest(
            config(SpeechProtocols.DASHSCOPE, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-asr-flash", "zh", "以太"),
            wav,
            encoder,
        )
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
        val json = request.jsonBody()
        val messages = json["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        val audio = messages[1].jsonObject["content"]!!.jsonArray[0].jsonObject["input_audio"]!!.jsonObject
        assertEquals("data:audio/wav;base64,${encoder(wav)}", audio["data"]!!.jsonPrimitive.content)
        assertEquals("zh", json["asr_options"]!!.jsonObject["language"]!!.jsonPrimitive.content)
    }

    @Test
    fun dashScopeSkipsSystemMessageWithoutVocabulary() {
        val request = CloudSpeechProtocol.buildRequest(
            config(SpeechProtocols.DASHSCOPE, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-asr-flash"),
            wav,
            encoder,
        )
        val messages = request.jsonBody()["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun elevenLabsUsesModelIdAndKeyterms() {
        val request = CloudSpeechProtocol.buildRequest(
            config(SpeechProtocols.ELEVENLABS, "https://api.elevenlabs.io/v1", "scribe_v3", "en-US", "Agora\nKiro"),
            wav,
            encoder,
        )
        assertEquals("https://api.elevenlabs.io/v1/speech-to-text", request.url.toString())
        assertEquals("sk-test", request.header("xi-api-key"))
        val body = request.bodyText()
        assertTrue(body.contains("name=\"model_id\""))
        assertTrue(body.contains("name=\"language_code\""))
        assertEquals(2, Regex("name=\"keyterms\"").findAll(body).count())
    }

    @Test
    fun deepgramPutsOptionsInQuery() {
        val request = CloudSpeechProtocol.buildRequest(
            config(SpeechProtocols.DEEPGRAM, "https://api.deepgram.com/v1", "nova-3", vocabulary = "Agora"),
            wav,
            encoder,
        )
        val url = request.url
        assertEquals("/v1/listen", url.encodedPath)
        assertEquals("nova-3", url.queryParameter("model"))
        assertEquals("multi", url.queryParameter("language"))
        assertEquals("Agora", url.queryParameter("keyterm"))
        assertEquals("Token sk-test", request.header("Authorization"))
    }

    @Test
    fun parsesEveryResponseShape() {
        assertEquals("hello", CloudSpeechProtocol.parseTranscript(SpeechProtocols.OPENAI, """{"text":" hello "}"""))
        assertEquals("a", CloudSpeechProtocol.parseTranscript(SpeechProtocols.GEMINI, """{"output_text":"a"}"""))
        assertEquals(
            "ab",
            CloudSpeechProtocol.parseTranscript(
                SpeechProtocols.GEMINI,
                """{"steps":[{"type":"thought","content":[{"text":"x"}]},{"type":"model_output","content":[{"text":"a"},{"text":"b"}]}]}""",
            ),
        )
        assertEquals(
            "hi",
            CloudSpeechProtocol.parseTranscript(
                SpeechProtocols.GEMINI,
                """{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}""",
            ),
        )
        assertEquals(
            "你好",
            CloudSpeechProtocol.parseTranscript(
                SpeechProtocols.DASHSCOPE,
                """{"choices":[{"message":{"content":"你好"}}]}""",
            ),
        )
        assertEquals(
            "yo",
            CloudSpeechProtocol.parseTranscript(
                SpeechProtocols.DEEPGRAM,
                """{"results":{"channels":[{"alternatives":[{"transcript":"yo"}]}]}}""",
            ),
        )
        assertEquals("ok", CloudSpeechProtocol.parseTranscript(SpeechProtocols.ELEVENLABS, """{"text":"ok"}"""))
    }

    @Test
    fun unrecognizedResponsesThrow() {
        for (body in listOf("not json", """{"other":1}""")) {
            try {
                CloudSpeechProtocol.parseTranscript(SpeechProtocols.OPENAI, body)
                fail("expected failure for $body")
            } catch (_: SpeechRecognitionException) {
            }
        }
    }

    @Test
    fun errorMessagePrefersApiText() {
        assertEquals("bad key", CloudSpeechProtocol.errorMessage(401, """{"error":{"message":"bad key"}}"""))
        assertEquals("quota", CloudSpeechProtocol.errorMessage(429, """{"detail":{"message":"quota"}}"""))
        assertEquals("nope", CloudSpeechProtocol.errorMessage(400, """{"detail":"nope"}"""))
        assertEquals("HTTP 502", CloudSpeechProtocol.errorMessage(502, "<html>"))
        val long = CloudSpeechProtocol.errorMessage(500, """{"message":"${"x".repeat(300)}"}""")
        assertEquals(241, long.length)
    }
}
