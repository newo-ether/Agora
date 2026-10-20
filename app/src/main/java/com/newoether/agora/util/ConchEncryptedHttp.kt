package com.newoether.agora.util

import android.util.Base64
import com.newoether.agora.api.readBoundedWireLine
import com.newoether.agora.api.readBoundedWireText
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.serialization.json.*
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.*

/** Conch's existing handshake, request HMAC and sequenced AEAD stream for any Filo API. */
internal class ConchChannelException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class ConchEncryptedHttp(private val token: String) : Interceptor {
    @Volatile private var serverReadKey: String? = null
    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            return try {
                exchange(chain, freshHandshake = false)
            } catch (error: IOException) {
                if (chain.request().method != "GET" || chain.call().isCanceled()) throw error
                exchange(chain, freshHandshake = true)
            }
        } catch (error: GeneralSecurityException) {
            // OkHttp dispatches IOException to onFailure; uncaught crypto errors crash its worker.
            throw ConchChannelException("Encrypted connection cryptography unavailable", error)
        }
    }

    private fun exchange(chain: Interceptor.Chain, freshHandshake: Boolean): Response {
        val original = chain.request()
        val origin = original.url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        val challenge = ShellCrypto.generateNonce()
        val handshakeRequest = Request.Builder().url(origin.newBuilder().addPathSegment("public-key")
            .addQueryParameter("challenge", challenge).build()).build()
        val serverKey = (serverReadKey.takeIf { original.method == "GET" && !freshHandshake }) ?: chain.proceed(handshakeRequest).use { response ->
            if (!response.isSuccessful) throw ConchChannelException("Encrypted handshake failed")
            ConchHandshake.decode(response.body.source().readBoundedWireText(64L * 1024))
                .also { if (!it.verify(token, challenge)) throw ConchChannelException("Unauthenticated encrypted handshake") }.public_key
        }
        serverReadKey = serverKey
        val pair = ShellCrypto.generateEphemeralKeyPair()
        val public = ShellCrypto.encodePublicKey(pair.public)
        val key = ShellCrypto.deriveAesKey(pair.private, ShellCrypto.decodePublicKey(serverKey))
        val requestBody = Buffer()
        var bytes = 0L
        val limited = object : ForwardingSink(requestBody) {
            override fun write(source: Buffer, byteCount: Long) {
                if (byteCount > 512L * 1024 - bytes) throw ConchChannelException("Encrypted request exceeds the transport limit")
                bytes += byteCount
                super.write(source, byteCount)
            }
        }.buffer()
        original.body?.writeTo(limited); limited.flush()
        val envelope = buildJsonObject {
            put("method", original.method)
            put("path", original.url.encodedPath + original.url.encodedQuery?.let { "?$it" }.orEmpty())
            put("contentType", original.body?.contentType()?.toString().orEmpty())
            put("body", Base64.encodeToString(requestBody.readByteArray(), Base64.NO_WRAP))
        }.toString()
        val encrypted = ShellCrypto.encrypt(key, envelope.toByteArray(Charsets.UTF_8))
        val nonce = ShellCrypto.generateNonce()
        val timestamp = System.currentTimeMillis() / 1000
        val path = "/e2e/request"
        val request = Request.Builder().url(origin.newBuilder().encodedPath(path).build())
            .post(encrypted.toRequestBody("application/octet-stream".toMediaType()))
            .header("X-Encryption", "v2").header("X-Client-Public-Key", public)
            .header("X-Nonce", nonce).header("X-Timestamp", timestamp.toString())
            .header("X-Signature", ShellCrypto.sign(token, timestamp, "POST", path,
                ShellCrypto.sha256Hex(encrypted.toByteArray(Charsets.UTF_8)), nonce, public)).build()
        val response = chain.proceed(request)
        try {
            if (!response.isSuccessful) throw ConchChannelException("Encrypted request was not accepted")
            val frames = ConchHttpFrames(response.body.source(), key)
            val header = frames.read()
            if (header.first != "http_headers") throw ConchChannelException("Missing encrypted HTTP headers")
            val status = header.second["status"]?.jsonPrimitive?.intOrNull ?: throw ConchChannelException("Missing encrypted status")
            if (status !in 200..599) throw ConchChannelException("Invalid encrypted status")
            val type = header.second["contentType"]?.jsonPrimitive?.content.orEmpty().toMediaTypeOrNull()
            val source = ConchHttpBodySource(frames).buffer()
            val body = object : ResponseBody() {
                override fun contentType() = type
                override fun contentLength() = -1L
                override fun source() = source
            }
            return response.newBuilder().code(status).message("Encrypted response")
                .headers(Headers.Builder().add("Cache-Control", "no-store").apply {
                    type?.let { add("Content-Type", it.toString()) }
                }.build()).body(body).build()
        } catch (error: Throwable) { response.close(); key.fill(0); throw error }
    }
}

internal class ConchHttpFrames(private val source: BufferedSource, private val key: ByteArray) {
    private var sequence = 0L
    fun read(): Pair<String, JsonObject> {
        val event = source.readBoundedWireLine(128) ?: throw ConchChannelException("Encrypted response ended without a terminal frame")
        val kind = event.removePrefix("event: ")
        if (!event.startsWith("event: ") || kind !in setOf("http_headers", "http_body", "http_end")) throw ConchChannelException("Invalid encrypted frame")
        val data = source.readBoundedWireLine(64L * 1024) ?: throw ConchChannelException("Missing encrypted frame data")
        if (!data.startsWith("data: ") || source.readBoundedWireLine(1) != "") throw ConchChannelException("Invalid encrypted frame boundary")
        val value = try { Json.parseToJsonElement(String(ShellCrypto.decrypt(key, data.substring(6)), Charsets.UTF_8)).jsonObject }
            catch (error: Exception) { throw ConchChannelException("Encrypted frame authentication failed", error) }
        if (value["_conch_event"]?.jsonPrimitive?.content != kind ||
            value["_conch_sequence"]?.jsonPrimitive?.longOrNull != sequence) throw ConchChannelException("Encrypted frame order mismatch")
        sequence++
        return kind to value
    }
    fun requireEnd() {
        // The HTTP response body ends even when its connection remains reusable.
        if (!source.exhausted()) throw ConchChannelException("Bytes after encrypted HTTP terminal frame")
    }
    fun timeout() = source.timeout()
    fun close() { source.close(); key.fill(0) }
}

internal class ConchHttpBodySource(private val frames: ConchHttpFrames) : Source {
    private val pending = Buffer()
    private var ended = false
    private var terminalError: IOException? = null
    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        terminalError?.let { throw it }
        while (pending.size == 0L && !ended) {
            val (kind, frame) = frames.read()
            when (kind) {
                "http_end" -> {
                    try { frames.requireEnd() } catch (error: IOException) {
                        terminalError = error
                        throw error
                    }
                    ended = true
                }
                "http_body" -> {
                    val raw = frame["body"]?.jsonPrimitive?.content ?: throw ConchChannelException("Missing encrypted bytes")
                    val bytes = try { Base64.decode(raw, Base64.DEFAULT) } catch (error: IllegalArgumentException) {
                        throw ConchChannelException("Invalid encrypted bytes", error)
                    }
                    if (bytes.size > 16 * 1024) throw ConchChannelException("Encrypted body frame is too large")
                    pending.write(bytes)
                }
                else -> throw ConchChannelException("Unexpected repeated encrypted headers")
            }
        }
        return if (pending.size == 0L) -1 else pending.read(sink, minOf(byteCount, pending.size))
    }
    override fun timeout() = frames.timeout()
    override fun close() { pending.clear(); frames.close() }
}
