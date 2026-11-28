package com.newoether.agora.speech

import android.util.Base64
import com.newoether.agora.api.HttpClient
import com.newoether.agora.data.SpeechProtocols
import com.newoether.agora.data.SpeechRecognitionConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A recognizer failure whose message is safe to show the user (no keys, bounded length). */
class SpeechRecognitionException(message: String) : IOException(message)

/**
 * Builds and parses the cloud ASR wire formats. Pure functions over [SpeechRecognitionConfig] so the
 * request shape of every protocol is unit-testable without a network.
 */
internal object CloudSpeechProtocol {
    private val json = Json { ignoreUnknownKeys = true }
    private val wavType = "audio/wav".toMediaType()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun buildRequest(config: SpeechRecognitionConfig, wav: ByteArray, base64: (ByteArray) -> String): Request {
        val base = config.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) throw SpeechRecognitionException("Base URL is empty")
        val key = config.apiKey.trim()
        val model = config.model.trim()
        val language = SpeechLanguages.primary(config.language)
        val terms = speechVocabularyTerms(config.vocabulary)
        return when (config.protocol) {
            SpeechProtocols.GEMINI -> geminiRequest(base, key, model, config.language.trim(), terms, base64(wav))
            SpeechProtocols.DASHSCOPE -> dashScopeRequest(base, key, model, language, terms, base64(wav))
            SpeechProtocols.ELEVENLABS -> elevenLabsRequest(base, key, model, language, terms, wav)
            SpeechProtocols.DEEPGRAM -> deepgramRequest(base, key, model, config.language.trim(), terms, wav)
            SpeechProtocols.DEEPSEEK -> deepSeekRequest(base, key, wav)
            else -> openAiRequest(base, key, model, language, terms, wav)
        }
    }

    private fun openAiRequest(
        base: String,
        key: String,
        model: String,
        language: String,
        terms: List<String>,
        wav: ByteArray,
    ): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(wavType))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
        // gpt-transcribe takes array hints; older models and compatible servers take a single
        // language plus a free-form prompt. Arrays use the bracket form the official SDK sends.
        if (model.startsWith("gpt-transcribe")) {
            if (language.isNotEmpty()) body.addFormDataPart("languages[]", language)
            terms.forEach { body.addFormDataPart("keywords[]", it) }
        } else {
            if (language.isNotEmpty()) body.addFormDataPart("language", language)
            if (terms.isNotEmpty()) body.addFormDataPart("prompt", terms.joinToString(", "))
        }
        return Request.Builder().url("$base/audio/transcriptions").post(body.build())
            .apply { if (key.isNotEmpty()) header("Authorization", "Bearer $key") }
            .build()
    }

    /** DeepSeek ASR: only the audio file; no model, language or vocabulary fields exist. */
    private fun deepSeekRequest(base: String, key: String, wav: ByteArray): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(wavType))
            .build()
        return Request.Builder().url("$base/tools/asr").post(body)
            .header("Authorization", "Bearer $key")
            .build()
    }

    private fun geminiRequest(
        base: String,
        key: String,
        model: String,
        language: String,
        terms: List<String>,
        audio: String,
    ): Request {
        val transcribeModel = model.contains("transcribe")
        val modelPath = if (model.startsWith("models/")) model else "models/$model"
        val payload = if (transcribeModel) {
            buildJsonObject {
                put("model", modelPath)
                put("input", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "audio")
                        put("data", audio)
                        put("mime_type", "audio/wav")
                    })
                })
                put("generation_config", buildJsonObject {
                    put("transcription_config", buildJsonObject {
                        put("language_codes", buildJsonArray { if (language.isNotEmpty()) add(JsonPrimitive(language)) })
                        if (terms.isNotEmpty()) put("custom_vocabulary", JsonArray(terms.map(::JsonPrimitive)))
                    })
                })
            }
        } else {
            // General Gemini models transcribe through generateContent with an explicit instruction.
            val instruction = buildString {
                append("Transcribe the speech in this audio verbatim. Reply with the transcript only, ")
                append("with no commentary. If there is no speech, reply with an empty message.")
                if (language.isNotEmpty()) append(" The expected language is $language.")
                if (terms.isNotEmpty()) append(" Terms that may appear: ${terms.joinToString(", ")}.")
            }
            buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", instruction) })
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", "audio/wav")
                                    put("data", audio)
                                })
                            })
                        })
                    })
                })
            }
        }
        val url = if (transcribeModel) "$base/interactions" else "$base/$modelPath:generateContent"
        return Request.Builder().url(url).post(payload.toString().toRequestBody(jsonType))
            .header("x-goog-api-key", key)
            .build()
    }

    private fun dashScopeRequest(
        base: String,
        key: String,
        model: String,
        language: String,
        terms: List<String>,
        audio: String,
    ): Request {
        val payload = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                if (terms.isNotEmpty()) {
                    // Qwen-ASR reads the system message as recognition context, not instructions.
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("text", terms.joinToString(", ")) })
                        })
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_audio")
                            put("input_audio", buildJsonObject { put("data", "data:audio/wav;base64,$audio") })
                        })
                    })
                })
            })
            put("stream", false)
            put("asr_options", buildJsonObject {
                if (language.isNotEmpty()) put("language", language)
                put("enable_itn", true)
            })
        }
        return Request.Builder().url("$base/chat/completions").post(payload.toString().toRequestBody(jsonType))
            .header("Authorization", "Bearer $key")
            .build()
    }

    private fun elevenLabsRequest(
        base: String,
        key: String,
        model: String,
        language: String,
        terms: List<String>,
        wav: ByteArray,
    ): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(wavType))
            .addFormDataPart("model_id", model)
            .addFormDataPart("tag_audio_events", "false")
        if (language.isNotEmpty()) body.addFormDataPart("language_code", language)
        terms.forEach { body.addFormDataPart("keyterms", it) }
        return Request.Builder().url("$base/speech-to-text").post(body.build())
            .header("xi-api-key", key)
            .build()
    }

    private fun deepgramRequest(
        base: String,
        key: String,
        model: String,
        language: String,
        terms: List<String>,
        wav: ByteArray,
    ): Request {
        val url = "$base/listen".toHttpUrlOrNull()?.newBuilder()
            ?: throw SpeechRecognitionException("Invalid base URL")
        url.addQueryParameter("model", model)
        url.addQueryParameter("smart_format", "true")
        url.addQueryParameter("punctuate", "true")
        url.addQueryParameter("language", language.ifEmpty { "multi" })
        terms.forEach { url.addQueryParameter("keyterm", it) }
        return Request.Builder().url(url.build()).post(wav.toRequestBody(wavType))
            .header("Authorization", "Token $key")
            .build()
    }

    /** Extracts the transcript, trimmed. Throws when the body is not a recognizable success shape. */
    fun parseTranscript(protocol: String, body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw SpeechRecognitionException("Unreadable response")
        val text = when (protocol) {
            SpeechProtocols.GEMINI -> geminiText(root)
            SpeechProtocols.DASHSCOPE -> root.path("choices")?.jsonArray?.firstOrNull()
                ?.jsonObject?.path("message")?.jsonObject?.string("content")
            SpeechProtocols.DEEPGRAM -> root.path("results")?.jsonObject?.path("channels")?.jsonArray
                ?.firstOrNull()?.jsonObject?.path("alternatives")?.jsonArray
                ?.firstOrNull()?.jsonObject?.string("transcript")
            else -> root.string("text")
        } ?: throw SpeechRecognitionException("Response has no transcript")
        return text.trim()
    }

    private fun geminiText(root: JsonObject): String? {
        root.string("output_text")?.let { return it }
        root.path("steps")?.let { steps ->
            val parts = (steps as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it.string("type") == null || it.string("type") == "model_output" }
                .flatMap { (it.path("content") as? JsonArray).orEmpty() }
                .mapNotNull { (it as? JsonObject)?.string("text") }
            if (parts.isNotEmpty()) return parts.joinToString("")
        }
        root.path("outputs")?.let { outputs ->
            val parts = (outputs as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("text") }
            if (parts.isNotEmpty()) return parts.joinToString("")
        }
        val candidate = root.path("candidates")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val parts = candidate.path("content")?.jsonObject?.path("parts") as? JsonArray ?: return ""
        return parts.mapNotNull { part ->
            (part as? JsonObject)?.takeIf { it.string("thought") != "true" }?.string("text")
        }.joinToString("")
    }

    /** A short error for the snackbar: the API's own message when present, else the status code. */
    fun errorMessage(code: Int, body: String): String {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = root?.let { obj ->
            val error = obj.path("error")
            (error as? JsonObject)?.string("message")
                ?: (error as? JsonPrimitive)?.contentOrNull
                ?: (obj.path("detail") as? JsonObject)?.string("message")
                ?: (obj.path("detail") as? JsonPrimitive)?.contentOrNull
                ?: obj.string("message")
                ?: obj.string("err_msg")
        }
        val text = message?.takeIf { it.isNotBlank() } ?: "HTTP $code"
        return if (text.length > 240) text.take(240) + "…" else text
    }

    private fun JsonObject.path(key: String): JsonElement? = this[key]

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** Sends one recorded utterance to the configured cloud recognizer. Cancelling the caller aborts the call. */
internal object CloudSpeechTranscriber {
    private const val MAX_RESPONSE_CHARS = 1_000_000
    private val client by lazy {
        HttpClient.client.newBuilder()
            .callTimeout(120, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    suspend fun transcribe(config: SpeechRecognitionConfig, wav: ByteArray): String {
        val request = CloudSpeechProtocol.buildRequest(config, wav) { Base64.encodeToString(it, Base64.NO_WRAP) }
        HttpClient.guardCleartextCredentials(
            request.url.toString(),
            request.headers.names().associateWith { request.header(it).orEmpty() },
        )
        val (code, body) = execute(request)
        if (code !in 200..299) throw SpeechRecognitionException(CloudSpeechProtocol.errorMessage(code, body))
        return CloudSpeechProtocol.parseTranscript(config.protocol, body)
    }

    private suspend fun execute(request: Request): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(SpeechRecognitionException(e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use { it.code to it.body.string().take(MAX_RESPONSE_CHARS) }
                }
                if (!cont.isActive) return
                result.fold(
                    onSuccess = { cont.resume(it) },
                    onFailure = { cont.resumeWithException(SpeechRecognitionException(it.message ?: "Read error")) },
                )
            }
        })
    }
}
