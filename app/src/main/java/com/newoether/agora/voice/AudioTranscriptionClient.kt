package com.newoether.agora.voice

import com.newoether.agora.api.HttpClient
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Speech-only endpoint. Neither conversation history nor the template/skill catalogue is uploaded. */
class AudioTranscriptionClient(client: OkHttpClient = HttpClient.client) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(2, java.util.concurrent.TimeUnit.MINUTES).build()
    suspend fun transcribe(audio: File, config: VoiceTypingConfig, apiKey: String): String {
        require(config.mode.usesApi && audio.length() in 1..MAX_AUDIO_BYTES) { "Invalid recording" }
        require((config.mode == VoiceTypingMode.COMPATIBLE && config.apiKeyId == null) || apiKey.isNotBlank()) { "Select a transcription API key" }
        val model = config.model.trim().ifBlank { config.mode.defaultModel }
        require(model.length in 1..200 && config.language.length <= 35) { "Invalid transcription settings" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model).addFormDataPart("response_format", "json")
            .addFormDataPart("file", "dictation.m4a", audio.asRequestBody("audio/mp4".toMediaType()))
        config.language.trim().takeIf { it.isNotEmpty() }?.let { body.addFormDataPart("language", it) }
        val request = Request.Builder().url(config.transcriptionUrl()).post(body.build())
        if (apiKey.isNotBlank()) request.header("Authorization", "Bearer $apiKey")
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        if (!it.isSuccessful) throw IOException("Transcription failed (HTTP ${it.code})")
                        val bytes = it.body?.byteStream()?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 1024 * 1024) throw IOException("Transcription response too large")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: throw IOException("Empty transcription response")
                        val text = Json.parseToJsonElement(bytes.decodeToString()).jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim()
                        if (text.isNullOrBlank()) throw IOException("No speech was recognised")
                        text
                    } }
                    if (!continuation.isCancelled) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }
    companion object { const val MAX_AUDIO_BYTES = 20L * 1024 * 1024 }
}
